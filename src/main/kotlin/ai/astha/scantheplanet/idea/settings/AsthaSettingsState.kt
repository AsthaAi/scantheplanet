package ai.astha.scantheplanet.idea.settings

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.Transient

enum class ScanScope(val cliValue: String, private val label: String) {
    OPEN_FILES("open-files", "Open files"),
    GIT_DIFF("git-diff", "Git diff"),
    FULL("full", "Full project");

    override fun toString(): String = label
}

enum class LlmProvider(val cliValue: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    OLLAMA("ollama")
}

data class AsthaSettingsState(
    @XCollection(propertyElementName = "techniques", elementName = "technique", valueAttributeName = "id")
    var techniques: MutableList<String> = mutableListOf("SAFE-T0001"),
    @Attribute("techniqueId")
    var legacyTechniqueId: String? = null,
    @Attribute("scope")
    var scope: ScanScope = ScanScope.OPEN_FILES,
    @Attribute("includeUntracked")
    var includeUntracked: Boolean = true,
    @Attribute("provider")
    var provider: LlmProvider = LlmProvider.OPENAI,
    @Attribute("openaiModelName")
    var openaiModelName: String = "gpt-5.2",
    @Attribute("ollamaModelName")
    var ollamaModelName: String = "llama3.1",
    @Attribute("modelName")
    var modelName: String = "",
    @Attribute("configPath")
    var configPath: String = "",
    @Attribute("llmEndpoint")
    var llmEndpoint: String = "",
    @Attribute("ollamaLoggingEnabled")
    var ollamaLoggingEnabled: Boolean = false,
    @Transient
    var llmToken: String = "",
    @Attribute("batchEnabled")
    var batchEnabled: Boolean = true,
    @Attribute("batchSize")
    var batchSize: Int = 3,
    @Attribute("cleanFindings")
    var cleanFindings: Boolean = true,
    @Attribute("cleaningModelName")
    var cleaningModelName: String = "",
    @Attribute("cacheEnabled")
    var cacheEnabled: Boolean = true,
    @Attribute("openaiChunkParallelism")
    var openaiChunkParallelism: Int = 10,
    @Attribute("openaiChunkParallelismMax")
    var openaiChunkParallelismMax: Int = 10,
    @Attribute("ollamaChunkParallelism")
    var ollamaChunkParallelism: Int = 1,
    @Attribute("ollamaChunkParallelismMax")
    var ollamaChunkParallelismMax: Int = 1,
    @Attribute("chunkParallelism")
    var chunkParallelism: Int = 10,
    @Attribute("chunkParallelismMax")
    var chunkParallelismMax: Int = 10,
    @Attribute("providerParallelismInitialized")
    var providerParallelismInitialized: Boolean = false,
    @Attribute("providerModelInitialized")
    var providerModelInitialized: Boolean = false,
    @Attribute("sourceCodeOnly")
    var sourceCodeOnly: Boolean = false,
    @Attribute("suppressTokenWarning")
    var suppressTokenWarning: Boolean = false
)
