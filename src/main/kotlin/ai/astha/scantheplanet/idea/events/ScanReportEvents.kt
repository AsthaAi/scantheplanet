package ai.astha.scantheplanet.idea.events

import ai.astha.scantheplanet.idea.services.ScanReport
import com.intellij.util.messages.Topic

interface ScanReportEvents {
    fun onReport(report: ScanReport)

    companion object {
        val TOPIC: Topic<ScanReportEvents> = Topic.create("Scan The Planet scan report", ScanReportEvents::class.java)
    }
}
