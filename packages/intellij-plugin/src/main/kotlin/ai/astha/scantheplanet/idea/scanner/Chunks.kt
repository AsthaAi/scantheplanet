package ai.astha.scantheplanet.idea.scanner

import java.nio.file.Path

enum class ChunkKind {
    SlidingWindow,
    WholeFile,
    Selection
}

data class RuleHint(
    val signalId: String,
    val line: Int,
    val snippet: String
)

data class CodeChunk(
    val id: String,
    val file: Path,
    val startLine: Int,
    val endLine: Int,
    val kind: ChunkKind,
    val code: String,
    var ruleHints: List<RuleHint> = emptyList()
)
