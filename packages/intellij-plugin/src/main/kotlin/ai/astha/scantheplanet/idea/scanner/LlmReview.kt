package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.databind.ObjectMapper

object LlmReview {
    private val mapper = ObjectMapper()

    fun maybeReview(
        provider: String,
        modelName: String?,
        apiKey: String?,
        config: ScannerConfig,
        techniqueId: String,
        analysis: ScanSummary
    ): ScanSummary? {
        if (provider.lowercase() != "openai") return null
        if (analysis.findings.isEmpty()) return null
        val key = apiKey ?: config.openaiApiKey ?: return null
        val reviewModel = modelName ?: config.modelNames?.firstOrNull() ?: "gpt-4o-mini"

        val payload = mapOf(
            "technique_id" to techniqueId,
            "summary" to analysis.summary,
            "findings" to analysis.findings
        )

        val body = mapOf(
            "model" to reviewModel,
            "response_format" to mapOf("type" to "json_object"),
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a security reviewer. Given technique_id and initial findings, return JSON {\"findings\":[...]} keeping only code-backed, technique-relevant items. Use the original file/line/evidence; do not invent new findings."),
                mapOf("role" to "user", "content" to mapper.writeValueAsString(payload))
            )
        )

        return try {
            val requestBody = mapper.writeValueAsString(body)
            val response = clientRaw(requestBody, key)
            if (response.isBlank()) return null
            val parsed = mapper.readTree(response)
            val choices = parsed.path("choices")
            val content = if (choices.isArray && choices.size() > 0) {
                choices[0].path("message").path("content").asText()
            } else {
                null
            }
                ?: return null
            val reviewed = mapper.readTree(content)
            val findingsNode = reviewed.get("findings") ?: return null
            val findings = mutableListOf<ScanFinding>()
            for (node in findingsNode) {
                val technique = node.get("techniqueId")?.asText() ?: techniqueId
                val severity = node.get("severity")?.asText() ?: "unknown"
                val file = node.get("file")?.asText() ?: "unknown"
                val startLine = node.get("startLine")?.asInt() ?: -1
                val endLine = node.get("endLine")?.asInt() ?: -1
                val observation = node.get("observation")?.asText() ?: ""
                findings.add(ScanFinding(technique, "", severity, file, startLine, endLine, observation))
            }
            analysis.copy(findings = findings)
        } catch (_: Exception) {
            null
        }
    }

    private fun clientRaw(body: String, apiKey: String): String {
        val client = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() in 200..299) response.body() else ""
    }
}
