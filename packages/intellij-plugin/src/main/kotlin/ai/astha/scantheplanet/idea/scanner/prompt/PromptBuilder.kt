package ai.astha.scantheplanet.idea.scanner.prompt

object PromptBuilder {
    const val PROMPT_VERSION: String = "v1"
    val SYSTEM_PROMPT: String = PromptStrings.SYSTEM_PROMPT
    val BATCH_SYSTEM_PROMPT: String = PromptStrings.BATCH_SYSTEM_PROMPT
    val JSON_PREFILL: String = PromptStrings.JSON_PREFILL

    fun buildUserPrompt(prompt: PromptPayload): String {
        val ext = fileExtension(prompt.codeChunk.file)
        val mitigations = prompt.mitigations.joinToString("; ") { "${it.id}: ${it.description}" }
        val ruleHints = prompt.codeChunk.ruleHints.map { it.snippet }
        val readme = prompt.readmeExcerpt ?: ""

        return """
Technique: ${prompt.techniqueId} (severity ${prompt.severity})
Summary: ${prompt.summary}
Mitigations: $mitigations
File: ${prompt.codeChunk.file} (ext: ${if (ext.isEmpty()) "none" else ext}, lines ${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine})
Code: ${prompt.codeChunk.code}
Rule hints: $ruleHints
README: $readme
""".trimIndent()
    }

    fun buildUserPromptBatch(prompt: BatchPromptPayload): String {
        val techniqueBlocks = prompt.techniques.joinToString("\n\n") { technique ->
            val mitigations = technique.mitigations.joinToString("; ") { "${it.id}: ${it.description}" }
            val hints = technique.ruleHints.map { it.snippet }
            """
Technique: ${technique.id} (severity ${technique.severity})
Name: ${technique.name}
Summary: ${technique.summary}
Description: ${technique.description}
Mitigations: $mitigations
Rule hints: $hints
""".trimIndent()
        }
        val ext = fileExtension(prompt.codeChunk.file)
        return """
Analyze the following code chunk against the techniques listed.

$techniqueBlocks

File: ${prompt.codeChunk.file} (ext: ${if (ext.isEmpty()) "none" else ext}, lines ${prompt.codeChunk.startLine}-${prompt.codeChunk.endLine})
Code: ${prompt.codeChunk.code}
README: ${prompt.readmeExcerpt ?: ""}
""".trimIndent()
    }

    fun fileExtension(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val idx = name.lastIndexOf('.')
        return if (idx > 0 && idx < name.length - 1) name.substring(idx + 1) else ""
    }
}
