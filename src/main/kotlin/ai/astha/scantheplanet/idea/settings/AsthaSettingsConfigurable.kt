package ai.astha.scantheplanet.idea.settings

import ai.astha.scantheplanet.idea.scanner.SafeMcpRepository
import ai.astha.scantheplanet.idea.scanner.ScanCachePaths
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.CardLayout
import java.util.Comparator
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import java.nio.file.Files

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
    private val settingsService = project.getService(AsthaSettingsService::class.java)
    private val techniqueIds = loadTechniqueIds()
    private var isTechniquePresetSync = false
    private val selectedTechniques = mutableListOf<String>()

    private val techniquePresetBox = JComboBox(TechniquePreset.entries.map { it.label }.toTypedArray())
    private val techniqueSummaryLabel = JBLabel()
    private val customizeTechniquesButton = JButton("Customize techniques...")

    private val scopeBox = JComboBox(ScanScope.entries.toTypedArray())
    private val includeUntrackedBox = JBCheckBox("Include untracked files (git diff)")
    private val providerBox = JComboBox(listOf(LlmProvider.OPENAI, LlmProvider.OLLAMA).toTypedArray())
    private val openaiModelBox = JComboBox(arrayOf(OPENAI_DEFAULT_MODEL))
    private val modelNameField = JBTextField()
    private val modelPanel = JPanel(CardLayout())
    private val tokenField = JBPasswordField()
    private val clearTokenButton = JButton("Clear")
    private val tokenStoredLabel = JBLabel("Stored")

    private val advancedToggle = JBCheckBox("Show advanced settings")
    private val configPathField = TextFieldWithBrowseButton()
    private val endpointOverrideBox = JBCheckBox("Override")
    private val endpointField = JBTextField()
    private val ollamaLoggingBox = JBCheckBox("Enable Ollama request/response logging")

    private val batchModeBox = JComboBox(BatchMode.entries.map { it.label }.toTypedArray())
    private val batchSizeField = JBTextField()

    private val cleaningModeBox = JComboBox(CleaningMode.entries.map { it.label }.toTypedArray())
    private val cleaningModelField = JBTextField()

    private val cacheEnabledBox = JBCheckBox("Enable scan cache")
    private val clearCacheButton = JButton("Clear scan cache")
    private val chunkParallelismField = JBTextField()
    private val chunkParallelismMaxField = JBTextField()
    private val sourceCodeOnlyBox = JBCheckBox("Scan source code only (full project)")
    private val advancedPanel = JPanel()
    private var openaiModelDraft: String = OPENAI_DEFAULT_MODEL
    private var ollamaModelDraft: String = OLLAMA_DEFAULT_MODEL
    private var openaiChunkParallelismDraft: Int = 10
    private var openaiChunkParallelismMaxDraft: Int = 10
    private var ollamaChunkParallelismDraft: Int = 1
    private var ollamaChunkParallelismMaxDraft: Int = 1

    val panel: JComponent

    init {
        configureTechniqueControls()
        configureScopeControls()
        configureModelControls()
        configureAdvancedControls()

        val basicPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Techniques", buildTechniquesRow())
            .addLabeledComponent("Scope", scopeBox)
            .addComponent(includeUntrackedBox)
            .addLabeledComponent("Provider", providerBox)
            .addLabeledComponent("Model name", buildModelRow())
            .addLabeledComponent("LLM token", buildTokenRow())
            .panel

        advancedPanel.layout = BoxLayout(advancedPanel, BoxLayout.Y_AXIS)
        advancedPanel.add(FormBuilder.createFormBuilder()
            .addSeparator()
            .addLabeledComponent("Config file", configPathField)
            .addLabeledComponent("LLM endpoint", buildEndpointRow())
            .addComponent(ollamaLoggingBox)
            .addLabeledComponent("Batching", buildBatchRow())
            .addLabeledComponent("Clean findings", buildCleaningRow())
            .addLabeledComponent("Chunk parallelism", chunkParallelismField)
            .addLabeledComponent("Chunk parallelism max", chunkParallelismMaxField)
            .addComponent(sourceCodeOnlyBox)
            .addComponent(cacheEnabledBox)
            .addComponent(clearCacheButton)
            .panel)

        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.add(basicPanel)
        root.add(advancedToggle)
        root.add(advancedPanel)
        panel = root
    }

    private fun configureTechniqueControls() {
        updateTechniqueSummary()
        customizeTechniquesButton.addActionListener { showTechniqueDialog() }
        techniquePresetBox.addActionListener {
            if (isTechniquePresetSync) return@addActionListener
            val preset = TechniquePreset.fromLabel(techniquePresetBox.selectedItem as? String)
            when (preset) {
                TechniquePreset.ALL -> setSelectedTechniques(techniqueIds)
                TechniquePreset.CUSTOM -> Unit
                null -> Unit
            }
        }
    }

    private fun configureScopeControls() {
        scopeBox.addActionListener { updateScopeDependentFields() }
        updateScopeDependentFields()
    }

    private fun configureModelControls() {
        modelPanel.add(openaiModelBox, MODEL_CARD_OPENAI)
        modelPanel.add(modelNameField, MODEL_CARD_OTHER)
        providerBox.addActionListener {
            persistCurrentProviderModel()
            persistCurrentProviderParallelism()
            updateModelCard()
            updateTokenIndicator()
            val provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
            loadProviderModelField(provider)
            loadProviderParallelismFields(provider)
        }
        updateModelCard()
        updateTokenIndicator()
    }

    private fun configureAdvancedControls() {
        advancedToggle.addActionListener {
            advancedPanel.isVisible = advancedToggle.isSelected
            advancedPanel.revalidate()
            advancedPanel.repaint()
        }

        val configDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        configDescriptor.title = "Select scanner config"
        configPathField.addBrowseFolderListener(
            "Select scanner config",
            null,
            project,
            configDescriptor
        )

        endpointOverrideBox.addActionListener { updateEndpointEnabled() }
        updateEndpointEnabled()

        batchSizeField.columns = 4
        batchModeBox.addActionListener { updateBatchModeFields() }
        updateBatchModeFields()

        cleaningModeBox.addActionListener { updateCleaningFields() }
        updateCleaningFields()

        clearCacheButton.addActionListener { clearScanCache() }
        clearTokenButton.addActionListener { clearToken() }
    }

    private fun buildTechniquesRow(): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(techniquePresetBox)
        row.add(Box.createHorizontalStrut(8))
        row.add(techniqueSummaryLabel)
        row.add(Box.createHorizontalStrut(8))
        row.add(customizeTechniquesButton)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun buildTokenRow(): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(tokenField)
        row.add(Box.createHorizontalStrut(8))
        row.add(clearTokenButton)
        row.add(Box.createHorizontalStrut(8))
        row.add(tokenStoredLabel)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun buildModelRow(): JComponent {
        return modelPanel
    }

    private fun buildEndpointRow(): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(endpointOverrideBox)
        row.add(Box.createHorizontalStrut(8))
        row.add(endpointField)
        return row
    }

    private fun buildBatchRow(): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(batchModeBox)
        row.add(Box.createHorizontalStrut(8))
        row.add(JBLabel("Size"))
        row.add(Box.createHorizontalStrut(4))
        row.add(batchSizeField)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun buildCleaningRow(): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.add(cleaningModeBox)
        row.add(Box.createHorizontalStrut(8))
        row.add(JBLabel("Custom model"))
        row.add(Box.createHorizontalStrut(4))
        row.add(cleaningModelField)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun updateScopeDependentFields() {
        val isGitDiff = scopeBox.selectedItem == ScanScope.GIT_DIFF
        includeUntrackedBox.isEnabled = isGitDiff
    }

    private fun updateModelCard() {
        val provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
        val layout = modelPanel.layout as CardLayout
        if (provider == LlmProvider.OPENAI) {
            layout.show(modelPanel, MODEL_CARD_OPENAI)
        } else {
            layout.show(modelPanel, MODEL_CARD_OTHER)
        }
    }

    private fun updateEndpointEnabled() {
        endpointField.isEnabled = endpointOverrideBox.isSelected
    }

    private fun updateBatchModeFields() {
        val mode = BatchMode.fromLabel(batchModeBox.selectedItem as? String) ?: BatchMode.OFF
        val isCustom = mode == BatchMode.CUSTOM
        batchSizeField.isEnabled = isCustom
        if (!isCustom) {
            batchSizeField.text = mode.size?.toString().orEmpty()
        }
    }

    private fun updateCleaningFields() {
        val mode = CleaningMode.fromLabel(cleaningModeBox.selectedItem as? String) ?: CleaningMode.OFF
        val isCustom = mode == CleaningMode.CUSTOM
        cleaningModelField.isEnabled = isCustom
    }

    fun reset(state: AsthaSettingsState) {
        val techniques = state.techniques.takeIf { it.isNotEmpty() } ?: techniqueIds
        setSelectedTechniques(techniques)
        scopeBox.selectedItem = state.scope
        includeUntrackedBox.isSelected = state.includeUntracked
        providerBox.selectedItem = state.provider
        openaiModelDraft = state.openaiModelName.ifBlank { OPENAI_DEFAULT_MODEL }
        ollamaModelDraft = state.ollamaModelName.ifBlank { OLLAMA_DEFAULT_MODEL }
        loadProviderModelField(state.provider)
        configPathField.text = state.configPath
        endpointOverrideBox.isSelected = state.llmEndpoint.isNotBlank()
        endpointField.text = state.llmEndpoint
        ollamaLoggingBox.isSelected = state.ollamaLoggingEnabled
        tokenField.text = ""

        applyBatchState(state)
        applyCleaningState(state)

        cacheEnabledBox.isSelected = state.cacheEnabled
        openaiChunkParallelismDraft = state.openaiChunkParallelism.coerceAtLeast(1)
        openaiChunkParallelismMaxDraft = state.openaiChunkParallelismMax.coerceAtLeast(openaiChunkParallelismDraft)
        ollamaChunkParallelismDraft = state.ollamaChunkParallelism.coerceAtLeast(1)
        ollamaChunkParallelismMaxDraft = state.ollamaChunkParallelismMax.coerceAtLeast(ollamaChunkParallelismDraft)
        loadProviderParallelismFields(state.provider)
        sourceCodeOnlyBox.isSelected = state.sourceCodeOnly
        advancedToggle.isSelected = shouldShowAdvanced(state)
        advancedPanel.isVisible = advancedToggle.isSelected
        updateScopeDependentFields()
        updateModelCard()
        updateTokenIndicator()
        updateEndpointEnabled()
        updateBatchModeFields()
        updateCleaningFields()
    }

    fun applyTo(state: AsthaSettingsState) {
        persistCurrentProviderModel()
        persistCurrentProviderParallelism()
        state.techniques = selectedTechniques.toMutableList()
        state.scope = scopeBox.selectedItem as? ScanScope ?: ScanScope.GIT_DIFF
        state.includeUntracked = includeUntrackedBox.isSelected
        state.provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
        state.openaiModelName = openaiModelDraft.ifBlank { OPENAI_DEFAULT_MODEL }
        state.ollamaModelName = ollamaModelDraft.ifBlank { OLLAMA_DEFAULT_MODEL }
        state.providerModelInitialized = true
        state.modelName = if (state.provider == LlmProvider.OPENAI) state.openaiModelName else state.ollamaModelName
        state.configPath = configPathField.text.trim()
        state.llmEndpoint = if (endpointOverrideBox.isSelected) endpointField.text.trim() else ""
        state.ollamaLoggingEnabled = ollamaLoggingBox.isSelected

        val token = String(tokenField.password).trim()
        if (token.isNotEmpty()) {
            settingsService.saveLlmToken(state.provider.cliValue, token)
        }
        tokenField.text = ""
        state.llmToken = ""
        updateTokenIndicator()

        applyBatchToState(state)
        applyCleaningToState(state)

        state.cacheEnabled = cacheEnabledBox.isSelected
        state.openaiChunkParallelism = openaiChunkParallelismDraft
        state.openaiChunkParallelismMax = openaiChunkParallelismMaxDraft.coerceAtLeast(state.openaiChunkParallelism)
        state.ollamaChunkParallelism = ollamaChunkParallelismDraft
        state.ollamaChunkParallelismMax = ollamaChunkParallelismMaxDraft.coerceAtLeast(state.ollamaChunkParallelism)
        state.providerParallelismInitialized = true
        val selectedParallelism = if (state.provider == LlmProvider.OLLAMA) {
            state.ollamaChunkParallelism to state.ollamaChunkParallelismMax
        } else {
            state.openaiChunkParallelism to state.openaiChunkParallelismMax
        }
        state.chunkParallelism = selectedParallelism.first
        state.chunkParallelismMax = selectedParallelism.second
        state.sourceCodeOnly = sourceCodeOnlyBox.isSelected
        if (state.chunkParallelismMax < state.chunkParallelism) {
            state.chunkParallelismMax = state.chunkParallelism
        }
    }

    fun isModified(state: AsthaSettingsState): Boolean {
        val effectiveTechniques = state.techniques.takeIf { it.isNotEmpty() } ?: techniqueIds
        return selectedTechniques != effectiveTechniques ||
            scopeBox.selectedItem != state.scope ||
            includeUntrackedBox.isSelected != state.includeUntracked ||
            providerBox.selectedItem != state.provider ||
            isModelModified(state) ||
            configPathField.text.trim() != state.configPath ||
            endpointOverrideBox.isSelected != state.llmEndpoint.isNotBlank() ||
            endpointField.text.trim() != state.llmEndpoint ||
            ollamaLoggingBox.isSelected != state.ollamaLoggingEnabled ||
            String(tokenField.password).isNotBlank() ||
            isBatchModified(state) ||
            isCleaningModified(state) ||
            cacheEnabledBox.isSelected != state.cacheEnabled ||
            effectiveProviderParallelism(LlmProvider.OPENAI).first != state.openaiChunkParallelism ||
            effectiveProviderParallelism(LlmProvider.OPENAI).second != state.openaiChunkParallelismMax ||
            effectiveProviderParallelism(LlmProvider.OLLAMA).first != state.ollamaChunkParallelism ||
            effectiveProviderParallelism(LlmProvider.OLLAMA).second != state.ollamaChunkParallelismMax ||
            sourceCodeOnlyBox.isSelected != state.sourceCodeOnly
    }

    private fun applyBatchState(state: AsthaSettingsState) {
        val mode = BatchMode.fromState(state)
        batchModeBox.selectedItem = mode.label
        batchSizeField.text = state.batchSize.toString()
    }

    private fun applyCleaningState(state: AsthaSettingsState) {
        val mode = CleaningMode.fromState(state)
        cleaningModeBox.selectedItem = mode.label
        cleaningModelField.text = state.cleaningModelName
    }

    private fun applyBatchToState(state: AsthaSettingsState) {
        val mode = BatchMode.fromLabel(batchModeBox.selectedItem as? String) ?: BatchMode.OFF
        when (mode) {
            BatchMode.OFF -> {
                state.batchEnabled = false
            }
            BatchMode.CUSTOM -> {
                state.batchEnabled = true
            }
            else -> {
                state.batchEnabled = true
                state.batchSize = mode.size ?: state.batchSize
            }
        }
        if (mode == BatchMode.CUSTOM) {
            state.batchSize = batchSizeField.text.trim().toIntOrNull()?.coerceIn(1, 20) ?: state.batchSize
        }
    }

    private fun applyCleaningToState(state: AsthaSettingsState) {
        val mode = CleaningMode.fromLabel(cleaningModeBox.selectedItem as? String) ?: CleaningMode.OFF
        state.cleanFindings = mode != CleaningMode.OFF
        state.cleaningModelName = when (mode) {
            CleaningMode.CUSTOM -> cleaningModelField.text.trim()
            CleaningMode.SAME_MODEL -> ""
            CleaningMode.OFF -> cleaningModelField.text.trim()
        }
    }

    private fun isBatchModified(state: AsthaSettingsState): Boolean {
        val mode = BatchMode.fromLabel(batchModeBox.selectedItem as? String) ?: BatchMode.OFF
        if (mode != BatchMode.fromState(state)) return true
        return if (mode == BatchMode.CUSTOM) {
            batchSizeField.text.trim() != state.batchSize.toString()
        } else {
            false
        }
    }

    private fun isCleaningModified(state: AsthaSettingsState): Boolean {
        val mode = CleaningMode.fromLabel(cleaningModeBox.selectedItem as? String) ?: CleaningMode.OFF
        if (mode != CleaningMode.fromState(state)) return true
        return if (mode == CleaningMode.CUSTOM) {
            cleaningModelField.text.trim() != state.cleaningModelName
        } else {
            false
        }
    }

    private fun isModelModified(state: AsthaSettingsState): Boolean {
        val currentProvider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
        val currentOpenAi = if (currentProvider == LlmProvider.OPENAI) {
            (openaiModelBox.selectedItem as? String).orEmpty().ifBlank { OPENAI_DEFAULT_MODEL }
        } else {
            openaiModelDraft.ifBlank { OPENAI_DEFAULT_MODEL }
        }
        val currentOllama = if (currentProvider == LlmProvider.OLLAMA) {
            modelNameField.text.trim().ifBlank { OLLAMA_DEFAULT_MODEL }
        } else {
            ollamaModelDraft.ifBlank { OLLAMA_DEFAULT_MODEL }
        }
        return currentOpenAi != state.openaiModelName.ifBlank { OPENAI_DEFAULT_MODEL } ||
            currentOllama != state.ollamaModelName.ifBlank { OLLAMA_DEFAULT_MODEL }
    }

    private fun shouldShowAdvanced(state: AsthaSettingsState): Boolean {
        return state.configPath.isNotBlank() ||
            state.llmEndpoint.isNotBlank() ||
            state.ollamaLoggingEnabled ||
            state.batchEnabled != true ||
            state.batchSize != 3 ||
            state.cleanFindings != true ||
            state.cleaningModelName.isNotBlank() ||
            state.cacheEnabled != true ||
            state.openaiChunkParallelism != 10 ||
            state.openaiChunkParallelismMax != 10 ||
            state.ollamaChunkParallelism != 1 ||
            state.ollamaChunkParallelismMax != 1 ||
            state.sourceCodeOnly
    }

    private fun persistCurrentProviderParallelism() {
        when (providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI) {
            LlmProvider.OLLAMA -> {
                val parallelism = parsePositiveInt(chunkParallelismField.text.trim(), ollamaChunkParallelismDraft)
                val parallelismMax = parsePositiveInt(chunkParallelismMaxField.text.trim(), ollamaChunkParallelismMaxDraft)
                    .coerceAtLeast(parallelism)
                ollamaChunkParallelismDraft = parallelism
                ollamaChunkParallelismMaxDraft = parallelismMax
            }
            else -> {
                val parallelism = parsePositiveInt(chunkParallelismField.text.trim(), openaiChunkParallelismDraft)
                val parallelismMax = parsePositiveInt(chunkParallelismMaxField.text.trim(), openaiChunkParallelismMaxDraft)
                    .coerceAtLeast(parallelism)
                openaiChunkParallelismDraft = parallelism
                openaiChunkParallelismMaxDraft = parallelismMax
            }
        }
    }

    private fun loadProviderParallelismFields(provider: LlmProvider) {
        val values = when (provider) {
            LlmProvider.OLLAMA -> ollamaChunkParallelismDraft to ollamaChunkParallelismMaxDraft
            else -> openaiChunkParallelismDraft to openaiChunkParallelismMaxDraft
        }
        chunkParallelismField.text = values.first.toString()
        chunkParallelismMaxField.text = values.second.toString()
    }

    private fun effectiveProviderParallelism(provider: LlmProvider): Pair<Int, Int> {
        val isCurrentProvider = (providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI) == provider
        val stored = when (provider) {
            LlmProvider.OLLAMA -> ollamaChunkParallelismDraft to ollamaChunkParallelismMaxDraft
            else -> openaiChunkParallelismDraft to openaiChunkParallelismMaxDraft
        }
        if (!isCurrentProvider) return stored
        val parallelism = parsePositiveInt(chunkParallelismField.text.trim(), stored.first)
        val parallelismMax = parsePositiveInt(chunkParallelismMaxField.text.trim(), stored.second)
            .coerceAtLeast(parallelism)
        return parallelism to parallelismMax
    }

    private fun showTechniqueDialog() {
        val dialog = TechniqueDialog(project, techniqueIds, selectedTechniques)
        if (dialog.showAndGet()) {
            setSelectedTechniques(dialog.selectedValues())
        }
    }

    private fun setSelectedTechniques(techniques: List<String>) {
        selectedTechniques.clear()
        selectedTechniques.addAll(techniques)
        updateTechniqueSummary()
        syncTechniquePreset()
    }

    private fun updateTechniqueSummary() {
        val count = selectedTechniques.size
        val preview = selectedTechniques.take(3).joinToString(", ")
        val suffix = if (count > 3) ", ..." else ""
        val label = if (count == 0) {
            "No techniques selected"
        } else {
            "Selected: $count (${preview}$suffix)"
        }
        techniqueSummaryLabel.text = label
        techniqueSummaryLabel.toolTipText = if (count == 0) null else selectedTechniques.joinToString(", ")
    }

    private fun syncTechniquePreset() {
        val preset = TechniquePreset.fromSelection(selectedTechniques, techniqueIds)
        isTechniquePresetSync = true
        techniquePresetBox.selectedItem = preset.label
        isTechniquePresetSync = false
    }

    private fun clearScanCache() {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            Messages.showWarningDialog(panel, "Project base path is not available.", "Scan The Planet")
            return
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

    private fun clearToken() {
        val provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
        settingsService.saveLlmToken(provider.cliValue, null)
        tokenField.text = ""
        updateTokenIndicator()
        Messages.showInfoMessage(panel, "LLM token cleared.", "Scan The Planet")
    }

    private fun updateTokenIndicator() {
        val provider = providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI
        ApplicationManager.getApplication().executeOnPooledThread {
            val hasToken = settingsService.hasLlmToken(provider.cliValue)
            ApplicationManager.getApplication().invokeLater {
                tokenStoredLabel.isVisible = hasToken
            }
        }
    }

    private fun parsePositiveInt(raw: String, fallback: Int): Int {
        val value = raw.toIntOrNull() ?: return fallback
        return value.coerceAtLeast(1)
    }

    private fun persistCurrentProviderModel() {
        when (providerBox.selectedItem as? LlmProvider ?: LlmProvider.OPENAI) {
            LlmProvider.OLLAMA -> {
                ollamaModelDraft = modelNameField.text.trim().ifBlank { OLLAMA_DEFAULT_MODEL }
            }
            else -> {
                openaiModelDraft = (openaiModelBox.selectedItem as? String).orEmpty().ifBlank { OPENAI_DEFAULT_MODEL }
            }
        }
    }

    private fun loadProviderModelField(provider: LlmProvider) {
        if (provider == LlmProvider.OPENAI) {
            openaiModelBox.selectedItem = openaiModelDraft.ifBlank { OPENAI_DEFAULT_MODEL }
        } else {
            modelNameField.text = ollamaModelDraft.ifBlank { OLLAMA_DEFAULT_MODEL }
        }
    }

    private fun loadTechniqueIds(): List<String> {
        val repo = SafeMcpRepository()
        return repo.listTechniqueIds()
    }

    private class TechniqueDialog(
        project: Project,
        private val techniqueIds: List<String>,
        private val selected: List<String>
    ) : DialogWrapper(project) {
        private val list = JBList(techniqueIds)

        init {
            title = "Select Techniques"
            list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            list.visibleRowCount = 10
            setSelectedIndices()
            init()
        }

        override fun createCenterPanel(): JComponent {
            return JBScrollPane(list)
        }

        fun selectedValues(): MutableList<String> = list.selectedValuesList.toMutableList()

        private fun setSelectedIndices() {
            val indices = selected.mapNotNull { technique ->
                techniqueIds.indexOf(technique).takeIf { it >= 0 }
            }
            if (indices.isNotEmpty()) {
                list.selectedIndices = indices.toIntArray()
            }
        }
    }

    private enum class TechniquePreset(val label: String) {
        ALL("All techniques"),
        CUSTOM("Custom")
        ;

        companion object {
            fun fromLabel(label: String?): TechniquePreset? = entries.firstOrNull { it.label == label }

            fun fromSelection(
                selected: List<String>,
                all: List<String>
            ): TechniquePreset {
                if (selected.isEmpty()) return CUSTOM
                if (selected.size == all.size && selected.toSet() == all.toSet()) return ALL
                return CUSTOM
            }
        }
    }

    private enum class BatchMode(val label: String, val size: Int?) {
        OFF("Off", null),
        SMALL("Small", 2),
        MEDIUM("Medium", 3),
        LARGE("Large", 5),
        CUSTOM("Custom", null)
        ;

        companion object {
            fun fromLabel(label: String?): BatchMode? = entries.firstOrNull { it.label == label }

            fun fromState(state: AsthaSettingsState): BatchMode {
                if (!state.batchEnabled) return OFF
                return entries.firstOrNull { it.size == state.batchSize } ?: CUSTOM
            }
        }
    }

    private enum class CleaningMode(val label: String) {
        OFF("Off"),
        SAME_MODEL("Same model"),
        CUSTOM("Custom")
        ;

        companion object {
            fun fromLabel(label: String?): CleaningMode? = entries.firstOrNull { it.label == label }

            fun fromState(state: AsthaSettingsState): CleaningMode {
                if (!state.cleanFindings) return OFF
                return if (state.cleaningModelName.isBlank()) SAME_MODEL else CUSTOM
            }
        }
    }

    companion object {
        private const val OPENAI_DEFAULT_MODEL = "gpt-5.2"
        private const val OLLAMA_DEFAULT_MODEL = "llama3.1"
        private const val MODEL_CARD_OPENAI = "openai"
        private const val MODEL_CARD_OTHER = "other"
    }
}
