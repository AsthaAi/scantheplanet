package ai.astha.scantheplanet.idea.scanner

object SarifBuilder {
    fun buildSarif(result: ScanSummary, techniqueMap: Map<String, TechniqueSpec>): SarifLog {
        val rules = techniqueMap.values.map {
            SarifRule(
                id = it.id,
                name = it.name.ifBlank { it.id },
                shortDescription = SarifText(it.summary.ifBlank { it.description.ifBlank { it.name } })
            )
        }
        val results = result.findings.map { finding ->
            SarifResult(
                ruleId = finding.techniqueId,
                level = severityToLevel(finding.severity),
                message = SarifText(finding.observation.ifBlank { finding.techniqueId }),
                locations = listOf(
                    SarifLocation(
                        physicalLocation = SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(uri = finding.file),
                            region = SarifRegion(
                                startLine = finding.startLine.coerceAtLeast(1),
                                endLine = finding.endLine.coerceAtLeast(finding.startLine.coerceAtLeast(1))
                            )
                        )
                    )
                )
            )
        }
        return SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifDriver(
                            name = "Scan The Planet Scanner",
                            rules = rules
                        )
                    ),
                    results = results
                )
            )
        )
    }

    private fun severityToLevel(severity: String): String {
        return when (severity.lowercase()) {
            "critical", "high" -> "error"
            "medium" -> "warning"
            else -> "note"
        }
    }
}
