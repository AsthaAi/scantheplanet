package ai.astha.scantheplanet.idea.toolWindow

import ai.astha.scantheplanet.idea.MyBundle
import ai.astha.scantheplanet.idea.events.ScanEvents
import ai.astha.scantheplanet.idea.events.ScanReportEvents
import ai.astha.scantheplanet.idea.services.ScanFinding
import ai.astha.scantheplanet.idea.services.ScanReport
import ai.astha.scantheplanet.idea.services.ScannerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBLabel
import javax.swing.JProgressBar
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.JBColor
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.text.BadLocationException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem

class ScannerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowPanel = ScannerToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(toolWindowPanel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class ScannerToolWindowPanel(project: Project) : JBPanel<ScannerToolWindowPanel>(BorderLayout()) {
    private val logArea = JBTextArea()
    private val scanButton = JButton(MyBundle.message("scanProject"))
    private val copyAllButton = JButton("Copy all")
    private val copyCsvButton = JButton("Copy CSV")
    private val clearButton = JButton("Clear")
    private val maxLogChars = 20000
    private var currentFindings: List<ScanFinding> = emptyList()
    private val statusLabel = JBLabel("No scan run yet.")
    private val progressBar = JProgressBar(0, 100)
    private val progressLabel = JBLabel("")
    private val progressDetailLabel = JBLabel("")
    private val progressPhaseLabel = JBLabel("")
    private val progressTableModel = object : DefaultTableModel(arrayOf("Status", "Technique", "Name", "File", "Chunk"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val progressTable = JBTable(progressTableModel)
    private val progressRows = LinkedHashMap<String, ProgressRow>()
    private var currentProgressKey: String? = null
    private var lastReportStatus: String? = null

    private val tableModel = object : DefaultTableModel(arrayOf("Severity", "Technique", "Name", "File", "Line", "Observation"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val findingsTable = JBTable(tableModel)
    private val centerCardLayout = CardLayout()
    private val centerCardPanel = JBPanel<JBPanel<*>>(centerCardLayout)
    private val progressPanel = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        logArea.isEditable = false
        logArea.lineWrap = true
        logArea.wrapStyleWord = true

        val tablePane = JBScrollPane(findingsTable)
        val progressTablePane = JBScrollPane(progressTable)
        val logPane = JBScrollPane(logArea)
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, centerCardPanel, logPane)
        splitPane.resizeWeight = 0.7

        findingsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        findingsTable.columnModel.getColumn(0).cellRenderer = SeverityRenderer()

        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout())
        headerPanel.add(statusLabel, BorderLayout.NORTH)
        val actionsPanel = JBPanel<JBPanel<*>>(BorderLayout())
        actionsPanel.add(copyAllButton, BorderLayout.WEST)
        actionsPanel.add(copyCsvButton, BorderLayout.EAST)
        actionsPanel.add(clearButton, BorderLayout.CENTER)
        headerPanel.add(actionsPanel, BorderLayout.EAST)

        progressPanel.add(progressBar, BorderLayout.NORTH)
        progressPanel.add(progressLabel, BorderLayout.CENTER)
        progressPanel.add(progressDetailLabel, BorderLayout.SOUTH)
        val progressCard = JBPanel<JBPanel<*>>(BorderLayout())
        progressCard.add(progressPanel, BorderLayout.NORTH)
        progressCard.add(progressPhaseLabel, BorderLayout.SOUTH)
        val progressListPanel = JBPanel<JBPanel<*>>(BorderLayout())
        progressListPanel.add(progressTablePane, BorderLayout.CENTER)
        progressCard.add(progressListPanel, BorderLayout.CENTER)
        centerCardPanel.add(progressCard, "progress")
        centerCardPanel.add(tablePane, "findings")

        add(headerPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
        add(scanButton, BorderLayout.SOUTH)

        val logService = project.service<ai.astha.scantheplanet.idea.services.ScanLogService>()
        logService.snapshot().forEach { appendLog(it) }

        val reportService = project.service<ai.astha.scantheplanet.idea.services.ScanReportService>()
        reportService.snapshot()?.let { updateReport(it) }

        val connection = project.messageBus.connect(project)
        connection.subscribe(ScanEvents.TOPIC, object : ScanEvents {
            override fun onLog(message: String) {
                appendLog(message)
            }

            override fun onClear() {
                runOnEdt { logArea.text = "" }
            }
        })
        connection.subscribe(ScanReportEvents.TOPIC, object : ScanReportEvents {
            override fun onReport(report: ScanReport) {
                updateReport(report)
            }
        })

        val copyKeyStroke = "copyFindings"
        findingsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke("control C"), copyKeyStroke)
        findingsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke("meta C"), copyKeyStroke)
        findingsTable.actionMap.put(copyKeyStroke, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                copySelectionToClipboard()
            }
        })

        findingsTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && findingsTable.selectedRow >= 0) {
                    openSelectedFinding(project, logService)
                }
            }
        })
        findingsTable.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openFinding")
        findingsTable.actionMap.put("openFinding", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (findingsTable.selectedRow >= 0) {
                    openSelectedFinding(project, logService)
                }
            }
        })

        val scannerService = project.service<ScannerService>()
        scanButton.addActionListener {
            scannerService.scanProject()
        }
        copyAllButton.addActionListener {
            copyAllToClipboard()
        }
        copyCsvButton.addActionListener {
            copyAllToClipboardCsv()
        }
        clearButton.addActionListener {
            logService.clear()
            reportService.clear()
        }
    }

    private fun appendLog(message: String) {
        runOnEdt {
            val doc = logArea.document
            try {
                if (doc.length > maxLogChars) {
                    doc.remove(0, doc.length - maxLogChars / 2)
                }
            } catch (_: BadLocationException) {
                // ignore trimming errors to keep UI responsive
            }
            if (logArea.text.isNotEmpty()) {
                logArea.append("\n")
            }
            logArea.append(message)
        }
    }

    private fun updateReport(report: ScanReport) {
        runOnEdt {
            tableModel.setRowCount(0)
            currentFindings = report.findings
            if (report.status.isBlank() && report.summary.isBlank() && report.findings.isEmpty()) {
                statusLabel.text = "No scan run yet."
                progressBar.value = 0
                progressLabel.text = ""
                progressDetailLabel.text = ""
                progressPhaseLabel.text = ""
                progressTableModel.setRowCount(0)
                progressRows.clear()
                currentProgressKey = null
                lastReportStatus = null
                return@runOnEdt
            }
            statusLabel.text = "Status: ${report.status} — ${report.summary}"
            if (lastReportStatus != report.status && report.status == "running") {
                progressTableModel.setRowCount(0)
                progressRows.clear()
                currentProgressKey = null
            }
            lastReportStatus = report.status
            updateProgress(report.progress)
            updateProgressRows(report.progress)
            if (report.status == "running") {
                centerCardLayout.show(centerCardPanel, "progress")
            } else {
                centerCardLayout.show(centerCardPanel, "findings")
            }
            if (report.findings.isEmpty()) {
                tableModel.addRow(arrayOf("info", "-", "-", "-", "-", "No findings"))
                return@runOnEdt
            }
            for (finding in report.findings) {
                tableModel.addRow(finding.toRow())
            }
        }
    }

    private fun updateProgress(progress: ai.astha.scantheplanet.idea.scanner.ScanProgress?) {
        if (progress == null) {
            progressBar.value = 0
            progressLabel.text = ""
            progressDetailLabel.text = ""
            progressPhaseLabel.text = ""
            return
        }
        val percent = if (progress.totalFiles > 0) {
            (progress.processedFiles * 100 / progress.totalFiles).coerceIn(0, 100)
        } else {
            0
        }
        progressBar.value = percent
        val techniqueLabel = if (progress.totalTechniques > 0) {
            "Technique ${progress.techniqueIndex}/${progress.totalTechniques}: ${progress.techniqueId}"
        } else {
            "Technique: ${progress.techniqueId}"
        }
        progressLabel.text = techniqueLabel + (if (progress.techniqueName.isNotBlank()) " (${progress.techniqueName})" else "")
        val currentFile = progress.currentFile.ifBlank { "unknown file" }
        val chunk = progress.currentChunkId?.let { "Chunk: $it" } ?: ""
        val remaining = (progress.totalFiles - progress.processedFiles).coerceAtLeast(0)
        progressDetailLabel.text = "Scanned: ${progress.processedFiles}/${progress.totalFiles}. Remaining: $remaining. Now: $currentFile. $chunk"
        progressPhaseLabel.text = "Phase: ${progress.phase.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }

    private fun updateProgressRows(progress: ai.astha.scantheplanet.idea.scanner.ScanProgress?) {
        if (progress == null) return
        val phase = progress.phase
        if (phase.name == "CLEANING") {
            markCurrentDone()
            val key = "CLEANING"
            val row = progressRows[key]
            if (row == null) {
                val index = progressTableModel.rowCount
                progressTableModel.addRow(arrayOf("scanning", "CLEANING", "Findings cleanup", "-", "-"))
                progressRows[key] = ProgressRow("scanning", index)
            } else {
                updateProgressStatus(row, "scanning")
            }
            return
        }
        if (phase.name == "DONE") {
            markCurrentDone()
            progressRows["CLEANING"]?.let { updateProgressStatus(it, "done") }
            return
        }
        val chunkId = progress.currentChunkId ?: return
        val key = listOf(progress.techniqueId, progress.currentFile, chunkId).joinToString("|")
        val status = if (progress.chunkStatus == ai.astha.scantheplanet.idea.scanner.ChunkStatus.CACHED) {
            "cached"
        } else {
            "scanning"
        }
        if (currentProgressKey != key) {
            markCurrentDone()
            currentProgressKey = key
        }
        val row = progressRows[key]
        if (row == null) {
            val index = progressTableModel.rowCount
            val name = progress.techniqueName.takeIf { it.isNotBlank() } ?: "-"
            progressTableModel.addRow(arrayOf(status, progress.techniqueId, name, progress.currentFile, chunkId))
            progressRows[key] = ProgressRow(status, index)
        } else {
            updateProgressStatus(row, status)
        }
    }

    private fun markCurrentDone() {
        val key = currentProgressKey ?: return
        val row = progressRows[key] ?: return
        if (row.status == "scanning") {
            updateProgressStatus(row, "done")
        }
        currentProgressKey = null
    }

    private fun updateProgressStatus(row: ProgressRow, status: String) {
        if (row.status == status) return
        row.status = status
        progressTableModel.setValueAt(status, row.row, 0)
    }

    private fun ScanFinding.toRow(): Array<Any> {
        val line = if (endLine > 0 && endLine != startLine) {
            "$startLine-$endLine"
        } else {
            startLine.toString()
        }
        val name = techniqueName.takeIf { it.isNotBlank() } ?: "-"
        return arrayOf(severity, techniqueId, name, file, line, observation)
    }

    private fun copySelectionToClipboard() {
        val rows = findingsTable.selectedRows
        if (rows.isEmpty()) return
        val text = buildCopyText(rows.toList())
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun copyAllToClipboard() {
        if (findingsTable.rowCount == 0) return
        val rows = (0 until findingsTable.rowCount).toList()
        val text = buildCopyText(rows)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun copyAllToClipboardCsv() {
        if (findingsTable.rowCount == 0) return
        val rows = (0 until findingsTable.rowCount).toList()
        val text = buildCopyCsv(rows)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun buildCopyText(rows: List<Int>): String {
        val header = (0 until findingsTable.columnCount)
            .joinToString("\t") { findingsTable.getColumnName(it) }
        val body = rows.joinToString("\n") { row ->
            (0 until findingsTable.columnCount)
                .joinToString("\t") { col ->
                    findingsTable.getValueAt(row, col)?.toString().orEmpty()
                }
        }
        return header + "\n" + body
    }

    private fun buildCopyCsv(rows: List<Int>): String {
        val header = (0 until findingsTable.columnCount)
            .joinToString(",") { csvEscape(findingsTable.getColumnName(it)) }
        val body = rows.joinToString("\n") { row ->
            (0 until findingsTable.columnCount)
                .joinToString(",") { col ->
                    csvEscape(findingsTable.getValueAt(row, col)?.toString().orEmpty())
                }
        }
        return header + "\n" + body
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun openSelectedFinding(project: Project, logService: ai.astha.scantheplanet.idea.services.ScanLogService) {
        val index = findingsTable.selectedRow
        if (index < 0 || index >= currentFindings.size) {
            return
        }
        val finding = currentFindings[index]
        val basePath = project.basePath ?: return
        val path = Path.of(finding.file)
        val resolved = if (path.isAbsolute) path.toString() else Path.of(basePath, finding.file).toString()
        val vFile = LocalFileSystem.getInstance().findFileByPath(resolved)
        if (vFile == null) {
            logService.log("Could not open file: $resolved")
            return
        }
        val line = if (finding.startLine > 0) finding.startLine - 1 else 0
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile, line, 0), true)
    }

    private class SeverityRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected && value is String) {
                component.foreground = when (value.lowercase()) {
                    "critical", "p0" -> JBColor(0xB71C1C, 0xFF8A80)
                    "high", "p1" -> JBColor(0xD32F2F, 0xFFAB91)
                    "medium", "p2" -> JBColor(0xF57C00, 0xFFD180)
                    "low", "p3" -> JBColor(0xFBC02D, 0xFFE082)
                    "info" -> JBColor(0x1976D2, 0x90CAF9)
                    else -> JBColor(0x616161, 0xB0BEC5)
                }
            }
            return component
        }
    }

    private data class ProgressRow(var status: String, val row: Int)

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeLater(action)
        }
    }
}
