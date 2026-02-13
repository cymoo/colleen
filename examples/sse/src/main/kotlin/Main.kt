import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.SseEvent
import io.github.cymoo.colleen.middleware.RequestLogger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Server-Sent Events (SSE) Example
 *
 * Features demonstrated:
 * - Real-time server push
 * - Keep-alive mechanism
 * - Multiple concurrent connections
 * - Connection lifecycle handling
 */

fun main() {
    val app = Colleen()

    app.use(RequestLogger())

    // SSE endpoint - server time updates
    app.get("/time") { ctx ->
        ctx.sse { conn ->
            // Enable keep-alive to prevent timeout
            conn.keepAlive(15)

            // Handle connection close
            conn.onClose { reason ->
                println("Connection closed: $reason")
            }

            // Send time updates every second
            repeat(60) {
                if (conn.isClosed) return@repeat

                val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                conn.send("Current time: $time")

                Thread.sleep(1000)
            }
        }
    }

    // SSE endpoint - counter with custom events
    app.get("/counter") { ctx ->
        ctx.sse { conn ->
            conn.keepAlive(15)

            repeat(10) { count ->
                if (conn.isClosed) return@repeat

                conn.send(
                    SseEvent(
                        data = count.toString(),
                        event = "count",
                        id = count.toString()
                    )
                )

                Thread.sleep(1000)
            }

            // Send completion event
            conn.send(
                SseEvent(
                    data = "Done!",
                    event = "complete"
                )
            )
        }
    }

    // SSE endpoint - system metrics
    app.get("/metrics") { ctx ->
        ctx.sse { conn ->
            conn.keepAlive(30)

            while (!conn.isClosed) {
                val runtime = Runtime.getRuntime()
                val totalMemory = runtime.totalMemory() / 1024 / 1024
                val freeMemory = runtime.freeMemory() / 1024 / 1024
                val usedMemory = totalMemory - freeMemory

                val metrics = """
                    {
                      "memory": {
                        "used": $usedMemory,
                        "total": $totalMemory
                      },
                      "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()

                conn.send(
                    SseEvent(
                        data = metrics,
                        event = "metrics"
                    )
                )

                Thread.sleep(2000)
            }
        }
    }

    // Home page with SSE client
    app.get("/") { ctx ->
        ctx.html(
            $$"""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>SSE Demo</title>
                <style>
                    body { margin: 40px; }
                    .demo { margin: 20px 0; padding: 15px; background: #f5f5f5; }
                    #time, #counter, #metrics { 
                        font-size: 18px; 
                        color: #0066cc; 
                        font-weight: bold; 
                    }
                    button { padding: 8px 16px; margin: 5px; }
                </style>
            </head>
            <body>
                <h1>📡 Server-Sent Events Demo</h1>
                
                <div class="demo">
                    <h3>Real-time Clock</h3>
                    <div id="time">Not connected</div>
                    <button onclick="connectTime()">Connect</button>
                    <button onclick="disconnectTime()">Disconnect</button>
                </div>
                
                <div class="demo">
                    <h3>Counter (0-9)</h3>
                    <div id="counter">Not started</div>
                    <button onclick="connectCounter()">Start</button>
                </div>
                
                <div class="demo">
                    <h3>System Metrics</h3>
                    <div id="metrics">Not connected</div>
                    <button onclick="connectMetrics()">Connect</button>
                    <button onclick="disconnectMetrics()">Disconnect</button>
                </div>
                
                <script>
                    let timeSource, metricsSource;
                    
                    function connectTime() {
                        if (timeSource) return;
                        timeSource = new EventSource('/time');
                        timeSource.onmessage = (e) => {
                            document.getElementById('time').textContent = e.data;
                        };
                        timeSource.onerror = () => {
                            document.getElementById('time').textContent = 'Connection lost';
                            timeSource = null;
                        };
                    }
                    
                    function disconnectTime() {
                        if (timeSource) {
                            timeSource.close();
                            timeSource = null;
                            document.getElementById('time').textContent = 'Disconnected';
                        }
                    }
                    
                    function connectCounter() {
                        const source = new EventSource('/counter');
                        
                        source.addEventListener('count', (e) => {
                            document.getElementById('counter').textContent = 'Count: ' + e.data;
                        });
                        
                        source.addEventListener('complete', (e) => {
                            document.getElementById('counter').textContent = e.data;
                            source.close();
                        });
                        
                        source.onerror = () => source.close();
                    }
                    
                    function connectMetrics() {
                        if (metricsSource) return;
                        metricsSource = new EventSource('/metrics');
                        
                        metricsSource.addEventListener('metrics', (e) => {
                            const data = JSON.parse(e.data);
                            document.getElementById('metrics').textContent = 
                                `Memory: ${data.memory.used}MB / ${data.memory.total}MB`;
                        });
                        
                        metricsSource.onerror = () => {
                            document.getElementById('metrics').textContent = 'Connection lost';
                            metricsSource = null;
                        };
                    }
                    
                    function disconnectMetrics() {
                        if (metricsSource) {
                            metricsSource.close();
                            metricsSource = null;
                            document.getElementById('metrics').textContent = 'Disconnected';
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ SSE Server running on http://localhost:8000")
}