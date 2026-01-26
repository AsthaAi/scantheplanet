package ai.astha.scantheplanet.idea.services

import ai.astha.scantheplanet.idea.scanner.ScanProgress

data class ScanReport(
    val status: String,
    val summary: String,
    val findings: List<ScanFinding>,
    val progress: ScanProgress? = null
)

data class ScanFinding(
    val techniqueId: String,
    val techniqueName: String = "",
    val severity: String,
    val file: String,
    val startLine: Int,
    val endLine: Int,
    val observation: String
)
