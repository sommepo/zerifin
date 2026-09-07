package org.jellyfin.mobile.settings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.anki.ANKI_READ_WRITE_PERMISSION
import org.jellyfin.mobile.player.anki.AnkiAccess
import org.jellyfin.mobile.player.anki.AnkiCollectionItem
import org.jellyfin.mobile.player.anki.AnkiDroidGateway
import org.jellyfin.mobile.player.anki.AnkiFieldMapping
import org.jellyfin.mobile.player.anki.AnkiFieldSource
import org.jellyfin.mobile.player.anki.AnkiMiningPreset
import org.jellyfin.mobile.utils.toast

@Suppress("MagicNumber", "TooManyFunctions")
class AnkiMappingFragment : Fragment() {
    private val gateway by lazy { AnkiDroidGateway.get(requireContext()) }

    private lateinit var connectionCard: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var connectionAction: Button
    private lateinit var lastFailure: TextView
    private var connectionJob: Job? = null
    private lateinit var saveButton: Button
    private lateinit var loadingGroup: LinearLayout
    private lateinit var loadingMessage: TextView
    private lateinit var errorGroup: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var formScroll: NestedScrollView
    private lateinit var form: LinearLayout
    private lateinit var deckSpinner: LearningChoice
    private lateinit var modelSpinner: LearningChoice
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var tagsInput: EditText
    private lateinit var blockDuplicatesInput: SwitchCompat
    private lateinit var validationMessage: TextView

    private var decks: List<AnkiCollectionItem> = emptyList()
    private var models: List<AnkiCollectionItem> = emptyList()
    private var selectedDeck: AnkiCollectionItem? = null
    private var selectedModel: AnkiCollectionItem? = null
    private var preset: AnkiMiningPreset? = null
    private var currentFieldNames: List<String> = emptyList()
    private val currentMappings = linkedMapOf<String, AnkiFieldSource>()
    private val mappingDrafts = mutableMapOf<Long, List<AnkiFieldMapping>>()
    private var catalogLoadJob: Job? = null
    private var fieldLoadJob: Job? = null
    private var fieldLoadGeneration = 0
    private var loadedFieldsModelId: Long? = null

    private var restoredDeckId: Long? = null
    private var restoredDeckName: String? = null
    private var restoredModelId: Long? = null
    private var restoredModelName: String? = null
    private var restoredTags: String? = null
    private var restoredBlockDuplicates: Boolean? = null
    private var restoredMappings: List<AnkiFieldMapping>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredDeckId = savedInstanceState?.optionalLong(STATE_DECK_ID)
        restoredDeckName = savedInstanceState?.getString(STATE_DECK_NAME)
        restoredModelId = savedInstanceState?.optionalLong(STATE_MODEL_ID)
        restoredModelName = savedInstanceState?.getString(STATE_MODEL_NAME)
        restoredTags = savedInstanceState?.getString(STATE_TAGS)
        restoredBlockDuplicates = if (savedInstanceState?.containsKey(STATE_BLOCK_DUPLICATES) == true) {
            savedInstanceState.getBoolean(STATE_BLOCK_DUPLICATES)
        } else {
            null
        }
        restoredMappings = savedInstanceState?.getStringArrayList(STATE_MAPPING_FIELDS)
            ?.zip(savedInstanceState.getStringArrayList(STATE_MAPPING_SOURCES).orEmpty())
            ?.mapNotNull { (fieldName, sourceName) ->
                enumValues<AnkiFieldSource>()
                    .firstOrNull { it.name == sourceName }
                    ?.let { source -> AnkiFieldMapping(fieldName, source) }
            }
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) loadCatalog() else showError(getString(R.string.anki_permission_denied))
        checkConnection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = LearningPage(requireContext(), getString(R.string.anki_mapping_title)) {
            parentFragmentManager.popBackStack()
        }
        saveButton = learningButton(requireContext(), getString(R.string.anki_mapping_save)) { save() }.apply {
            isEnabled = false
        }
        page.toolbar.addView(saveButton, androidx.appcompat.widget.Toolbar.LayoutParams(-2, dp(48), Gravity.END))
        val connection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = learningBackground(context)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        connectionCard = connection
        connectionStatus = learningText(requireContext(), getString(R.string.anki_connection_checking))
        connection.addView(connectionStatus)
        connection.addView(learningText(requireContext(), getString(R.string.anki_connection_local), 13f, true))
        connectionAction = learningButton(requireContext(), getString(R.string.anki_connection_check)) { checkConnection() }
        connection.addView(connectionAction, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(8) })
        lastFailure = learningText(requireContext(), "", 13f, true)
        connection.addView(lastFailure)
        page.addView(connection, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(24), dp(8), dp(24), dp(12))
        })
        page.addView(buildBody(requireContext()), LinearLayout.LayoutParams(-1, 0, 1f))
        retryButton.setOnClickListener { requestCatalog() }
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { requestCatalog() }

    override fun onResume() {
        super.onResume()
        checkConnection()
    }

    private fun checkConnection() {
        connectionJob?.cancel()
        val failure = org.jellyfin.mobile.player.anki.AnkiMiningPreferences.get(requireContext()).lastFailure
        lastFailure.text = failure?.let { getString(R.string.anki_last_failure, it) }.orEmpty()
        lastFailure.isVisible = failure != null
        when (gateway.access()) {
            AnkiAccess.UNAVAILABLE -> {
                connectionStatus.setText(R.string.anki_connection_unavailable)
                connectionAction.setText(R.string.anki_connection_open)
                connectionAction.setOnClickListener {
                    val intent = requireContext().packageManager.getLaunchIntentForPackage("com.ichi2.anki")
                    if (intent != null) startActivity(intent) else requireContext().toast(R.string.anki_unavailable)
                }
            }
            AnkiAccess.PERMISSION_REQUIRED -> {
                connectionStatus.setText(R.string.anki_connection_permission)
                connectionAction.setText(R.string.anki_connection_grant)
                connectionAction.setOnClickListener { permissionRequest.launch(ANKI_READ_WRITE_PERMISSION) }
            }
            AnkiAccess.AVAILABLE -> {
                connectionStatus.setText(R.string.anki_connection_checking)
                connectionAction.setText(R.string.anki_connection_check)
                connectionAction.setOnClickListener { checkConnection() }
                connectionJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        gateway.getDecks()
                        gateway.getModels()
                        connectionStatus.setText(R.string.anki_connection_ready)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        connectionStatus.setText(R.string.anki_connection_failed)
                    }
                }
            }
        }
    }

    private fun requestCatalog() {
        when (gateway.access()) {
            AnkiAccess.AVAILABLE -> loadCatalog()
            AnkiAccess.PERMISSION_REQUIRED -> permissionRequest.launch(ANKI_READ_WRITE_PERMISSION)
            AnkiAccess.UNAVAILABLE -> showError(getString(R.string.anki_unavailable))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureCurrentMappings()
        selectedDeck?.id?.let { outState.putLong(STATE_DECK_ID, it) }
        selectedDeck?.name?.let { outState.putString(STATE_DECK_NAME, it) }
        selectedModel?.id?.let { outState.putLong(STATE_MODEL_ID, it) }
        selectedModel?.name?.let { outState.putString(STATE_MODEL_NAME, it) }
        if (::tagsInput.isInitialized) outState.putString(STATE_TAGS, tagsInput.text.toString())
        if (::blockDuplicatesInput.isInitialized) {
            outState.putBoolean(STATE_BLOCK_DUPLICATES, blockDuplicatesInput.isChecked)
        }
        val model = selectedModel
        val mappings = model?.id?.let(mappingDrafts::get)
            ?: restoredMappings?.takeIf {
                model != null && (restoredModelId == model.id || restoredModelName == model.name)
            }
        if (mappings != null) {
            outState.putStringArrayList(
                STATE_MAPPING_FIELDS,
                ArrayList(mappings.map(AnkiFieldMapping::fieldName)),
            )
            outState.putStringArrayList(
                STATE_MAPPING_SOURCES,
                ArrayList(mappings.map { mapping -> mapping.source.name }),
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        connectionJob?.cancel()
        catalogLoadJob?.cancel()
        fieldLoadJob?.cancel()
        super.onDestroyView()
    }

    private fun buildBody(context: Context): FrameLayout {
        val frame = FrameLayout(context).apply {
            minimumHeight = dp(180)
        }
        loadingGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        loadingGroup.addView(ProgressBar(context))
        loadingMessage = learningText(context, "").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setText(R.string.anki_mapping_loading)
        }
        loadingGroup.addView(loadingMessage)
        frame.addView(loadingGroup, matchParentWrapContent())

        errorGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            isVisible = false
        }
        errorMessage = learningText(context, "").apply {
            gravity = Gravity.CENTER
        }
        retryButton = learningButton(context, getString(R.string.anki_mapping_retry)) { requestCatalog() }
        errorGroup.addView(errorMessage, matchParentWrapContent())
        errorGroup.addView(retryButton, wrapContentWithTopMargin(12))
        frame.addView(errorGroup, matchParentWrapContent())

        form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(20))
        }
        formScroll = NestedScrollView(context).apply {
            isFillViewport = true
            isVisible = false
            addView(form, matchParentWrapContent())
        }
        frame.addView(
            formScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return frame
    }

    private fun loadCatalog() {
        catalogLoadJob?.cancel()
        renderLoading(R.string.anki_mapping_loading)
        catalogLoadJob = lifecycleScope.launch {
            val result = runCatching {
                coroutineScope {
                    val decksDeferred = async { gateway.getDecks() }
                    val modelsDeferred = async { gateway.getModels() }
                    Catalog(decksDeferred.await(), modelsDeferred.await(), gateway.loadPreset())
                }
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            result.onSuccess(::showCatalog).onFailure { error ->
                showError(getString(R.string.anki_mapping_error, error.userFacingMessage()))
            }
        }
    }

    private fun showCatalog(catalog: Catalog) {
        decks = catalog.decks
        models = catalog.models
        preset = catalog.preset
        when {
            decks.isEmpty() -> showError(getString(R.string.anki_mapping_no_decks))
            models.isEmpty() -> showError(getString(R.string.anki_mapping_no_models))
            else -> buildForm()
        }
    }

    private fun buildForm() {
        form.removeAllViews()
        (connectionCard.parent as? ViewGroup)?.removeView(connectionCard)
        form.addView(connectionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        addDescriptionAndSelectors()
        addFieldMappingSection()
        addMiningOptions()
        bindInitialSelections()
        loadingGroup.isVisible = false
        errorGroup.isVisible = false
        formScroll.isVisible = true
        loadFields(selectedModel ?: return)
    }

    private fun addDescriptionAndSelectors() {
        deckSpinner = LearningChoice(requireContext(), getString(R.string.anki_mapping_deck)).apply {
            setItems(decks.map(AnkiCollectionItem::name))
        }
        modelSpinner = LearningChoice(requireContext(), getString(R.string.anki_mapping_note_type)).apply {
            setItems(models.map(AnkiCollectionItem::name))
        }
        form.addView(deckSpinner, cardParams())
        form.addView(modelSpinner, cardParams())
    }

    private fun addFieldMappingSection() {
        form.addView(sectionLabel(R.string.anki_mapping_fields_title, topMargin = 18))
        form.addView(
            learningText(requireContext(), "").apply {
                setText(R.string.anki_mapping_media_fields_description)
                setPadding(0, dp(2), 0, dp(4))
            },
        )
        fieldsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        form.addView(fieldsContainer, matchParentWrapContent())
    }

    private fun addMiningOptions() {
        form.addView(sectionLabel(R.string.anki_mapping_tags, topMargin = 18))
        tagsInput = EditText(requireContext()).apply {
            hint = getString(R.string.anki_mapping_tags_hint)
            setSingleLine(true)
            setTextColor(LearningPalette.get(context).text)
            setHintTextColor(LearningPalette.get(context).secondary)
            background = learningBackground(context)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setText(restoredTags ?: preset?.tags?.sorted()?.joinToString(" ").orEmpty())
        }
        form.addView(tagsInput, matchParentWrapContent())

        blockDuplicatesInput = learningSwitch(
            requireContext(), getString(R.string.anki_mapping_block_duplicates),
            restoredBlockDuplicates ?: preset?.blockDuplicates ?: true
        ) {}
        form.addView(blockDuplicatesInput, cardParams())

        validationMessage = learningText(requireContext(), "").apply {
            setPadding(0, dp(8), 0, 0)
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        form.addView(validationMessage, matchParentWrapContent())
    }

    private fun bindInitialSelections() {
        val initialDeckId = restoredDeckId ?: preset?.deckId
        val initialDeckName = restoredDeckName ?: preset?.deckName
        val initialDeckIndex = preferredItemIndex(decks, initialDeckId, initialDeckName)
        selectedDeck = decks[initialDeckIndex]
        deckSpinner.setSelection(initialDeckIndex)
        deckSpinner.onSelected = { position ->
            selectedDeck = decks[position]
            hideValidation()
        }

        val initialModelId = restoredModelId ?: preset?.modelId
        val initialModelName = restoredModelName ?: preset?.modelName
        val initialModelIndex = preferredItemIndex(models, initialModelId, initialModelName)
        selectedModel = models[initialModelIndex]
        modelSpinner.setSelection(initialModelIndex)
        modelSpinner.onSelected = { position ->
            val model = models[position]
            if (model.id != selectedModel?.id) {
                captureCurrentMappings()
                selectedModel = model
                loadFields(model)
            }
            hideValidation()
        }
    }

    private fun loadFields(model: AnkiCollectionItem) {
        fieldLoadJob?.cancel()
        val generation = ++fieldLoadGeneration
        loadedFieldsModelId = null
        currentFieldNames = emptyList()
        currentMappings.clear()
        modelSpinner.isEnabled = false
        saveButton.isEnabled = false
        fieldsContainer.removeAllViews()
        fieldsContainer.addView(
            LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                addView(
                    ProgressBar(requireContext()).apply {
                        isIndeterminate = true
                    },
                    LinearLayout.LayoutParams(dp(28), dp(28)),
                )
                addView(
                    learningText(requireContext(), "").apply {
                        setText(R.string.anki_mapping_loading)
                        setPadding(dp(12), 0, 0, 0)
                    },
                )
            },
            matchParentWrapContent(),
        )
        fieldLoadJob = lifecycleScope.launch {
            val result = runCatching { gateway.getFields(model.id) }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            result
                .onSuccess { fieldNames ->
                    if (generation == fieldLoadGeneration && selectedModel?.id == model.id) {
                        showFields(model, fieldNames)
                    }
                }
                .onFailure { error ->
                    if (generation == fieldLoadGeneration) {
                        modelSpinner.isEnabled = true
                        fieldsContainer.removeAllViews()
                        fieldsContainer.addView(
                            learningText(requireContext(), "").apply {
                                text = getString(R.string.anki_mapping_error, error.userFacingMessage())
                                setPadding(0, dp(12), 0, dp(12))
                            },
                        )
                    }
                }
        }
    }

    private fun showFields(model: AnkiCollectionItem, fieldNames: List<String>) {
        loadedFieldsModelId = model.id
        modelSpinner.isEnabled = true
        currentFieldNames = fieldNames
        currentMappings.clear()
        fieldsContainer.removeAllViews()
        if (fieldNames.isEmpty()) {
            fieldsContainer.addView(
                learningText(requireContext(), "").apply {
                    setText(R.string.anki_mapping_no_fields)
                    setPadding(0, dp(12), 0, dp(12))
                },
            )
            saveButton.isEnabled = false
            return
        }

        val startingMappings = startingMappingsFor(model, fieldNames)
            .associateBy(AnkiFieldMapping::fieldName)
        val sourceValues = enumValues<AnkiFieldSource>().toList()
        fieldNames.forEach { fieldName ->
            val initialSource = startingMappings[fieldName]?.source ?: AnkiFieldSource.UNUSED
            currentMappings[fieldName] = initialSource
            val sourceSpinner = LearningChoice(requireContext(), fieldName).apply {
                setItems(sourceValues.map(::sourceLabel), sourceValues.indexOf(initialSource).coerceAtLeast(0))
                onSelected = { position ->
                    currentMappings[fieldName] = sourceValues[position]
                    hideValidation()
                }
            }
            fieldsContainer.addView(sourceSpinner, cardParams())
        }
        saveButton.isEnabled = true
    }

    private fun startingMappingsFor(
        model: AnkiCollectionItem,
        fieldNames: List<String>,
    ): List<AnkiFieldMapping> {
        mappingDrafts[model.id]?.let { return it }
        if (restoredModelId == model.id || restoredModelName == model.name) {
            restoredMappings?.let { return it }
        }
        if (preset?.modelId == model.id || preset?.modelName == model.name) return preset?.fields.orEmpty()
        return gateway.suggestMappings(fieldNames)
    }

    private fun captureCurrentMappings() {
        val modelId = selectedModel?.id ?: return
        if (loadedFieldsModelId != modelId) return
        if (currentFieldNames.isEmpty()) return
        mappingDrafts[modelId] = currentFieldNames.map { fieldName ->
            AnkiFieldMapping(fieldName, currentMappings[fieldName] ?: AnkiFieldSource.UNUSED)
        }
    }

    private fun save() {
        val deck = selectedDeck ?: return
        val model = selectedModel ?: return
        val firstField = currentFieldNames.firstOrNull() ?: return
        if (currentMappings[firstField] == null || currentMappings[firstField] == AnkiFieldSource.UNUSED) {
            showValidation(getString(R.string.anki_mapping_first_field_required, firstField))
            return
        }
        if (currentMappings[firstField]?.isMedia == true) {
            showValidation(getString(R.string.anki_mapping_first_field_text, firstField))
            return
        }
        val mappings = currentFieldNames.map { fieldName ->
            AnkiFieldMapping(fieldName, currentMappings[fieldName] ?: AnkiFieldSource.UNUSED)
        }
        val tags = tagsInput.text.toString()
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .toSet()
        val newPreset = AnkiMiningPreset(
            deckId = deck.id,
            deckName = deck.name,
            modelId = model.id,
            modelName = model.name,
            fields = mappings,
            tags = tags,
            blockDuplicates = blockDuplicatesInput.isChecked,
        )
        runCatching { gateway.savePreset(newPreset) }
            .onSuccess {
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply { putBoolean(RESULT_SAVED, true) },
                )
                requireContext().toast(R.string.anki_mapping_saved)
                parentFragmentManager.popBackStack()
            }
            .onFailure { error ->
                showValidation(getString(R.string.anki_mapping_error, error.userFacingMessage()))
            }
    }

    private fun renderLoading(messageRes: Int) {
        if (!::loadingGroup.isInitialized) return
        loadingMessage.setText(messageRes)
        loadingGroup.isVisible = true
        errorGroup.isVisible = false
        formScroll.isVisible = false
        if (::saveButton.isInitialized) {
            saveButton.isEnabled = false
        }
    }

    private fun showError(message: String) {
        loadingGroup.isVisible = false
        formScroll.isVisible = false
        errorMessage.text = message
        errorGroup.isVisible = true
        saveButton.isEnabled = false
    }

    private fun showValidation(message: String) {
        validationMessage.text = message
        validationMessage.isVisible = true
    }

    private fun hideValidation() {
        if (::validationMessage.isInitialized) validationMessage.isVisible = false
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }

    private fun sectionLabel(labelRes: Int, topMargin: Int): TextView =
        learningText(requireContext(), "").apply {
            setText(labelRes)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(LearningPalette.get(context).text)
            setPadding(0, dp(topMargin), 0, 0)
        }

    private fun sourceLabel(source: AnkiFieldSource): String = getString(
        when (source) {
            AnkiFieldSource.UNUSED -> R.string.anki_field_source_unused
            AnkiFieldSource.WORD -> R.string.anki_field_source_word
            AnkiFieldSource.READING -> R.string.anki_field_source_reading
            AnkiFieldSource.DEFINITION -> R.string.anki_field_source_definition
            AnkiFieldSource.SUBTITLE -> R.string.anki_field_source_subtitle
            AnkiFieldSource.ENGLISH_SUBTITLE -> R.string.anki_field_source_english_subtitle
            AnkiFieldSource.SENTENCE_AUDIO -> R.string.anki_field_source_sentence_audio
            AnkiFieldSource.WORD_AUDIO -> R.string.anki_field_source_word_audio
            AnkiFieldSource.IMAGE -> R.string.anki_field_source_image
            AnkiFieldSource.SOURCE_TITLE -> R.string.anki_field_source_source_title
            AnkiFieldSource.FREQUENCY -> R.string.anki_field_source_frequency
        },
    )

    private fun Throwable.userFacingMessage(): String = message?.takeIf(String::isNotBlank)
        ?: javaClass.simpleName

    private fun preferredItemIndex(
        items: List<AnkiCollectionItem>,
        preferredId: Long?,
        preferredName: String?,
    ): Int {
        val idMatch = items.indexOfFirst { item -> item.id == preferredId }
        if (idMatch >= 0) return idMatch
        return items.indexOfFirst { item -> item.name == preferredName }.coerceAtLeast(0)
    }

    private fun matchParentWrapContent() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun wrapContentWithTopMargin(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = dp(topMargin)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

    private data class Catalog(
        val decks: List<AnkiCollectionItem>,
        val models: List<AnkiCollectionItem>,
        val preset: AnkiMiningPreset?,
    )

    companion object {
        const val TAG = "anki_mapping_dialog"
        const val RESULT_KEY = "anki_mapping_result"
        const val RESULT_SAVED = "saved"

        private const val STATE_DECK_ID = "deck_id"
        private const val STATE_DECK_NAME = "deck_name"
        private const val STATE_MODEL_ID = "model_id"
        private const val STATE_MODEL_NAME = "model_name"
        private const val STATE_TAGS = "tags"
        private const val STATE_BLOCK_DUPLICATES = "block_duplicates"
        private const val STATE_MAPPING_FIELDS = "mapping_fields"
        private const val STATE_MAPPING_SOURCES = "mapping_sources"
    }
}
