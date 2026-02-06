package ai.astha.scantheplanet.idea.scanner.evidence

import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload

object EvidenceParser {
    private val codeExtensions = setOf(
        "rs", "py", "ts", "tsx", "js", "jsx", "go", "rb", "php", "java", "kt", "c", "cpp", "cc", "h",
        "hpp", "cs", "swift", "m", "mm", "scala", "lua", "vue", "svelte", "dart", "ex", "exs", "erl",
        "sh", "ps1", "bash", "zsh", "toml", "yaml", "yml", "json", "sql", "r", "pl", "pm"
    )

    data class NormalizedEvidence(
        val path: String,
        val start: Int,
        val end: Int,
        val text: String
    )

    fun extractJsonObject(raw: String): String? {
        return extractJsonObject(raw, null)
    }

    fun extractJsonObject(raw: String, preferredField: String?): String? {
        var firstCandidate: String? = null
        for (start in raw.indices) {
            if (raw[start] != '{') continue
            val end = findJsonObjectEnd(raw, start) ?: continue
            val candidate = raw.substring(start, end + 1)
            if (!isJsonObject(candidate)) continue
            if (firstCandidate == null) firstCandidate = candidate
            if (preferredField == null) return candidate
            if (containsJsonField(candidate, preferredField)) return candidate
        }
        return firstCandidate
    }

    fun normalizeFromPrompt(raw: String, prompt: PromptPayload, enforceCodePath: Boolean): NormalizedEvidence {
        val parsed = parseEvidence(raw)
        var path = parsed?.path ?: prompt.codeChunk.file
        var start = parsed?.start ?: prompt.codeChunk.startLine
        var end = parsed?.end ?: prompt.codeChunk.endLine
        var snippet = parsed?.snippet ?: raw.trim()

        if (start <= 0 || end <= 0 || end < start) {
            path = prompt.codeChunk.file
            start = prompt.codeChunk.startLine
            end = prompt.codeChunk.endLine
            snippet = defaultSnippet(prompt.codeChunk.code)
        }

        if (start < prompt.codeChunk.startLine || end > prompt.codeChunk.endLine) {
            path = prompt.codeChunk.file
            start = prompt.codeChunk.startLine
            end = prompt.codeChunk.endLine
            snippet = defaultSnippet(prompt.codeChunk.code)
        }

        if (enforceCodePath && !isCodeFile(path)) {
            path = prompt.codeChunk.file
            start = prompt.codeChunk.startLine
            end = prompt.codeChunk.endLine
            snippet = defaultSnippet(prompt.codeChunk.code)
        }

        if (snippet.trim().isEmpty()) {
            snippet = defaultSnippet(prompt.codeChunk.code)
        }

        val text = "$path:$start-$end $snippet"
        return NormalizedEvidence(path, start, end, text)
    }

    fun formatRuleHintEvidence(file: String, line: Int, snippet: String): String {
        val safeSnippet = if (snippet.trim().isEmpty()) "rule hint matched" else snippet
        return "$file:$line-$line $safeSnippet"
    }

    private fun parseEvidence(raw: String): ParsedEvidence? {
        val trimmed = raw.trim()
        val match = evidenceRegex.find(trimmed) ?: return null
        val path = match.groups["path"]?.value ?: return null
        val start = match.groups["start"]?.value?.toIntOrNull() ?: return null
        val end = match.groups["end"]?.value?.toIntOrNull() ?: return null
        val snippet = match.groups["snippet"]?.value ?: ""
        return ParsedEvidence(path, start, end, snippet)
    }

    private fun defaultSnippet(code: String): String {
        return code.lines().take(3).joinToString("\n")
    }

    private fun isCodeFile(path: String): Boolean {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (fileName.startsWith("readme") || fileName == "license") return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in codeExtensions
    }

    private fun findJsonObjectEnd(raw: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (idx in start until raw.length) {
            val ch = raw[idx]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    if (depth == 0) continue
                    depth--
                    if (depth == 0) return idx
                }
            }
        }
        return null
    }

    private fun isJsonObject(candidate: String): Boolean {
        return candidate.trim().startsWith("{") && candidate.trim().endsWith("}")
    }

    private fun containsJsonField(candidate: String, field: String): Boolean {
        val regex = Regex("\"" + Regex.escape(field) + "\"\\s*:")
        return regex.containsMatchIn(candidate)
    }

    private data class ParsedEvidence(
        val path: String,
        val start: Int,
        val end: Int,
        val snippet: String
    )

    private val evidenceRegex = Regex("^(?<path>.+):(?<start>\\d+)-(?<end>\\d+)\\s*(?<snippet>.*)$")
}
