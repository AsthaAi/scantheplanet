package ai.astha.scantheplanet.idea.actions

import ai.astha.scantheplanet.idea.MyBundle
import ai.astha.scantheplanet.idea.services.ScannerService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class ScanProjectAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ScannerService>().scanProject()
        ToolWindowManager.getInstance(project).getToolWindow("Scan The Planet by Astha")?.show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = MyBundle.message("scanProject")
        e.presentation.description = MyBundle.message("scanProjectDescription")
        e.presentation.isEnabled = e.project != null
    }
}
