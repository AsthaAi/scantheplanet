package ai.astha.scantheplanet.idea.oauth

import ai.astha.scantheplanet.idea.settings.OAuthIdentityEntry
import ai.astha.scantheplanet.idea.settings.OAuthProviderConfigEntry
import ai.astha.scantheplanet.idea.settings.OAuthSettingsService
import ai.astha.scantheplanet.idea.settings.TokenMetadataEntry
import ai.astha.scantheplanet.idea.storage.TokenStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.RestService
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class OAuthService {
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val ttlMillis = TimeUnit.MINUTES.toMillis(5)
    private val pendingStore = PendingAuthStore(ttlMillis)

    fun getConfiguredProvider(settingsService: OAuthSettingsService, providerId: String? = null): OAuthProvider? {
        val selectedId = providerId ?: settingsService.state.selectedProviderId
        val template = Providers.templateById(selectedId) ?: return null
        val configEntry = settingsService.getProviderConfig(selectedId) ?: OAuthProviderConfigEntry(id = selectedId)
        val config = configFromEntry(configEntry)
        if (config.clientId.isBlank()) return null
        if (template.id == "custom") {
            if (configEntry.authorizeEndpoint.isBlank() || configEntry.tokenEndpoint.isBlank()) return null
        }
        return Providers.buildProvider(template, config)
    }

    fun startLogin(provider: OAuthProvider): URL {
        cleanupExpiredAttempts()
        val state = Pkce.generateState()
        val codeVerifier = Pkce.generateCodeVerifier()
        val codeChallenge = Pkce.codeChallenge(codeVerifier)
        val redirectUri = buildRedirectUri()

        pendingStore.put(state, PendingAuthAttempt(
            providerId = provider.id,
            codeVerifier = codeVerifier,
            redirectUri = redirectUri,
            createdAtMillis = System.currentTimeMillis()
        ))

        val params = mutableMapOf(
            "response_type" to "code",
            "client_id" to provider.clientId,
            "redirect_uri" to redirectUri,
            "scope" to provider.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256"
        )

        provider.audience?.takeIf { it.isNotBlank() }?.let { params["audience"] = it }
        provider.resource?.takeIf { it.isNotBlank() }?.let { params["resource"] = it }
        params.putAll(provider.additionalAuthParams)

        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val base = provider.authorizeEndpoint.toString()
        val separator = if (base.contains("?")) "&" else "?"
        return URI.create(base + separator + query).toURL()
    }

    fun handleCallback(
        settingsService: OAuthSettingsService,
        tokenStore: TokenStore,
        state: String?,
        code: String?,
        error: String?,
        errorDescription: String?
    ): String {
        cleanupExpiredAttempts()
        if (!error.isNullOrBlank()) {
            notifyError("OAuth sign-in failed", errorDescription ?: error)
            return "Authorization failed. You may close this window."
        }

        if (state.isNullOrBlank() || code.isNullOrBlank()) {
            notifyError("OAuth sign-in failed", "Missing state or authorization code.")
            return "Invalid OAuth response. You may close this window."
        }

        val attempt = pendingStore.consume(state)
        if (attempt == null || attempt.isExpired(ttlMillis)) {
            notifyError("OAuth sign-in failed", "Authorization attempt expired. Please try again.")
            return "Authorization expired. You may close this window."
        }

        val provider = getConfiguredProvider(settingsService, attempt.providerId)
            ?: run {
                notifyError("OAuth sign-in failed", "Provider is not configured.")
                return "Provider not configured. You may close this window."
            }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                exchangeCodeForTokens(settingsService, tokenStore, provider, attempt, code)
                RestService.activateLastFocusedFrame()
            } catch (ex: Exception) {
                notifyError("OAuth sign-in failed", "Token exchange failed. Check provider configuration.")
            }
        }

        return "Authorization complete. You may close this window."
    }

    fun refreshTokensIfNeeded(
        settingsService: OAuthSettingsService,
        tokenStore: TokenStore,
        provider: OAuthProvider
    ): Boolean {
        if (!provider.supportsRefreshToken) return false
        val metadata = settingsService.getTokenMetadata(provider.id)
        val expiresAt = metadata?.expiresAtEpochSeconds ?: return false
        val now = Instant.now().epochSecond
        if (expiresAt == 0L || now < expiresAt - 60) return false

        val refreshToken = tokenStore.loadRefreshToken(provider.id) ?: return false
        val clientSecret = if (provider.usesClientSecret) tokenStore.loadClientSecret(provider.id) else null

        val params = mutableMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to provider.clientId
        )
        if (!clientSecret.isNullOrBlank()) {
            params["client_secret"] = clientSecret
        }

        val response = postForm(provider.tokenEndpoint.toURI(), params)
        if (response.statusCode() !in 200..299) return false
        val tokenResponse = provider.tokenResponseParser.parse(response.body())
        storeTokens(settingsService, tokenStore, provider, tokenResponse, preserveRefreshToken = true)
        return true
    }

    fun clearTokens(settingsService: OAuthSettingsService, tokenStore: TokenStore, providerId: String) {
        tokenStore.clearTokens(providerId)
        settingsService.clearTokenMetadata(providerId)
        settingsService.clearIdentity(providerId)
    }

    private fun exchangeCodeForTokens(
        settingsService: OAuthSettingsService,
        tokenStore: TokenStore,
        provider: OAuthProvider,
        attempt: PendingAuthAttempt,
        code: String
    ) {
        val clientSecret = if (provider.usesClientSecret) tokenStore.loadClientSecret(provider.id) else null
        val params = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to attempt.redirectUri,
            "client_id" to provider.clientId,
            "code_verifier" to attempt.codeVerifier
        )
        if (!clientSecret.isNullOrBlank()) {
            params["client_secret"] = clientSecret
        }

        val response = postForm(provider.tokenEndpoint.toURI(), params)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Token endpoint returned ${response.statusCode()}")
        }

        val tokenResponse = provider.tokenResponseParser.parse(response.body())
        storeTokens(settingsService, tokenStore, provider, tokenResponse, preserveRefreshToken = false)
        val identity = resolveIdentity(provider, tokenResponse)
        if (identity != null) {
            settingsService.putIdentity(
                OAuthIdentityEntry(
                    id = provider.id,
                    subject = identity.subject ?: "",
                    displayName = identity.displayName ?: "",
                    email = identity.email ?: ""
                )
            )
            notifyInfo("Signed in", "Signed in as ${identity.displayName ?: identity.email ?: "user"}.")
        } else {
            notifyInfo("Signed in", "Signed in successfully.")
        }
    }

    private fun storeTokens(
        settingsService: OAuthSettingsService,
        tokenStore: TokenStore,
        provider: OAuthProvider,
        tokenResponse: TokenResponse,
        preserveRefreshToken: Boolean
    ) {
        val refreshToken = if (tokenResponse.refreshToken == null && preserveRefreshToken) {
            tokenStore.loadRefreshToken(provider.id)
        } else {
            tokenResponse.refreshToken
        }
        tokenStore.saveTokens(provider.id, tokenResponse.accessToken, refreshToken, tokenResponse.idToken)
        val expiresAt = tokenResponse.expiresIn?.let { Instant.now().epochSecond + it } ?: 0
        settingsService.putTokenMetadata(
            TokenMetadataEntry(
                id = provider.id,
                tokenType = tokenResponse.tokenType,
                expiresAtEpochSeconds = expiresAt
            )
        )
    }

    private fun resolveIdentity(
        provider: OAuthProvider,
        tokenResponse: TokenResponse
    ): OAuthIdentity? {
        if (provider.hasOpenIdScope && tokenResponse.idToken != null) {
            parseIdToken(tokenResponse.idToken)?.let { return it }
        }
        val userInfoEndpoint = provider.userInfoEndpoint ?: return null
        val accessToken = tokenResponse.accessToken
        val response = getJson(userInfoEndpoint.toURI(), accessToken)
        if (response.statusCode() !in 200..299) return null
        val obj = gson.fromJson(response.body(), JsonObject::class.java)
        val subject = obj.get("sub")?.asString ?: obj.get("id")?.asString
        val displayName = obj.get("name")?.asString ?: obj.get("login")?.asString
        val email = obj.get("email")?.asString
        return OAuthIdentity(subject, displayName, email)
    }

    private fun parseIdToken(idToken: String): OAuthIdentity? {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val payload = parts[1]
        val normalizedPayload = padBase64Url(payload)
        val decoded = try {
            java.util.Base64.getUrlDecoder().decode(normalizedPayload)
        } catch (ex: IllegalArgumentException) {
            return null
        }
        val json = String(decoded, Charsets.UTF_8)
        val obj = gson.fromJson(json, JsonObject::class.java)
        val subject = obj.get("sub")?.asString
        val displayName = obj.get("name")?.asString
            ?: obj.get("preferred_username")?.asString
        val email = obj.get("email")?.asString
        return OAuthIdentity(subject, displayName, email)
    }

    private fun padBase64Url(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) {
            value
        } else {
            value + "=".repeat(4 - remainder)
        }
    }

    private fun buildRedirectUri(): String {
        val port = BuiltInServerManager.getInstance().waitForStart().port
        return "http://127.0.0.1:$port/api/$PLUGIN_ID/oauth/callback"
    }

    private fun postForm(uri: URI, params: Map<String, String>): HttpResponse<String> {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getJson(uri: URI, accessToken: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun notifyError(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(null)
    }

    private fun notifyInfo(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(null)
    }

    private fun cleanupExpiredAttempts() {
        pendingStore.cleanup()
    }

    private fun configFromEntry(entry: OAuthProviderConfigEntry): OAuthProviderConfig {
        return OAuthProviderConfig(
            clientId = entry.clientId.trim(),
            authorizeEndpoint = entry.authorizeEndpoint.trim().ifBlank { null },
            tokenEndpoint = entry.tokenEndpoint.trim().ifBlank { null },
            scopes = entry.scopes.split(',',' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            audience = entry.audience.trim().ifBlank { null },
            resource = entry.resource.trim().ifBlank { null },
            additionalAuthParams = parseAdditionalParams(entry.additionalAuthParams),
            usesClientSecret = entry.usesClientSecret,
            supportsRefreshToken = entry.supportsRefreshToken,
            userInfoEndpoint = entry.userInfoEndpoint.trim().ifBlank { null }
        )
    }

    private fun parseAdditionalParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.contains('=')) return@mapNotNull null
                val parts = trimmed.split('=', limit = 2)
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val PLUGIN_ID = "ai.astha.scantheplanet.idea"
        const val NOTIFICATION_GROUP = "Scan The Planet OAuth"
    }
}
