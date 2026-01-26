package ai.astha.scantheplanet.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import ai.astha.scantheplanet.idea.scanner.SafeMcpRepository
import ai.astha.scantheplanet.idea.scanner.ScanCachePaths
import java.nio.file.Files
import java.util.Comparator
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.JButton

class AsthaSettingsConfigurable(private val project: Project) : Configurable {
    private var form: AsthaSettingsForm? = null

    override fun getDisplayName(): String = "Scan The Planet"

    override fun createComponent(): JComponent {
        val form = AsthaSettingsForm(project)
        this.form = form
        return form.panel
    }

    override fun isModified(): Boolean {
        val form = form ?: return false
        val settings = project.getService(AsthaSettingsService::class.java).state
        return form.isModified(settings)
    }

    override fun apply() {
        val form = form ?: return
        val settings = project.getService(AsthaSettingsService::class.java).state
        form.applyTo(settings)
    }

    override fun reset() {
        val form = form ?: return
        val settings = project.getService(AsthaSettingsService::class.java).state
        form.reset(settings)
    }

    override fun disposeUIResources() {
        form = null
    }
}

private class AsthaSettingsForm(private val project: Project) {
    private val techniqueList = JBList(loadTechniqueIds())
    private val scopeBox = JComboBox(ScanScope.entries.toTypedArray())
    private val includeUntrackedBox = JBCheckBox("Include untracked files (git diff)")
    private val providerBox = JComboBox(LlmProvider.entries.toTypedArray())
    private val modelNameField = JBTextField()
    private val configPathField = JBTextField()
    private val endpointField = JBTextField()
    private val tokenField = JBPasswordField()
    private val batchEnabledBox = JBCheckBox("Enable batch mode (multi-technique prompts)")
    private val batchSizeField = JBTextField()
    private val cleanFindingsBox = JBCheckBox("Clean findings with LLM")
    private val cleaningModelField = JBTextField()
    private val cacheEnabledBox = JBCheckBox("Enable scan cache")
    private val clearCacheButton = JButton("Clear scan cache")

    val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Techniques", JBScrollPane(techniqueList))
        .addLabeledComponent("Scope", scopeBox)
        .addComponent(includeUntrackedBox)
        .addLabeledComponent("Provider", providerBox)
        .addLabeledComponent("Model name", modelNameField)
        .addLabeledComponent("Config path", configPathField)
        .addLabeledComponent("LLM endpoint", endpointField)
        .addLabeledComponent("LLM token", tokenField)
        .addComponent(batchEnabledBox)
        .addLabeledComponent("Batch size", batchSizeField)
        .addComponent(cleanFindingsBox)
        .addLabeledComponent("Cleaning model", cleaningModelField)
        .addComponent(cacheEnabledBox)
        .addComponent(clearCacheButton)
        .panel

    init {
        techniqueList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        techniqueList.visibleRowCount = 8
        updateCleaningFieldsEnabled()
        cleanFindingsBox.addActionListener {
            updateCleaningFieldsEnabled()
        }
        clearCacheButton.addActionListener {
            val basePath = project.basePath
            if (basePath.isNullOrBlank()) {
                Messages.showWarningDialog(panel, "Project base path is not available.", "Scan The Planet")
                return@addActionListener
            }
            val cleared = try {
                val cacheDirs = ScanCachePaths.cacheDirectories(basePath)
                for (cacheDir in cacheDirs) {
                    if (Files.exists(cacheDir)) {
                        Files.walk(cacheDir).use { stream ->
                            stream.sorted(Comparator.reverseOrder())
                                .forEach { Files.deleteIfExists(it) }
                        }
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
            val message = if (cleared) {
                "Scan cache cleared."
            } else {
                "Failed to clear scan cache."
            }
            Messages.showInfoMessage(panel, message, "Scan The Planet")
        }
    }

    private fun updateCleaningFieldsEnabled() {
        val enabled = cleanFindingsBox.isSelected
        cleaningModelField.isEnabled = enabled
    }

    fun reset(state: AsthaSettingsState) {
        selectTechniques(state.techniques)
        scopeBox.selectedItem = state.scope
        includeUntrackedBox.isSelected = state.includeUntracked
        providerBox.selectedItem = state.provider
        modelNameField.text = state.modelName
        configPathField.text = state.configPath
        endpointField.text = state.llmEndpoint
        tokenField.text = state.llmToken
        batchEnabledBox.isSelected = state.batchEnabled
        batchSizeField.text = state.batchSize.toString()
        cleanFindingsBox.isSelected = state.cleanFindings
        cleaningModelField.text = state.cleaningModelName
        cacheEnabledBox.isSelected = state.cacheEnabled
        updateCleaningFieldsEnabled()
    }

    fun applyTo(state: AsthaSettingsState) {
        state.techniques = selectedTechniques()
        state.scope = scopeBox.selectedItem as? ScanScope ?: ScanScope.GIT_DIFF
        state.includeUntracked = includeUntrackedBox.isSelected
        state.provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.LOCAL
        state.modelName = modelNameField.text.trim()
        state.configPath = configPathField.text.trim()
        state.llmEndpoint = endpointField.text.trim()
        state.llmToken = String(tokenField.password)
        state.batchEnabled = batchEnabledBox.isSelected
        state.batchSize = batchSizeField.text.trim().toIntOrNull()?.coerceIn(1, 20) ?: state.batchSize
        state.cleanFindings = cleanFindingsBox.isSelected
        state.cleaningModelName = cleaningModelField.text.trim()
        state.cacheEnabled = cacheEnabledBox.isSelected
    }

    fun isModified(state: AsthaSettingsState): Boolean {
        return selectedTechniques() != state.techniques ||
            scopeBox.selectedItem != state.scope ||
            includeUntrackedBox.isSelected != state.includeUntracked ||
            providerBox.selectedItem != state.provider ||
            modelNameField.text.trim() != state.modelName ||
            configPathField.text.trim() != state.configPath ||
            endpointField.text.trim() != state.llmEndpoint ||
            String(tokenField.password) != state.llmToken ||
            batchEnabledBox.isSelected != state.batchEnabled ||
            batchSizeField.text.trim() != state.batchSize.toString() ||
            cleanFindingsBox.isSelected != state.cleanFindings ||
            cleaningModelField.text.trim() != state.cleaningModelName ||
            cacheEnabledBox.isSelected != state.cacheEnabled
    }

    private fun selectTechniques(techniques: List<String>) {
        val model = techniqueList.model
        val indices = techniques.mapNotNull { technique ->
            (0 until model.size).firstOrNull { model.getElementAt(it) == technique }
        }
        val indexArray = indices.toIntArray()
        if (indexArray.isNotEmpty()) {
            techniqueList.selectedIndices = indexArray
        }
    }

    private fun selectedTechniques(): MutableList<String> {
        return techniqueList.selectedValuesList.toMutableList()
    }

    private fun loadTechniqueIds(): List<String> {
        val repo = SafeMcpRepository()
        return repo.listTechniqueIds()
    }
}
