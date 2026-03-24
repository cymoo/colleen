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
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
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
- JDK: Temurin 21+ (`/usr/lib/jvm/temurin-21-jdk-amd64`)
- Tool: ApacheBench (`ab`)

Method:

1. Keep machine and benchmark tool fixed.
2. Split scenarios into:
   - **cross-framework baseline** (only shared, common API behaviors)
   - **Colleen-specific capability checks** (framework feature scenarios)
3. Warm up each framework before sampling.
4. Sample the same request count and concurrency per scenario.
5. Report throughput and latency percentiles (`P50`, `P95`, `P99`) plus failures.

Scenarios:

### A) Cross-framework baseline (Colleen vs Spring Boot vs Flask)

- Lightweight text: `GET /text`
- Small JSON: `GET /json`
- Multipart upload (1 MB file): `POST /upload`
- Multipart upload (1 KB file): `POST /upload`

### B) Colleen-specific scenarios (not used for Spring/Flask comparison)

- Parameter extraction (`extractAuto`): `POST /extract/auto?q=abc&page=2` + JSON body
- Large JSON payload with Colleen streaming API: `GET /json-stream`

## Commands (example)

```bash
# build benchmark request payload files once (Linux/macOS shell)
head -c 1048576 /dev/urandom > /tmp/bench-compare/1mb.bin
head -c 1024    /dev/urandom > /tmp/bench-compare/1kb.bin
cat > /tmp/bench-compare/body.json <<'JSON'
{"userId":123,"name":"bench","tags":["a","b","c"]}
JSON

# multipart body for 1 MB upload
{
  printf -- '------benchboundary\r\n'
  printf -- 'Content-Disposition: form-data; name="file"; filename="1mb.bin"\r\n'
  printf -- 'Content-Type: application/octet-stream\r\n\r\n'
  cat /tmp/bench-compare/1mb.bin
  printf -- '\r\n------benchboundary--\r\n'
} > /tmp/bench-compare/upload-multipart.body

# multipart body for 1 KB upload
{
  printf -- '------benchboundary\r\n'
  printf -- 'Content-Disposition: form-data; name="file"; filename="1kb.bin"\r\n'
  printf -- 'Content-Type: application/octet-stream\r\n\r\n'
  cat /tmp/bench-compare/1kb.bin
  printf -- '\r\n------benchboundary--\r\n'
} > /tmp/bench-compare/upload-small.body

# warm-up (cross-framework baseline, one service port)
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/text
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 80 -c 10 http://127.0.0.1:<port>/upload
ab -p /tmp/bench-compare/upload-small.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 500 -c 50 http://127.0.0.1:<port>/upload

# sample (cross-framework baseline)
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/text
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 300 -c 20 http://127.0.0.1:<port>/upload
ab -p /tmp/bench-compare/upload-small.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 5000 -c 100 http://127.0.0.1:<port>/upload

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
ab -p /tmp/bench-compare/upload-small.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 15000 -c 200 http://127.0.0.1:<port>/upload
ab -p /tmp/bench-compare/body.json -T application/json -k -n 30000 -c 300 \
  "http://127.0.0.1:7070/extract/auto?q=abc&page=2"
ab -k -n 3000 -c 120 http://127.0.0.1:7070/json-stream
```

## Measured results

The tables below are sample numbers from JDK 21 on a 4 vCPU GitHub Actions runner.
Use them only as a reference format, and re-run all scenarios on your own environment for decision-making.

### A) Cross-framework baseline

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `GET /text` | 200 | 15,000 | 31,845 | 5ms | 14ms | 22ms | 0 |
| Colleen | `GET /json` | 200 | 15,000 | 44,378 | 3ms | 12ms | 21ms | 0 |
| Colleen | `POST /upload (1 MB)` | 20 | 300 | 228 | 78ms | 147ms | 162ms | 0 |
| Colleen | `POST /upload (1 KB)` | 100 | 5,000 | 12,205 | 6ms | 22ms | 36ms | 0 |

### B) Colleen-only feature scenarios

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `POST /extract/auto` | 120 | 9,000 | 8,036 | 10ms | 27ms | 95ms | 0 |
| Colleen | `GET /json-stream` | 40 | 800 | 776 | 40ms | 129ms | 192ms | 0 |

### C) High-concurrency stress profile

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `GET /text` | 500 | 60,000 | 59,328 | 6ms | 20ms | 50ms | 0 |
| Colleen | `GET /json` | 500 | 60,000 | 55,015 | 7ms | 22ms | 58ms | 0 |
| Colleen | `POST /upload (1 MB)` | 60 | 1,200 | 268 | 176ms | 497ms | 573ms | 0 |
| Colleen | `POST /upload (1 KB)` | 200 | 15,000 | 28,064 | 5ms | 20ms | 35ms | 0 |

## Notes on interpretation

- These numbers are from one fixed environment and one config set; they are **not universal rankings**.
- The small-file upload (1 KB) benchmark exercises multipart parsing overhead without I/O bottleneck; `FormParserFactory` is now cached to avoid per-request rebuild.
- The large-file upload (1 MB) is primarily I/O-bound; throughput is limited by data transfer rather than framework overhead.
- `extractAuto` and `json-stream` are reported as Colleen-only feature scenarios.
- Re-test on your own hardware and production-like tuning before making architecture decisions.
