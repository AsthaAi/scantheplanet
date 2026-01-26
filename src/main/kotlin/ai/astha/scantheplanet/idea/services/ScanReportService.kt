package ai.astha.scantheplanet.idea.services

import ai.astha.scantheplanet.idea.events.ScanReportEvents
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

@Tag("AsthaScanFindingState")
data class ScanFindingState(
    var techniqueId: String = "",
    var techniqueName: String = "",
    var severity: String = "",
    var file: String = "",
    var startLine: Int = -1,
    var endLine: Int = -1,
    var observation: String = ""
)

@Tag("AsthaScanProgressState")
data class ScanProgressState(
    var techniqueId: String = "",
    var techniqueName: String = "",
    var techniqueIndex: Int = 0,
    var totalTechniques: Int = 0,
    var currentFile: String = "",
    var currentChunkId: String = "",
    var processedFiles: Int = 0,
    var totalFiles: Int = 0,
    var chunksAnalyzed: Int = 0,
    var chunksFailed: Int = 0,
    var phase: String = ""
)

@Tag("AsthaScanReportState")
data class ScanReportState(
    var status: String = "",
    var summary: String = "",
    var progress: ScanProgressState? = null,
    @XCollection
    var findings: MutableList<ScanFindingState> = mutableListOf()
)

@Service(Service.Level.PROJECT)
@State(name = "AsthaScanReport", storages = [Storage("astha-report.xml")])
class ScanReportService(private val project: Project) : PersistentStateComponent<ScanReportState> {
    private var state = ScanReportState()

    override fun getState(): ScanReportState = state

    override fun loadState(state: ScanReportState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun update(report: ScanReport) {
        state.status = report.status
        state.summary = report.summary
        state.progress = report.progress?.let { progress ->
            ScanProgressState(
                techniqueId = progress.techniqueId,
                techniqueName = progress.techniqueName,
                techniqueIndex = progress.techniqueIndex,
                totalTechniques = progress.totalTechniques,
                currentFile = progress.currentFile,
                currentChunkId = progress.currentChunkId ?: "",
                processedFiles = progress.processedFiles,
                totalFiles = progress.totalFiles,
                chunksAnalyzed = progress.chunksAnalyzed,
                chunksFailed = progress.chunksFailed,
                phase = progress.phase.name
            )
        }
        state.findings = report.findings.map { finding ->
            ScanFindingState(
                techniqueId = finding.techniqueId,
                techniqueName = finding.techniqueName,
                severity = finding.severity,
                file = finding.file,
                startLine = finding.startLine,
                endLine = finding.endLine,
                observation = finding.observation
            )
        }.toMutableList()
        project.messageBus.syncPublisher(ScanReportEvents.TOPIC).onReport(report)
    }

    fun snapshot(): ScanReport? {
        if (state.status.isBlank() && state.summary.isBlank() && state.findings.isEmpty()) {
            return null
        }
        return ScanReport(
            state.status,
            state.summary,
            state.findings.map {
                ScanFinding(
                    techniqueId = it.techniqueId,
                    techniqueName = it.techniqueName,
                    severity = it.severity,
                    file = it.file,
                    startLine = it.startLine,
                    endLine = it.endLine,
                    observation = it.observation
                )
            },
            state.progress?.let { progress ->
                ai.astha.scantheplanet.idea.scanner.ScanProgress(
                    techniqueId = progress.techniqueId,
                    techniqueName = progress.techniqueName,
                    techniqueIndex = progress.techniqueIndex,
                    totalTechniques = progress.totalTechniques,
                    currentFile = progress.currentFile,
                    currentChunkId = progress.currentChunkId.ifBlank { null },
                    processedFiles = progress.processedFiles,
                    totalFiles = progress.totalFiles,
                    chunksAnalyzed = progress.chunksAnalyzed,
                    chunksFailed = progress.chunksFailed,
                    phase = ai.astha.scantheplanet.idea.scanner.ScanPhase.valueOf(progress.phase.ifBlank { "SCANNING" })
                )
            }
        )
    }

    fun clear() {
        state = ScanReportState()
        project.messageBus.syncPublisher(ScanReportEvents.TOPIC).onReport(
            ScanReport("","", emptyList(), null)
        )
    }
}
