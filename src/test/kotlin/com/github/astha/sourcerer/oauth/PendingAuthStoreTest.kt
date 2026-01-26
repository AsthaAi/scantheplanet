package ai.astha.scantheplanet.idea.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAuthStoreTest {
    @Test
    fun cleansUpExpiredAttempts() {
        val store = PendingAuthStore(ttlMillis = 1000)
        val attempt = PendingAuthAttempt(
            providerId = "github",
            codeVerifier = "verifier",
            redirectUri = "http://127.0.0.1:1234/api/test",
            createdAtMillis = 0
        )
        store.put("state", attempt)
        store.cleanup(nowMillis = 500)
        assertFalse(store.isEmpty())
        store.cleanup(nowMillis = 2000)
        assertTrue(store.isEmpty())
    }
}
