package ai.astha.scantheplanet.idea.scanner

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

object ScannerUtils {
    fun collectFiles(repoPath: Path, filters: PathFilters): List<Path> {
        val root = repoPath.normalize()
        if (!root.isDirectory()) return emptyList()
        val matcherInclude = filters.includeGlobs.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val matcherExclude = filters.excludeGlobs.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val matcherExcludePatterns = filters.excludePatterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val matcherIncludeOverride = filters.includeOverride.map { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val files = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }.forEach { file ->
                val relative = root.relativize(file)
                if (!isIncluded(relative, file, filters, matcherInclude, matcherExclude, matcherExcludePatterns, matcherIncludeOverride)) {
                    return@forEach
                }
                files.add(file)
            }
        }
        return files
    }

    fun isIncluded(
        relativePath: Path,
        absolutePath: Path,
        filters: PathFilters,
        includeMatchers: List<java.nio.file.PathMatcher>,
        excludeMatchers: List<java.nio.file.PathMatcher>,
        excludePatternMatchers: List<java.nio.file.PathMatcher>,
        includeOverrideMatchers: List<java.nio.file.PathMatcher>
    ): Boolean {
        val fileName = relativePath.name
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (isGitPath(relativePath)) return false
        if (isLockfile(fileName)) return false
        if (isIdeMetadataPath(relativePath)) return false
        if (isIgnoredDotfile(fileName)) return false
        if (filters.onlyFiles != null && relativePath !in filters.onlyFiles) return false

        if (filters.excludeDocs && isDocFile(fileName)) return false
        if (filters.excludeTests && isTestFile(relativePath)) return false

        if (filters.excludeExtensions.isNotEmpty() && ext.isNotEmpty() && filters.excludeExtensions.contains(ext)) {
            return false
        }
        if (filters.includeExtensions.isNotEmpty() && ext.isNotEmpty() && !filters.includeExtensions.contains(ext)) {
            return false
        }

        val pathString = relativePath.toString()
        if (excludeMatchers.any { it.matches(relativePath) } || excludePatternMatchers.any { it.matches(relativePath) }) {
            if (includeOverrideMatchers.any { it.matches(relativePath) }) {
                return true
            }
            return false
        }
        if (includeMatchers.isNotEmpty() && includeMatchers.none { it.matches(relativePath) }) {
            return false
        }

        if (filters.maxFileBytes != null) {
            val size = try { Files.size(absolutePath) } catch (_: Exception) { 0L }
            if (size > filters.maxFileBytes) return false
        }

        return true
    }

    private fun isGitPath(path: Path): Boolean {
        val parts = path.toString().split('/', '\\')
        return parts.any { it == ".git" }
    }

    private fun isLockfile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (lower.endsWith(".lock")) return true
        return lower in setOf(
            "package-lock.json",
            "pnpm-lock.yaml",
            "yarn.lock",
            "poetry.lock",
            "pipfile.lock",
            "uv.lock",
            "composer.lock",
            "cargo.lock",
            "go.sum",
            "mix.lock",
            "gemfile.lock",
            "podfile.lock"
        )
    }

    private fun isIdeMetadataPath(path: Path): Boolean {
        val parts = path.toString().split('/', '\\')
        return parts.any { it == ".idea" || it == ".vscode" }
    }

    private fun isIgnoredDotfile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (!lower.startsWith(".")) return false
        return lower in setOf(
            ".gitignore",
            ".gitattributes",
            ".gitmodules",
            ".python-version",
            ".editorconfig"
        ) || lower.endsWith(".iml")
    }

    fun isBinary(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

    fun readTextIfValid(path: Path, maxLineChars: Int = maxLineChars()): List<String>? {
        val bytes = try { Files.readAllBytes(path) } catch (_: Exception) { return null }
        if (isBinary(bytes)) return null
        val text = try { bytes.toString(Charset.forName("UTF-8")) } catch (_: Exception) { return null }
        val lines = text.lines()
        if (lines.isEmpty()) return null
        if (lines.any { it.length > maxLineChars }) return null
        return lines
    }

    private fun maxLineChars(): Int {
        val raw = System.getenv("MAX_LINE_CHARS") ?: return 10000
        val parsed = raw.toIntOrNull() ?: return 10000
        return parsed.coerceIn(1000, 200000)
    }

    fun isDocFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (lower == "readme" || lower.startsWith("readme.")) return true
        if (lower == "license" || lower.startsWith("license.")) return true
        val ext = lower.substringAfterLast('.', "")
        return ext in setOf("md", "rst", "txt", "adoc")
    }

    fun isTestFile(path: Path): Boolean {
        val lower = path.toString().lowercase()
        return lower.contains("/test/") || lower.contains("/tests/") ||
            lower.contains("__tests__") || lower.contains(".test.") || lower.contains(".spec.")
    }

    fun gitChangedFiles(repoPath: Path, baseRef: String, includeUntracked: Boolean): Set<Path> {
        val files = mutableSetOf<Path>()
        val diff = runGit(repoPath, listOf("diff", "--name-only", baseRef, "HEAD"))
        files.addAll(diff)
        if (includeUntracked) {
            files.addAll(runGit(repoPath, listOf("ls-files", "--others", "--exclude-standard")))
        }
        return files
    }

    fun gitDiffAddedLines(repoPath: Path, baseRef: String): Map<Path, Set<Int>> {
        val diffText = runGitText(repoPath, listOf("diff", "--unified=0", baseRef, "HEAD"))
        if (diffText.isBlank()) return emptyMap()
        return GitDiff.parseUnifiedDiff(repoPath, diffText)
    }

    private fun runGit(repoPath: Path, args: List<String>): Set<Path> {
        return try {
            val process = ProcessBuilder(listOf("git", "-C", repoPath.toString()) + args)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { repoPath.resolve(it).normalize() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun runGitText(repoPath: Path, args: List<String>): String {
        return try {
            val process = ProcessBuilder(listOf("git", "-C", repoPath.toString()) + args)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output
        } catch (_: Exception) {
            ""
        }
    }
}
