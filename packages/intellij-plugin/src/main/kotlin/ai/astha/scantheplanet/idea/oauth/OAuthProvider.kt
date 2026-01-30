package ai.astha.scantheplanet.idea.oauth

import java.net.URL

fun interface TokenResponseParser {
    fun parse(json: String): TokenResponse
}

data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long?,
    val refreshToken: String?,
    val idToken: String?,
    val rawJson: String
)

interface OAuthProvider {
    val id: String
    val displayName: String
    val authorizeEndpoint: URL
    val tokenEndpoint: URL
    val scopes: List<String>
    val clientId: String
    val audience: String?
    val resource: String?
    val additionalAuthParams: Map<String, String>
    val usesClientSecret: Boolean
    val supportsRefreshToken: Boolean
    val tokenResponseParser: TokenResponseParser
    val userInfoEndpoint: URL?

    val hasOpenIdScope: Boolean
        get() = scopes.any { it.equals("openid", ignoreCase = true) }
}

data class OAuthIdentity(
    val subject: String?,
    val displayName: String?,
    val email: String?
)
