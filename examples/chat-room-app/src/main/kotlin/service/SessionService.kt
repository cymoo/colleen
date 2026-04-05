package service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SessionInfo(val userId: Int, val expiresAt: Long)

/**
 * In-memory session management. Sessions survive until server restart or expiry.
 */
class SessionService {
    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val sessionDurationMs = 7 * 24 * 60 * 60 * 1000L // 7 days

    init {
        // Periodically clean up expired sessions
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r).apply { isDaemon = true; name = "session-cleanup" }
        }
        scheduler.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            sessions.entries.removeIf { it.value.expiresAt < now }
        }, 1, 1, TimeUnit.HOURS)
    }

    fun createSession(userId: Int): String {
        val token = UUID.randomUUID().toString()
        sessions[token] = SessionInfo(userId, System.currentTimeMillis() + sessionDurationMs)
        return token
    }

    fun validateSession(token: String): Int? {
        val session = sessions[token] ?: return null
        if (session.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return session.userId
    }

    fun invalidateSession(token: String) {
        sessions.remove(token)
    }
}
