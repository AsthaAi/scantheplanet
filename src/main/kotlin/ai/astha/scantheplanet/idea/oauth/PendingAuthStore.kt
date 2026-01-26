package ai.astha.scantheplanet.idea.oauth

import java.util.concurrent.ConcurrentHashMap

class PendingAuthStore(private val ttlMillis: Long) {
    private val pending = ConcurrentHashMap<String, PendingAuthAttempt>()

    fun put(state: String, attempt: PendingAuthAttempt) {
        pending[state] = attempt
    }

    fun consume(state: String): PendingAuthAttempt? = pending.remove(state)

    fun cleanup(nowMillis: Long = System.currentTimeMillis()) {
        pending.entries.removeIf { (_, attempt) -> nowMillis - attempt.createdAtMillis > ttlMillis }
    }

    fun isEmpty(): Boolean = pending.isEmpty()
}

data class PendingAuthAttempt(
    val providerId: String,
    val codeVerifier: String,
    val redirectUri: String,
    val createdAtMillis: Long
) {
    fun isExpired(ttlMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - createdAtMillis > ttlMillis
}
