package ai.astha.scantheplanet.idea.scanner

data class ScanProgress(
    val techniqueId: String,
    val techniqueName: String,
    val techniqueIndex: Int,
    val totalTechniques: Int,
    val currentFile: String,
    val currentChunkId: String?,
    val processedFiles: Int,
    val totalFiles: Int,
    val chunksAnalyzed: Int,
    val chunksFailed: Int,
    val phase: ScanPhase,
    val chunkStatus: ChunkStatus = ChunkStatus.SCANNING
)

enum class ScanPhase {
    SCANNING,
    CLEANING,
    DONE
}

enum class ChunkStatus {
    SCANNING,
    CACHED
}
