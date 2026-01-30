package ai.astha.scantheplanet.idea.actions

import ai.astha.scantheplanet.idea.oauth.OAuthService
import ai.astha.scantheplanet.idea.settings.OAuthConfigurable
import ai.astha.scantheplanet.idea.settings.OAuthSettingsService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

class SignInAction : AnAction("Sign in...") {
    override fun actionPerformed(e: AnActionEvent) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val settingsService = app.getService(OAuthSettingsService::class.java)
        val oauthService = app.getService(OAuthService::class.java)
        val provider = oauthService.getConfiguredProvider(settingsService)
        if (provider == null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, OAuthConfigurable::class.java)
            return
        }

        val authorizeUrl = oauthService.startLogin(provider)
        BrowserUtil.browse(authorizeUrl.toString())
    }
}
