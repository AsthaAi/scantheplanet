package ai.astha.scantheplanet.idea.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generateState(): String = randomUrlSafeString(32)

    fun generateCodeVerifier(): String = randomUrlSafeString(64)

    fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(hashed)
    }

    private fun randomUrlSafeString(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}
