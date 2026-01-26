package ai.astha.scantheplanet.idea.scanner.providers

import ai.astha.scantheplanet.idea.scanner.ScannerConfig
import ai.astha.scantheplanet.idea.scanner.ScannerConfigDefaults
import ai.astha.scantheplanet.idea.scanner.model.CodeModel

object ModelFactory {
    fun build(
        provider: String,
        modelName: String?,
        apiKey: String?,
        config: ScannerConfig
    ): CodeModel {
        val normalized = provider.lowercase()
        if (normalized == "local") {
            return LocalModel()
        }
        if (normalized == "ollama") {
            enforceProviderAllowlist(config, normalized)
            val resolvedModel = modelName
                ?: config.modelNames?.firstOrNull()
                ?: defaultModelForProvider(normalized)
            val endpoint = config.ollamaEndpoint ?: ScannerConfigDefaults.DEFAULT_OLLAMA_ENDPOINT
            return OllamaModel(resolvedModel, config, endpoint)
        }
        if (normalized !in setOf("openai", "anthropic", "gemini")) {
            throw IllegalArgumentException("unsupported provider: $normalized")
        }
        enforceProviderAllowlist(config, normalized)

        val key = apiKey ?: when (normalized) {
            "openai" -> config.openaiApiKey
            "anthropic" -> config.anthropicApiKey
            "gemini" -> config.geminiApiKey
            else -> null
        }
        if (key.isNullOrBlank()) {
            throw IllegalArgumentException("API key missing for provider $normalized")
        }

        val resolvedModel = modelName
            ?: config.modelNames?.firstOrNull()
            ?: defaultModelForProvider(normalized)

        return when (normalized) {
            "openai" -> OpenAIModel(resolvedModel, key, config)
            "anthropic" -> AnthropicModel(resolvedModel, key, config)
            "gemini" -> GeminiModel(resolvedModel, key, config)
            else -> LocalModel()
        }
    }

    private fun enforceProviderAllowlist(config: ScannerConfig, provider: String) {
        if (provider == "local" || provider == "ollama") return
        if (!config.allowRemoteProviders) {
            throw IllegalArgumentException("provider $provider disallowed: allow_remote_providers=false")
        }
        val allowed = config.allowedProviders?.map { it.lowercase() } ?: return
        if (provider !in allowed) {
            throw IllegalArgumentException("provider $provider not in allowed_providers")
        }
    }

    private fun defaultModelForProvider(provider: String): String {
        return when (provider) {
            "openai" -> "gpt-4o-mini"
            "anthropic" -> "claude-3-5-sonnet-20240620"
            "gemini" -> "gemini-1.5-flash"
            "ollama" -> "llama3.1"
            else -> "local"
        }
    }
}
