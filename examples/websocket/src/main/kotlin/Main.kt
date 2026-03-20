import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Event
import io.github.cymoo.colleen.WebSocketConnection
import io.github.cymoo.colleen.WebSocketMessage
import io.github.cymoo.colleen.Ws
import io.github.cymoo.colleen.middleware.RequestLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WebSocket Example
 *
 * Features demonstrated:
 * - Simple echo WebSocket endpoint
 * - Multi-room broadcast chat using path parameters
 * - Binary message handling
 * - Connection lifecycle (onOpen / onMessage / onClose / onError)
 * - Middleware integration (auth token check before upgrade)
 * - Controller-style WebSocket handler via @Ws annotation
 * - WebSocketConnected / WebSocketDisconnected lifecycle events
 * - Browser-based chat client served at "/"
 */

// ============================================================================
// Room registry — shared state for the broadcast chat
// ============================================================================

/** Map of room name → active connections in that room. */
val rooms = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketConnection>>()

fun roomConnections(room: String): CopyOnWriteArrayList<WebSocketConnection> =
    rooms.getOrPut(room) { CopyOnWriteArrayList() }

fun broadcast(room: String, message: String, exclude: WebSocketConnection? = null) {
    roomConnections(room).forEach { conn ->
        if (conn !== exclude && !conn.isClosed) {
            conn.send(message)
        }
    }
}

// ============================================================================
// Controller-style WebSocket handler
// ============================================================================

@Controller("/ws")
class WebSocketController {

    /**
     * Echo endpoint — reflects every text message back to the sender.
     *
     * Connect: ws://localhost:8000/ws/echo
     */
    @Ws("/echo")
    fun echo(conn: WebSocketConnection) {
        conn.onOpen {
            conn.send("Connected to echo service. Send any message!")
        }

        conn.onMessage { msg ->
            when (msg) {
                is WebSocketMessage.Text ->
                    conn.send("Echo: ${msg.text()}")

                is WebSocketMessage.Binary ->
                    conn.send("Binary echo: ${msg.bytes().size} bytes".also {
                        conn.send(msg.bytes())
                    })
            }
        }

        conn.onClose { reason ->
            println("[echo] closed — code=${reason.code} reason='${reason.reason}'")
        }

        conn.onError { err ->
            println("[echo] error: ${err.message}")
        }
    }

    /**
     * Multi-room broadcast chat.
     *
     * Connect: ws://localhost:8000/ws/chat/{room}
     *
     * - Sends a welcome message on join.
     * - Broadcasts every text message to all other connections in the same room.
     * - Announces when a participant leaves.
     */
    @Ws("/chat/{room}")
    fun chat(conn: WebSocketConnection) {
        val room = conn.pathParam("room") ?: "default"
        val nickname = conn.query("nickname") ?: "anonymous"
        val connections = roomConnections(room)

        conn.onOpen {
            connections.add(conn)
            conn.send("""{"type":"welcome","room":"$room","nickname":"$nickname","online":${connections.size}}""")
            broadcast(room, """{"type":"join","nickname":"$nickname","online":${connections.size}}""", exclude = conn)
            println("[chat/$room] $nickname joined (${connections.size} online)")
        }

        conn.onMessage { msg ->
            if (msg is WebSocketMessage.Text) {
                val text = msg.text()
                broadcast(room, """{"type":"message","nickname":"$nickname","text":${jsonEscape(text)}}""")
                println("[chat/$room] $nickname: $text")
            }
        }

        conn.onClose { reason ->
            connections.remove(conn)
            broadcast(room, """{"type":"leave","nickname":"$nickname","online":${connections.size}}""")
            println("[chat/$room] $nickname left (code=${reason.code}, ${connections.size} online)")
        }

        conn.onError { err ->
            println("[chat/$room] error for $nickname: ${err.message}")
        }
    }
}

/** Minimal JSON string escaping for the inline JSON literals above. */
private fun jsonEscape(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

// ============================================================================
// Main application
// ============================================================================

fun main() {
    val app = Colleen()

    app.use(RequestLogger())

    // Middleware: validate optional ?token= query parameter on /ws/secure
    app.use("/ws/secure") { ctx, next ->
        val token = ctx.query("token")
        if (token.isNullOrBlank()) {
            throw io.github.cymoo.colleen.Unauthorized("Missing token")
        }
        ctx.setState("userId", "user-$token")
        next()
    }

    // -------------------------------------------------------------------------
    // Controller-style routes (@Ws annotation)
    // -------------------------------------------------------------------------
    app.addController(WebSocketController())

    // -------------------------------------------------------------------------
    // Functional-style WebSocket routes
    // -------------------------------------------------------------------------

    /**
     * Secured endpoint — middleware checks the token before upgrading.
     * Access via: ws://localhost:8000/ws/secure?token=<your-token>
     *
     * After upgrade, the userId set by middleware is available via conn.attribute().
     */
    app.ws("/ws/secure") { conn ->
        val userId = conn.attribute<String>("userId") ?: "unknown"

        conn.onOpen {
            conn.send("Hello, $userId! This endpoint requires a token.")
        }

        conn.onMessage { msg ->
            if (msg is WebSocketMessage.Text) {
                conn.send("[$userId] you said: ${msg.text()}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Serve browser client
    // -------------------------------------------------------------------------
    app.get("/") { ctx ->
        ctx.html(homePage())
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------
    app.config {
        server {
            wsIdleTimeout = 300_000        // 5 minutes
            maxWebSocketMessageSize = 64 * 1024  // 64 KB
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle event observers
    // -------------------------------------------------------------------------

    // Log every new WebSocket connection
    app.on<Event.WebSocketConnected> { event ->
        println("[event] WebSocketConnected  path=${event.path}")
    }

    // Log every WebSocket disconnection with close reason
    app.on<Event.WebSocketDisconnected> { event ->
        println("[event] WebSocketDisconnected path=${event.path} code=${event.reason.code} reason='${event.reason.reason}'")
    }

    // Log when a new WS route is registered
    app.on<Event.WebSocketRouteRegistered> { event ->
        println("[event] WebSocketRouteRegistered path=${event.node.path}")
    }

    app.listen(8000)
    println("✅ WebSocket server running on http://localhost:8000")
    println("   Echo:    ws://localhost:8000/ws/echo")
    println("   Chat:    ws://localhost:8000/ws/chat/{room}?nickname={name}")
    println("   Secure:  ws://localhost:8000/ws/secure?token=abc123")
}

// ============================================================================
// Browser client HTML
// ============================================================================

@Suppress("CssInvalidPropertyValue")
private fun homePage() = $$"""
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>WebSocket Demo</title>
    <style>
        * { box-sizing: border-box; }
        body { margin: 0; font-family: system-ui, sans-serif; background: #f0f2f5; }
        .container { max-width: 960px; margin: 0 auto; padding: 20px; }
        h1 { color: #1a1a2e; }
        .panel { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px;
                 box-shadow: 0 2px 8px rgba(0,0,0,.08); }
        .panel h2 { margin-top: 0; color: #16213e; font-size: 1.1em; }
        .log { height: 180px; overflow-y: auto; background: #1a1a2e; color: #a8ff78;
               font-family: monospace; font-size: 13px; padding: 10px; border-radius: 4px;
               margin-bottom: 10px; }
        .log .sys  { color: #78c1ff; }
        .log .err  { color: #ff7878; }
        .controls { display: flex; gap: 8px; flex-wrap: wrap; }
        input[type=text] { flex: 1; min-width: 160px; padding: 7px 10px; border: 1px solid #ccc;
                           border-radius: 4px; font-size: 14px; }
        button { padding: 7px 14px; border: none; border-radius: 4px; cursor: pointer;
                 font-size: 14px; }
        .btn-connect  { background: #2ecc71; color: white; }
        .btn-disconnect { background: #e74c3c; color: white; }
        .btn-send  { background: #3498db; color: white; }
        button:disabled { opacity: .5; cursor: default; }
    </style>
</head>
<body>
<div class="container">
    <h1>🔌 WebSocket Demo</h1>

    <!-- Echo panel -->
    <div class="panel">
        <h2>Echo (/ws/echo)</h2>
        <div id="echo-log" class="log"></div>
        <div class="controls">
            <button class="btn-connect"    onclick="echoConnect()">Connect</button>
            <button class="btn-disconnect" onclick="echoDisconnect()" disabled id="echo-disc">Disconnect</button>
            <input  id="echo-input" type="text" placeholder="Type a message..." onkeydown="if(event.key==='Enter')echoSend()">
            <button class="btn-send" onclick="echoSend()">Send</button>
        </div>
    </div>

    <!-- Chat panel -->
    <div class="panel">
        <h2>Chat (/ws/chat/{room})</h2>
        <div id="chat-log" class="log"></div>
        <div class="controls">
            <input id="chat-room"     type="text" placeholder="Room"     value="lobby" style="max-width:100px">
            <input id="chat-nickname" type="text" placeholder="Nickname" value="user1" style="max-width:100px">
            <button class="btn-connect"    onclick="chatConnect()">Join</button>
            <button class="btn-disconnect" onclick="chatDisconnect()" disabled id="chat-disc">Leave</button>
            <input  id="chat-input" type="text" placeholder="Message..." onkeydown="if(event.key==='Enter')chatSend()">
            <button class="btn-send" onclick="chatSend()">Send</button>
        </div>
    </div>

    <!-- Secure panel -->
    <div class="panel">
        <h2>Secured (/ws/secure) — requires ?token=</h2>
        <div id="secure-log" class="log"></div>
        <div class="controls">
            <input id="secure-token" type="text" placeholder="token" value="abc123" style="max-width:120px">
            <button class="btn-connect"    onclick="secureConnect()">Connect</button>
            <button class="btn-disconnect" onclick="secureDisconnect()" disabled id="secure-disc">Disconnect</button>
            <input  id="secure-input" type="text" placeholder="Message..." onkeydown="if(event.key==='Enter')secureSend()">
            <button class="btn-send" onclick="secureSend()">Send</button>
        </div>
    </div>
</div>

<script>
// ---- helpers ----
function appendLog(id, text, cls) {
    const el = document.getElementById(id);
    const line = document.createElement('div');
    if (cls) line.className = cls;
    line.textContent = new Date().toLocaleTimeString() + '  ' + text;
    el.appendChild(line);
    el.scrollTop = el.scrollHeight;
}

// ---- Echo ----
let echoWs;
function echoConnect() {
    echoWs = new WebSocket('ws://localhost:8000/ws/echo');
    echoWs.onopen    = ()  => { appendLog('echo-log', 'Connected', 'sys'); document.getElementById('echo-disc').disabled = false; };
    echoWs.onmessage = (e) => appendLog('echo-log', '← ' + e.data);
    echoWs.onerror   = ()  => appendLog('echo-log', 'Error', 'err');
    echoWs.onclose   = (e) => { appendLog('echo-log', 'Closed (code ' + e.code + ')', 'sys'); document.getElementById('echo-disc').disabled = true; echoWs = null; };
}
function echoDisconnect() { if (echoWs) echoWs.close(); }
function echoSend() {
    const inp = document.getElementById('echo-input');
    if (echoWs && inp.value) { appendLog('echo-log', '→ ' + inp.value); echoWs.send(inp.value); inp.value = ''; }
}

// ---- Chat ----
let chatWs;
function chatConnect() {
    const room = document.getElementById('chat-room').value || 'lobby';
    const nick = document.getElementById('chat-nickname').value || 'anon';
    chatWs = new WebSocket(`ws://localhost:8000/ws/chat/${room}?nickname=${encodeURIComponent(nick)}`);
    chatWs.onopen    = ()  => { document.getElementById('chat-disc').disabled = false; };
    chatWs.onmessage = (e) => {
        try {
            const d = JSON.parse(e.data);
            if      (d.type === 'welcome') appendLog('chat-log', `Joined room "${d.room}" as ${d.nickname} (${d.online} online)`, 'sys');
            else if (d.type === 'join')    appendLog('chat-log', `${d.nickname} joined (${d.online} online)`, 'sys');
            else if (d.type === 'leave')   appendLog('chat-log', `${d.nickname} left (${d.online} online)`, 'sys');
            else if (d.type === 'message') appendLog('chat-log', `${d.nickname}: ${d.text}`);
        } catch { appendLog('chat-log', e.data); }
    };
    chatWs.onerror   = ()  => appendLog('chat-log', 'Error', 'err');
    chatWs.onclose   = (e) => { appendLog('chat-log', 'Left room (code ' + e.code + ')', 'sys'); document.getElementById('chat-disc').disabled = true; chatWs = null; };
}
function chatDisconnect() { if (chatWs) chatWs.close(); }
function chatSend() {
    const inp = document.getElementById('chat-input');
    if (chatWs && inp.value) { chatWs.send(inp.value); inp.value = ''; }
}

// ---- Secure ----
let secureWs;
function secureConnect() {
    const token = document.getElementById('secure-token').value;
    secureWs = new WebSocket(`ws://localhost:8000/ws/secure?token=${encodeURIComponent(token)}`);
    secureWs.onopen    = ()  => { appendLog('secure-log', 'Connected', 'sys'); document.getElementById('secure-disc').disabled = false; };
    secureWs.onmessage = (e) => appendLog('secure-log', '← ' + e.data);
    secureWs.onerror   = ()  => appendLog('secure-log', 'Error', 'err');
    secureWs.onclose   = (e) => { appendLog('secure-log', 'Closed (code ' + e.code + ')', 'sys'); document.getElementById('secure-disc').disabled = true; secureWs = null; };
}
function secureDisconnect() { if (secureWs) secureWs.close(); }
function secureSend() {
    const inp = document.getElementById('secure-input');
    if (secureWs && inp.value) { appendLog('secure-log', '→ ' + inp.value); secureWs.send(inp.value); inp.value = ''; }
}
</script>
</body>
</html>
""".trimIndent()
