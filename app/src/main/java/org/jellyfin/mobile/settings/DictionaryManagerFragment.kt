package org.jellyfin.mobile.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.player.subtitle.InstalledDictionary
import org.jellyfin.mobile.player.subtitle.YomitanDictionaryRepository
import org.jellyfin.mobile.utils.toast

class DictionaryManagerFragment : Fragment() {
    private val repository by lazy { YomitanDictionaryRepository.get(requireContext()) }
    private lateinit var list: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var importButton: MenuItem
    private lateinit var importHint: android.widget.TextView
    private var frequencyTab = false
    private var dictionaries = emptyList<InstalledDictionary>()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                importButton.isEnabled = false
                importHint.setText(R.string.pref_japanese_dictionary_importing)
                try {
                    val imported = repository.import(uri)
                    frequencyTab = imported.termCount == 0 && imported.frequencyCount > 0
                    reload()
                    requireContext().toast(R.string.pref_japanese_dictionary_imported)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    requireContext().toast(R.string.learning_import_failed)
                } finally {
                    importButton.isEnabled = true
                    importHint.setText(R.string.learning_import_hint)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        frequencyTab = savedInstanceState?.getBoolean("frequency_tab") ?: false
        val context = requireContext()
        val page = LearningPage(
            context,
            getString(R.string.pref_japanese_dictionary)
        ) { parentFragmentManager.popBackStack() }
        importButton = page.toolbar.menu.add(R.string.learning_import).apply {
            setIcon(R.drawable.ic_learning_add)
            icon?.setTint(LearningPalette.get(context).accent)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                true
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.learningDp(20), context.learningDp(12), context.learningDp(20), context.learningDp(24))
        }
        importHint = learningText(context, getString(R.string.learning_import_hint), 14f, true)
        content.addView(importHint, rowParams())
        tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(tabs, rowParams())
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, rowParams())
        val preferences = LookupPreferences(context)
        content.addView(learningText(context, getString(R.string.learning_popup_settings), 18f), rowParams())
        content.addView(
            LearningChoice(context, getString(R.string.learning_popup_theme)).apply {
                setItems(
                    listOf(
                        getString(R.string.learning_system_theme),
                        getString(R.string.learning_light_theme),
                        getString(R.string.learning_dark_theme)
                    ),
                    preferences.theme.ordinal
                )
                onSelected = { preferences.theme = LookupTheme.entries[it] }
            },
            rowParams()
        )
        page.addView(NestedScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        render()
        viewLifecycleOwner.lifecycleScope.launch { reload() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("frequency_tab", frequencyTab)
        super.onSaveInstanceState(outState)
    }

    private suspend fun reload() {
        dictionaries = repository.dictionaries()
        render()
    }

    private fun render() {
        val context = requireContext()
        tabs.removeAllViews()
        listOf(R.string.learning_term_tab, R.string.learning_frequency_tab).forEachIndexed { index, label ->
            tabs.addView(
                learningButton(context, getString(label)) {
                    frequencyTab = index == 1;
                    render()
                }.apply {
                    isSelected = frequencyTab == (index == 1)
                    background = learningBackground(context, 22, isSelected)
                },
                LinearLayout.LayoutParams(0, context.learningDp(48), 1f).apply { marginEnd = context.learningDp(4) }
            )
        }
        list.removeAllViews()
        val visible = dictionaries.filter { if (frequencyTab) it.frequencies > 0 else it.terms > 0 }
        if (visible.isEmpty()) {
            list.addView(
                learningText(
                    context,
                    getString(
                        if (frequencyTab) R.string.learning_empty_frequency else R.string.learning_empty_terms
                    ),
                    15f, true
                ),
                rowParams()
            )
        }
        visible.forEach { dictionary ->
            val count = if (frequencyTab) dictionary.frequencies else dictionary.terms
            list.addView(
                learningSwitch(
                    context,
                    dictionary.title + "\n" +
                        getString(R.string.learning_dictionary_entries, java.text.NumberFormat.getIntegerInstance().format(count)),
                    dictionary.enabled
                ) { enabled ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.setEnabled(dictionary.id, enabled)
                        reload()
                    }
                },
                rowParams()
            )
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = requireContext().learningDp(16) }
}
