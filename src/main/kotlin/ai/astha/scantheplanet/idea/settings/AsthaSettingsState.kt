package ai.astha.scantheplanet.idea.settings

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection

enum class ScanScope(val cliValue: String) {
    GIT_DIFF("git-diff"),
    FULL("full")
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
    var scope: ScanScope = ScanScope.GIT_DIFF,
    @Attribute("includeUntracked")
    var includeUntracked: Boolean = true,
    @Attribute("provider")
    var provider: LlmProvider = LlmProvider.OPENAI,
    @Attribute("modelName")
    var modelName: String = "",
    @Attribute("configPath")
    var configPath: String = "",
    @Attribute("llmEndpoint")
    var llmEndpoint: String = "",
    @Attribute("llmToken")
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
    var cacheEnabled: Boolean = true
)
