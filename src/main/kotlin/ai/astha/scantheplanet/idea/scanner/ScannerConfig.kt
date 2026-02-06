package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path

data class ScannerConfig(
    val allowRemoteProviders: Boolean = false,
    val allowedProviders: List<String>? = null,
    val modelNames: List<String>? = null,
    val openaiApiKey: String? = null,
    val anthropicApiKey: String? = null,
    val geminiApiKey: String? = null,
    val ollamaEndpoint: String? = null,
    val ollamaLoggingEnabled: Boolean = false,
    val ollamaLogPath: String? = null,
    val retryMaxRetries: Int? = null,
    val retryDelayMs: Long? = null,
    val timeoutMs: Long? = null,
    val includeExtensions: List<String>? = null,
    val excludeExtensions: List<String>? = null,
    val includeGlobs: List<String>? = null,
    val excludeGlobs: List<String>? = null,
    val sourceCodeOnly: Boolean? = null,
    val maxFileBytes: Long? = null,
    val excludePatterns: List<String>? = null,
    val includeOverride: List<String>? = null,
    val excludeDocs: Boolean? = null,
    val excludeTests: Boolean? = null,
    val llmReview: Boolean? = null,
    val adaptiveChunking: Boolean? = null,
    val maxPromptTokens: Int? = null,
    val reserveOutputTokens: Int? = null,
    val gatingEnabled: Boolean? = null,
    val shadowSampleRate: Double? = null,
    val chunkParallelism: Int? = null,
    val chunkParallelismMax: Int? = null
)

class ScannerConfigLoader {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun load(path: Path?): ScannerConfig {
        val base = if (path != null) {
            val content = path.toFile().readText(Charsets.UTF_8)
            if (path.toString().endsWith(".json")) {
                jsonMapper.readValue<ScannerConfig>(content)
            } else {
                yamlMapper.readValue<ScannerConfig>(content)
            }
        } else {
            ScannerConfig()
        }
        return applyEnvOverrides(base)
    }

    private fun applyEnvOverrides(config: ScannerConfig): ScannerConfig {
        var cfg = config
        val allowRemoteProviders = envBool("ALLOW_REMOTE_PROVIDERS", cfg.allowRemoteProviders)
        cfg = cfg.copy(allowRemoteProviders = allowRemoteProviders)

        envList("ALLOWED_PROVIDERS")?.let { cfg = cfg.copy(allowedProviders = it) }
        envList("MODEL_NAMES")?.let { cfg = cfg.copy(modelNames = it) }
        envString("OPENAI_API_KEY")?.let { cfg = cfg.copy(openaiApiKey = it) }
        envString("ANTHROPIC_API_KEY")?.let { cfg = cfg.copy(anthropicApiKey = it) }

        val gemini = envString("GEMINI_API_KEY")
            ?: envString("GOOGLE_GENERATIVE_AI_API_KEY")
            ?: envString("GOOGLE_API_KEY")
        if (!gemini.isNullOrBlank()) {
            cfg = cfg.copy(geminiApiKey = gemini)
        }
        envString("OLLAMA_ENDPOINT")?.let { cfg = cfg.copy(ollamaEndpoint = it) }
        if (System.getenv().containsKey("OLLAMA_LOGGING_ENABLED")) {
            cfg = cfg.copy(ollamaLoggingEnabled = envBool("OLLAMA_LOGGING_ENABLED", false))
        }
        envString("OLLAMA_LOG_PATH")?.let { cfg = cfg.copy(ollamaLogPath = it) }

        envInt("RETRY_MAX_RETRIES")?.let { cfg = cfg.copy(retryMaxRetries = it) }
        envLong("RETRY_DELAY_MS")?.let { cfg = cfg.copy(retryDelayMs = it) }
        envLong("TIMEOUT_MS")?.let { cfg = cfg.copy(timeoutMs = it) }
        envList("INCLUDE_EXTENSIONS")?.let { cfg = cfg.copy(includeExtensions = it) }
        envList("EXCLUDE_EXTENSIONS")?.let { cfg = cfg.copy(excludeExtensions = it) }
        envList("INCLUDE_GLOBS")?.let { cfg = cfg.copy(includeGlobs = it) }
        envList("EXCLUDE_GLOBS")?.let { cfg = cfg.copy(excludeGlobs = it) }
        if (System.getenv().containsKey("SCANNER_SOURCE_CODE_ONLY")) {
            cfg = cfg.copy(sourceCodeOnly = envBool("SCANNER_SOURCE_CODE_ONLY", false))
        }
        envLong("MAX_FILE_BYTES")?.let { cfg = cfg.copy(maxFileBytes = it) }
        envList("SCANNER_EXCLUDE_PATTERNS")?.let { cfg = cfg.copy(excludePatterns = it) }
        envList("SCANNER_INCLUDE_OVERRIDE")?.let { cfg = cfg.copy(includeOverride = it) }
        if (System.getenv().containsKey("SCANNER_EXCLUDE_DOCS")) {
            cfg = cfg.copy(excludeDocs = envBool("SCANNER_EXCLUDE_DOCS", true))
        }
        if (System.getenv().containsKey("SCANNER_EXCLUDE_TESTS")) {
            cfg = cfg.copy(excludeTests = envBool("SCANNER_EXCLUDE_TESTS", true))
        }
        if (System.getenv().containsKey("LLM_REVIEW")) {
            cfg = cfg.copy(llmReview = envBool("LLM_REVIEW", false))
        }
        if (System.getenv().containsKey("ADAPTIVE_CHUNKING")) {
            cfg = cfg.copy(adaptiveChunking = envBool("ADAPTIVE_CHUNKING", true))
        }
        envInt("MAX_PROMPT_TOKENS")?.let { cfg = cfg.copy(maxPromptTokens = it) }
        envInt("RESERVE_OUTPUT_TOKENS")?.let { cfg = cfg.copy(reserveOutputTokens = it) }
        if (System.getenv().containsKey("GATING_ENABLED")) {
            cfg = cfg.copy(gatingEnabled = envBool("GATING_ENABLED", true))
        }
        envDouble("SHADOW_SAMPLE_RATE")?.let { cfg = cfg.copy(shadowSampleRate = it) }
        envInt("SCANNER_CHUNK_PARALLELISM")?.let { cfg = cfg.copy(chunkParallelism = it) }
        envInt("SCANNER_CHUNK_PARALLELISM_MAX")?.let { cfg = cfg.copy(chunkParallelismMax = it) }

        val maxFileBytes = cfg.maxFileBytes ?: ScannerConfigDefaults.DEFAULT_MAX_FILE_BYTES
        val adaptiveChunking = cfg.adaptiveChunking ?: true
        val maxPromptTokens = cfg.maxPromptTokens ?: ScannerConfigDefaults.DEFAULT_MAX_PROMPT_TOKENS
        val reserveOutputTokens = cfg.reserveOutputTokens ?: ScannerConfigDefaults.DEFAULT_RESERVE_OUTPUT_TOKENS
        val gatingEnabled = cfg.gatingEnabled ?: true
        val shadowSampleRate = cfg.shadowSampleRate ?: 0.0
        val chunkParallelism = cfg.chunkParallelism
        val chunkParallelismMax = cfg.chunkParallelismMax
        cfg = cfg.copy(
            maxFileBytes = maxFileBytes,
            adaptiveChunking = adaptiveChunking,
            maxPromptTokens = maxPromptTokens,
            reserveOutputTokens = reserveOutputTokens,
            gatingEnabled = gatingEnabled,
            shadowSampleRate = shadowSampleRate,
            chunkParallelism = chunkParallelism,
            chunkParallelismMax = chunkParallelismMax
        )
        return cfg
    }

    private fun envString(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

    private fun envList(key: String): List<String>? {
        val raw = envString(key) ?: return null
        val list = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) null else list
    }

    private fun envBool(key: String, default: Boolean): Boolean {
        return when (System.getenv(key)?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            null -> default
            else -> default
        }
    }

    private fun envInt(key: String): Int? = System.getenv(key)?.toIntOrNull()

    private fun envLong(key: String): Long? = System.getenv(key)?.toLongOrNull()

    private fun envDouble(key: String): Double? = System.getenv(key)?.toDoubleOrNull()

}

object ScannerConfigDefaults {
    const val DEFAULT_MAX_FILE_BYTES: Long = 5L * 1024 * 1024
    const val DEFAULT_MAX_PROMPT_TOKENS: Int = 6000
    const val DEFAULT_RESERVE_OUTPUT_TOKENS: Int = 1024
    const val DEFAULT_OLLAMA_ENDPOINT: String = "http://localhost:11434"
    const val GPT5_MAX_PROMPT_TOKENS: Int = 200000
    const val GPT5_RESERVE_OUTPUT_TOKENS: Int = 8192
}
