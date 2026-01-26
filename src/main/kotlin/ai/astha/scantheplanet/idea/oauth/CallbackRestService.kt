package ai.astha.scantheplanet.idea.oauth

import ai.astha.scantheplanet.idea.settings.OAuthSettingsService
import ai.astha.scantheplanet.idea.storage.TokenStore
import com.intellij.openapi.application.ApplicationManager
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpHeaderNames
import org.jetbrains.ide.RestService

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class CallbackRestService : RestService() {
    override fun getServiceName(): String = "${OAuthService.PLUGIN_ID}/oauth/callback"

    override fun isMethodSupported(method: HttpMethod): Boolean = method == HttpMethod.GET

    @Suppress("OVERRIDE_DEPRECATION")
    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: io.netty.channel.ChannelHandlerContext
    ): String {
        val state = getNullableParam("state", urlDecoder)
        val code = getNullableParam("code", urlDecoder)
        val error = getNullableParam("error", urlDecoder)
        val errorDescription = getNullableParam("error_description", urlDecoder)

        val app = ApplicationManager.getApplication()
        val oauthService = app.getService(OAuthService::class.java)
        val settingsService = app.getService(OAuthSettingsService::class.java)
        val tokenStore = TokenStore(OAuthService.PLUGIN_ID)

        return oauthService.handleCallback(settingsService, tokenStore, state, code, error, errorDescription)
    }

    @Suppress("DEPRECATION")
    override fun isHostTrusted(request: FullHttpRequest): Boolean {
        val hostHeader = request.headers().get(HttpHeaderNames.HOST) ?: return false
        val host = hostHeader.substringBefore(':').lowercase()
        return host == "127.0.0.1" || host == "localhost"
    }

    private fun getNullableParam(name: String, decoder: QueryStringDecoder): String? {
        return decoder.parameters()[name]?.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
