package ai.astha.scantheplanet.idea.scanner

import java.util.regex.Pattern

object RuleHints {
    fun applyRuleHints(technique: TechniqueSpec, chunks: List<CodeChunk>) {
        for (chunk in chunks) {
            chunk.ruleHints = computeHints(technique, chunk)
        }
    }

    fun computeHints(technique: TechniqueSpec, chunk: CodeChunk): List<RuleHint> {
        val hints = mutableListOf<RuleHint>()
        val hintKeys = mutableSetOf<Pair<String, Int>>()
        val lines = chunk.code.lines()
        if (lines.isEmpty()) {
            return emptyList()
        }
        for (signal in technique.codeSignals) {
            for (heuristic in signal.heuristics) {
                val matchers = mutableListOf<HeuristicMatcher>()
                var invalid = false
                val flags = heuristic.flags
                if (heuristic.entropyMin != null) {
                    val minLen = (heuristic.minLength ?: 20).coerceAtLeast(8)
                    val threshold = heuristic.entropyMin
                    val candidateLines = lines.mapIndexedNotNull { idx, line ->
                        if (line.length < minLen) return@mapIndexedNotNull null
                        val score = shannonEntropy(line)
                        if (score >= threshold) idx else null
                    }
                    for (idx in candidateLines) {
                        val lineNo = chunk.startLine + idx
                        if (hintKeys.add(signal.id to lineNo)) {
                            hints.add(RuleHint(signal.id, lineNo, lines[idx]))
                        }
                    }
                }
                if (heuristic.allOf.isNotEmpty()) {
                    for (clause in heuristic.allOf) {
                        val matcher = buildMatcher(signal.id, clause.pattern, clause.regex, flags)
                        if (matcher != null) {
                            matchers.add(matcher)
                        }
                    }
                } else {
                    val matcher = buildMatcher(signal.id, heuristic.pattern, heuristic.regex, flags)
                    if (matcher != null) {
                        matchers.add(matcher)
                    }
                }

                if (invalid || matchers.isEmpty()) {
                    continue
                }

                if (matchers.size == 1) {
                    for ((idx, line) in lines.withIndex()) {
                        if (matchers[0].matches(line)) {
                            val lineNo = chunk.startLine + idx
                            if (hintKeys.add(signal.id to lineNo)) {
                                hints.add(RuleHint(signal.id, lineNo, line))
                            }
                        }
                    }
                    continue
                }

                val window = heuristic.window ?: 0
                val matchesPerClause = mutableListOf<List<Int>>()
                for (matcher in matchers) {
                    val matches = lines.mapIndexedNotNull { idx, line -> if (matcher.matches(line)) idx else null }
                    if (matches.isEmpty()) {
                        matchesPerClause.clear()
                        break
                    }
                    matchesPerClause.add(matches)
                }
                if (matchesPerClause.isEmpty()) continue

                for (idx in matchesPerClause.first()) {
                    val start = (idx - window).coerceAtLeast(0)
                    val end = (idx + window).coerceAtMost(lines.size - 1)
                    val allWithin = matchesPerClause.drop(1).all { candidates ->
                        candidates.any { it in start..end }
                    }
                    if (allWithin) {
                        val lineNo = chunk.startLine + idx
                        if (hintKeys.add(signal.id to lineNo)) {
                            hints.add(RuleHint(signal.id, lineNo, lines[idx]))
                        }
                    }
                }
            }
        }
        return hints
    }

    private fun shannonEntropy(input: String): Double {
        if (input.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>()
        var total = 0
        for (ch in input) {
            if (ch.isWhitespace()) continue
            counts[ch] = (counts[ch] ?: 0) + 1
            total += 1
        }
        if (total == 0) return 0.0
        var entropy = 0.0
        for (count in counts.values) {
            val p = count.toDouble() / total
            entropy -= p * kotlin.math.ln(p) / kotlin.math.ln(2.0)
        }
        return entropy
    }

    private fun buildMatcher(
        signalId: String,
        pattern: String?,
        regex: String?,
        flags: String?
    ): HeuristicMatcher? {
        val caseInsensitive = flags?.contains('i') == true
        if (!pattern.isNullOrBlank()) {
            if (pattern.contains('*') || pattern.contains('?') || caseInsensitive) {
                val regexPattern = if (pattern.contains('*') || pattern.contains('?')) {
                    globToRegex(pattern)
                } else {
                    Pattern.quote(pattern)
                }
                return buildRegexMatcher(regexPattern, caseInsensitive)
            }
            return HeuristicMatcher.Substring(pattern)
        }
        if (regex.isNullOrBlank()) return null
        return buildRegexMatcher(regex, caseInsensitive)
    }

    private fun buildRegexMatcher(pattern: String, caseInsensitive: Boolean): HeuristicMatcher? {
        return try {
            val flags = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
            val compiled = Pattern.compile(pattern, flags)
            HeuristicMatcher.Regex(compiled)
        } catch (_: Exception) {
            null
        }
    }

    private fun globToRegex(pattern: String): String {
        val out = StringBuilder()
        for (ch in pattern) {
            when (ch) {
                '*' -> out.append(".*")
                '?' -> out.append('.')
                '\\' -> out.append("\\\\")
                '.', '+', '(', ')', '|', '{', '}', '[', ']', '^', '$' -> {
                    out.append('\\')
                    out.append(ch)
                }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private sealed class HeuristicMatcher {
        data class Substring(val value: String) : HeuristicMatcher()
        data class Regex(val pattern: Pattern) : HeuristicMatcher()

        fun matches(line: String): Boolean {
            return when (this) {
                is Substring -> line.contains(value)
                is Regex -> pattern.matcher(line).find()
            }
        }
    }
}
