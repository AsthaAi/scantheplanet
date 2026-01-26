package ai.astha.scantheplanet.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "AsthaOAuthSettings", storages = [Storage("astha-oauth.xml")])
class OAuthSettingsService : PersistentStateComponent<OAuthSettingsState> {
    private var state = OAuthSettingsState()

    override fun getState(): OAuthSettingsState = state

    override fun loadState(state: OAuthSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getProviderConfig(providerId: String): OAuthProviderConfigEntry? =
        state.providers.firstOrNull { it.id == providerId }

    fun putProviderConfig(entry: OAuthProviderConfigEntry) {
        state.providers.removeIf { it.id == entry.id }
        state.providers.add(entry)
    }

    fun getIdentity(providerId: String): OAuthIdentityEntry? =
        state.identities.firstOrNull { it.id == providerId }

    fun putIdentity(entry: OAuthIdentityEntry) {
        state.identities.removeIf { it.id == entry.id }
        state.identities.add(entry)
    }

    fun clearIdentity(providerId: String) {
        state.identities.removeIf { it.id == providerId }
    }

    fun getTokenMetadata(providerId: String): TokenMetadataEntry? =
        state.tokenMetadata.firstOrNull { it.id == providerId }

    fun putTokenMetadata(entry: TokenMetadataEntry) {
        state.tokenMetadata.removeIf { it.id == entry.id }
        state.tokenMetadata.add(entry)
    }

    fun clearTokenMetadata(providerId: String) {
        state.tokenMetadata.removeIf { it.id == providerId }
    }
}
