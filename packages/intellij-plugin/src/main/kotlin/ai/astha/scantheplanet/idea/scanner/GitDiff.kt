package ai.astha.scantheplanet.idea.scanner

import java.nio.file.Path

object GitDiff {
    data class DiffHunk(
        val file: Path,
        val newStart: Int,
        val newCount: Int,
        val addedLines: Set<Int>
    )

    private val hunkHeader = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*")

    fun parseUnifiedDiff(repoPath: Path, diffText: String): Map<Path, Set<Int>> {
        val addedByFile = mutableMapOf<Path, MutableSet<Int>>()
        var currentFile: Path? = null
        var currentNewLine = 0
        var inHunk = false

        for (line in diffText.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> {
                    currentFile = null
                    inHunk = false
                }
                line.startsWith("+++ ") -> {
                    val pathPart = line.removePrefix("+++ ").trim()
                    if (pathPart == "/dev/null") {
                        currentFile = null
                    } else {
                        val cleaned = pathPart.removePrefix("b/")
                        currentFile = repoPath.resolve(cleaned).normalize()
                        addedByFile.putIfAbsent(currentFile, mutableSetOf())
                    }
                }
                line.startsWith("@@") -> {
                    val match = hunkHeader.matchEntire(line)
                    if (match != null) {
                        val start = match.groupValues[1].toIntOrNull() ?: 0
                        currentNewLine = start
                        inHunk = true
                    } else {
                        inHunk = false
                    }
                }
                inHunk && currentFile != null -> {
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> {
                            addedByFile[currentFile]?.add(currentNewLine)
                            currentNewLine += 1
                        }
                        line.startsWith("-") && !line.startsWith("---") -> {
                            // deletion: does not advance new line
                        }
                        line.startsWith("\\ No newline") -> {
                            // ignore
                        }
                        else -> {
                            currentNewLine += 1
                        }
                    }
                }
            }
        }
        return addedByFile
    }
}
