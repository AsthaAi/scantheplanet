package ai.astha.scantheplanet.idea.scanner

class SafeMcpRepository(private val basePath: String = "safe-mcp") {
    fun readText(resourcePath: String): String? {
        val fullPath = "$basePath/$resourcePath"
        val stream = javaClass.classLoader.getResourceAsStream(fullPath) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun listTechniqueIds(): List<String> {
        val prioritized = readText("techniques/prioritized-techniques.md") ?: return listOf("SAFE-T0001")
        val ids = prioritized
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("SAFE-")) it else "SAFE-$it" }
            .distinct()
            .sorted()
        if (ids.isEmpty()) return listOf("SAFE-T0001")
        return ids
    }

    fun readTechniqueSpec(id: String): String? {
        val normalized = if (id.startsWith("SAFE-")) id else "SAFE-$id"
        val fileName = normalized.replace("SAFE-", "")
        return readText("techniques/$normalized/technique.yaml")
            ?: readText("techniques/$normalized/technique.yml")
            ?: readText("techniques/$normalized.yaml")
            ?: readText("techniques/$fileName.yaml")
            ?: readText("techniques/$normalized.json")
            ?: readText("techniques/$fileName.json")
    }

    fun readTechniqueReadme(id: String): String? {
        val normalized = if (id.startsWith("SAFE-")) id else "SAFE-$id"
        return readText("techniques/$normalized/README.md")
            ?: readText("techniques/$normalized/README.txt")
    }

    fun readMitigationReadme(id: String): String? {
        val normalized = if (id.startsWith("SAFE-")) id else "SAFE-$id"
        return readText("mitigations/$normalized/README.md")
    }
}
