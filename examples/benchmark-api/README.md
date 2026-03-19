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

## Comparison setup used for this benchmark

To keep repository changes minimal, comparison services were created in `/tmp`:

- Spring Boot (`spring-boot-starter-web`) on `127.0.0.1:8081`
- Flask `3.0.3` + gunicorn `23.0.0` on `127.0.0.1:8082`
  - gunicorn command: `gunicorn -w 4 -k gthread --threads 8 -b 127.0.0.1:8082 app:app`

## Benchmark methodology

Environment:

- OS: Linux (GitHub Actions runner)
- CPU: 4 vCPU
- JDK: Temurin 21 (`/usr/lib/jvm/temurin-21-jdk-amd64`)
- Tool: ApacheBench (`ab`)

Method:

1. Keep machine and benchmark tool fixed.
2. Use matched endpoint behavior across frameworks.
3. Warm up each framework before sampling.
4. Sample the same request count and concurrency per scenario.
5. Report throughput and latency percentiles (`P50`, `P95`, `P99`) plus failures.

Scenarios:

- Lightweight text: `GET /text`
- Small JSON: `GET /json`
- Parameter extraction (`extractAuto`): `POST /extract/auto?q=abc&page=2` + JSON body
- Large JSON payload: `GET /json-stream`
- Multipart upload: `POST /upload` (1MB file)

## Commands (example)

```bash
# warm-up (example for one service port)
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/text
ab -k -n 2000 -c 100 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/body.json -T application/json -k -n 1200 -c 80 \
  "http://127.0.0.1:<port>/extract/auto?q=abc&page=2"

# sample
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/text
ab -k -n 15000 -c 200 http://127.0.0.1:<port>/json
ab -p /tmp/bench-compare/body.json -T application/json -k -n 9000 -c 120 \
  "http://127.0.0.1:<port>/extract/auto?q=abc&page=2"
ab -k -n 800 -c 40 http://127.0.0.1:<port>/json-stream
ab -p /tmp/bench-compare/upload-multipart.body \
  -T "multipart/form-data; boundary=----benchboundary" \
  -k -n 300 -c 20 http://127.0.0.1:<port>/upload
```

## Measured results

| Framework | Endpoint | Concurrency | Requests | RPS | P50 | P95 | P99 | Failed |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Colleen | `GET /text` | 200 | 15,000 | 13,605.07 | 12ms | 31ms | 62ms | 0 |
| Colleen | `GET /json` | 200 | 15,000 | 15,483.25 | 10ms | 31ms | 49ms | 0 |
| Colleen | `POST /extract/auto` | 120 | 9,000 | 8,809.25 | 11ms | 30ms | 48ms | 0 |
| Colleen | `GET /json-stream` | 40 | 800 | 783.98 | 33ms | 158ms | 272ms | 0 |
| Colleen | `POST /upload (1MB)` | 20 | 300 | 219.89 | 72ms | 228ms | 343ms | 0 |
| Spring Boot | `GET /text` | 200 | 15,000 | 17,255.56 | 11ms | 20ms | 28ms | 0 |
| Spring Boot | `GET /json` | 200 | 15,000 | 6,976.75 | 29ms | 32ms | 33ms | 0 |
| Spring Boot | `POST /extract/auto` | 120 | 9,000 | 4,037.07 | 22ms | 83ms | 120ms | 0 |
| Spring Boot | `GET /json-stream` | 40 | 800 | 1,401.31 | 26ms | 53ms | 62ms | 0 |
| Spring Boot | `POST /upload (1MB)` | 20 | 300 | 424.72 | 44ms | 72ms | 86ms | 0 |
| Flask (gunicorn) | `GET /text` | 200 | 15,000 | 9,103.38 | 17ms | 50ms | 57ms | 0 |
| Flask (gunicorn) | `GET /json` | 200 | 15,000 | 9,301.71 | 18ms | 51ms | 62ms | 0 |
| Flask (gunicorn) | `POST /extract/auto` | 120 | 9,000 | 7,024.95 | 7ms | 49ms | 58ms | 0 |
| Flask (gunicorn) | `GET /json-stream` | 40 | 800 | 974.82 | 23ms | 100ms | 114ms | 0 |
| Flask (gunicorn) | `POST /upload (1MB)` | 20 | 300 | 245.79 | 73ms | 129ms | 138ms | 0 |

## Notes on interpretation

- These numbers are from one fixed environment and one config set; they are **not universal rankings**.
- The comparison now includes `extractAuto` and upload scenarios, not only text/json.
- Flask + gunicorn results are in a more plausible range after re-running with consistent scenario coverage and reporting methodology.
- Re-test on your own hardware and production-like tuning before making architecture decisions.
