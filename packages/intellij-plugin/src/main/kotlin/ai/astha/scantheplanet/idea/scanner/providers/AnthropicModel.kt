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

class AnthropicModel(
    private val modelName: String,
    private val apiKey: String,
    private val config: ai.astha.scantheplanet.idea.scanner.ScannerConfig,
    private val timeoutSeconds: Long = 60
) : CodeModel {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    private val mapper = ObjectMapper()
    private val logger = Logger.getInstance(AnthropicModel::class.java)

    override val name: String = modelName

    override fun analyzeChunk(prompt: PromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPrompt(prompt)
        val body = mapOf(
            "model" to modelName,
            "temperature" to 0.0,
            "max_tokens" to 4096,
            "system" to PromptBuilder.SYSTEM_PROMPT,
            "messages" to listOf(
                mapOf("role" to "user", "content" to userContent),
                mapOf("role" to "assistant", "content" to PromptBuilder.JSON_PREFILL)
            )
        )
        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("ANTHROPIC", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Anthropic model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Anthropic call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Anthropic response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usage")
                if (!usage.isMissingNode) {
                    val inputTokens = usage.path("input_tokens").asInt(-1)
                    val outputTokens = usage.path("output_tokens").asInt(-1)
                    val totalTokens = if (inputTokens >= 0 && outputTokens >= 0) inputTokens + outputTokens else -1
                    if (totalTokens >= 0) {
                        logger.info("LLM tokens: provider=Anthropic model=$modelName input=$inputTokens output=$outputTokens total=$totalTokens")
                    }
                }
                val contentArray = root.path("content")
                val content = if (contentArray.isArray && contentArray.size() > 0) {
                    contentArray[0].path("text").asText()
                } else {
                    null
                } ?: throw CodeModelException("Anthropic response missing content")
                try {
                    ModelResponseParser.parseFindingsWithPrefill(content, PromptBuilder.JSON_PREFILL, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Anthropic response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Anthropic call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    override fun analyzeChunkBatch(prompt: BatchPromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPromptBatch(prompt)
        val body = mapOf(
            "model" to modelName,
            "temperature" to 0.0,
            "max_tokens" to 4096,
            "system" to PromptBuilder.BATCH_SYSTEM_PROMPT,
            "messages" to listOf(
                mapOf("role" to "user", "content" to userContent),
                mapOf("role" to "assistant", "content" to PromptBuilder.JSON_PREFILL)
            )
        )
        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("ANTHROPIC", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Anthropic model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Anthropic call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Anthropic response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usage")
                if (!usage.isMissingNode) {
                    val inputTokens = usage.path("input_tokens").asInt(-1)
                    val outputTokens = usage.path("output_tokens").asInt(-1)
                    val totalTokens = if (inputTokens >= 0 && outputTokens >= 0) inputTokens + outputTokens else -1
                    if (totalTokens >= 0) {
                        logger.info("LLM tokens: provider=Anthropic model=$modelName input=$inputTokens output=$outputTokens total=$totalTokens")
                    }
                }
                val contentArray = root.path("content")
                val content = if (contentArray.isArray && contentArray.size() > 0) {
                    contentArray[0].path("text").asText()
                } else {
                    null
                } ?: throw CodeModelException("Anthropic response missing content")
                try {
                    ModelResponseParser.parseFindingsBatch(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Anthropic response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Anthropic call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Anthropic model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    private fun truncateBody(body: String?, max: Int = 400): String {
        if (body.isNullOrBlank()) return "<empty>"
        val sanitized = body.replace(Regex("\\s+"), " ").trim()
        return if (sanitized.length <= max) sanitized else sanitized.substring(0, max) + "…"
    }
}
