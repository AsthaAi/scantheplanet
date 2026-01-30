package ai.astha.scantheplanet.idea.contracts

import ai.astha.scantheplanet.idea.scanner.model.ModelResponseParser
import ai.astha.scantheplanet.idea.scanner.prompt.BatchPromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.CodeChunkPrompt
import ai.astha.scantheplanet.idea.scanner.prompt.MitigationPrompt
import ai.astha.scantheplanet.idea.scanner.prompt.PromptPayload
import ai.astha.scantheplanet.idea.scanner.prompt.TechniquePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ResponseParserContractsTest {
    @Test
    fun parseFindingsUsesEvidenceLineNumbers() {
        val content = readFixture("response-single.json")
        val prompt = buildPrompt("src/app.ts", 1, 20)
        val findings = ModelResponseParser.parseFindings(content, prompt, "test-model")
        assertEquals(1, findings.size)
        val finding = findings[0]
        assertEquals("src/app.ts", finding.file)
        assertEquals(10, finding.startLine)
        assertEquals(12, finding.endLine)
    }

    @Test
    fun parseFindingsExtractsWrappedJson() {
        val content = readFixture("response-wrapped.txt")
        val prompt = buildPrompt("src/main.go", 1, 10)
        val findings = ModelResponseParser.parseFindings(content, prompt, "test-model")
        assertEquals(1, findings.size)
        assertEquals("src/main.go", findings[0].file)
    }

    @Test
    fun parseFindingsBatchUsesTechniqueId() {
        val content = readFixture("response-batch.json")
        val batch = BatchPromptPayload(
            techniques = listOf(
                TechniquePrompt(
                    id = "SAFE-T1001",
                    name = "Technique",
                    severity = "medium",
                    summary = "summary",
                    description = "description",
                    mitigations = emptyList(),
                    ruleHints = emptyList()
                )
            ),
            codeChunk = CodeChunkPrompt(
                id = "chunk2",
                file = "lib/util.py",
                startLine = 1,
                endLine = 10,
                code = "print(\"hello\")",
                ruleHints = emptyList()
            ),
            readmeExcerpt = null
        )
        val findings = ModelResponseParser.parseFindingsBatch(content, batch, "test-model")
        assertEquals(1, findings.size)
        assertEquals("SAFE-T1001", findings[0].techniqueId)
        assertEquals(5, findings[0].startLine)
    }

    private fun buildPrompt(file: String, startLine: Int, endLine: Int): PromptPayload {
        return PromptPayload(
            techniqueId = "SAFE-T0001",
            techniqueName = "Test",
            severity = "high",
            summary = "summary",
            description = "description",
            mitigations = listOf(MitigationPrompt("SAFE-M-0", "Mitigation")),
            codeChunk = CodeChunkPrompt(
                id = "chunk1",
                file = file,
                startLine = startLine,
                endLine = endLine,
                code = "line1\nline2\nline3",
                ruleHints = emptyList()
            ),
            readmeExcerpt = null
        )
    }

    private fun readFixture(name: String): String {
        val base = resolveContractsDir()
        val path = base.resolve("fixtures").resolve(name)
        val content = Files.readString(path)
        assertTrue("fixture should not be empty", content.isNotBlank())
        return content
    }

    private fun resolveContractsDir(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve("..").resolve("contracts"),
            cwd.resolve("packages").resolve("contracts"),
            cwd.resolve("..").resolve("..").resolve("packages").resolve("contracts")
        )
        for (candidate in candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate
            }
        }
        throw IllegalStateException("contracts directory not found from $cwd")
    }
}
