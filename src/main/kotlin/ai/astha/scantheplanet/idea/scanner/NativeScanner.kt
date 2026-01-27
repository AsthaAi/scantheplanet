package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import ai.astha.scantheplanet.idea.scanner.model.CodeModel
import ai.astha.scantheplanet.idea.scanner.model.ModelFinding
import ai.astha.scantheplanet.idea.scanner.prompt.CodeChunkPrompt
import ai.astha.scantheplanet.idea.scanner.prompt.MitigationPrompt
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import ai.astha.scantheplanet.idea.scanner.providers.ModelFactory
import java.nio.file.Path
import java.security.MessageDigest

class NativeScanner(
    private val repository: SafeMcpRepository,
    private val config: ScannerConfig,
    private val cache: ScanCache? = null
) {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    private val patternLibrary = PatternLibrary(repository)

    fun scan(
        repoPath: Path,
        techniqueIds: List<String>,
        scope: ScopeKind,
        provider: String,
        modelName: String?,
        apiKey: String?,
        maxLinesPerChunk: Int = 200,
        cancelRequested: (() -> Boolean)? = null,
        progress: ((ScanProgress) -> Unit)? = null,
        batchEnabled: Boolean = true,
        batchSize: Int = 3
    ): ScanSummary {
        val model = ModelFactory.build(provider, modelName, apiKey, config)
        val techniqueHasher = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val diffAddedLines = if (scope is ScopeKind.GitDiff) {
            ScannerUtils.gitDiffAddedLines(repoPath, scope.baseRef)
        } else {
            emptyMap()
        }
        val validation = TechniqueValidator(repository).validateAll()
        if (validation.errors.isNotEmpty()) {
            return ScanSummary(
                status = ScanStatus.UNKNOWN.name.lowercase(),
                summary = "Technique validation failed: ${validation.errors.first().messages.firstOrNull() ?: "unknown"}",
                findings = emptyList()
            )
        }
        val validated = validation.techniques.associateBy { it.id }
        val findings = mutableListOf<ScanFinding>()
        val summaries = mutableListOf<String>()
        val gatingSummaries = mutableListOf<String>()
        val techniqueCounts = linkedMapOf<String, Int>()
        val gatingByTechnique = linkedMapOf<String, GatingStats>()
        val techniqueNames = linkedMapOf<String, String>()
        var overallStatus = ScanStatus.PASS
        var filesScannedTotal = 0
        var chunksAnalyzedTotal = 0
        var chunksFailedTotal = 0
        val shouldCancel = cancelRequested ?: { false }

        if (batchEnabled) {
            val batches = techniqueIds.chunked(batchSize.coerceAtLeast(1))
            val totalBatches = batches.size
            for ((batchIndex, batchIds) in batches.withIndex()) {
                if (shouldCancel()) throw ScanCancelledException()
                val techniques = batchIds.mapNotNull { id ->
                    val normalized = normalizeTechniqueId(id)
                    validated[normalized] ?: loadTechnique(normalized)
                }
                if (techniques.isEmpty()) {
                    continue
                }
                for (tech in techniques) {
                    techniqueCounts.putIfAbsent(tech.id, 0)
                    gatingByTechnique.putIfAbsent(tech.id, GatingStats())
                    techniqueNames.putIfAbsent(tech.id, tech.name)
                }

                val filters = adjustFiltersForScope(buildFilters(config), scope)
                val files = when (scope) {
                    is ScopeKind.FullRepo -> ScannerUtils.collectFiles(repoPath, filters)
                    is ScopeKind.File -> listOf(scope.file)
                    is ScopeKind.Selection -> listOf(scope.file)
                    is ScopeKind.OpenFiles -> scope.files
                    is ScopeKind.GitDiff -> {
                        val changed = ScannerUtils.gitChangedFiles(repoPath, scope.baseRef, scope.includeUntracked)
                        changed.toList()
                    }
                }
                val batchSignature = hashString(techniqueHasher.writeValueAsString(techniques))
                val modelLineCap = selectMaxLinesPerChunk(model.name, maxLinesPerChunk)
                val batchLabel = "Batch ${batchIndex + 1} of $totalBatches"
                val batchName = techniques.joinToString(", ") { it.name }.take(200)
                val batchResult = scanFilesBatch(
                    files,
                    repoPath,
                    techniques,
                    batchSignature,
                    provider,
                    scope,
                    modelLineCap,
                    filters,
                    model,
                    diffAddedLines,
                    shouldCancel,
                    progress,
                    gatingByTechnique,
                    batchLabel,
                    batchName,
                    batchIndex + 1,
                    totalBatches
                )
                filesScannedTotal += batchResult.filesScanned
                chunksAnalyzedTotal += batchResult.chunksAnalyzed
                chunksFailedTotal += batchResult.chunksFailed
                if (batchResult.findings.isNotEmpty()) {
                    overallStatus = ScanStatus.FAIL
                }
                batchResult.findings.forEach { finding ->
                    val techniqueId = finding.techniqueId
                    techniqueCounts[techniqueId] = (techniqueCounts[techniqueId] ?: 0) + 1
                    if (!techniqueNames.containsKey(techniqueId)) {
                        techniqueNames[techniqueId] = techniqueId
                    }
                }
                findings.addAll(batchResult.findings)
            }
            for ((tech, count) in techniqueCounts) {
                summaries.add("$tech: $count findings")
            }
            for ((tech, stats) in gatingByTechnique) {
                gatingSummaries.add(
                    "$tech: gated ${stats.gatedChunks}/${stats.totalChunks} chunks; shadow ${stats.shadowChunks} sampled, ${stats.shadowFindings} findings"
                )
            }
        } else {
            val totalTechniques = techniqueIds.size
            for ((index, techniqueId) in techniqueIds.withIndex()) {
                if (shouldCancel()) throw ScanCancelledException()
                val technique = validated[normalizeTechniqueId(techniqueId)] ?: loadTechnique(techniqueId)
                if (technique == null) {
                    summaries.add("$techniqueId: technique not found")
                    overallStatus = ScanStatus.UNKNOWN
                    continue
                }

                val filters = adjustFiltersForScope(buildFilters(config), scope)
                val files = when (scope) {
                    is ScopeKind.FullRepo -> ScannerUtils.collectFiles(repoPath, filters)
                    is ScopeKind.File -> listOf(scope.file)
                    is ScopeKind.Selection -> listOf(scope.file)
                    is ScopeKind.OpenFiles -> scope.files
                    is ScopeKind.GitDiff -> {
                        val changed = ScannerUtils.gitChangedFiles(repoPath, scope.baseRef, scope.includeUntracked)
                        changed.toList()
                    }
                }

                val techniqueSignature = hashString(techniqueHasher.writeValueAsString(technique))
                val modelLineCap = selectMaxLinesPerChunk(model.name, maxLinesPerChunk)
                val batch = scanFiles(
                    files,
                    repoPath,
                    technique,
                    techniqueSignature,
                    provider,
                    scope,
                    modelLineCap,
                    filters,
                    model,
                    diffAddedLines,
                    shouldCancel,
                    progress,
                    technique.id,
                    technique.name,
                    index + 1,
                    totalTechniques
                )
                filesScannedTotal += batch.filesScanned
                chunksAnalyzedTotal += batch.chunksAnalyzed
                chunksFailedTotal += batch.chunksFailed
                if (batch.findings.isNotEmpty()) {
                    overallStatus = ScanStatus.FAIL
                }
                findings.addAll(batch.findings)
                summaries.add("${technique.id}: ${batch.findings.size} findings")
                batch.gatingStats?.let { stats ->
                    gatingSummaries.add(
                        "${technique.id}: gated ${stats.gatedChunks}/${stats.totalChunks} chunks; " +
                            "shadow ${stats.shadowChunks} sampled, ${stats.shadowFindings} findings"
                    )
                }
            }
        }

        val summaryText = "Techniques scanned: ${techniqueIds.size}. Findings: ${findings.size}." +
            if (summaries.isNotEmpty()) "\n" + summaries.joinToString("\n") else ""
        val gatingText = if (gatingSummaries.isNotEmpty()) {
            "\nGating summary:\n" + gatingSummaries.joinToString("\n")
        } else {
            ""
        }

        var summary = ScanSummary(
            status = overallStatus.name.lowercase(),
            summary = summaryText + gatingText,
            findings = findings,
            scannedAtUtc = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).toString(),
            filesScanned = filesScannedTotal,
            chunksAnalyzed = chunksAnalyzedTotal,
            chunksFailed = chunksFailedTotal,
            modelSupport = listOf(model.name)
        )
        if (config.llmReview == true) {
            val reviewed = LlmReview.maybeReview(provider, modelName, apiKey, config, techniqueIds.firstOrNull() ?: "unknown", summary)
            if (reviewed != null) {
                summary = summary.copy(findings = reviewed.findings)
            }
        }
        cache?.flush()
        return summary
    }

    private fun loadTechnique(id: String): TechniqueSpec? {
        val raw = repository.readTechniqueSpec(id) ?: return null
        val technique = yamlMapper.readValue(raw, TechniqueSpec::class.java)
        val resolvedSignals = technique.codeSignals.map { signal ->
            val resolvedHeuristics = signal.heuristics.flatMap { heuristic ->
                if (!heuristic.patternRef.isNullOrBlank()) {
                    patternLibrary.resolve(heuristic.patternRef) ?: emptyList()
                } else {
                    listOf(heuristic)
                }
            }
            signal.copy(heuristics = resolvedHeuristics)
        }
        return technique.copy(codeSignals = resolvedSignals)
    }

    private fun normalizeTechniqueId(id: String): String {
        return if (id.startsWith("SAFE-")) id else "SAFE-$id"
    }

    private fun scanFiles(
        files: List<Path>,
        repoPath: Path,
        technique: TechniqueSpec,
        techniqueSignature: String,
        providerName: String,
        scope: ScopeKind,
        maxLinesPerChunk: Int,
        filters: PathFilters,
        model: CodeModel,
        diffAddedLines: Map<Path, Set<Int>>,
        cancelRequested: () -> Boolean,
        progress: ((ScanProgress) -> Unit)?,
        techniqueId: String,
        techniqueName: String,
        techniqueIndex: Int,
        totalTechniques: Int
    ): ScanBatch {
        val findings = mutableListOf<ScanFinding>()
        val filesScanned = mutableSetOf<Path>()
        var chunksAnalyzed = 0
        var chunksFailed = 0
        val estimator = PromptTokenEstimator(providerName, model.name)
        val gatingStats = if (config.gatingEnabled == true || (config.shadowSampleRate ?: 0.0) > 0.0) GatingStats() else null
        val rng = java.util.Random()
        val totalFiles = files.size
        var processedFiles = 0
        var lastProgressAt = 0
        val includeMatchers = filters.includeGlobs.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludeMatchers = filters.excludeGlobs.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludePatternMatchers = filters.excludePatterns.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val includeOverrideMatchers = filters.includeOverride.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }

        for (file in files) {
            if (cancelRequested()) throw ScanCancelledException()
            processedFiles += 1
            val relative = try { repoPath.relativize(file) } catch (_: Exception) { file }
            if (!ScannerUtils.isIncluded(relative, file, filters, includeMatchers, excludeMatchers, excludePatternMatchers, includeOverrideMatchers)) {
                maybeReportProgress(
                    progress,
                    techniqueId,
                    techniqueName,
                    techniqueIndex,
                    totalTechniques,
                    relative.toString(),
                    null,
                    processedFiles,
                    totalFiles,
                    chunksAnalyzed,
                    chunksFailed,
                    lastProgressAt
                ).also {
                    if (it) lastProgressAt = processedFiles
                }
                continue
            }
            if (!shouldScanFileForTechnique(relative, technique)) {
                maybeReportProgress(
                    progress,
                    techniqueId,
                    techniqueName,
                    techniqueIndex,
                    totalTechniques,
                    relative.toString(),
                    null,
                    processedFiles,
                    totalFiles,
                    chunksAnalyzed,
                    chunksFailed,
                    lastProgressAt
                ).also {
                    if (it) lastProgressAt = processedFiles
                }
                continue
            }
            val lines = ScannerUtils.readTextIfValid(file) ?: continue
            val lineRange = when (scope) {
                is ScopeKind.Selection -> scope.startLine..scope.endLine
                else -> 1..lines.size
            }
            val includeLines = if (scope is ScopeKind.GitDiff) {
                diffAddedLines[file]
            } else {
                null
            }
            val readmeExcerpt = repository.readTechniqueReadme(technique.id)?.let { truncateReadme(it) }
            val mitigationPrompts = technique.mitigations.map { mitigationId ->
                val description = repository.readMitigationReadme(mitigationId)?.lines()?.drop(1)?.joinToString("\n")
                    ?.trim().orEmpty().ifBlank { "See mitigation $mitigationId" }
                MitigationPrompt(mitigationId, description)
            }
            val chunks = if (config.adaptiveChunking == true) {
                val modelConfig = selectAdaptiveConfig(model.name, config)
                buildChunksAdaptive(
                    relative,
                    lines,
                    lineRange,
                    includeLines,
                    technique,
                    readmeExcerpt,
                    mitigationPrompts,
                    estimator,
                    maxLinesPerChunk,
                    modelConfig.maxPromptTokens,
                    modelConfig.reserveOutputTokens
                )
            } else {
                buildChunksFixed(relative, lines, lineRange, maxLinesPerChunk, includeLines)
            }
            RuleHints.applyRuleHints(technique, chunks)
            val modelFindings = chunks.flatMap { chunk ->
                if (cancelRequested()) throw ScanCancelledException()
                gatingStats?.totalChunks = (gatingStats?.totalChunks ?: 0) + 1
                var shadowed = false
                if (chunk.ruleHints.isEmpty() && !technique.llmRequired) {
                    val gatingEnabled = config.gatingEnabled == true
                    val sampleRate = (config.shadowSampleRate ?: 0.0).coerceIn(0.0, 1.0)
                    if (gatingEnabled) {
                        if (sampleRate > 0.0 && rng.nextDouble() <= sampleRate) {
                            shadowed = true
                            gatingStats?.shadowChunks = (gatingStats?.shadowChunks ?: 0) + 1
                        } else {
                            gatingStats?.gatedChunks = (gatingStats?.gatedChunks ?: 0) + 1
                            return@flatMap emptyList()
                        }
                    }
                }
                chunksAnalyzed += 1
                gatingStats?.llmChunks = (gatingStats?.llmChunks ?: 0) + 1
                maybeReportProgress(
                    progress,
                    techniqueId,
                    techniqueName,
                    techniqueIndex,
                    totalTechniques,
                    relative.toString(),
                    chunk.id,
                    processedFiles,
                    totalFiles,
                    chunksAnalyzed,
                    chunksFailed,
                    lastProgressAt
                ).also {
                    if (it) lastProgressAt = processedFiles
                }
                val cacheKey = buildCacheKey(
                    technique.id,
                    techniqueSignature,
                    providerName,
                    model.name,
                    chunk
                )
                val cached = cache?.get(cacheKey)
                if (cached != null) {
                    reportChunkStatus(
                        progress,
                        techniqueId,
                        techniqueName,
                        techniqueIndex,
                        totalTechniques,
                        relative.toString(),
                        chunk.id,
                        processedFiles,
                        totalFiles,
                        chunksAnalyzed,
                        chunksFailed,
                        ai.astha.scantheplanet.idea.scanner.ChunkStatus.CACHED
                    )
                    return@flatMap cached
                }
                val payload = PromptPayload(
                    techniqueId = technique.id,
                    techniqueName = technique.name,
                    severity = technique.severity,
                    summary = technique.summary,
                    description = technique.description,
                    mitigations = mitigationPrompts,
                    codeChunk = CodeChunkPrompt(
                        id = chunk.id,
                        file = relative.toString(),
                        startLine = chunk.startLine,
                        endLine = chunk.endLine,
                        code = chunk.code,
                        ruleHints = chunk.ruleHints
                    ),
                    readmeExcerpt = readmeExcerpt
                )
                try {
                    val findings = model.analyzeChunk(payload)
                    if (shadowed && findings.isNotEmpty()) {
                        gatingStats?.shadowFindings = (gatingStats?.shadowFindings ?: 0) + findings.size
                    }
                    cache?.put(cacheKey, findings)
                    cache?.flushMaybe()
                    findings
                } catch (e: Exception) {
                    chunksFailed += 1
                    throw e
                }
            }
            findings.addAll(modelFindings.map { it.toScanFinding(technique.id, technique.name) })
            if (chunks.isNotEmpty()) {
                filesScanned.add(relative)
            }
            maybeReportProgress(
                progress,
                techniqueId,
                techniqueName,
                techniqueIndex,
                totalTechniques,
                relative.toString(),
                null,
                processedFiles,
                totalFiles,
                chunksAnalyzed,
                chunksFailed,
                lastProgressAt
            ).also {
                if (it) lastProgressAt = processedFiles
            }
        }

        return ScanBatch(findings, filesScanned.size, chunksAnalyzed, chunksFailed, gatingStats)
    }

    private fun scanFilesBatch(
        files: List<Path>,
        repoPath: Path,
        techniques: List<TechniqueSpec>,
        batchSignature: String,
        providerName: String,
        scope: ScopeKind,
        maxLinesPerChunk: Int,
        filters: PathFilters,
        model: CodeModel,
        diffAddedLines: Map<Path, Set<Int>>,
        cancelRequested: () -> Boolean,
        progress: ((ScanProgress) -> Unit)?,
        gatingByTechnique: MutableMap<String, GatingStats>,
        batchLabel: String,
        batchName: String,
        batchIndex: Int,
        totalBatches: Int
    ): ScanBatch {
        val findings = mutableListOf<ScanFinding>()
        val filesScanned = mutableSetOf<Path>()
        var chunksAnalyzed = 0
        var chunksFailed = 0
        val estimator = PromptTokenEstimator(providerName, model.name)
        val totalFiles = files.size
        var processedFiles = 0
        var lastProgressAt = 0
        val includeMatchers = filters.includeGlobs.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludeMatchers = filters.excludeGlobs.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludePatternMatchers = filters.excludePatterns.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val includeOverrideMatchers = filters.includeOverride.map { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        val rng = java.util.Random()

        for (file in files) {
            if (cancelRequested()) throw ScanCancelledException()
            processedFiles += 1
            val relative = try { repoPath.relativize(file) } catch (_: Exception) { file }
            if (!ScannerUtils.isIncluded(relative, file, filters, includeMatchers, excludeMatchers, excludePatternMatchers, includeOverrideMatchers)) {
                maybeReportProgress(
                    progress,
                    batchLabel,
                    batchName,
                    batchIndex,
                    totalBatches,
                    relative.toString(),
                    null,
                    processedFiles,
                    totalFiles,
                    chunksAnalyzed,
                    chunksFailed,
                    lastProgressAt
                ).also {
                    if (it) lastProgressAt = processedFiles
                }
                continue
            }
            val eligibleTechniques = techniques.filter { shouldScanFileForTechnique(relative, it) }
            if (eligibleTechniques.isEmpty()) {
                maybeReportProgress(
                    progress,
                    batchLabel,
                    batchName,
                    batchIndex,
                    totalBatches,
                    relative.toString(),
                    null,
                    processedFiles,
                    totalFiles,
                    chunksAnalyzed,
                    chunksFailed,
                    lastProgressAt
                ).also {
                    if (it) lastProgressAt = processedFiles
                }
                continue
            }
            val lines = ScannerUtils.readTextIfValid(file) ?: continue
            val lineRange = when (scope) {
                is ScopeKind.Selection -> scope.startLine..scope.endLine
                else -> 1..lines.size
            }
            val includeLines = if (scope is ScopeKind.GitDiff) {
                diffAddedLines[file]
            } else {
                null
            }
            val chunks = if (config.adaptiveChunking == true) {
                val modelConfig = selectAdaptiveConfig(model.name, config)
                buildChunksAdaptive(
                    relative,
                    lines,
                    lineRange,
                    includeLines,
                    eligibleTechniques.first(),
                    null,
                    emptyList(),
                    estimator,
                    maxLinesPerChunk,
                    modelConfig.maxPromptTokens,
                    modelConfig.reserveOutputTokens
                )
            } else {
                buildChunksFixed(relative, lines, lineRange, maxLinesPerChunk, includeLines)
            }

            for (chunk in chunks) {
                if (cancelRequested()) throw ScanCancelledException()
                val hintsForChunk = eligibleTechniques.associate { tech -> tech.id to RuleHints.computeHints(tech, chunk) }
                val activeTechniques = mutableListOf<ai.astha.scantheplanet.idea.scanner.prompt.TechniquePrompt>()
                var gated = true
                for (tech in eligibleTechniques) {
                    val hints = hintsForChunk[tech.id].orEmpty()
                    val stats = gatingByTechnique.getOrPut(tech.id) { GatingStats() }
                    stats.totalChunks += 1
                    if (hints.isNotEmpty() || tech.llmRequired || config.gatingEnabled != true) {
                        gated = false
                    }
                }

                var shadowed = false
                val sampleRate = (config.shadowSampleRate ?: 0.0).coerceIn(0.0, 1.0)
                if (config.gatingEnabled == true && gated && sampleRate > 0.0 && rng.nextDouble() <= sampleRate) {
                    shadowed = true
                    gated = false
                }

                if (config.gatingEnabled == true && gated) {
                    for (tech in eligibleTechniques) {
                        val stats = gatingByTechnique.getOrPut(tech.id) { GatingStats() }
                        stats.gatedChunks += 1
                    }
                    continue
                }

                for (tech in eligibleTechniques) {
                    if (cancelRequested()) throw ScanCancelledException()
                    val hints = hintsForChunk[tech.id].orEmpty()
                    if (config.gatingEnabled == true && !tech.llmRequired && hints.isEmpty() && !shadowed) {
                        continue
                    }
                    if (shadowed && hints.isEmpty() && !tech.llmRequired) {
                        val stats = gatingByTechnique.getOrPut(tech.id) { GatingStats() }
                        stats.shadowChunks += 1
                    }
                    val mitigations = tech.mitigations.map { mitigationId ->
                        val description = repository.readMitigationReadme(mitigationId)?.lines()?.drop(1)?.joinToString("\n")
                            ?.trim().orEmpty().ifBlank { "See mitigation $mitigationId" }
                        ai.astha.scantheplanet.idea.scanner.prompt.MitigationPrompt(mitigationId, description)
                    }
                    activeTechniques.add(
                        ai.astha.scantheplanet.idea.scanner.prompt.TechniquePrompt(
                            id = tech.id,
                            name = tech.name,
                            severity = tech.severity,
                            summary = tech.summary,
                            description = tech.description,
                            mitigations = mitigations,
                            ruleHints = hints
                        )
                    )
                }

            if (activeTechniques.isEmpty()) {
                continue
            }

            chunksAnalyzed += 1
            maybeReportProgress(
                progress,
                batchLabel,
                batchName,
                batchIndex,
                totalBatches,
                relative.toString(),
                chunk.id,
                processedFiles,
                totalFiles,
                chunksAnalyzed,
                chunksFailed,
                lastProgressAt
            ).also {
                if (it) lastProgressAt = processedFiles
            }
            for (tech in activeTechniques) {
                val stats = gatingByTechnique.getOrPut(tech.id) { GatingStats() }
                stats.llmChunks += 1
                }
                val cacheKey = buildCacheKeyBatch(
                    batchSignature,
                    providerName,
                    model.name,
                    chunk,
                    activeTechniques.map { it.id }
                )
                val techniqueNameMap = activeTechniques.associate { it.id to it.name }
                val defaultTechniqueId = if (activeTechniques.size == 1) activeTechniques[0].id else "unknown"
                val defaultTechniqueName = if (activeTechniques.size == 1) activeTechniques[0].name else ""
                val cached = cache?.get(cacheKey)
                if (cached != null) {
                    reportChunkStatus(
                        progress,
                        batchLabel,
                        batchName,
                        batchIndex,
                        totalBatches,
                        relative.toString(),
                        chunk.id,
                        processedFiles,
                        totalFiles,
                        chunksAnalyzed,
                        chunksFailed,
                        ai.astha.scantheplanet.idea.scanner.ChunkStatus.CACHED
                    )
                    findings.addAll(
                        cached.map { finding ->
                            val id = finding.techniqueId ?: defaultTechniqueId
                            val name = techniqueNameMap[finding.techniqueId] ?: defaultTechniqueName
                            finding.toScanFinding(id, name)
                        }
                    )
                    continue
                }
                val payload = ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload(
                    techniques = activeTechniques,
                    codeChunk = ai.astha.scantheplanet.idea.scanner.prompt.CodeChunkPrompt(
                        id = chunk.id,
                        file = relative.toString(),
                        startLine = chunk.startLine,
                        endLine = chunk.endLine,
                        code = chunk.code,
                        ruleHints = emptyList()
                    ),
                    readmeExcerpt = null
                )
                try {
                    val result = model.analyzeChunkBatch(payload)
                    for (finding in result) {
                        if (shadowed) {
                            val stats = gatingByTechnique.getOrPut(finding.techniqueId ?: "unknown") { GatingStats() }
                            stats.shadowFindings += 1
                        }
                    }
                    cache?.put(cacheKey, result)
                    cache?.flushMaybe()
                    findings.addAll(
                        result.map { finding ->
                            val id = finding.techniqueId ?: defaultTechniqueId
                            val name = techniqueNameMap[finding.techniqueId] ?: defaultTechniqueName
                            finding.toScanFinding(id, name)
                        }
                    )
                } catch (e: Exception) {
                    chunksFailed += 1
                    throw e
                }
            }

            if (chunks.isNotEmpty()) {
                filesScanned.add(relative)
            }
            maybeReportProgress(
                progress,
                batchLabel,
                batchName,
                batchIndex,
                totalBatches,
                relative.toString(),
                null,
                processedFiles,
                totalFiles,
                chunksAnalyzed,
                chunksFailed,
                lastProgressAt
            ).also {
                if (it) lastProgressAt = processedFiles
            }
        }

        return ScanBatch(findings, filesScanned.size, chunksAnalyzed, chunksFailed, null)
    }

    private fun maybeReportProgress(
        progress: ((ScanProgress) -> Unit)?,
        techniqueId: String,
        techniqueName: String,
        techniqueIndex: Int,
        totalTechniques: Int,
        currentFile: String,
        currentChunkId: String?,
        processedFiles: Int,
        totalFiles: Int,
        chunksAnalyzed: Int,
        chunksFailed: Int,
        lastReportedAt: Int
    ): Boolean {
        if (progress == null) return false
        if (totalFiles == 0) return false
        val shouldReport = processedFiles == totalFiles || processedFiles - lastReportedAt >= 5
        if (!shouldReport) return false
        progress(
            ScanProgress(
                techniqueId = techniqueId,
                techniqueName = techniqueName,
                techniqueIndex = techniqueIndex,
                totalTechniques = totalTechniques,
                currentFile = currentFile,
                currentChunkId = currentChunkId,
                processedFiles = processedFiles,
                totalFiles = totalFiles,
                chunksAnalyzed = chunksAnalyzed,
                chunksFailed = chunksFailed,
                phase = ScanPhase.SCANNING,
                chunkStatus = ai.astha.scantheplanet.idea.scanner.ChunkStatus.SCANNING
            )
        )
        return true
    }

    private fun reportChunkStatus(
        progress: ((ScanProgress) -> Unit)?,
        techniqueId: String,
        techniqueName: String,
        techniqueIndex: Int,
        totalTechniques: Int,
        currentFile: String,
        currentChunkId: String?,
        processedFiles: Int,
        totalFiles: Int,
        chunksAnalyzed: Int,
        chunksFailed: Int,
        status: ai.astha.scantheplanet.idea.scanner.ChunkStatus
    ) {
        if (progress == null || totalFiles == 0) return
        progress(
            ScanProgress(
                techniqueId = techniqueId,
                techniqueName = techniqueName,
                techniqueIndex = techniqueIndex,
                totalTechniques = totalTechniques,
                currentFile = currentFile,
                currentChunkId = currentChunkId,
                processedFiles = processedFiles,
                totalFiles = totalFiles,
                chunksAnalyzed = chunksAnalyzed,
                chunksFailed = chunksFailed,
                phase = ScanPhase.SCANNING,
                chunkStatus = status
            )
        )
    }

    private data class ScanBatch(
        val findings: List<ScanFinding>,
        val filesScanned: Int,
        val chunksAnalyzed: Int,
        val chunksFailed: Int,
        val gatingStats: GatingStats?
    )

    private data class GatingStats(
        var totalChunks: Int = 0,
        var gatedChunks: Int = 0,
        var llmChunks: Int = 0,
        var shadowChunks: Int = 0,
        var shadowFindings: Int = 0
    )

    private fun buildChunksFixed(
        relativePath: Path,
        lines: List<String>,
        lineRange: IntRange,
        maxLinesPerChunk: Int,
        includeLines: Set<Int>?
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        var start = lineRange.first
        val end = lineRange.last
        while (start <= end) {
            val chunkEnd = (start + maxLinesPerChunk - 1).coerceAtMost(end)
            if (includeLines == null || includeLines.any { it in start..chunkEnd }) {
                val code = lines.subList(start - 1, chunkEnd).joinToString("\n")
                val chunkId = "${relativePath}:${start}-${chunkEnd}"
                chunks.add(
                    CodeChunk(
                        id = chunkId,
                        file = relativePath,
                        startLine = start,
                        endLine = chunkEnd,
                        kind = if (chunkEnd - start + 1 >= maxLinesPerChunk) ChunkKind.SlidingWindow else ChunkKind.WholeFile,
                        code = code
                    )
                )
            }
            start = chunkEnd + 1
        }
        return chunks
    }

    private fun buildChunksAdaptive(
        relativePath: Path,
        lines: List<String>,
        lineRange: IntRange,
        includeLines: Set<Int>?,
        technique: TechniqueSpec,
        readmeExcerpt: String?,
        mitigations: List<MitigationPrompt>,
        estimator: PromptTokenEstimator,
        maxLinesPerChunk: Int,
        maxPromptTokens: Int,
        reserveOutputTokens: Int
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val baseTokens = estimator.baseTokens(
            technique = technique,
            readmeExcerpt = readmeExcerpt,
            mitigations = mitigations,
            filePath = relativePath.toString()
        )
        val budget = (maxPromptTokens - reserveOutputTokens).coerceAtLeast(500)
        val effectiveBudget = (budget * 0.85).toInt()
        val lineTokens = lines.map { estimator.countTokens(it + "\n") }

        var start = lineRange.first
        val end = lineRange.last
        while (start <= end) {
            var tokenSum = baseTokens
            var cursor = start
            var lastGood = start - 1
            while (cursor <= end && (cursor - start + 1) <= maxLinesPerChunk) {
                tokenSum += lineTokens[cursor - 1]
                if (tokenSum <= effectiveBudget) {
                    lastGood = cursor
                    cursor += 1
                } else {
                    break
                }
            }
            if (lastGood < start) {
                lastGood = start
            }
            if (includeLines == null || includeLines.any { it in start..lastGood }) {
                val code = lines.subList(start - 1, lastGood).joinToString("\n")
                val chunkId = "${relativePath}:${start}-${lastGood}"
                chunks.add(
                    CodeChunk(
                        id = chunkId,
                        file = relativePath,
                        startLine = start,
                        endLine = lastGood,
                        kind = if (lastGood - start + 1 >= maxLinesPerChunk) ChunkKind.SlidingWindow else ChunkKind.WholeFile,
                        code = code
                    )
                )
            }
            start = lastGood + 1
        }
        return chunks
    }

    private fun selectAdaptiveConfig(modelName: String, config: ScannerConfig): AdaptiveChunkConfig {
        val lower = modelName.lowercase()
        if (lower.startsWith("gpt-5")) {
            return AdaptiveChunkConfig(
                maxPromptTokens = ScannerConfigDefaults.GPT5_MAX_PROMPT_TOKENS,
                reserveOutputTokens = ScannerConfigDefaults.GPT5_RESERVE_OUTPUT_TOKENS
            )
        }
        return AdaptiveChunkConfig(
            maxPromptTokens = config.maxPromptTokens ?: ScannerConfigDefaults.DEFAULT_MAX_PROMPT_TOKENS,
            reserveOutputTokens = config.reserveOutputTokens ?: ScannerConfigDefaults.DEFAULT_RESERVE_OUTPUT_TOKENS
        )
    }

    private data class AdaptiveChunkConfig(
        val maxPromptTokens: Int,
        val reserveOutputTokens: Int
    )

    private fun selectMaxLinesPerChunk(modelName: String, base: Int): Int {
        val lower = modelName.lowercase()
        return if (lower.startsWith("gpt-5")) {
            maxOf(base, 2000)
        } else {
            base
        }
    }

    private fun ModelFinding.toScanFinding(techniqueId: String, techniqueName: String): ScanFinding {
        val cleanedName = cleanTechniqueName(techniqueId, techniqueName)
        return ScanFinding(
            techniqueId = this.techniqueId ?: techniqueId,
            techniqueName = cleanedName,
            severity = severity,
            file = file,
            startLine = startLine,
            endLine = endLine,
            observation = observation
        )
    }

    private fun cleanTechniqueName(techniqueId: String, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.equals(techniqueId, ignoreCase = true)) return ""
        val prefix = Regex("^SAFE-T\\d+\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
        return trimmed.replace(prefix, "").trim()
    }

    private fun buildFilters(config: ScannerConfig): PathFilters {
        return PathFilters(
            includeExtensions = config.includeExtensions?.map { it.lowercase() } ?: emptyList(),
            excludeExtensions = config.excludeExtensions?.map { it.lowercase() } ?: emptyList(),
            includeGlobs = config.includeGlobs ?: emptyList(),
            excludeGlobs = config.excludeGlobs ?: emptyList(),
            maxFileBytes = config.maxFileBytes,
            excludeDocs = config.excludeDocs ?: true,
            excludeTests = config.excludeTests ?: true,
            includeOverride = config.includeOverride ?: emptyList(),
            excludePatterns = config.excludePatterns ?: emptyList(),
            onlyFiles = null
        )
    }

    private fun adjustFiltersForScope(filters: PathFilters, scope: ScopeKind): PathFilters {
        return when (scope) {
            is ScopeKind.OpenFiles, is ScopeKind.GitDiff -> {
                // Focused scopes should respect the user's explicit file selection, not broad repo filters.
                PathFilters(
                    includeExtensions = emptyList(),
                    excludeExtensions = emptyList(),
                    includeGlobs = emptyList(),
                    excludeGlobs = emptyList(),
                    maxFileBytes = filters.maxFileBytes,
                    excludeDocs = false,
                    excludeTests = false,
                    includeOverride = emptyList(),
                    excludePatterns = emptyList(),
                    onlyFiles = filters.onlyFiles
                )
            }
            else -> filters
        }
    }

    private fun truncateReadme(readme: String): String {
        val max = readmeMaxChars()
        return if (readme.length <= max) readme else readme.substring(0, max)
    }

    private fun readmeMaxChars(): Int {
        val raw = System.getenv("MAX_README_CHARS") ?: return 8000
        val parsed = raw.toIntOrNull() ?: return 8000
        return parsed.coerceIn(500, 100000)
    }

    private fun buildCacheKey(
        techniqueId: String,
        techniqueSignature: String,
        providerName: String,
        modelName: String,
        chunk: CodeChunk
    ): String {
        val input = listOf(
            techniqueId,
            techniqueSignature,
            providerName,
            modelName,
            ai.astha.scantheplanet.idea.scanner.prompt.PromptBuilder.PROMPT_VERSION,
            chunk.file.toString(),
            chunk.startLine.toString(),
            chunk.endLine.toString(),
            chunk.code
        ).joinToString("|")
        return hashString(input)
    }

    private fun buildCacheKeyBatch(
        batchSignature: String,
        providerName: String,
        modelName: String,
        chunk: CodeChunk,
        techniqueIds: List<String>
    ): String {
        val ids = techniqueIds.sorted().joinToString(",")
        val input = listOf(
            batchSignature,
            providerName,
            modelName,
            ai.astha.scantheplanet.idea.scanner.prompt.PromptBuilder.PROMPT_VERSION,
            ids,
            chunk.file.toString(),
            chunk.startLine.toString(),
            chunk.endLine.toString(),
            chunk.code
        ).joinToString("|")
        return hashString(input)
    }

    private fun hashString(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append(String.format("%02x", byte))
        }
        return sb.toString()
    }

    private fun shouldScanFileForTechnique(relative: Path, technique: TechniqueSpec): Boolean {
        val name = relative.fileName?.toString() ?: return true
        if (!name.equals("pyproject.toml", ignoreCase = true)) {
            return true
        }
        return isTechniqueRelevantForPyproject(technique)
    }

    private fun isTechniqueRelevantForPyproject(technique: TechniqueSpec): Boolean {
        val explicit = setOf("SAFE-T1002", "SAFE-T1004")
        if (explicit.contains(technique.id)) {
            return true
        }
        val text = buildString {
            append(technique.name)
            append(' ')
            append(technique.summary)
            append(' ')
            append(technique.description)
        }.lowercase()
        val keywords = listOf(
            "dependency",
            "dependencies",
            "package",
            "packaging",
            "version",
            "supply chain",
            "supply-chain",
            "registry",
            "typosquat",
            "typosquatting",
            "name-collision",
            "lockfile",
            "manifest",
            "pypi",
            "poetry",
            "python"
        )
        return keywords.any { text.contains(it) }
    }
}
