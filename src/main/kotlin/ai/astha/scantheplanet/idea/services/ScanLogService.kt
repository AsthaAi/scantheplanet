package ai.astha.scantheplanet.idea.services

import ai.astha.scantheplanet.idea.events.ScanEvents
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ScanLogService(private val project: Project) {
    private val lines = CopyOnWriteArrayList<String>()

    fun log(message: String) {
        lines.add(message)
        project.messageBus.syncPublisher(ScanEvents.TOPIC).onLog(message)
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() {
        lines.clear()
        project.messageBus.syncPublisher(ScanEvents.TOPIC).onClear()
    }
}
