package ai.astha.scantheplanet.idea.scanner

import java.nio.file.Path

sealed class ScopeKind {
    object FullRepo : ScopeKind()
    data class File(val file: Path) : ScopeKind()
    data class Selection(val file: Path, val startLine: Int, val endLine: Int) : ScopeKind()
    data class GitDiff(val baseRef: String, val includeUntracked: Boolean) : ScopeKind()
}

class PathFilters(
    val includeExtensions: List<String> = emptyList(),
    val excludeExtensions: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val maxFileBytes: Long? = null,
    val excludeDocs: Boolean = true,
    val excludeTests: Boolean = true,
    val includeOverride: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val onlyFiles: Set<Path>? = null
)
