package ai.astha.scantheplanet.idea.actions

import ai.astha.scantheplanet.idea.oauth.OAuthService
import ai.astha.scantheplanet.idea.settings.OAuthSettingsService
import ai.astha.scantheplanet.idea.storage.TokenStore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class SignOutAction : AnAction("Sign out") {
    override fun actionPerformed(e: AnActionEvent) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val settingsService = app.getService(OAuthSettingsService::class.java)
        val oauthService = app.getService(OAuthService::class.java)
        val providerId = settingsService.state.selectedProviderId
        val tokenStore = TokenStore(OAuthService.PLUGIN_ID)

        oauthService.clearTokens(settingsService, tokenStore, providerId)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(OAuthService.NOTIFICATION_GROUP)
            .createNotification("Signed out", "OAuth tokens cleared.", NotificationType.INFORMATION)
            .notify(e.project)
    }
}
