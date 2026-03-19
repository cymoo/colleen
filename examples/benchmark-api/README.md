# benchmark-api

This example provides a minimal API surface for benchmarking Colleen and comparing it with other frameworks under the same scenarios.

## Endpoints

- `GET /text` - tiny plain-text response (`"ok"`)
- `GET /json` - small JSON response
- `GET /json-stream` - large JSON payload (2,000 items)
- `POST /upload` - multipart upload (`file`)
- `POST /extract/auto` - query + JSON extraction in one handler (`extractAuto`)

Source file:

- `examples/benchmark-api/src/main/kotlin/Main.kt`

## Run Colleen benchmark service

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

cd /home/runner/work/colleen/colleen/examples/benchmark-api
mvn compile exec:java -Dkotlin.compiler.jdkHome=$JAVA_HOME
```

Service listens on `127.0.0.1:7070`.
`-Dkotlin.compiler.jdkHome=$JAVA_HOME` is included to match this repository's Kotlin build configuration.

## Comparison setup used for this benchmark

To keep repository changes minimal, comparison services were created in `/tmp`:

- Spring Boot (`spring-boot-starter-web`) on `127.0.0.1:8081`
- Flask `3.0.3` + gunicorn `23.0.0` on `127.0.0.1:8082`
  - gunicorn command: `gunicorn -w 4 -k gthread --threads 8 -b 127.0.0.1:8082 app:app`

For reproducibility, both temporary comparison services implemented the same baseline endpoint contracts:

- `GET /text` -> plain text `"ok"`
- `GET /json` -> JSON with fields `ok`, `framework`, `ts`
- `POST /upload` -> JSON with fields `uploaded`, `name`, `size`, `contentType`

Minimal Flask app used in `/tmp/bench-compare/flask/app.py`:

```python
from flask import Flask, request, jsonify
import time

app = Flask(__name__)

@app.get('/text')
def text():
    return 'ok'

@app.get('/json')
def json_endpoint():
    return jsonify({'ok': True, 'framework': 'flask', 'ts': int(time.time() * 1000)})

@app.post('/upload')
def upload():
    f = request.files.get('file')
    return jsonify({
        'uploaded': f is not None,
        'name': getattr(f, 'filename', None),
        'size': len(f.read()) if f is not None else None,
        'contentType': getattr(f, 'content_type', None),
    })
```

## Benchmark methodology

Recommended environment for benchmark runs:

- OS: Linux (GitHub Actions runner in this sample run)
- CPU: 4 vCPU
- JDK: Temurin 25 (`/usr/lib/jvm/temurin-25-jdk-amd64`)
- Tool: ApacheBench (`ab`)

Method:

1. Keep machine and benchmark tool fixed.
2. Split scenarios into:
   - **cross-framework baseline** (only shared, common API behaviors)
   - **Colleen-specific capability checks** (framework feature scenarios)
3. Warm up each framework before sampling.
4. Sample the same request count and concurrency per scenario.
5. Report throughput and latency percentiles (`P50`, `P95`, `P99`) plus failures.

> Why JDK 25?
>
> For high-concurrency virtual-thread workloads, JDK 25 is recommended.
> JDK 21 may show extra noise caused by older virtual-thread pinning behavior in some scenarios.

Scenarios:

### A) Cross-framework baseline (Colleen vs Spring Boot vs Flask)

- Lightweight text: `GET /text`
- Small JSON: `GET /json`
- Multipart upload: `POST /upload` (1MB file)

### B) Colleen-specific scenarios (not used for Spring/Flask comparison)

- Parameter extraction (`extractAuto`): `POST /extract/auto?q=abc&page=2` + JSON body
- Large JSON payload with Colleen streaming API: `GET /json-stream`

## Commands (example)

```bash
# build benchmark request payload files once (Linux/macOS shell)
head -c 1048576 /dev/urandom > /tmp/bench-compare/1mb.bin
# If /dev/urandom is unavailable, use any other method to generate a 1MB file.
cat > /tmp/bench-compare/body.json <<'JSON'
{"userId":123,"name":"bench","tags":["a","b","c"]}
JSON
{
  printf -- '------benchboundary\r\n'
  printf -- 'Content-Disposition: form-data; name="file"; filename="1mb.bin"\r\n'
  printf -- 'Content-Type: application/octet-stream\r\n\r\n'
  cat /tmp/bench-compare/1mb.bin
  printf -- '\r\n------benchboundary--\r\n'
} > /tmp/bench-compare/upload-multipart.body

# warm-up (cross-framework baseline, one service port)
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/text
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 80 -c 10 http://127.0.0.1:<port>/upload

# sample (cross-framework baseline)
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/text
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 300 -c 20 http://127.0.0.1:<port>/upload

# Colleen-only feature scenarios (run on :7070 only)
ab -p /tmp/bench-compare/body.json -T application/json -k -n 9000 -c 120 \
  "http://127.0.0.1:7070/extract/auto?q=abc&page=2"
ab -k -n 800 -c 40 http://127.0.0.1:7070/json-stream

# high-concurrency sample (recommended additional stress profile)
# note: keep `maxConcurrentRequests = 0` (or set it above these levels) during this profile.
ab -k -n 60000 -c 500 http://127.0.0.1:<port>/text
ab -k -n 60000 -c 500 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 1200 -c 60 http://127.0.0.1:<port>/upload
ab -p /tmp/bench-compare/body.json -T application/json -k -n 30000 -c 300 \
  "http://127.0.0.1:7070/extract/auto?q=abc&page=2"
ab -k -n 3000 -c 120 http://127.0.0.1:7070/json-stream
```

## Measured results

The tables below are historical sample numbers from the baseline profile.
Use them only as a reference format, and re-run all scenarios on your own environment (preferably JDK 25) for decision-making.

### A) Cross-framework baseline

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `GET /text` | 200 | 15,000 | 13,605.07 | 12ms | 31ms | 62ms | 0 |
| Colleen | `GET /json` | 200 | 15,000 | 15,483.25 | 10ms | 31ms | 49ms | 0 |
| Colleen | `POST /upload (1MB)` | 20 | 300 | 219.89 | 72ms | 228ms | 343ms | 0 |
| Spring Boot | `GET /text` | 200 | 15,000 | 17,255.56 | 11ms | 20ms | 28ms | 0 |
| Spring Boot | `GET /json` | 200 | 15,000 | 6,976.75 | 29ms | 32ms | 33ms | 0 |
| Spring Boot | `POST /upload (1MB)` | 20 | 300 | 424.72 | 44ms | 72ms | 86ms | 0 |
| Flask (gunicorn) | `GET /text` | 200 | 15,000 | 9,103.38 | 17ms | 50ms | 57ms | 0 |
| Flask (gunicorn) | `GET /json` | 200 | 15,000 | 9,301.71 | 18ms | 51ms | 62ms | 0 |
| Flask (gunicorn) | `POST /upload (1MB)` | 20 | 300 | 245.79 | 73ms | 129ms | 138ms | 0 |

### B) Colleen-only feature scenarios

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `POST /extract/auto` | 120 | 9,000 | 8,809.25 | 11ms | 30ms | 48ms | 0 |
| Colleen | `GET /json-stream` | 40 | 800 | 783.98 | 33ms | 158ms | 272ms | 0 |

## Notes on interpretation

- These numbers are from one fixed environment and one config set; they are **not universal rankings**.
- Spring Boot and Flask are compared only on baseline common endpoints (`/text`, `/json`, `/upload`).
- `extractAuto` and `json-stream` are reported as Colleen-only feature scenarios.
- Flask + gunicorn results use the same scenario coverage and measurement process as the other frameworks.
- Re-test on your own hardware and production-like tuning before making architecture decisions.
