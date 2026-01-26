package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper
import ai.astha.scantheplanet.idea.settings.LlmProvider
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object FindingsCleaner {
    private val mapper = ObjectMapper()
    private val logger = Logger.getInstance(FindingsCleaner::class.java)

    fun cleanFindings(
        provider: String,
        modelName: String?,
        apiKey: String?,
        config: ScannerConfig,
        findings: List<ScanFinding>
    ): List<ScanFinding> {
        if (findings.isEmpty()) return findings
        val cache = FindingsCleanerCache()
        val cacheKey = cacheKey(provider, modelName, findings)
        cache.get(cacheKey)?.let { return it }
        return when (provider.lowercase()) {
            LlmProvider.OPENAI.cliValue -> cleanWithOpenAI(modelName, apiKey, config, findings)
            LlmProvider.ANTHROPIC.cliValue -> cleanWithAnthropic(modelName, apiKey, config, findings)
            LlmProvider.GEMINI.cliValue -> cleanWithGemini(modelName, apiKey, config, findings)
            LlmProvider.OLLAMA.cliValue -> cleanWithOllama(modelName, apiKey, config, findings)
            else -> findings
        }.also { cleaned ->
            cache.put(cacheKey, cleaned)
        }
    }

    private fun cleanWithOpenAI(modelName: String?, apiKey: String?, config: ScannerConfig, findings: List<ScanFinding>): List<ScanFinding> {
        val key = apiKey ?: config.openaiApiKey ?: return findings
        val model = modelName ?: config.modelNames?.firstOrNull() ?: "gpt-4o-mini"
        val prompt = buildPrompt(findings)
        val body = mutableMapOf(
            "model" to model,
            "temperature" to 0.0,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt()),
                mapOf("role" to "user", "content" to prompt)
            )
        )
        val maxTokensKey = if (model.lowercase().startsWith("gpt-5")) "max_completion_tokens" else "max_tokens"
        body[maxTokensKey] = 2048

        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        logger.info("LLM clean: provider=OpenAI model=$model findings=${findings.size}")
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return findings
        val content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asText()
        return parseAndFilter(content, findings)
    }

    private fun cleanWithAnthropic(modelName: String?, apiKey: String?, config: ScannerConfig, findings: List<ScanFinding>): List<ScanFinding> {
        val key = apiKey ?: config.anthropicApiKey ?: return findings
        val model = modelName ?: config.modelNames?.firstOrNull() ?: "claude-3-5-sonnet-latest"
        val prompt = buildPrompt(findings)
        val body = mapOf(
            "model" to model,
            "temperature" to 0.0,
            "max_tokens" to 2048,
            "system" to systemPrompt(),
            "messages" to listOf(mapOf("role" to "user", "content" to prompt))
        )
        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(60))
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        logger.info("LLM clean: provider=Anthropic model=$model findings=${findings.size}")
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return findings
        val content = mapper.readTree(response.body()).path("content").path(0).path("text").asText()
        return parseAndFilter(content, findings)
    }

    private fun cleanWithGemini(modelName: String?, apiKey: String?, config: ScannerConfig, findings: List<ScanFinding>): List<ScanFinding> {
        val key = apiKey ?: config.geminiApiKey ?: return findings
        val model = modelName ?: config.modelNames?.firstOrNull() ?: "gemini-1.5-pro"
        val prompt = buildPrompt(findings)
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt()))),
            "contents" to listOf(mapOf(
                "role" to "user",
                "parts" to listOf(mapOf("text" to prompt))
            )),
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "maxOutputTokens" to 2048,
                "responseMimeType" to "application/json"
            )
        )
        val requestBody = mapper.writeValueAsString(body)
        val sanitizedModel = model.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$sanitizedModel:generateContent"
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .header("X-Goog-Api-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        logger.info("LLM clean: provider=Gemini model=$model findings=${findings.size}")
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return findings
        val content = mapper.readTree(response.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text").asText()
        return parseAndFilter(content, findings)
    }

    private fun cleanWithOllama(modelName: String?, apiKey: String?, config: ScannerConfig, findings: List<ScanFinding>): List<ScanFinding> {
        val model = modelName ?: config.modelNames?.firstOrNull() ?: "llama3.1"
        val endpoint = config.ollamaEndpoint ?: ScannerConfigDefaults.DEFAULT_OLLAMA_ENDPOINT
        val prompt = buildPrompt(findings)
        val body = mutableMapOf(
            "model" to model,
            "temperature" to 0.0,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt()),
                mapOf("role" to "user", "content" to prompt)
            )
        )
        body["max_tokens"] = 2048

        val requestBody = mapper.writeValueAsString(body)
        val builder = HttpRequest.newBuilder(URI(endpoint.trimEnd('/') + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
        logger.info("LLM clean: provider=Ollama model=$model findings=${findings.size}")
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return findings
        val content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asText()
        return parseAndFilter(content, findings)
    }

    private fun systemPrompt(): String {
        return """
You are a security reviewer. You are given ONLY existing findings (no source files).
Return JSON: {"findings":[...]} with a STRICT subset of the input findings.
Rules:
- Remove any items that are irrelevant, speculative, generic, or framed as "no evidence"/"does not match"/"not present".
- If the observation admits the issue is not actually present, remove it.
- Prefer fewer, higher-confidence findings over keeping borderline ones.
- If multiple findings describe the SAME underlying risk (overlapping techniques), keep ONLY the single most important one.
  * Prefer higher severity.
  * Prefer the most specific, direct, evidence-based observation.
  * Prefer the finding that best matches the actual behavior in the observation (avoid speculative variants).
- If multiple findings overlap, keep the single best one; do NOT drop all overlapping findings.
- Drop any finding that does NOT describe a concrete input->sink path.
- Drop any finding with implied severity mappings like "token theft" or "in-memory secret extraction"
  unless the observation explicitly cites such behavior.
- Keep the remaining findings UNCHANGED (techniqueId, techniqueName, severity, file, startLine, endLine, observation).
- Do not add new findings.
""".trimIndent()
    }

    private fun buildPrompt(findings: List<ScanFinding>): String {
        val payload = mapOf("findings" to findings)
        return mapper.writeValueAsString(payload)
    }

    private fun parseAndFilter(content: String, original: List<ScanFinding>): List<ScanFinding> {
        return try {
            val jsonText = ai.astha.scantheplanet.idea.scanner.evidence.EvidenceParser.extractJsonObject(content) ?: content
            val node = mapper.readTree(jsonText)
            val findingsNode = node.get("findings") ?: return original
            if (!findingsNode.isArray) return original
            val allowed = original.associateBy { keyOf(it) }
            val cleaned = mutableListOf<ScanFinding>()
            for (item in findingsNode) {
                val finding = ScanFinding(
                    techniqueId = item.get("techniqueId")?.asText() ?: "",
                    techniqueName = item.get("techniqueName")?.asText() ?: "",
                    severity = item.get("severity")?.asText() ?: "",
                    file = item.get("file")?.asText() ?: "",
                    startLine = item.get("startLine")?.asInt() ?: -1,
                    endLine = item.get("endLine")?.asInt() ?: -1,
                    observation = item.get("observation")?.asText() ?: ""
                )
                val key = keyOf(finding)
                allowed[key]?.let { cleaned.add(it) }
            }
            cleaned
        } catch (_: Exception) {
            original
        }
    }


    private fun keyOf(finding: ScanFinding): String {
        return listOf(
            finding.techniqueId,
            finding.techniqueName,
            finding.severity,
            finding.file,
            finding.startLine.toString(),
            finding.endLine.toString(),
            finding.observation
        ).joinToString("|")
    }

    private fun cacheKey(provider: String, modelName: String?, findings: List<ScanFinding>): String {
        val base = StringBuilder()
        base.append(provider.lowercase()).append("|")
        base.append(modelName ?: "default").append("|")
        base.append(promptVersionHash()).append("|")
        for (finding in findings) {
            base.append(keyOf(finding)).append("\n")
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(base.toString().toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun promptVersionHash(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(systemPrompt().toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString().substring(0, 12)
    }
}
