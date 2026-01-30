package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import ai.astha.scantheplanet.idea.scanner.model.ModelFinding
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime

class ScanCache(private val cacheFile: Path) {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val lock = Any()
    private var state: CacheState = load()
    private var pendingWrites = 0
    private var lastFlushNanos = System.nanoTime()

    fun get(key: String): List<ModelFinding>? {
        synchronized(lock) {
            val entry = state.entries[key] ?: return null
            return entry.findings.map { it.toModelFinding() }
        }
    }

    fun put(key: String, findings: List<ModelFinding>) {
        synchronized(lock) {
            state.entries[key] = CacheEntry(
                cachedAtUtc = ZonedDateTime.now(java.time.ZoneOffset.UTC).toString(),
                findings = findings.map { ModelFindingCache.from(it) }
            )
            pendingWrites += 1
        }
    }

    fun flush() {
        synchronized(lock) {
            flushLocked(System.nanoTime())
        }
    }

    fun flushMaybe() {
        synchronized(lock) {
            if (pendingWrites == 0) return
            val now = System.nanoTime()
            val shouldFlush = pendingWrites >= 20 || (now - lastFlushNanos) >= 5_000_000_000L
            if (shouldFlush) {
                flushLocked(now)
            }
        }
    }

    private fun flushLocked(nowNanos: Long) {
        try {
            Files.createDirectories(cacheFile.parent)
            mapper.writeValue(cacheFile.toFile(), state)
            pendingWrites = 0
            lastFlushNanos = nowNanos
        } catch (_: Exception) {
            // best-effort cache
        }
    }

    private fun load(): CacheState {
        return try {
            if (Files.exists(cacheFile)) {
                mapper.readValue(cacheFile.toFile())
            } else {
                CacheState()
            }
        } catch (_: Exception) {
            CacheState()
        }
    }

    data class CacheState(
        val entries: MutableMap<String, CacheEntry> = mutableMapOf()
    )

    data class CacheEntry(
        val cachedAtUtc: String,
        val findings: List<ModelFindingCache>
    )

    data class ModelFindingCache(
        val techniqueId: String? = null,
        val chunkId: String,
        val file: String,
        val startLine: Int,
        val endLine: Int,
        val severity: String,
        val confidence: Double?,
        val observation: String,
        val evidence: String,
        val reasoning: String?,
        val unknownMitigations: List<String>,
        val modelName: String
    ) {
        fun toModelFinding(): ModelFinding {
            return ModelFinding(
                techniqueId = techniqueId,
                chunkId = chunkId,
                file = file,
                startLine = startLine,
                endLine = endLine,
                severity = severity,
                confidence = confidence,
                observation = observation,
                evidence = evidence,
                reasoning = reasoning,
                unknownMitigations = unknownMitigations,
                modelName = modelName
            )
        }

        companion object {
            fun from(finding: ModelFinding): ModelFindingCache {
                return ModelFindingCache(
                    techniqueId = finding.techniqueId,
                    chunkId = finding.chunkId,
                    file = finding.file,
                    startLine = finding.startLine,
                    endLine = finding.endLine,
                    severity = finding.severity,
                    confidence = finding.confidence,
                    observation = finding.observation,
                    evidence = finding.evidence,
                    reasoning = finding.reasoning,
                    unknownMitigations = finding.unknownMitigations,
                    modelName = finding.modelName
                )
            }
        }
    }
}
