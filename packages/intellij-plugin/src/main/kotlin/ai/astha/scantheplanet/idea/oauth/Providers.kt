package ai.astha.scantheplanet.idea.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.URL

private const val SCOPE_OPENID = "openid"
private const val SCOPE_PROFILE = "profile"
private const val SCOPE_EMAIL = "email"

object Providers {
    private val gson = Gson()

    val defaultTokenParser = TokenResponseParser { json ->
        val obj = gson.fromJson(json, JsonObject::class.java)
        val accessToken = obj.get("access_token")?.asString
            ?: throw IllegalArgumentException("Missing access_token in token response")
        val tokenType = obj.get("token_type")?.asString ?: "Bearer"
        val expiresIn = obj.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong
        val refreshToken = obj.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString
        val idToken = obj.get("id_token")?.takeIf { it.isJsonPrimitive }?.asString
        TokenResponse(accessToken, tokenType, expiresIn, refreshToken, idToken, json)
    }

    val templates: List<OAuthProviderTemplate> = listOf(
        OAuthProviderTemplate(
            id = "github",
            displayName = "GitHub",
            authorizeEndpoint = URI.create("https://github.com/login/oauth/authorize").toURL(),
            tokenEndpoint = URI.create("https://github.com/login/oauth/access_token").toURL(),
            defaultScopes = listOf("read:user", "user:email"),
            usesClientSecret = true,
            supportsRefreshToken = false,
            additionalAuthParams = emptyMap(),
            userInfoEndpoint = URI.create("https://api.github.com/user").toURL(),
            tokenResponseParser = defaultTokenParser
        ),
        OAuthProviderTemplate(
            id = "google",
            displayName = "Google",
            authorizeEndpoint = URI.create("https://accounts.google.com/o/oauth2/v2/auth").toURL(),
            tokenEndpoint = URI.create("https://oauth2.googleapis.com/token").toURL(),
            defaultScopes = listOf(SCOPE_OPENID, SCOPE_PROFILE, SCOPE_EMAIL),
            usesClientSecret = true,
            supportsRefreshToken = true,
            additionalAuthParams = mapOf(
                "access_type" to "offline",
                "prompt" to "consent"
            ),
            tokenResponseParser = defaultTokenParser
        ),
        OAuthProviderTemplate(
            id = "okta",
            displayName = "Okta",
            authorizeEndpoint = URI.create("https://example.okta.com/oauth2/default/v1/authorize").toURL(),
            tokenEndpoint = URI.create("https://example.okta.com/oauth2/default/v1/token").toURL(),
            defaultScopes = listOf(SCOPE_OPENID, SCOPE_PROFILE, SCOPE_EMAIL, "offline_access"),
            usesClientSecret = true,
            supportsRefreshToken = true,
            tokenResponseParser = defaultTokenParser
        ),
        OAuthProviderTemplate(
            id = "entra",
            displayName = "Microsoft Entra ID",
            authorizeEndpoint = URI.create("https://login.microsoftonline.com/common/oauth2/v2.0/authorize").toURL(),
            tokenEndpoint = URI.create("https://login.microsoftonline.com/common/oauth2/v2.0/token").toURL(),
            defaultScopes = listOf(SCOPE_OPENID, SCOPE_PROFILE, SCOPE_EMAIL, "offline_access"),
            usesClientSecret = true,
            supportsRefreshToken = true,
            tokenResponseParser = defaultTokenParser
        ),
        OAuthProviderTemplate(
            id = "keycloak",
            displayName = "Keycloak",
            authorizeEndpoint = URI.create("https://example.com/realms/realm/protocol/openid-connect/auth").toURL(),
            tokenEndpoint = URI.create("https://example.com/realms/realm/protocol/openid-connect/token").toURL(),
            defaultScopes = listOf(SCOPE_OPENID, SCOPE_PROFILE, SCOPE_EMAIL, "offline_access"),
            usesClientSecret = true,
            supportsRefreshToken = true,
            tokenResponseParser = defaultTokenParser
        ),
        OAuthProviderTemplate(
            id = "custom",
            displayName = "Custom Provider",
            authorizeEndpoint = URI.create("https://example.com/oauth/authorize").toURL(),
            tokenEndpoint = URI.create("https://example.com/oauth/token").toURL(),
            defaultScopes = listOf("read"),
            usesClientSecret = false,
            supportsRefreshToken = false,
            tokenResponseParser = defaultTokenParser
        )
    )

    fun templateById(id: String): OAuthProviderTemplate? = templates.firstOrNull { it.id == id }

    fun buildProvider(template: OAuthProviderTemplate, config: OAuthProviderConfig): OAuthProvider {
        val authorizeEndpoint = config.authorizeEndpoint?.toUrl() ?: template.authorizeEndpoint
        val tokenEndpoint = config.tokenEndpoint?.toUrl() ?: template.tokenEndpoint
        val userInfoEndpoint = config.userInfoEndpoint?.toUrl() ?: template.userInfoEndpoint
        val scopes = config.scopes.ifEmpty { template.defaultScopes }
        val additionalAuthParams = if (config.additionalAuthParams.isNotEmpty()) {
            config.additionalAuthParams
        } else {
            template.additionalAuthParams
        }
        val usesClientSecret = config.usesClientSecret ?: template.usesClientSecret
        val supportsRefreshToken = config.supportsRefreshToken ?: template.supportsRefreshToken

        return ConfiguredOAuthProvider(
            id = template.id,
            displayName = template.displayName,
            authorizeEndpoint = authorizeEndpoint,
            tokenEndpoint = tokenEndpoint,
            scopes = scopes,
            clientId = config.clientId,
            audience = config.audience ?: template.audience,
            resource = config.resource ?: template.resource,
            additionalAuthParams = additionalAuthParams,
            usesClientSecret = usesClientSecret,
            supportsRefreshToken = supportsRefreshToken,
            tokenResponseParser = template.tokenResponseParser,
            userInfoEndpoint = userInfoEndpoint
        )
    }
}

data class OAuthProviderTemplate(
    val id: String,
    val displayName: String,
    val authorizeEndpoint: URL,
    val tokenEndpoint: URL,
    val defaultScopes: List<String>,
    val audience: String? = null,
    val resource: String? = null,
    val additionalAuthParams: Map<String, String> = emptyMap(),
    val usesClientSecret: Boolean = false,
    val supportsRefreshToken: Boolean = false,
    val tokenResponseParser: TokenResponseParser,
    val userInfoEndpoint: URL? = null
)

data class OAuthProviderConfig(
    val clientId: String,
    val authorizeEndpoint: String?,
    val tokenEndpoint: String?,
    val scopes: List<String>,
    val audience: String?,
    val resource: String?,
    val additionalAuthParams: Map<String, String>,
    val usesClientSecret: Boolean?,
    val supportsRefreshToken: Boolean?,
    val userInfoEndpoint: String?
)

data class ConfiguredOAuthProvider(
    override val id: String,
    override val displayName: String,
    override val authorizeEndpoint: URL,
    override val tokenEndpoint: URL,
    override val scopes: List<String>,
    override val clientId: String,
    override val audience: String?,
    override val resource: String?,
    override val additionalAuthParams: Map<String, String>,
    override val usesClientSecret: Boolean,
    override val supportsRefreshToken: Boolean,
    override val tokenResponseParser: TokenResponseParser,
    override val userInfoEndpoint: URL?
) : OAuthProvider

private fun String.toUrl(): URL = URI.create(this).toURL()
