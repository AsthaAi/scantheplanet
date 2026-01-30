package ai.astha.scantheplanet.idea.oauth

import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    private val urlSafeRegex = Regex("^[A-Za-z0-9_-]+$")

    @Test
    fun generatesUrlSafeStateAndVerifier() {
        val state = Pkce.generateState()
        val verifier = Pkce.generateCodeVerifier()
        assertTrue(state.matches(urlSafeRegex))
        assertTrue(verifier.matches(urlSafeRegex))
        assertTrue(verifier.length in 43..128)
    }

    @Test
    fun codeChallengeIsDeterministicAndUrlSafe() {
        val verifier = "test_verifier_1234567890"
        val challenge1 = Pkce.codeChallenge(verifier)
        val challenge2 = Pkce.codeChallenge(verifier)
        assertTrue(challenge1.matches(urlSafeRegex))
        assertTrue(challenge1 == challenge2)
    }
}
