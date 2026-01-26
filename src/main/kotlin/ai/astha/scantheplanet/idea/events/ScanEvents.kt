package ai.astha.scantheplanet.idea.events

import com.intellij.util.messages.Topic

interface ScanEvents {
    fun onLog(message: String)
    fun onClear()

    companion object {
        val TOPIC: Topic<ScanEvents> = Topic.create("Scanner log", ScanEvents::class.java)
    }
}
