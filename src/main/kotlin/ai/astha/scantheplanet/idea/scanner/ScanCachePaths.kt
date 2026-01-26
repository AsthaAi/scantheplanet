package ai.astha.scantheplanet.idea.scanner

import com.intellij.openapi.application.PathManager
import java.nio.file.Path
import java.security.MessageDigest

object ScanCachePaths {
    fun buildCachePath(projectBasePath: String, namespace: String = "scantheplanet"): Path {
        val systemPath = Path.of(PathManager.getSystemPath())
        val projectHash = hashString(projectBasePath)
        return systemPath.resolve(namespace).resolve("scan-cache").resolve(projectHash).resolve("cache.json")
    }

    fun ensureCachePath(projectBasePath: String): Path {
        val target = buildCachePath(projectBasePath)
        if (java.nio.file.Files.exists(target)) {
            return target
        }
        for (legacy in legacyCachePaths(projectBasePath)) {
            if (java.nio.file.Files.exists(legacy)) {
                try {
                    java.nio.file.Files.createDirectories(target.parent)
                    java.nio.file.Files.copy(
                        legacy,
                        target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    // best-effort migration
                }
                break
            }
        }
        return target
    }

    fun cacheDirectories(projectBasePath: String): List<Path> {
        val targetDir = buildCachePath(projectBasePath).parent
        val legacyDirs = legacyCachePaths(projectBasePath).map { it.parent }
        return listOfNotNull(targetDir) + legacyDirs.filterNotNull()
    }

    private fun legacyCachePaths(projectBasePath: String): List<Path> {
        val namespaces = listOf("astha", "sourcerer")
        return namespaces.map { buildCachePath(projectBasePath, it) }
    }

    private fun hashString(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            sb.append(String.format("%02x", byte))
        }
        return sb.toString()
    }
}
