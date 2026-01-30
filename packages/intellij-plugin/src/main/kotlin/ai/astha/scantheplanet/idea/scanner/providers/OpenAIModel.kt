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

class OpenAIModel(
    private val modelName: String,
    private val apiKey: String,
    private val config: ai.astha.scantheplanet.idea.scanner.ScannerConfig,
    private val timeoutSeconds: Long = 60
) : CodeModel {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    private val mapper = ObjectMapper()
    private val logger = Logger.getInstance(OpenAIModel::class.java)

    override val name: String = modelName

    override fun analyzeChunk(prompt: PromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPrompt(prompt)
        val body = mutableMapOf(
            "model" to modelName,
            "response_format" to mapOf("type" to "json_object"),
            "prompt_cache_key" to cacheKeyForSingle(prompt),
            "messages" to listOf(
                mapOf("role" to "system", "content" to PromptBuilder.SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userContent)
            )
        )
        if (supportsTemperature()) {
            body["temperature"] = 0.0
        }
        val maxTokensKey = if (modelName.lowercase().startsWith("gpt-5")) "max_completion_tokens" else "max_tokens"
        body[maxTokensKey] = 4096
        cacheRetentionForModel()?.let { body["prompt_cache_retention"] = it }

        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("OPENAI", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=OpenAI model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("OpenAI call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("OpenAI response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usage")
                if (!usage.isMissingNode) {
                    val promptTokens = usage.path("prompt_tokens").asInt(-1)
                    val completionTokens = usage.path("completion_tokens").asInt(-1)
                    val totalTokens = usage.path("total_tokens").asInt(-1)
                    if (totalTokens >= 0) {
                        val cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(-1)
                        val cachedText = if (cached >= 0) " cached=$cached" else ""
                        logger.info("LLM tokens: provider=OpenAI model=$modelName prompt=$promptTokens completion=$completionTokens total=$totalTokens$cachedText")
                    }
                }
                val choices = root.path("choices")
                val content = if (choices.isArray && choices.size() > 0) {
                    choices[0].path("message").path("content").asText()
                } else {
                    null
                } ?: throw CodeModelException("OpenAI response missing content")
                try {
                    ModelResponseParser.parseFindings(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("OpenAI response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("OpenAI call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    override fun analyzeChunkBatch(prompt: BatchPromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPromptBatch(prompt)
        val body = mutableMapOf(
            "model" to modelName,
            "response_format" to mapOf("type" to "json_object"),
            "prompt_cache_key" to cacheKeyForBatch(prompt),
            "messages" to listOf(
                mapOf("role" to "system", "content" to PromptBuilder.BATCH_SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userContent)
            )
        )
        if (supportsTemperature()) {
            body["temperature"] = 0.0
        }
        val maxTokensKey = if (modelName.lowercase().startsWith("gpt-5")) "max_completion_tokens" else "max_tokens"
        body[maxTokensKey] = 4096
        cacheRetentionForModel()?.let { body["prompt_cache_retention"] = it }

        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("OPENAI", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=OpenAI model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("OpenAI call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("OpenAI response invalid JSON: ${e.message}", e)
                }
                val usage = root.path("usage")
                if (!usage.isMissingNode) {
                    val promptTokens = usage.path("prompt_tokens").asInt(-1)
                    val completionTokens = usage.path("completion_tokens").asInt(-1)
                    val totalTokens = usage.path("total_tokens").asInt(-1)
                    if (totalTokens >= 0) {
                        val cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(-1)
                        val cachedText = if (cached >= 0) " cached=$cached" else ""
                        logger.info("LLM tokens: provider=OpenAI model=$modelName prompt=$promptTokens completion=$completionTokens total=$totalTokens$cachedText")
                    }
                }
                val choices = root.path("choices")
                val content = if (choices.isArray && choices.size() > 0) {
                    choices[0].path("message").path("content").asText()
                } else {
                    null
                } ?: throw CodeModelException("OpenAI response missing content")
                try {
                    ModelResponseParser.parseFindingsBatch(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("OpenAI response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("OpenAI call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=OpenAI model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    private fun cacheKeyForSingle(prompt: PromptPayload): String {
        return "scantheplanet:${prompt.techniqueId}:${modelKey()}:${promptPrefixHash(false)}"
    }

    private fun cacheKeyForBatch(prompt: BatchPromptPayload): String {
        val ids = prompt.techniques.map { it.id }.sorted().joinToString(",")
        return "scantheplanet:batch:$ids:${modelKey()}:${promptPrefixHash(true)}"
    }

    private fun promptPrefixHash(batch: Boolean): String {
        val prefix = if (batch) PromptBuilder.BATCH_SYSTEM_PROMPT else PromptBuilder.SYSTEM_PROMPT
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(prefix.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString().substring(0, 12)
    }

    private fun modelKey(): String {
        return modelName.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
    }

    private fun supportsTemperature(): Boolean {
        val lower = modelName.lowercase()
        return !(lower.startsWith("gpt-5-mini") || lower.startsWith("gpt-5-nano"))
    }

    private fun cacheRetentionForModel(): String? {
        val lower = modelName.lowercase()
        if (!lower.startsWith("gpt-5")) return null
        if (lower.startsWith("gpt-5-mini") || lower.startsWith("gpt-5-nano")) return null
        return "24h"
    }

    private fun truncateBody(body: String?, max: Int = 400): String {
        if (body.isNullOrBlank()) return "<empty>"
        val sanitized = body.replace(Regex("\\s+"), " ").trim()
        return if (sanitized.length <= max) sanitized else sanitized.substring(0, max) + "…"
    }
}
