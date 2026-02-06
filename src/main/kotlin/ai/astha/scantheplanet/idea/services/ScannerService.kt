package ai.astha.scantheplanet.idea.services

import ai.astha.scantheplanet.idea.MyBundle
import ai.astha.scantheplanet.idea.scanner.NativeScanner
import ai.astha.scantheplanet.idea.scanner.ScanCache
import ai.astha.scantheplanet.idea.scanner.ScanCachePaths
import ai.astha.scantheplanet.idea.scanner.SafeMcpRepository
import ai.astha.scantheplanet.idea.scanner.ScanProgress
import ai.astha.scantheplanet.idea.scanner.ScannerConfigLoader
import ai.astha.scantheplanet.idea.scanner.ScopeKind
import ai.astha.scantheplanet.idea.scanner.ScannerUtils
import ai.astha.scantheplanet.idea.settings.AsthaSettingsService
import ai.astha.scantheplanet.idea.settings.LlmProvider
import ai.astha.scantheplanet.idea.settings.ScanScope
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ScannerService(private val project: Project) {
    private val logService = project.getService(ScanLogService::class.java)
    private val reportService = project.getService(ScanReportService::class.java)
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Volatile private var cancelFlag: AtomicBoolean? = null
    @Volatile private var currentScanTask: java.util.concurrent.Future<*>? = null

    fun scanProject() {
        val existing = cancelFlag
        if (existing != null && !existing.get()) {
            val running = currentScanTask?.isDone == false
            if (running) {
                logService.log("Scan already running.")
                return
            }
            cancelFlag = null
            currentScanTask = null
        }
        val projectBasePath = project.basePath
        if (projectBasePath == null) {
            logService.log(MyBundle.message("scanProjectMissingBasePath"))
            return
        }

        val settingsService = project.getService(AsthaSettingsService::class.java)
        val settings = settingsService.state
        val techniques = settings.techniques.takeIf { it.isNotEmpty() } ?: mutableListOf("SAFE-T0001")
        val safeMcpRepository = SafeMcpRepository()
        val configPath = settings.configPath.trim().takeIf { it.isNotEmpty() }?.let { Path.of(expandHome(it)) }
        var config = ScannerConfigLoader().load(configPath)
        config = config.copy(
            sourceCodeOnly = settings.sourceCodeOnly,
            ollamaLoggingEnabled = settings.ollamaLoggingEnabled
        )

        val hasGitRepo = java.nio.file.Files.isDirectory(Path.of(projectBasePath, ".git"))
        val desiredScope = settings.scope
        val baseRef = if (desiredScope == ScanScope.GIT_DIFF && hasGitRepo) {
            resolveGitBaseRef(projectBasePath)
        } else {
            null
        }
        var gitDiffCount: Int? = null
        val scope = when {
            desiredScope == ScanScope.OPEN_FILES -> {
                val openFiles = openProjectFiles(projectBasePath)
                if (openFiles.isEmpty()) {
                    logService.clear()
                    reportService.clear()
                    logService.log("No open files to scan.")
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.ui.Messages.showWarningDialog(
                            project,
                            "No open files to scan. Open a file or change the scan scope.",
                            "Scan The Planet"
                        )
                    }
                    reportService.update(ScanReport("unknown", "No open files to scan.", emptyList(), null))
                    return
                }
                ScopeKind.OpenFiles(openFiles)
            }
            desiredScope == ScanScope.GIT_DIFF && hasGitRepo && baseRef != null -> {
                val changedFiles = ScannerUtils.gitChangedFiles(Path.of(projectBasePath), baseRef, settings.includeUntracked)
                if (changedFiles.isEmpty()) {
                    logService.clear()
                    reportService.clear()
                    logService.log("No changed files to scan.")
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.ui.Messages.showWarningDialog(
                            project,
                            "No changed files to scan. Make a change or switch the scan scope.",
                            "Scan The Planet"
                        )
                    }
                    reportService.update(ScanReport("unknown", "No changed files to scan.", emptyList(), null))
                    return
                }
                gitDiffCount = changedFiles.size
                ScopeKind.GitDiff(baseRef, settings.includeUntracked)
            }
            desiredScope == ScanScope.GIT_DIFF -> ScopeKind.FullRepo
            else -> ScopeKind.FullRepo
        }

        logService.clear()
        reportService.clear()
        logService.log(MyBundle.message("scanQueued", "project", project.name))
        logService.log("Scan configuration: scope=${settings.scope} provider=${settings.provider.cliValue} techniques=${techniques.size}")
        if (scope is ScopeKind.OpenFiles) {
            logService.log("Open files: ${scope.files.size}")
        }
        if (scope is ScopeKind.GitDiff && gitDiffCount != null) {
            logService.log("Git diff files: $gitDiffCount")
        }

        val localCancel = AtomicBoolean(false)
        cancelFlag = localCancel
        try {
            currentScanTask = executor.submit {
            logService.log(MyBundle.message("scanStarted", "project", project.name, timestamp()))
            reportService.update(ScanReport("running", "Scan in progress...", emptyList(), null))

            var provider = settings.provider.cliValue
            val supportedProviders = setOf(LlmProvider.OPENAI.cliValue, LlmProvider.OLLAMA.cliValue)
            if (provider !in supportedProviders) {
                logService.log("Provider $provider is currently disabled; falling back to OpenAI.")
                provider = LlmProvider.OPENAI.cliValue
            }
            logService.log("Resolved provider: $provider")
            val allowed = (config.allowedProviders ?: emptyList()).toMutableSet()
            allowed.add(provider)
            config = config.copy(allowRemoteProviders = true, allowedProviders = allowed.toList())
            val selectedParallelism = if (provider == LlmProvider.OLLAMA.cliValue) {
                settings.ollamaChunkParallelism to settings.ollamaChunkParallelismMax
            } else {
                settings.openaiChunkParallelism to settings.openaiChunkParallelismMax
            }
            config = config.copy(
                chunkParallelism = selectedParallelism.first,
                chunkParallelismMax = selectedParallelism.second
            )
            val endpointOverride = settings.llmEndpoint.trim().ifBlank { null }
            if (provider == LlmProvider.OLLAMA.cliValue) {
                if (!endpointOverride.isNullOrBlank()) {
                    config = config.copy(ollamaEndpoint = endpointOverride)
                    logService.log("Ollama endpoint override: ${config.ollamaEndpoint}")
                }
                if (config.ollamaLoggingEnabled) {
                    val ollamaLogPath = ScanCachePaths.ensureOllamaLogPath(projectBasePath)
                    config = config.copy(ollamaLogPath = ollamaLogPath.toString())
                    logService.log("Ollama logging enabled: ${ollamaLogPath.toAbsolutePath()}")
                }
            } else if (!endpointOverride.isNullOrBlank()) {
                logService.log(MyBundle.message("scanEndpointUnsupported", provider))
            }
            logService.log("Chunk parallelism: ${selectedParallelism.first} (max ${selectedParallelism.second})")
            val cache = if (settings.cacheEnabled) {
                ScanCache(ScanCachePaths.ensureCachePath(projectBasePath))
            } else {
                null
            }
            val scanner = NativeScanner(safeMcpRepository, config, cache)
            val apiKey = settingsService.getLlmToken(provider).orEmpty().trim().ifBlank { null }
            val modelName = if (provider == LlmProvider.OLLAMA.cliValue) {
                settings.ollamaModelName.trim().ifBlank { null }
            } else {
                settings.openaiModelName.trim().ifBlank { null }
            }
            logService.log("Model: ${modelName ?: "default"}")
            val report = try {
                var lastProgress: ScanProgress? = null
                val progressHandler: (ScanProgress) -> Unit = { progress ->
                    val summary = buildProgressSummary(progress)
                    ApplicationManager.getApplication().invokeLater {
                        lastProgress = progress
                        reportService.update(ScanReport("running", summary, emptyList(), progress))
                    }
                }
                val result = scanner.scan(
                    Path.of(projectBasePath),
                    techniques,
                    scope,
                    provider,
                    modelName,
                    apiKey,
                    cancelRequested = { localCancel.get() },
                    progress = progressHandler,
                    batchEnabled = settings.batchEnabled,
                    batchSize = settings.batchSize
                )
                val cleaningProgress = lastProgress?.copy(phase = ai.astha.scantheplanet.idea.scanner.ScanPhase.CLEANING)
                if (settings.cleanFindings) {
                    ApplicationManager.getApplication().invokeLater {
                        reportService.update(ScanReport("running", "Cleaning findings...", emptyList(), cleaningProgress))
                    }
                }
                val cleanedFindings = if (settings.cleanFindings) {
                    if (localCancel.get()) {
                        throw ai.astha.scantheplanet.idea.scanner.ScanCancelledException()
                    }
                    val cleaningProvider = provider
                    val cleaningModel = settings.cleaningModelName.trim().ifBlank { modelName }
                    val cleaningKey = apiKey
                    val cleaningExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    try {
                        val task = cleaningExecutor.submit(java.util.concurrent.Callable<List<ai.astha.scantheplanet.idea.scanner.ScanFinding>> {
                            ai.astha.scantheplanet.idea.scanner.FindingsCleaner.cleanFindings(
                                provider = cleaningProvider,
                                modelName = cleaningModel,
                                apiKey = cleaningKey,
                                config = config,
                                findings = result.findings
                            )
                        })
                        try {
                            task.get(120, java.util.concurrent.TimeUnit.SECONDS)
                        } catch (e: java.util.concurrent.TimeoutException) {
                            logService.log("Cleaning timed out after 120 seconds; keeping original findings.")
                            task.cancel(true)
                            result.findings
                        }
                    } finally {
                        cleaningExecutor.shutdownNow()
                    }
                } else {
                    result.findings
                }
                val finalResult = if (cleanedFindings === result.findings) {
                    result
                } else {
                    result.copy(findings = cleanedFindings)
                }
                val doneProgress = lastProgress?.copy(phase = ai.astha.scantheplanet.idea.scanner.ScanPhase.DONE)
                ScanReport(
                    status = finalResult.status,
                    summary = finalResult.summary,
                    findings = finalResult.findings.map {
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
                    doneProgress
                )
            } catch (e: ai.astha.scantheplanet.idea.scanner.ScanCancelledException) {
                logService.log("Scan cancelled.")
                ScanReport("cancelled", "Scan cancelled.", emptyList(), null)
            } catch (e: Exception) {
                logService.log("Scan failed: ${e.message ?: "unknown error"}")
                ScanReport("unknown", "Scan failed", emptyList(), null)
            } finally {
                cancelFlag = null
                currentScanTask = null
            }

            reportService.update(report)
            logService.log(formatReport(report))
            logService.log(MyBundle.message("scanFinished", "project", project.name, timestamp()))
            }
        } catch (e: Exception) {
            cancelFlag = null
            currentScanTask = null
            logService.log("Failed to start scan: ${e.message ?: "unknown error"}")
        }
    }

    fun cancelScan() {
        val current = cancelFlag ?: return
        if (current.get()) return
        current.set(true)
        logService.log("Scan cancellation requested.")
        val snapshot = reportService.snapshot()
        if (snapshot != null && snapshot.status == "running") {
            reportService.update(snapshot.copy(summary = "Cancelling scan..."))
        }
    }

    private fun timestamp(): String = ZonedDateTime.now().format(formatter)

    private fun formatReport(report: ScanReport): String {
        val builder = StringBuilder()
        builder.append("Status: ").append(report.status)
        if (report.summary.isNotBlank()) {
            builder.append("\n").append(report.summary)
        }

        if (report.findings.isEmpty()) {
            builder.append("\nFindings: none")
            return builder.toString()
        }

        builder.append("\nFindings:")
        for (finding in report.findings) {
            builder.append("\n- ")
                .append(finding.severity)
                .append(" ")
                .append(finding.techniqueId)
                .append(" ")
                .append(shortName(finding))
                .append(" ")
                .append(finding.file)
                .append(":")
                .append(finding.startLine)
            if (finding.endLine >= 0 && finding.endLine != finding.startLine) {
                builder.append("-").append(finding.endLine)
            }
            if (finding.observation.isNotBlank()) {
                builder.append(" ").append(finding.observation)
            }
        }

        return builder.toString()
    }

    private fun shortName(finding: ScanFinding): String {
        val name = finding.techniqueName.trim()
        return if (name.isEmpty()) "(unknown)" else "($name)"
    }

    private fun buildProgressSummary(progress: ScanProgress): String {
        val total = progress.totalFiles
        val processed = progress.processedFiles
        val percent = if (total > 0) (processed * 100 / total) else 0
        val techniqueLabel = if (progress.techniqueName.isNotBlank()) {
            "${progress.techniqueId} (${progress.techniqueName})"
        } else {
            progress.techniqueId
        }
        val techProgress = if (progress.totalTechniques > 0) {
            "Technique ${progress.techniqueIndex}/${progress.totalTechniques}"
        } else {
            "Technique"
        }
        val fileLabel = progress.currentFile.ifBlank { "unknown file" }
        return "$techProgress: $techniqueLabel — $processed/$total files ($percent%). Current: $fileLabel. LLM calls: ${progress.chunksAnalyzed}."
    }

    private fun expandHome(path: String): String {
        return if (path.startsWith("~/")) {
            path.replaceFirst("~", System.getProperty("user.home"))
        } else {
            path
        }
    }

    private fun openProjectFiles(projectBasePath: String): List<Path> {
        val basePath = Path.of(projectBasePath)
        val openFiles = FileEditorManager.getInstance(project).openFiles
        return openFiles.mapNotNull { file ->
            val path = file.toNioPath()
            if (path.startsWith(basePath)) {
                path
            } else {
                null
            }
        }.distinct()
    }


    private fun resolveGitBaseRef(repoPath: String): String? {
        val candidates = listOf("origin/main", "origin/master", "main", "master", "HEAD~1", "HEAD")
        for (candidate in candidates) {
            if (gitRefExists(repoPath, candidate)) {
                return candidate
            }
        }
        return null
    }


    private fun gitRefExists(repoPath: String, ref: String): Boolean {
        return try {
            val process = ProcessBuilder("git", "-C", repoPath, "rev-parse", "--verify", ref)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
