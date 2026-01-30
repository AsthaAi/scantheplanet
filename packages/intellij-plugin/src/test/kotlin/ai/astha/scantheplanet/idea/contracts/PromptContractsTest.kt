package ai.astha.scantheplanet.idea.contracts

import ai.astha.scantheplanet.idea.scanner.prompt.PromptStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PromptContractsTest {
    @Test
    fun systemPromptMatchesContracts() {
        val expected = readContract("prompts/system.prompt.txt")
        assertEquals(expected, PromptStrings.SYSTEM_PROMPT)
    }

    @Test
    fun batchPromptMatchesContracts() {
        val expected = readContract("prompts/batch.prompt.txt")
        assertEquals(expected, PromptStrings.BATCH_SYSTEM_PROMPT)
    }

    @Test
    fun jsonPrefillMatchesContracts() {
        val expected = readContract("prompts/json.prefill.txt")
        assertEquals(expected, PromptStrings.JSON_PREFILL)
    }

    private fun readContract(relativePath: String): String {
        val base = resolveContractsDir()
        val path = base.resolve(relativePath)
        return Files.readString(path).trimEnd()
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
