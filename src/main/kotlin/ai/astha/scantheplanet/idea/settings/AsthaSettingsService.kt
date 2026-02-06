package ai.astha.scantheplanet.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import ai.astha.scantheplanet.idea.storage.LlmTokenStore

@Service(Service.Level.PROJECT)
@State(name = "AsthaSettings", storages = [Storage("astha.xml")])
class AsthaSettingsService : PersistentStateComponent<AsthaSettingsState> {
    private var state = AsthaSettingsState()
    private val tokenStore = LlmTokenStore(PLUGIN_ID)

    override fun getState(): AsthaSettingsState = state

    override fun loadState(state: AsthaSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
        if (this.state.provider != LlmProvider.OPENAI && this.state.provider != LlmProvider.OLLAMA) {
            this.state.provider = LlmProvider.OPENAI
        }
        if (this.state.techniques.isEmpty()) {
            val legacy = state.legacyTechniqueId?.trim()
            if (!legacy.isNullOrEmpty()) {
                this.state.techniques = mutableListOf(legacy)
            } else {
                this.state.techniques = mutableListOf("SAFE-T0001")
            }
        }
        if (!this.state.providerParallelismInitialized) {
            val legacyParallelism = this.state.chunkParallelism.coerceAtLeast(1)
            val legacyParallelismMax = this.state.chunkParallelismMax.coerceAtLeast(legacyParallelism)
            this.state.openaiChunkParallelism = legacyParallelism
            this.state.openaiChunkParallelismMax = legacyParallelismMax
            this.state.ollamaChunkParallelism = 1
            this.state.ollamaChunkParallelismMax = 1
            this.state.providerParallelismInitialized = true
        }
        if (!this.state.providerModelInitialized) {
            val legacyModel = this.state.modelName.trim()
            if (this.state.provider == LlmProvider.OLLAMA) {
                this.state.ollamaModelName = legacyModel.ifBlank { "llama3.1" }
                this.state.openaiModelName = "gpt-5.2"
            } else {
                this.state.openaiModelName = legacyModel.ifBlank { "gpt-5.2" }
                this.state.ollamaModelName = "llama3.1"
            }
            this.state.providerModelInitialized = true
        }
    }

    fun saveLlmToken(providerId: String, token: String?) {
        tokenStore.saveToken(providerId, token)
    }

    fun getLlmToken(providerId: String): String? {
        return tokenStore.loadToken(providerId)
    }

    fun hasLlmToken(providerId: String): Boolean {
        return !tokenStore.loadToken(providerId).isNullOrBlank()
    }

    companion object {
        private const val PLUGIN_ID = "ai.astha.scantheplanet.idea"
    }
}
