package ai.astha.scantheplanet.idea.scanner

import ai.astha.scantheplanet.idea.scanner.prompt.CodeChunkPrompt
import ai.astha.scantheplanet.idea.scanner.prompt.PromptBuilder
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.MitigationPrompt
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType

class PromptTokenEstimator(providerName: String, modelName: String?) {
    private val provider = providerName.lowercase()
    private val encoding = encodingForModel(modelName)

    fun baseTokens(
        technique: TechniqueSpec,
        readmeExcerpt: String?,
        mitigations: List<MitigationPrompt>,
        filePath: String
    ): Int {
        val payload = PromptPayload(
            techniqueId = technique.id,
            techniqueName = technique.name,
            severity = technique.severity,
            summary = technique.summary,
            description = technique.description,
            mitigations = mitigations,
            codeChunk = CodeChunkPrompt(
                id = "base",
                file = filePath,
                startLine = 0,
                endLine = 0,
                code = "",
                ruleHints = emptyList()
            ),
            readmeExcerpt = readmeExcerpt
        )
        val userPrompt = PromptBuilder.buildUserPrompt(payload)
        var tokens = countTokens(userPrompt)
        tokens += countTokens(PromptBuilder.SYSTEM_PROMPT)
        if (provider == "anthropic") {
            tokens += countTokens(PromptBuilder.JSON_PREFILL)
        }
        tokens += messageOverheadTokens(provider)
        return tokens
    }

    fun countTokens(text: String): Int {
        return encoding.countTokensOrdinary(text)
    }

    private fun messageOverheadTokens(provider: String): Int {
        return when (provider) {
            "openai" -> 12
            "anthropic" -> 12
            "gemini" -> 8
            else -> 10
        }
    }

    companion object {
        private val registry: EncodingRegistry = Encodings.newLazyEncodingRegistry()

        private fun encodingForModel(modelName: String?): Encoding {
            val resolved = modelName?.let { registry.getEncodingForModel(it) }
            if (resolved != null && resolved.isPresent) {
                return resolved.get()
            }
            val fallback = when {
                modelName != null && modelName.lowercase().contains("gpt-4o") -> EncodingType.O200K_BASE
                modelName != null && modelName.lowercase().startsWith("gpt-5") -> EncodingType.O200K_BASE
                else -> EncodingType.CL100K_BASE
            }
            return registry.getEncoding(fallback)
        }
    }
}
