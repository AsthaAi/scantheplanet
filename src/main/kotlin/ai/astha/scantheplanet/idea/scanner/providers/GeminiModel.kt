package ai.astha.scantheplanet.idea.scanner.providers

import com.fasterxml.jackson.databind.ObjectMapper
import ai.astha.scantheplanet.idea.scanner.model.CodeModel
import ai.astha.scantheplanet.idea.scanner.model.CodeModelException
import ai.astha.scantheplanet.idea.scanner.model.ModelFinding
import ai.astha.scantheplanet.idea.scanner.model.ModelResponseParser
import ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.PromptBuilder
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GeminiModel(
    private val modelName: String,
    private val apiKey: String,
    private val config: ai.astha.scantheplanet.idea.scanner.ScannerConfig,
    private val timeoutSeconds: Long = 60
) : CodeModel {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    private val mapper = ObjectMapper()
    private val logger = Logger.getInstance(GeminiModel::class.java)

    override val name: String = modelName

    override fun analyzeChunk(prompt: PromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPrompt(prompt)
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to PromptBuilder.SYSTEM_PROMPT))),
            "contents" to listOf(mapOf(
                "role" to "user",
                "parts" to listOf(mapOf("text" to userContent))
            )),
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "maxOutputTokens" to 4096,
                "responseMimeType" to "application/json",
                "responseSchema" to findingsJsonSchema()
            )
        )
        val requestBody = mapper.writeValueAsString(body)
        val sanitizedModel = modelName.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$sanitizedModel:generateContent"
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("X-Goog-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("GEMINI", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Gemini model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Gemini call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Gemini response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usageMetadata")
                if (!usage.isMissingNode) {
                    val promptTokens = usage.path("promptTokenCount").asInt(-1)
                    val outputTokens = usage.path("candidatesTokenCount").asInt(-1)
                    val totalTokens = usage.path("totalTokenCount").asInt(-1)
                    if (totalTokens >= 0) {
                        logger.info("LLM tokens: provider=Gemini model=$modelName prompt=$promptTokens output=$outputTokens total=$totalTokens")
                    }
                }
                val candidates = root.path("candidates")
                val content = if (candidates.isArray && candidates.size() > 0) {
                    val parts = candidates[0].path("content").path("parts")
                    if (parts.isArray && parts.size() > 0) parts[0].path("text").asText() else null
                } else {
                    null
                } ?: throw CodeModelException("Gemini response missing content")
                try {
                    ModelResponseParser.parseFindings(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Gemini response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Gemini call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    override fun analyzeChunkBatch(prompt: BatchPromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPromptBatch(prompt)
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to PromptBuilder.BATCH_SYSTEM_PROMPT))),
            "contents" to listOf(mapOf(
                "role" to "user",
                "parts" to listOf(mapOf("text" to userContent))
            )),
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "maxOutputTokens" to 4096,
                "responseMimeType" to "application/json",
                "responseSchema" to findingsJsonSchema()
            )
        )
        val requestBody = mapper.writeValueAsString(body)
        val sanitizedModel = modelName.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$sanitizedModel:generateContent"
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("X-Goog-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("GEMINI", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Gemini model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Gemini call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Gemini response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usageMetadata")
                if (!usage.isMissingNode) {
                    val promptTokens = usage.path("promptTokenCount").asInt(-1)
                    val outputTokens = usage.path("candidatesTokenCount").asInt(-1)
                    val totalTokens = usage.path("totalTokenCount").asInt(-1)
                    if (totalTokens >= 0) {
                        logger.info("LLM tokens: provider=Gemini model=$modelName prompt=$promptTokens output=$outputTokens total=$totalTokens")
                    }
                }
                val candidates = root.path("candidates")
                val content = if (candidates.isArray && candidates.size() > 0) {
                    val parts = candidates[0].path("content").path("parts")
                    if (parts.isArray && parts.size() > 0) parts[0].path("text").asText() else null
                } else {
                    null
                } ?: throw CodeModelException("Gemini response missing content")
                try {
                    ModelResponseParser.parseFindingsBatch(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Gemini response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Gemini call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Gemini model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    private fun truncateBody(body: String?, max: Int = 400): String {
        if (body.isNullOrBlank()) return "<empty>"
        val sanitized = body.replace(Regex("\\s+"), " ").trim()
        return if (sanitized.length <= max) sanitized else sanitized.substring(0, max) + "…"
    }

    private fun findingsJsonSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "propertyOrdering" to listOf("findings"),
            "properties" to mapOf(
                "findings" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "severity" to mapOf("type" to "string"),
                            "observation" to mapOf("type" to "string"),
                            "evidence" to mapOf("type" to "string"),
                            "unknown_mitigations" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            )
                        ),
                        "required" to listOf("severity", "observation", "evidence")
                    )
                )
            ),
            "required" to listOf("findings")
        )
    }
}
