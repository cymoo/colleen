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
- Multipart upload: `POST /upload` (1MB file)

### B) Colleen-specific scenarios (not used for Spring/Flask comparison)

- Parameter extraction (`extractAuto`): `POST /extract/auto?q=abc&page=2` + JSON body
- Large JSON payload with Colleen streaming API: `GET /json-stream`

## Commands (example)

```bash
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
```

## Measured results

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
- Flask + gunicorn results are in a more plausible range after re-running with consistent scenario coverage and reporting methodology.
- Re-test on your own hardware and production-like tuning before making architecture decisions.
