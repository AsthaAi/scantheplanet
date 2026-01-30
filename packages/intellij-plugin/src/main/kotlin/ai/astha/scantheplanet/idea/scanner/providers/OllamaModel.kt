package ai.astha.scantheplanet.idea.scanner.providers

import ai.astha.scantheplanet.idea.scanner.ScannerConfig
import ai.astha.scantheplanet.idea.scanner.model.CodeModel
import ai.astha.scantheplanet.idea.scanner.model.CodeModelException
import ai.astha.scantheplanet.idea.scanner.model.ModelFinding
import ai.astha.scantheplanet.idea.scanner.model.ModelResponseParser
import ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.PromptBuilder
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OllamaModel(
    private val modelName: String,
    private val config: ScannerConfig,
    private val endpoint: String,
    private val timeoutSeconds: Long = 60
) : CodeModel {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    private val mapper = ObjectMapper()
    private val logger = Logger.getInstance(OllamaModel::class.java)

    override val name: String = modelName

    override fun analyzeChunk(prompt: PromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPrompt(prompt)
        val body = mutableMapOf(
            "model" to modelName,
            "temperature" to 0.0,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to PromptBuilder.SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userContent)
            )
        )
        body["max_tokens"] = 700

        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI(endpoint.trimEnd('/') + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("OLLAMA", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Ollama model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Ollama call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Ollama response invalid JSON: ${e.message}", e)
                }
                val choices = root.path("choices")
                val content = if (choices.isArray && choices.size() > 0) {
                    choices[0].path("message").path("content").asText()
                } else {
                    null
                } ?: throw CodeModelException("Ollama response missing content")
                try {
                    ModelResponseParser.parseFindings(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Ollama response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Ollama call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} " +
                "elapsed_ms=$elapsedMs retries=$retryCount"
        )
        return findings
    }

    override fun analyzeChunkBatch(prompt: BatchPromptPayload): List<ModelFinding> {
        val userContent = PromptBuilder.buildUserPromptBatch(prompt)
        val body = mutableMapOf(
            "model" to modelName,
            "temperature" to 0.0,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to PromptBuilder.BATCH_SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userContent)
            )
        )
        body["max_tokens"] = 700

        val requestBody = mapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder(URI(endpoint.trimEnd('/') + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val requestId = java.util.UUID.randomUUID().toString().substring(0, 8)
        logger.info("LLM request: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine}")
        val startNanos = System.nanoTime()
        var retryCount = 0
        val findings = try {
            withRetry(RetryConfig.fromEnv("OLLAMA", config), { _, _ -> retryCount += 1 }) {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    val bodySnippet = truncateBody(response.body())
                    logger.warn(
                        "LLM request failed: id=$requestId provider=Ollama model=$modelName status=${response.statusCode()} " +
                            "chunk=${prompt.codeChunk.id} file=${prompt.codeChunk.file} " +
                            "lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} body=$bodySnippet"
                    )
                    throw CodeModelException("Ollama call failed with status ${response.statusCode()}: $bodySnippet")
                }
                val root = try {
                    mapper.readTree(response.body())
                } catch (e: Exception) {
                    throw CodeModelException("Ollama response invalid JSON: ${e.message}", e)
                }
                val choices = root.path("choices")
                val content = if (choices.isArray && choices.size() > 0) {
                    choices[0].path("message").path("content").asText()
                } else {
                    null
                } ?: throw CodeModelException("Ollama response missing content")
                try {
                    ModelResponseParser.parseFindingsBatch(content, prompt, modelName)
                } catch (e: Exception) {
                    throw CodeModelException("Ollama response invalid JSON: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "LLM request failed: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} " +
                    "file=${prompt.codeChunk.file} lines=${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine} " +
                    "error=${e.javaClass.simpleName}: ${e.message}"
            )
            throw CodeModelException("Ollama call failed: ${e.message}", e)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "LLM response: id=$requestId provider=Ollama model=$modelName chunk=${prompt.codeChunk.id} " +
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
