package ai.astha.scantheplanet.idea.settings

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection

private const val DEFAULT_PROVIDER_ID = "github"

data class OAuthSettingsState(
    @Attribute("selectedProviderId")
    var selectedProviderId: String = DEFAULT_PROVIDER_ID,
    @XCollection(propertyElementName = "providers", elementName = "provider")
    var providers: MutableList<OAuthProviderConfigEntry> = mutableListOf(),
    @XCollection(propertyElementName = "identities", elementName = "identity")
    var identities: MutableList<OAuthIdentityEntry> = mutableListOf(),
    @XCollection(propertyElementName = "tokenMetadata", elementName = "token")
    var tokenMetadata: MutableList<TokenMetadataEntry> = mutableListOf()
)

data class OAuthProviderConfigEntry(
    @Attribute("id")
    var id: String = "",
    @Attribute("clientId")
    var clientId: String = "",
    @Attribute("authorizeEndpoint")
    var authorizeEndpoint: String = "",
    @Attribute("tokenEndpoint")
    var tokenEndpoint: String = "",
    @Attribute("scopes")
    var scopes: String = "",
    @Attribute("audience")
    var audience: String = "",
    @Attribute("resource")
    var resource: String = "",
    @Attribute("additionalAuthParams")
    var additionalAuthParams: String = "",
    @Attribute("usesClientSecret")
    var usesClientSecret: Boolean? = null,
    @Attribute("supportsRefreshToken")
    var supportsRefreshToken: Boolean? = null,
    @Attribute("userInfoEndpoint")
    var userInfoEndpoint: String = ""
)

data class OAuthIdentityEntry(
    @Attribute("id")
    var id: String = "",
    @Attribute("subject")
    var subject: String = "",
    @Attribute("displayName")
    var displayName: String = "",
    @Attribute("email")
    var email: String = ""
)

data class TokenMetadataEntry(
    @Attribute("id")
    var id: String = "",
    @Attribute("tokenType")
    var tokenType: String = "",
    @Attribute("expiresAtEpochSeconds")
    var expiresAtEpochSeconds: Long = 0
)
