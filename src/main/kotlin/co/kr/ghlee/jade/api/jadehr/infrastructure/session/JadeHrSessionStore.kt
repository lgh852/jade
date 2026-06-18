package co.kr.ghlee.jade.api.jadehr.infrastructure.session

import co.kr.ghlee.jade.api.jadehr.domain.JadeHrSession
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface JadeHrSessionStore {
    fun get(companyCode: String, userId: String): JadeHrSession?
    fun save(companyCode: String, userId: String, session: JadeHrSession)
    fun remove(companyCode: String, userId: String)
}

@Component
class InMemoryJadeHrSessionStore : JadeHrSessionStore {
    private val sessions = ConcurrentHashMap<SessionKey, CachedSession>()
    private val ttl = Duration.ofHours(4)

    override fun get(companyCode: String, userId: String): JadeHrSession? {
        val key = SessionKey(companyCode, userId)
        val cached = sessions[key] ?: return null

        if (cached.isExpired()) {
            sessions.remove(key)
            return null
        }

        return cached.session
    }

    override fun save(companyCode: String, userId: String, session: JadeHrSession) {
        sessions[SessionKey(companyCode, userId)] = CachedSession(
            session = session,
            expiresAt = Instant.now().plus(ttl),
        )
    }

    override fun remove(companyCode: String, userId: String) {
        sessions.remove(SessionKey(companyCode, userId))
    }

    private data class SessionKey(
        val companyCode: String,
        val userId: String,
    )

    private data class CachedSession(
        val session: JadeHrSession,
        val expiresAt: Instant,
    ) {
        fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
    }
}
