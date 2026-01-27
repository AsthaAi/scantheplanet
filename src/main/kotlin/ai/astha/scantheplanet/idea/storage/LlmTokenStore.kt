package ai.astha.scantheplanet.idea.storage

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

class LlmTokenStore(private val pluginId: String) {
    private val accountKey = "default"

    fun saveToken(providerId: String, token: String?) {
        if (token.isNullOrBlank()) {
            clearToken(providerId)
        } else {
            PasswordSafe.instance.setPassword(credentialAttributes(providerId), token)
        }
    }

    fun loadToken(providerId: String): String? {
        return PasswordSafe.instance.getPassword(credentialAttributes(providerId))
    }

    fun clearToken(providerId: String) {
        PasswordSafe.instance.setPassword(credentialAttributes(providerId), null)
    }

    private fun credentialAttributes(providerId: String): CredentialAttributes {
        val serviceName = "$pluginId.llm.$providerId.$accountKey"
        return CredentialAttributes(serviceName, "token")
    }
}
