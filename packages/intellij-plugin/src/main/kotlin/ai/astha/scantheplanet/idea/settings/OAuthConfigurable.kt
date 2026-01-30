package ai.astha.scantheplanet.idea.settings

import ai.astha.scantheplanet.idea.oauth.OAuthProviderConfig
import ai.astha.scantheplanet.idea.oauth.Providers
import ai.astha.scantheplanet.idea.storage.TokenStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

class OAuthConfigurable : Configurable {
    private var form: OAuthSettingsForm? = null

    override fun getDisplayName(): String = "Scan The Planet OAuth"

    override fun createComponent(): JComponent {
        val form = OAuthSettingsForm()
        this.form = form
        return form.panel
    }

    override fun isModified(): Boolean {
        val form = form ?: return false
        return form.isModified()
    }

    override fun apply() {
        form?.apply()
    }

    override fun reset() {
        form?.reset()
    }

    override fun disposeUIResources() {
        form = null
    }
}

private class OAuthSettingsForm {
    private val settingsService = ApplicationManager.getApplication().getService(OAuthSettingsService::class.java)
    private val tokenStore = TokenStore(ai.astha.scantheplanet.idea.oauth.OAuthService.PLUGIN_ID)
    private val templates = Providers.templates

    private val providerBox = JComboBox(templates.map { ProviderItem(it.id, it.displayName) }.toTypedArray())
    private val clientIdField = JBTextField()
    private val authorizeEndpointField = JBTextField()
    private val tokenEndpointField = JBTextField()
    private val scopesField = JBTextField()
    private val audienceField = JBTextField()
    private val resourceField = JBTextField()
    private val userInfoEndpointField = JBTextField()
    private val usesClientSecretBox = JBCheckBox("Requires client secret")
    private val supportsRefreshTokenBox = JBCheckBox("Supports refresh token")
    private val clientSecretField = JBPasswordField()
    private val additionalParamsArea = JBTextArea(4, 40)
    private val testButton = JButton("Test provider configuration")

    val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Provider", providerBox)
        .addSeparator()
        .addLabeledComponent("Client ID", clientIdField)
        .addLabeledComponent("Client secret (leave blank to keep)", clientSecretField)
        .addLabeledComponent("Authorization endpoint", authorizeEndpointField)
        .addLabeledComponent("Token endpoint", tokenEndpointField)
        .addLabeledComponent("Scopes (space or comma-separated)", scopesField)
        .addLabeledComponent("Audience (optional)", audienceField)
        .addLabeledComponent("Resource (optional)", resourceField)
        .addLabeledComponent("User info endpoint (optional)", userInfoEndpointField)
        .addComponent(usesClientSecretBox)
        .addComponent(supportsRefreshTokenBox)
        .addLabeledComponent("Additional auth params (key=value per line)", JBScrollPane(additionalParamsArea))
        .addComponent(testButton)
        .addComponentFillVertically(JBTextField(), 0)
        .panel

    init {
        providerBox.addActionListener { loadSelectedProvider() }
        testButton.addActionListener { testProviderConfiguration() }
        reset()
    }

    fun isModified(): Boolean {
        val selectedId = selectedProviderId()
        val state = settingsService.state
        if (selectedId != state.selectedProviderId) return true
        val entry = settingsService.getProviderConfig(selectedId) ?: OAuthProviderConfigEntry(id = selectedId)
        val template = templates.firstOrNull { it.id == selectedId }
        val effectiveAuthorize = if (entry.authorizeEndpoint.isNotBlank()) entry.authorizeEndpoint else template?.authorizeEndpoint?.toString().orEmpty()
        val effectiveToken = if (entry.tokenEndpoint.isNotBlank()) entry.tokenEndpoint else template?.tokenEndpoint?.toString().orEmpty()
        val effectiveScopes = if (entry.scopes.isNotBlank()) entry.scopes else template?.defaultScopes?.joinToString(" ").orEmpty()
        val effectiveUserInfo = if (entry.userInfoEndpoint.isNotBlank()) entry.userInfoEndpoint else template?.userInfoEndpoint?.toString().orEmpty()
        val effectiveParams = if (entry.additionalAuthParams.isNotBlank()) {
            entry.additionalAuthParams
        } else {
            template?.additionalAuthParams?.entries
                ?.joinToString("\n") { "${it.key}=${it.value}" }
                .orEmpty()
        }
        val effectiveUsesClientSecret = entry.usesClientSecret ?: (template?.usesClientSecret ?: false)
        val effectiveSupportsRefresh = entry.supportsRefreshToken ?: (template?.supportsRefreshToken ?: false)
        if (clientIdField.text.trim() != entry.clientId) return true
        if (authorizeEndpointField.text.trim() != effectiveAuthorize) return true
        if (tokenEndpointField.text.trim() != effectiveToken) return true
        if (scopesField.text.trim() != effectiveScopes) return true
        if (audienceField.text.trim() != entry.audience) return true
        if (resourceField.text.trim() != entry.resource) return true
        if (userInfoEndpointField.text.trim() != effectiveUserInfo) return true
        if (additionalParamsArea.text.trim() != effectiveParams) return true
        if (usesClientSecretBox.isSelected != effectiveUsesClientSecret) return true
        if (supportsRefreshTokenBox.isSelected != effectiveSupportsRefresh) return true
        if (clientSecretField.password.isNotEmpty()) return true
        return false
    }

    fun apply() {
        val selectedId = selectedProviderId()
        val state = settingsService.state
        state.selectedProviderId = selectedId

        val entry = OAuthProviderConfigEntry(
            id = selectedId,
            clientId = clientIdField.text.trim(),
            authorizeEndpoint = authorizeEndpointField.text.trim(),
            tokenEndpoint = tokenEndpointField.text.trim(),
            scopes = scopesField.text.trim(),
            audience = audienceField.text.trim(),
            resource = resourceField.text.trim(),
            additionalAuthParams = additionalParamsArea.text.trim(),
            usesClientSecret = usesClientSecretBox.isSelected,
            supportsRefreshToken = supportsRefreshTokenBox.isSelected,
            userInfoEndpoint = userInfoEndpointField.text.trim()
        )
        settingsService.putProviderConfig(entry)

        val secret = String(clientSecretField.password).trim()
        if (secret.isNotEmpty()) {
            tokenStore.saveClientSecret(selectedId, secret)
        } else if (entry.usesClientSecret == false) {
            tokenStore.clearClientSecret(selectedId)
        }
        clientSecretField.text = ""
    }

    fun reset() {
        val selectedId = settingsService.state.selectedProviderId
        providerBox.selectedItem = providerBox.model
            .run { (0 until size).map { getElementAt(it) } }
            .firstOrNull { it.id == selectedId }
        loadSelectedProvider()
    }

    private fun loadSelectedProvider() {
        val selectedId = selectedProviderId()
        val template = templates.firstOrNull { it.id == selectedId }
        val entry = settingsService.getProviderConfig(selectedId) ?: OAuthProviderConfigEntry(id = selectedId)
        clientIdField.text = entry.clientId
        authorizeEndpointField.text = if (entry.authorizeEndpoint.isNotBlank()) {
            entry.authorizeEndpoint
        } else {
            template?.authorizeEndpoint?.toString().orEmpty()
        }
        tokenEndpointField.text = if (entry.tokenEndpoint.isNotBlank()) {
            entry.tokenEndpoint
        } else {
            template?.tokenEndpoint?.toString().orEmpty()
        }
        scopesField.text = if (entry.scopes.isNotBlank()) {
            entry.scopes
        } else {
            template?.defaultScopes?.joinToString(" ").orEmpty()
        }
        audienceField.text = entry.audience
        resourceField.text = entry.resource
        userInfoEndpointField.text = if (entry.userInfoEndpoint.isNotBlank()) {
            entry.userInfoEndpoint
        } else {
            template?.userInfoEndpoint?.toString().orEmpty()
        }
        additionalParamsArea.text = if (entry.additionalAuthParams.isNotBlank()) {
            entry.additionalAuthParams
        } else {
            template?.additionalAuthParams?.entries
                ?.joinToString("\n") { "${it.key}=${it.value}" }
                .orEmpty()
        }
        usesClientSecretBox.isSelected = entry.usesClientSecret ?: (template?.usesClientSecret ?: false)
        supportsRefreshTokenBox.isSelected = entry.supportsRefreshToken ?: (template?.supportsRefreshToken ?: false)
        clientSecretField.text = ""
    }

    private fun selectedProviderId(): String {
        return (providerBox.selectedItem as? ProviderItem)?.id ?: templates.first().id
    }

    private data class ProviderItem(val id: String, val displayName: String) {
        override fun toString(): String = displayName
    }

    private fun testProviderConfiguration() {
        val selectedId = selectedProviderId()
        val template = templates.firstOrNull { it.id == selectedId }
        if (template == null) {
            Messages.showErrorDialog("Unknown provider selected.", "OAuth Configuration Test")
            return
        }
        val config = buildConfigFromForm()
        val provider = try {
            Providers.buildProvider(template, config)
        } catch (ex: Exception) {
            Messages.showErrorDialog("Invalid provider configuration.", "OAuth Configuration Test")
            return
        }

        val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        ApplicationManager.getApplication().executeOnPooledThread {
            val authorizeOk = checkAuthorizationEndpoint(httpClient, provider, provider.authorizeEndpoint.toURI())
            val tokenOk = checkEndpoint(httpClient, provider.tokenEndpoint.toURI())
            val message = buildString {
                appendLine("Authorization endpoint: ${if (authorizeOk) "OK" else "Failed"}")
                appendLine("Token endpoint: ${if (tokenOk) "OK" else "Failed"}")
            }.trim()
            ApplicationManager.getApplication().invokeLater({
                if (authorizeOk && tokenOk) {
                    Messages.showInfoMessage(message, "OAuth Configuration Test")
                } else {
                    Messages.showErrorDialog(message, "OAuth Configuration Test")
                }
            }, ModalityState.any())
        }
    }

    private fun buildConfigFromForm(): OAuthProviderConfig {
        return OAuthProviderConfig(
            clientId = clientIdField.text.trim(),
            authorizeEndpoint = authorizeEndpointField.text.trim().ifBlank { null },
            tokenEndpoint = tokenEndpointField.text.trim().ifBlank { null },
            scopes = scopesField.text.split(',', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            audience = audienceField.text.trim().ifBlank { null },
            resource = resourceField.text.trim().ifBlank { null },
            additionalAuthParams = parseAdditionalParams(additionalParamsArea.text),
            usesClientSecret = usesClientSecretBox.isSelected,
            supportsRefreshToken = supportsRefreshTokenBox.isSelected,
            userInfoEndpoint = userInfoEndpointField.text.trim().ifBlank { null }
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

    private fun checkAuthorizationEndpoint(client: HttpClient, provider: ai.astha.scantheplanet.idea.oauth.OAuthProvider, uri: URI): Boolean {
        val minimalUri = buildAuthorizeTestUri(uri, provider.clientId)
        return checkEndpoint(client, minimalUri)
    }

    private fun buildAuthorizeTestUri(uri: URI, clientId: String): URI {
        val encodedClientId = java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8)
        val query = "response_type=code&client_id=$encodedClientId&redirect_uri=http%3A%2F%2F127.0.0.1&scope=openid&state=test"
        val base = uri.toString()
        val separator = if (base.contains('?')) '&' else '?'
        return URI.create(base + separator + query)
    }

    private fun checkEndpoint(client: HttpClient, uri: URI): Boolean {
        val request = HttpRequest.newBuilder(uri)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            isReachableStatus(response.statusCode())
        } catch (ex: Exception) {
            try {
                val fallback = HttpRequest.newBuilder(uri).GET().build()
                val response = client.send(fallback, HttpResponse.BodyHandlers.discarding())
                isReachableStatus(response.statusCode())
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isReachableStatus(status: Int): Boolean {
        return status in 200..399 || status in 400..499
    }
}
