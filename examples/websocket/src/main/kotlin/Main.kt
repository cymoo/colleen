import io.github.cymoo.colleen.Colleen
import io.github.cymoo.colleen.Context
import io.github.cymoo.colleen.Controller
import io.github.cymoo.colleen.Next
import io.github.cymoo.colleen.middleware.RequestLogger
import io.github.cymoo.colleen.ws.Ws
import io.github.cymoo.colleen.ws.WsConnection
import io.github.cymoo.colleen.ws.WsUse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WebSocket Example
 *
 * Features demonstrated:
 * - Echo server (text and binary)
 * - Chat rooms with path parameters and query parameters
 * - WebSocket middleware for authentication
 * - Connection lifecycle (onMessage, onClose, onError)
 * - Controller-style WebSocket handlers (@Ws and @WsUse annotations)
 * - WebSocket configuration (idle timeout, max message size, ping/pong, max connections)
 * - Concurrent connection management
 * - Mounted sub-application with WS routes and middleware
 * - HTML client page with interactive WebSocket demo
 */
fun main() {
    val app = Colleen()

    app.use(RequestLogger())

    // ========================================================================
    // Shared broadcast executor
    //
    // Broadcast iterates over potentially thousands of connections and calls
    // send() on each one. If this runs inline inside a connection's drain loop,
    // it blocks that connection from processing any further messages until the
    // entire broadcast completes. By offloading broadcast to a dedicated
    // executor, the calling connection's drain returns immediately and stays
    // responsive to new messages.
    // ========================================================================

    val broadcastExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "ws-broadcast"
        }
    }

    // ========================================================================
    // WebSocket Configuration
    // ========================================================================

    app.config {
        ws {
            idleTimeoutMs = 600_000          // 10 minutes idle timeout
            maxMessageSizeBytes = 128 * 1024 // 128 KB max message size
            pingIntervalMs = 30_000          // Ping every 30s to keep connections alive
            pingTimeoutMs = 10_000           // Close if no pong within 10s
            maxConnections = 1000            // Allow up to 1000 concurrent connections
        }
    }

    // ========================================================================
    // 1. Echo Server
    // ========================================================================

    app.ws("/echo") { conn ->
        conn.onMessage { msg ->
            conn.send(msg)
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

        // Announce join — broadcast is async, returns immediately
        broadcast(broadcastExecutor, connections, "[$room] $username joined (${connections.size} online)")

        conn.onMessage { msg ->
            broadcast(broadcastExecutor, connections, "[$room] $username: $msg")
        }

        conn.onClose {
            connections.remove(conn)
            if (connections.isEmpty()) {
                chatRooms.remove(room)
            } else {
                broadcast(broadcastExecutor, connections, "[$room] $username left (${connections.size} online)")
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
            ctx.setState("user", "authenticated-user")
            next()
        } else {
            ctx.status(401).text("Unauthorized: invalid or missing token")
        }
    }

    app.ws("/secure/data") { conn ->
        // Access state set by middleware and HTTP headers from the upgrade request
        val user = conn.getStateOrNull<String>("user") ?: "unknown"
        val origin = conn.header("Origin") ?: "unknown"
        conn.send("Welcome, $user! (Origin: $origin)")

        conn.onMessage { msg ->
            conn.send("Secure echo: $msg")
        }
    }

    // ========================================================================
    // 4. Controller-Style WebSocket (@Ws and @WsUse annotations)
    // ========================================================================

    app.addController(NotificationController())

    // ========================================================================
    // 5. Mounted Sub-Application with WS Routes
    // ========================================================================

    val adminApp = Colleen()

    // WS middleware on the sub-app.
    // Browsers cannot set arbitrary HTTP headers on WebSocket connections,
    // so we accept the role via query param as a fallback for browser clients.
    // Production code should use a short-lived signed token instead.
    adminApp.wsUse { ctx, next ->
        val role = ctx.header("X-Role") ?: ctx.query("role")
        if (role == "admin") {
            next()
        } else {
            ctx.status(403).text("Admin access required")
        }
    }

    adminApp.ws("/console") { conn ->
        conn.send("Welcome to admin console")
        conn.onMessage { msg ->
            conn.send("admin> $msg")
        }
    }

    app.mount("/admin", adminApp)

    // ========================================================================
    // 6. Home Page — HTML WebSocket Client
    // ========================================================================

    app.get("/") { ctx ->
        ctx.html(buildHtmlPage())
    }

    app.listen(8000)
    println("✅ WebSocket server running on http://localhost:8000")
}

// ============================================================================
// Controller with @WsUse Middleware
// ============================================================================

@Controller("/notifications")
class NotificationController {

    /** Controller-level WebSocket middleware — reject without token */
    @WsUse
    fun authenticate(ctx: Context, next: Next) {
        val token = ctx.query("token")
        if (token != null) {
            ctx.setState("subscriber", token)
            next()
        } else {
            ctx.status(401).text("Missing token query parameter")
        }
    }

    @Ws("/live")
    fun liveNotifications(conn: WsConnection) {
        val subscriber = conn.getStateOrNull<String>("subscriber") ?: "unknown"
        conn.send("Connected to notification stream (subscriber=$subscriber)")

        conn.onMessage { msg ->
            conn.send("Notification acknowledged: $msg")
        }

        conn.onClose { reason ->
            println("Notification client '$subscriber' disconnected: $reason")
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * Broadcasts [message] to all connections in [connections] asynchronously.
 *
 * The actual send loop runs on [executor] rather than on the calling thread.
 * This means the connection that triggered the broadcast (e.g. a chat message
 * sender) returns from its onMessage handler immediately, keeping its own
 * OrderedExecutor drain unblocked and responsive to subsequent messages.
 *
 * Individual send failures are swallowed via runCatching so that one broken
 * connection cannot prevent the rest from receiving the message. The snapshot
 * (toList()) is taken before submitting the task so that connections which
 * join or leave during the broadcast do not affect this iteration.
 */
private fun broadcast(
    executor: ExecutorService,
    connections: Set<WsConnection>,
    message: String,
) {
    // Take a snapshot so join/leave during broadcast doesn't affect iteration.
    val snapshot = connections.toList()
    executor.execute {
        snapshot.forEach { conn ->
            runCatching { conn.send(message) }
        }
    }
}

// ============================================================================
// HTML Page
// ============================================================================

private fun buildHtmlPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>WebSocket Demo</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=Syne:wght@400;700&display=swap');

  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  :root {
    --bg:        #0d0f14;
    --surface:   #13161d;
    --border:    #1e2330;
    --muted:     #3a3f52;
    --text:      #c8cedd;
    --text-dim:  #5a6070;
    --accent:    #4f9eff;
    --accent2:   #a78bfa;
    --green:     #34d399;
    --red:       #f87171;
    --amber:     #fbbf24;
    --font-ui:   'Syne', sans-serif;
    --font-mono: 'JetBrains Mono', monospace;
    --radius:    8px;
  }

  body {
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-ui);
    min-height: 100vh;
    padding: 32px 24px 64px;
  }

  header {
    max-width: 920px;
    margin: 0 auto 40px;
    display: flex;
    align-items: baseline;
    gap: 16px;
    border-bottom: 1px solid var(--border);
    padding-bottom: 20px;
  }
  header h1 { font-size: 26px; font-weight: 700; letter-spacing: -0.5px; }
  header span { font-size: 13px; color: var(--text-dim); font-family: var(--font-mono); }

  .grid {
    max-width: 920px;
    margin: 0 auto;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }
  .grid .card.wide { grid-column: 1 / -1; }

  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 20px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .card-title {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
    color: var(--text);
  }
  .badge {
    font-family: var(--font-mono);
    font-size: 10px;
    padding: 2px 7px;
    border-radius: 4px;
    background: var(--border);
    color: var(--text-dim);
    letter-spacing: 0.5px;
  }
  .badge.get  { background: #0f2a1a; color: var(--green); }
  .badge.auth { background: #1a1a0f; color: var(--amber); }
  .badge.ctrl { background: #1a0f2a; color: var(--accent2); }
  .badge.adm  { background: #2a0f0f; color: var(--red); }

  /* Dot indicator */
  .dot {
    width: 8px; height: 8px;
    border-radius: 50%;
    background: var(--muted);
    transition: background 0.3s;
    flex-shrink: 0;
  }
  .dot.on  { background: var(--green); box-shadow: 0 0 6px var(--green); }
  .dot.err { background: var(--red); }

  /* Log terminal */
  .log {
    background: #080a0f;
    border: 1px solid var(--border);
    border-radius: 6px;
    font-family: var(--font-mono);
    font-size: 12px;
    line-height: 1.6;
    padding: 10px 12px;
    min-height: 96px;
    max-height: 180px;
    overflow-y: auto;
    white-space: pre-wrap;
    word-break: break-all;
    color: #8b9db8;
    flex: 1;
  }
  .log .line-in   { color: var(--green); }
  .log .line-out  { color: var(--accent); }
  .log .line-sys  { color: var(--text-dim); }
  .log .line-err  { color: var(--red); }

  /* Controls */
  .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  .row input {
    flex: 1;
    min-width: 0;
    background: #080a0f;
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text);
    font-family: var(--font-mono);
    font-size: 12px;
    padding: 7px 10px;
    outline: none;
    transition: border-color 0.2s;
  }
  .row input:focus { border-color: var(--accent); }
  .row input.sm { flex: 0 0 90px; }

  button {
    font-family: var(--font-ui);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.3px;
    padding: 7px 14px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    transition: opacity 0.15s, transform 0.1s;
    white-space: nowrap;
  }
  button:active { transform: scale(0.97); }
  .btn-primary  { background: var(--accent);  color: #fff; }
  .btn-primary:hover  { opacity: 0.85; }
  .btn-secondary{ background: var(--border);  color: var(--text); }
  .btn-secondary:hover{ background: var(--muted); }
  .btn-danger   { background: #3a1515; color: var(--red); }
  .btn-danger:hover   { background: #4a1a1a; }

  /* Scrollbar */
  .log::-webkit-scrollbar { width: 4px; }
  .log::-webkit-scrollbar-track { background: transparent; }
  .log::-webkit-scrollbar-thumb { background: var(--muted); border-radius: 2px; }

  @media (max-width: 640px) {
    .grid { grid-template-columns: 1fr; }
    .grid .card.wide { grid-column: 1; }
  }
</style>
</head>
<body>

<header>
  <h1>WebSocket Demo</h1>
  <span>colleen / undertow</span>
</header>

<div class="grid">

  <!-- 1. Echo -->
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        <div class="dot" id="d1"></div>
        Echo Server
      </div>
      <span class="badge get">WS /echo</span>
    </div>
    <div class="log" id="l1"></div>
    <div class="row">
      <input id="echo-msg" placeholder="Type a message…"/>
      <button class="btn-primary"  onclick="echoSend()">Send</button>
      <button class="btn-secondary" onclick="echoConnect()">Connect</button>
      <button class="btn-danger"   onclick="echoClose()">Close</button>
    </div>
  </div>

  <!-- 2. Chat -->
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        <div class="dot" id="d2"></div>
        Chat Room
      </div>
      <span class="badge get">WS /chat/{room}</span>
    </div>
    <div class="log" id="l2"></div>
    <div class="row">
      <input class="sm" id="chat-room" value="general" placeholder="Room"/>
      <input class="sm" id="chat-name" value="User1"   placeholder="Name"/>
    </div>
    <div class="row">
      <input id="chat-msg" placeholder="Message…"/>
      <button class="btn-primary"  onclick="chatSend()">Send</button>
      <button class="btn-secondary" onclick="chatConnect()">Join</button>
      <button class="btn-danger"   onclick="chatClose()">Leave</button>
    </div>
  </div>

  <!-- 3. Secure (Auth Middleware) -->
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        <div class="dot" id="d3"></div>
        Auth Middleware
      </div>
      <span class="badge auth">WS /secure/data</span>
    </div>
    <div class="log" id="l3"></div>
    <div class="row">
      <input id="secure-token" placeholder="Token (try: secret123)" value="secret123"/>
    </div>
    <div class="row">
      <input id="secure-msg" placeholder="Message…"/>
      <button class="btn-primary"  onclick="secureSend()">Send</button>
      <button class="btn-secondary" onclick="secureConnect()">Connect</button>
      <button class="btn-danger"   onclick="secureClose()">Close</button>
    </div>
  </div>

  <!-- 4. Notifications (Controller + @WsUse) -->
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        <div class="dot" id="d4"></div>
        Notifications
      </div>
      <span class="badge ctrl">WS /notifications/live</span>
    </div>
    <div class="log" id="l4"></div>
    <div class="row">
      <input id="notif-token" placeholder="Subscriber token" value="user-abc"/>
    </div>
    <div class="row">
      <input id="notif-msg" placeholder="Acknowledge message…"/>
      <button class="btn-primary"  onclick="notifSend()">Send</button>
      <button class="btn-secondary" onclick="notifConnect()">Subscribe</button>
      <button class="btn-danger"   onclick="notifClose()">Unsubscribe</button>
    </div>
  </div>

  <!-- 5. Admin Console (Mounted Sub-App) -->
  <div class="card wide">
    <div class="card-header">
      <div class="card-title">
        <div class="dot" id="d5"></div>
        Admin Console
      </div>
      <span class="badge adm">WS /admin/console · requires X-Role: admin</span>
    </div>
    <div class="log" id="l5"></div>
    <div class="row">
      <input id="admin-role" value="admin" placeholder="X-Role header value"/>
      <button class="btn-secondary" onclick="adminConnect()">Connect</button>
      <button class="btn-danger"   onclick="adminClose()">Disconnect</button>
    </div>
    <div class="row">
      <input id="admin-msg" placeholder="Admin command…" style="flex:1"/>
      <button class="btn-primary" onclick="adminSend()">Execute</button>
    </div>
  </div>

</div>

<script>
// ─── Shared helpers ───────────────────────────────────────────────────────────

function appendLog(id, text, cls) {
  const el = document.getElementById(id);
  const line = document.createElement('span');
  line.className = 'line-' + cls;
  line.textContent = text + '\n';
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

function dot(id, state) {
  const el = document.getElementById('d' + id);
  el.className = 'dot' + (state === 'on' ? ' on' : state === 'err' ? ' err' : '');
}

// ─── 1. Echo ─────────────────────────────────────────────────────────────────

let echoWs = null;
function echoConnect() {
  if (echoWs) { appendLog('l1','Already connected','sys'); return; }
  echoWs = new WebSocket('ws://' + location.host + '/echo');
  echoWs.onopen    = () => { dot(1,'on');  appendLog('l1','● connected','sys'); };
  echoWs.onmessage = e  => appendLog('l1','← ' + e.data, 'in');
  echoWs.onclose   = e  => { dot(1,'');   appendLog('l1','○ closed (' + e.code + ')','sys'); echoWs = null; };
  echoWs.onerror   = ()  => { dot(1,'err'); appendLog('l1','✕ error','err'); };
}
function echoSend() {
  const v = document.getElementById('echo-msg').value.trim();
  if (!v) return;
  if (!echoWs) { echoConnect(); setTimeout(echoSend, 300); return; }
  echoWs.send(v);
  appendLog('l1','→ ' + v, 'out');
  document.getElementById('echo-msg').value = '';
}
function echoClose() { echoWs?.close(); }
document.getElementById('echo-msg').onkeydown = e => { if (e.key==='Enter') echoSend(); };

// ─── 2. Chat ─────────────────────────────────────────────────────────────────

let chatWs = null;
function chatConnect() {
  chatWs?.close();
  const room = document.getElementById('chat-room').value || 'general';
  const name = document.getElementById('chat-name').value || 'Anon';
  chatWs = new WebSocket('ws://' + location.host + '/chat/' + encodeURIComponent(room) + '?name=' + encodeURIComponent(name));
  chatWs.onopen    = () => { dot(2,'on');  appendLog('l2','● joined #' + room + ' as ' + name,'sys'); };
  chatWs.onmessage = e  => appendLog('l2', e.data, 'in');
  chatWs.onclose   = ()  => { dot(2,'');   appendLog('l2','○ left room','sys'); chatWs = null; };
  chatWs.onerror   = ()  => { dot(2,'err'); appendLog('l2','✕ error','err'); };
}
function chatSend() {
  const v = document.getElementById('chat-msg').value.trim();
  if (!v) return;
  if (!chatWs) { chatConnect(); setTimeout(chatSend, 300); return; }
  chatWs.send(v);
  document.getElementById('chat-msg').value = '';
}
function chatClose() { chatWs?.close(); }
document.getElementById('chat-msg').onkeydown = e => { if (e.key==='Enter') chatSend(); };

// ─── 3. Secure (token via query param) ───────────────────────────────────────
//
// When the server rejects the WS upgrade (401), the browser fires onerror
// immediately followed by onclose(1006). To avoid showing two messages for a
// single failure, we track whether onerror already fired and suppress the
// redundant onclose log line.

let secureWs = null;
function secureConnect() {
  secureWs?.close();
  const token = document.getElementById('secure-token').value.trim();
  const url = 'ws://' + location.host + '/secure/data' + (token ? '?token=' + encodeURIComponent(token) : '');
  let errorFired = false;
  secureWs = new WebSocket(url);
  secureWs.onopen    = () => { dot(3,'on'); appendLog('l3','● connected','sys'); };
  secureWs.onmessage = e  => appendLog('l3','← ' + e.data, 'in');
  secureWs.onerror   = () => {
    errorFired = true;
    dot(3,'err');
    appendLog('l3','✕ connection refused — wrong token or server error','err');
  };
  secureWs.onclose   = e  => {
    secureWs = null;
    if (errorFired) return;   // onerror already reported the failure
    dot(3, e.code === 1000 ? '' : 'err');
    appendLog('l3', '○ closed (' + e.code + ')', e.code === 1000 ? 'sys' : 'err');
  };
}
function secureSend() {
  const v = document.getElementById('secure-msg').value.trim();
  if (!v) return;
  // Do NOT auto-reconnect here: if the last connect failed (bad token),
  // silently re-trying would flood the log with repeated errors.
  if (!secureWs) { appendLog('l3','⚠ not connected — click Connect first','err'); return; }
  secureWs.send(v);
  appendLog('l3','→ ' + v, 'out');
  document.getElementById('secure-msg').value = '';
}
function secureClose() { secureWs?.close(); }
document.getElementById('secure-msg').onkeydown = e => { if (e.key==='Enter') secureSend(); };

// ─── 4. Notifications (@Controller + @WsUse) ─────────────────────────────────

let notifWs = null;
function notifConnect() {
  notifWs?.close();
  const token = document.getElementById('notif-token').value.trim();
  if (!token) { appendLog('l4','⚠ token required','err'); return; }
  notifWs = new WebSocket('ws://' + location.host + '/notifications/live?token=' + encodeURIComponent(token));
  notifWs.onopen    = () => { dot(4,'on');  appendLog('l4','● subscribed (token=' + token + ')','sys'); };
  notifWs.onmessage = e  => appendLog('l4','← ' + e.data, 'in');
  notifWs.onclose   = e  => { dot(4,'');   appendLog('l4','○ unsubscribed (' + e.code + ')','sys'); notifWs = null; };
  notifWs.onerror   = ()  => { dot(4,'err'); appendLog('l4','✕ error','err'); };
}
function notifSend() {
  const v = document.getElementById('notif-msg').value.trim();
  if (!v) return;
  if (!notifWs) { appendLog('l4','⚠ not connected','err'); return; }
  notifWs.send(v);
  appendLog('l4','→ ' + v, 'out');
  document.getElementById('notif-msg').value = '';
}
function notifClose() { notifWs?.close(); }
document.getElementById('notif-msg').onkeydown = e => { if (e.key==='Enter') notifSend(); };

// ─── 5. Admin Console ────────────────────────────────────────────────────────
//
// Browsers cannot set arbitrary HTTP headers on WebSocket connections.
// The server-side middleware now accepts ?role=... as a fallback (in addition
// to the X-Role header used by non-browser clients), so we pass it as a query
// param here. Production code should use a short-lived signed token instead.

let adminWs = null;
function adminConnect() {
  adminWs?.close();
  const role = document.getElementById('admin-role').value.trim() || 'admin';
  let errorFired = false;
  adminWs = new WebSocket('ws://' + location.host + '/admin/console?role=' + encodeURIComponent(role));
  adminWs.onopen    = () => { dot(5,'on');  appendLog('l5','● connected to admin console','sys'); };
  adminWs.onmessage = e  => appendLog('l5','← ' + e.data, 'in');
  adminWs.onerror   = () => {
    errorFired = true;
    dot(5,'err');
    appendLog('l5','✕ connection refused — check role value (expected: admin)','err');
  };
  adminWs.onclose   = e  => {
    adminWs = null;
    if (errorFired) return;   // onerror already reported the failure
    dot(5, e.code === 1000 ? '' : 'err');
    appendLog('l5', '○ closed (' + e.code + ')', e.code === 1000 ? 'sys' : 'err');
  };
}
function adminSend() {
  const v = document.getElementById('admin-msg').value.trim();
  if (!v) return;
  if (!adminWs) { appendLog('l5','⚠ not connected','err'); return; }
  adminWs.send(v);
  appendLog('l5','→ ' + v, 'out');
  document.getElementById('admin-msg').value = '';
}
function adminClose() { adminWs?.close(); }
document.getElementById('admin-msg').onkeydown = e => { if (e.key==='Enter') adminSend(); };
</script>
</body>
</html>
""".trimIndent()