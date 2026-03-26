import io.github.cymoo.colleen.*
import io.github.cymoo.colleen.middleware.RequestLogger
import io.github.cymoo.colleen.ws.Ws
import io.github.cymoo.colleen.ws.WsCloseReason
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * WebSocket Example
 *
 * Features demonstrated:
 * - Echo server (text and binary)
 * - Chat rooms with path parameters
 * - Query parameter access
 * - WebSocket middleware for authentication
 * - Connection lifecycle (onMessage, onClose, onError)
 * - Controller-style WebSocket handlers (@Ws annotation)
 * - WebSocket configuration (idle timeout, max message size)
 * - Concurrent connection management
 * - HTML client page with interactive WebSocket demo
 */
fun main() {
    val app = Colleen()

    app.use(RequestLogger())

    // WebSocket configuration
    app.config {
        ws {
            idleTimeoutMs = 600_000        // 10 minutes
            maxMessageSizeBytes = 128 * 1024  // 128 KB
        }
    }

    // ========================================================================
    // 1. Echo Server
    // ========================================================================

    app.ws("/echo") { conn ->
        conn.onMessage { msg ->
            when (msg) {
                is WsMessage.Text -> conn.send(msg.data)
                is WsMessage.Binary -> conn.send(msg.data)
            }
        }

        conn.onClose { reason ->
            println("Echo connection closed: $reason")
        }
    }

    // ========================================================================
    // 2. Chat Room with Path Parameters
    // ========================================================================

    // Thread-safe room → set of connections
    val chatRooms = ConcurrentHashMap<String, CopyOnWriteArraySet<WsConnection>>()

    app.ws("/chat/{room}") { conn ->
        val room = conn.pathParam("room")!!
        val username = conn.query("name") ?: "Anonymous"

        // Add to room
        val connections = chatRooms.computeIfAbsent(room) { CopyOnWriteArraySet() }
        connections.add(conn)

        // Announce join
        broadcast(connections, "[$room] $username joined (${connections.size} online)")

        conn.onMessage { msg ->
            if (msg is WsMessage.Text) {
                broadcast(connections, "[$room] $username: ${msg.data}")
            }
        }

        conn.onClose {
            connections.remove(conn)
            if (connections.isEmpty()) {
                chatRooms.remove(room)
            } else {
                broadcast(connections, "[$room] $username left (${connections.size} online)")
            }
        }

        conn.onError { error ->
            println("Chat error for $username in $room: ${error.message}")
        }
    }

    // ========================================================================
    // 3. WebSocket Middleware — Authentication
    // ========================================================================

    app.wsUse("/secure") { ctx, next ->
        val token = ctx.query("token") ?: ctx.header("Authorization")?.removePrefix("Bearer ")
        if (token == "secret123") {
            next()
        } else {
            ctx.status(401).text("Unauthorized: invalid or missing token")
        }
    }

    app.ws("/secure/data") { conn ->
        conn.send("Welcome to the secure channel!")

        conn.onMessage { msg ->
            if (msg is WsMessage.Text) {
                conn.send("Secure echo: ${msg.data}")
            }
        }
    }

    // ========================================================================
    // 4. Controller-Style WebSocket (@Ws annotation)
    // ========================================================================

    app.addController(NotificationController())

    // ========================================================================
    // 5. Home Page — HTML WebSocket Client
    // ========================================================================

    app.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>WebSocket Demo</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: system-ui, sans-serif; max-width: 900px; margin: 0 auto; padding: 20px; }
                    h1 { margin-bottom: 20px; }
                    .section { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
                    .section h3 { margin-bottom: 12px; color: #333; }
                    .log { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px;
                           min-height: 100px; max-height: 200px; overflow-y: auto; font-family: monospace;
                           font-size: 13px; white-space: pre-wrap; margin-bottom: 8px; }
                    input, button { padding: 8px 12px; border: 1px solid #ccc; border-radius: 4px; }
                    input { width: 300px; }
                    button { cursor: pointer; background: #0066cc; color: white; border: none; margin-left: 4px; }
                    button:hover { background: #0052a3; }
                    button.danger { background: #cc3333; }
                    button.danger:hover { background: #a32929; }
                    .controls { display: flex; gap: 4px; align-items: center; flex-wrap: wrap; }
                    .status { display: inline-block; width: 10px; height: 10px; border-radius: 50%;
                              margin-right: 6px; }
                    .status.open { background: #22c55e; }
                    .status.closed { background: #ef4444; }
                </style>
            </head>
            <body>
                <h1>🔌 WebSocket Demo</h1>

                <!-- Echo -->
                <div class="section">
                    <h3><span class="status closed" id="echo-status"></span>Echo Server</h3>
                    <div class="log" id="echo-log"></div>
                    <div class="controls">
                        <input id="echo-input" placeholder="Type a message..." />
                        <button onclick="echoSend()">Send</button>
                        <button onclick="echoConnect()">Connect</button>
                        <button class="danger" onclick="echoDisconnect()">Disconnect</button>
                    </div>
                </div>

                <!-- Chat -->
                <div class="section">
                    <h3><span class="status closed" id="chat-status"></span>Chat Room</h3>
                    <div class="log" id="chat-log"></div>
                    <div class="controls">
                        <input id="chat-room" value="general" style="width:100px" placeholder="Room" />
                        <input id="chat-name" value="User" style="width:100px" placeholder="Name" />
                        <input id="chat-input" placeholder="Message..." />
                        <button onclick="chatSend()">Send</button>
                        <button onclick="chatConnect()">Join</button>
                        <button class="danger" onclick="chatDisconnect()">Leave</button>
                    </div>
                </div>

                <script>
                    // ---- Helpers ----
                    function log(id, msg) {
                        const el = document.getElementById(id);
                        el.textContent += msg + '\n';
                        el.scrollTop = el.scrollHeight;
                    }
                    function setStatus(id, open) {
                        const el = document.getElementById(id);
                        el.className = 'status ' + (open ? 'open' : 'closed');
                    }

                    // ---- Echo ----
                    let echoWs = null;
                    function echoConnect() {
                        if (echoWs) return;
                        echoWs = new WebSocket('ws://' + location.host + '/echo');
                        echoWs.onopen = () => { log('echo-log', '✅ Connected'); setStatus('echo-status', true); };
                        echoWs.onmessage = (e) => log('echo-log', '← ' + e.data);
                        echoWs.onclose = (e) => { log('echo-log', '❌ Closed: ' + e.code); setStatus('echo-status', false); echoWs = null; };
                        echoWs.onerror = () => log('echo-log', '⚠️ Error');
                    }
                    function echoSend() {
                        if (!echoWs) return log('echo-log', '⚠️ Not connected');
                        const msg = document.getElementById('echo-input').value;
                        echoWs.send(msg);
                        log('echo-log', '→ ' + msg);
                        document.getElementById('echo-input').value = '';
                    }
                    function echoDisconnect() { if (echoWs) echoWs.close(); }

                    // ---- Chat ----
                    let chatWs = null;
                    function chatConnect() {
                        if (chatWs) chatWs.close();
                        const room = document.getElementById('chat-room').value;
                        const name = document.getElementById('chat-name').value;
                        chatWs = new WebSocket('ws://' + location.host + '/chat/' + room + '?name=' + encodeURIComponent(name));
                        chatWs.onopen = () => { log('chat-log', '✅ Joined #' + room + ' as ' + name); setStatus('chat-status', true); };
                        chatWs.onmessage = (e) => log('chat-log', e.data);
                        chatWs.onclose = () => { log('chat-log', '❌ Left room'); setStatus('chat-status', false); chatWs = null; };
                    }
                    function chatSend() {
                        if (!chatWs) return log('chat-log', '⚠️ Not connected');
                        const msg = document.getElementById('chat-input').value;
                        chatWs.send(msg);
                        document.getElementById('chat-input').value = '';
                    }
                    function chatDisconnect() { if (chatWs) chatWs.close(); }

                    // Enter key support
                    document.getElementById('echo-input').onkeydown = (e) => { if (e.key === 'Enter') echoSend(); };
                    document.getElementById('chat-input').onkeydown = (e) => { if (e.key === 'Enter') chatSend(); };
                </script>
            </body>
            </html>
            """.trimIndent()
        )
    }

    app.listen(8000)
    println("✅ WebSocket server running on http://localhost:8000")
}

// ============================================================================
// Controller
// ============================================================================

@Controller("/notifications")
class NotificationController {

    @Ws("/live")
    fun liveNotifications(conn: WsConnection) {
        conn.send("Connected to notification stream")

        conn.onMessage { msg ->
            if (msg is WsMessage.Text) {
                conn.send("Notification acknowledged: ${msg.data}")
            }
        }

        conn.onClose { reason ->
            println("Notification client disconnected: $reason")
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

private fun broadcast(connections: Set<WsConnection>, message: String) {
    connections.forEach { conn ->
        runCatching { conn.send(message) }
    }
}
