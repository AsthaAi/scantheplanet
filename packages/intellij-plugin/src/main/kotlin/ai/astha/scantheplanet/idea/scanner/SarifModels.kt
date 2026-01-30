package ai.astha.scantheplanet.idea.scanner

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifLog(
    val version: String = "2.1.0",
    val runs: List<SarifRun>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifTool(
    val driver: SarifDriver
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifDriver(
    val name: String,
    val rules: List<SarifRule>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifRule(
    val id: String,
    val name: String,
    val shortDescription: SarifText
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifText,
    val locations: List<SarifLocation>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifArtifactLocation(
    val uri: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifRegion(
    val startLine: Int,
    val endLine: Int
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SarifText(
    val text: String
)
