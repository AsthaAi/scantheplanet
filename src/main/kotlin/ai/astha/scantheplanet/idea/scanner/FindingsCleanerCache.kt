package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path

class FindingsCleanerCache {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val lock = Any()
    private val cacheFile: Path = Path.of(PathManager.getSystemPath())
        .resolve("scantheplanet")
        .resolve("clean-cache")
        .resolve("cleaned-findings.json")
    private var state: CacheState = load()

    fun get(key: String): List<ScanFinding>? {
        synchronized(lock) {
            return state.entries[key]
        }
    }

    fun put(key: String, findings: List<ScanFinding>) {
        synchronized(lock) {
            state.entries[key] = findings
            flush()
        }
    }

    private fun flush() {
        try {
            Files.createDirectories(cacheFile.parent)
            mapper.writeValue(cacheFile.toFile(), state)
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
        val entries: MutableMap<String, List<ScanFinding>> = mutableMapOf()
    )
}
