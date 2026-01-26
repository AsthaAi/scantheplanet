package ai.astha.scantheplanet.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "AsthaSettings", storages = [Storage("astha.xml")])
class AsthaSettingsService : PersistentStateComponent<AsthaSettingsState> {
    private var state = AsthaSettingsState()

    override fun getState(): AsthaSettingsState = state

    override fun loadState(state: AsthaSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
        if (this.state.techniques.isEmpty()) {
            val legacy = state.legacyTechniqueId?.trim()
            if (!legacy.isNullOrEmpty()) {
                this.state.techniques = mutableListOf(legacy)
            } else {
                this.state.techniques = mutableListOf("SAFE-T0001")
            }
        }
    }
}
