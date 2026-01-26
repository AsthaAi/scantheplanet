package ai.astha.scantheplanet.idea.storage

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

class TokenStore(private val pluginId: String) {
    private val accountKey = "default"

    fun saveTokens(providerId: String, accessToken: String, refreshToken: String?, idToken: String?) {
        setSecret(providerId, "access_token", accessToken)
        if (refreshToken != null) {
            setSecret(providerId, "refresh_token", refreshToken)
        } else {
            clearSecret(providerId, "refresh_token")
        }
        if (idToken != null) {
            setSecret(providerId, "id_token", idToken)
        } else {
            clearSecret(providerId, "id_token")
        }
    }

    fun loadAccessToken(providerId: String): String? = getSecret(providerId, "access_token")

    fun loadRefreshToken(providerId: String): String? = getSecret(providerId, "refresh_token")

    fun loadIdToken(providerId: String): String? = getSecret(providerId, "id_token")

    fun clearTokens(providerId: String) {
        clearSecret(providerId, "access_token")
        clearSecret(providerId, "refresh_token")
        clearSecret(providerId, "id_token")
    }

    fun saveClientSecret(providerId: String, secret: String?) {
        if (secret.isNullOrBlank()) {
            clearSecret(providerId, "client_secret")
        } else {
            setSecret(providerId, "client_secret", secret)
        }
    }

    fun loadClientSecret(providerId: String): String? = getSecret(providerId, "client_secret")

    fun clearClientSecret(providerId: String) {
        clearSecret(providerId, "client_secret")
    }

    private fun credentialAttributes(providerId: String, itemKey: String): CredentialAttributes {
        val serviceName = "$pluginId.oauth.$providerId.$accountKey"
        return CredentialAttributes(serviceName, itemKey)
    }

    private fun setSecret(providerId: String, itemKey: String, secret: String) {
        PasswordSafe.instance.setPassword(credentialAttributes(providerId, itemKey), secret)
    }

    private fun getSecret(providerId: String, itemKey: String): String? {
        return PasswordSafe.instance.getPassword(credentialAttributes(providerId, itemKey))
    }

    private fun clearSecret(providerId: String, itemKey: String) {
        PasswordSafe.instance.setPassword(credentialAttributes(providerId, itemKey), null)
    }
}
