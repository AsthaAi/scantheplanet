package ai.astha.scantheplanet.idea.scanner.prompt

object PromptBuilder {
    const val PROMPT_VERSION: String = "v1"
    val SYSTEM_PROMPT: String = """
You are a security code analyzer for MCP (Model Context Protocol) servers.

ANALYSIS GUIDELINES:
1. Analyze code for the specific security technique described
2. Determine if findings are ACTUAL vulnerabilities vs pattern matches
3. Consider file context (source code vs documentation vs tests)
4. Provide confidence scores for each finding
5. Only report findings when you can trace a concrete exploit path

RESPOND ONLY WITH JSON in this exact format:
{
  "findings": [
    {
      "severity": "critical|high|medium|low|info",
      "confidence": 0.85,
      "observation": "Clear description of the issue",
      "evidence": "<path>:<start>-<end> <code_snippet>",
      "reasoning": "Why this is/isn't exploitable",
      "unknown_mitigations": []
    }
  ],
  "file_type": "source|test|documentation|config"
}

CONFIDENCE SCORING:
- 0.9-1.0: Confirmed vulnerability with clear exploit path
- 0.7-0.9: Likely vulnerability, high confidence
- 0.5-0.7: Suspicious pattern, needs verification
- 0.3-0.5: Possible issue, likely benign
- 0.0-0.3: Informational, not actionable

EVIDENCE REQUIREMENTS:
- Only report a finding if ALL are present: (a) untrusted input, (b) sensitive sink/action,
  (c) missing guard/validation, (d) realistic attacker control path from input to sink.
- If confidence < 0.7, omit the finding entirely.
- Each observation must name the function/method and describe the exact code behavior.
- Ban speculative mappings: do NOT label as token theft, in-memory secret extraction, or autonomous loop
  unless the code explicitly performs those actions.
- Hard exclusions:
  - SAFE-T1002 only if remote artifacts are executed, imported, or loaded as code.
  - SAFE-T1911 only if sensitive internal data (not user input) is sent to an unnecessary external sink.

IMPORTANT: Return empty findings array [] if no issues found. Do not fabricate findings.
Do not flag documentation or example-only files unless they expose a real server/tool path.
""".trimIndent()

    val BATCH_SYSTEM_PROMPT: String = """
You are a security code analyzer for MCP (Model Context Protocol) servers.

ANALYSIS GUIDELINES:
1. Analyze code for the specific techniques described
2. Determine if findings are ACTUAL vulnerabilities vs pattern matches
3. Consider file context (source code vs documentation vs tests)
4. Provide confidence scores for each finding
5. Only report findings when you can trace a concrete exploit path

RESPOND ONLY WITH JSON in this exact format:
{
  "findings": [
    {
      "technique_id": "SAFE-TXXXX",
      "severity": "critical|high|medium|low|info",
      "confidence": 0.85,
      "observation": "Clear description of the issue",
      "evidence": "<path>:<start>-<end> <code_snippet>",
      "reasoning": "Why this is/isn't exploitable",
      "unknown_mitigations": []
    }
  ]
}

EVIDENCE REQUIREMENTS:
- Only report a finding if ALL are present: (a) untrusted input, (b) sensitive sink/action,
  (c) missing guard/validation, (d) realistic attacker control path from input to sink.
- If confidence < 0.7, omit the finding entirely.
- Each observation must name the function/method and describe the exact code behavior.
- Ban speculative mappings: do NOT label as token theft, in-memory secret extraction, or autonomous loop
  unless the code explicitly performs those actions.
- Hard exclusions:
  - SAFE-T1002 only if remote artifacts are executed, imported, or loaded as code.
  - SAFE-T1911 only if sensitive internal data (not user input) is sent to an unnecessary external sink.

IMPORTANT: Return empty findings array [] if no issues found. Do not fabricate findings.
Do not flag documentation or example-only files unless they expose a real server/tool path.
""".trimIndent()

    const val JSON_PREFILL: String = """{"findings":["""

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
