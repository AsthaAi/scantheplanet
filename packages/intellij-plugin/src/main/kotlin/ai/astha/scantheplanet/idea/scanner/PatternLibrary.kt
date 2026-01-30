package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule

class PatternLibrary(private val repository: SafeMcpRepository) {
    private val mapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
    private val patterns: Map<String, List<Heuristic>> = loadPatterns()

    fun resolve(patternRef: String): List<Heuristic>? = patterns[patternRef]

    private fun loadPatterns(): Map<String, List<Heuristic>> {
        val patternFiles = listOf(
            "patterns/common.yaml",
            "patterns/js.yaml",
            "patterns/py.yaml",
            "patterns/go.yaml"
        )
        val map = mutableMapOf<String, List<Heuristic>>()
        for (file in patternFiles) {
            val raw = repository.readText(file) ?: continue
            val parsed = mapper.readValue(raw, PatternFile::class.java)
            val namespace = parsed.namespace.trim()
            for ((name, group) in parsed.patterns) {
                val key = "$namespace.$name"
                val heuristics = group.heuristics.map { it.toHeuristic() }
                map[key] = heuristics
            }
        }
        return map
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PatternFile(
        val namespace: String = "",
        val description: String = "",
        val patterns: Map<String, PatternGroup> = emptyMap()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PatternGroup(
        val description: String = "",
        val heuristics: List<PatternHeuristic> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PatternHeuristic(
        val pattern: String? = null,
        val regex: String? = null,
        val flags: String? = null,
        val all_of: List<PatternHeuristicClause> = emptyList(),
        val window: Int? = null
    ) {
        fun toHeuristic(): Heuristic {
            return Heuristic(
                pattern = pattern,
                regex = regex,
                flags = flags,
                allOf = all_of.map { HeuristicClause(pattern = it.pattern, regex = it.regex) },
                window = window,
                patternRef = null
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PatternHeuristicClause(
        val pattern: String? = null,
        val regex: String? = null
    )
}
