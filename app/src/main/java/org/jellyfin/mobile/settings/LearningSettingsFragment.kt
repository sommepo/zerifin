package org.jellyfin.mobile.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.subtitle.KnownWordsRepository
import org.jellyfin.mobile.update.UpdatePreferences
import org.jellyfin.mobile.update.UpdatePrompt
import org.jellyfin.mobile.update.UpdateSource
import java.io.IOException

/** Direct, compact settings for the learning controls used during playback. */
class LearningSettingsFragment : Fragment() {
    private var knownWordStatus: TextView? = null
    private val knownWordListPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importKnownWords(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val learning = LookupPreferences(context)
        val app = AppPreferences(context)
        val page = LearningPage(context, getString(R.string.learning_general_title)) {
            parentFragmentManager.popBackStack()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.learningDp(20), context.learningDp(12), context.learningDp(20), context.learningDp(24))
        }

        content.addView(section(R.string.learning_playback_section), row())
        content.addView(
            learningSwitch(context, getString(R.string.learning_pause_screen_tap), learning.pauseOnScreenTap) {
                learning.pauseOnScreenTap = it
            },
            row(),
        )
        content.addView(timeoutChoice(learning), row())

        content.addView(section(R.string.learning_subtitle_section), row())
        content.addView(
            learningSwitch(context, getString(R.string.learning_pause_lookup), learning.pauseOnLookup) {
                learning.pauseOnLookup = it
            },
            row(),
        )
        content.addView(
            learningSwitch(context, getString(R.string.pref_exoplayer_direct_play_ass), app.exoPlayerDirectPlayAss) {
                app.exoPlayerDirectPlayAss = it
            },
            row(),
        )

        content.addView(
            learningSwitch(context, getString(R.string.learning_blur_area_per_show), learning.blurAreaPerShow) {
                learning.blurAreaPerShow = it
            },
            row(),
        )
        content.addView(learningText(context, getString(R.string.learning_blur_gesture_hint), 14f, true), row())

        val subtitleSettings = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
        if (subtitleSettings.resolveActivity(context.packageManager) != null) {
            content.addView(
                learningButton(context, getString(R.string.pref_subtitle_style)) { startActivity(subtitleSettings) },
                row(),
            )
        }
        content.addView(
            learningText(context, getString(R.string.learning_drag_controls_hint), 14f, true),
            row(),
        )

        addKnownWordSettings(content, learning)
        addUpdateSettings(content)

        page.addView(
            NestedScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return page
    }

    override fun onDestroyView() {
        knownWordStatus = null
        super.onDestroyView()
    }

    private fun addKnownWordSettings(content: LinearLayout, learning: LookupPreferences) {
        val context = requireContext()
        content.addView(section(R.string.learning_known_words_section), row())
        content.addView(
            learningSwitch(context, getString(R.string.learning_known_words), learning.highlightKnownWords) {
                learning.highlightKnownWords = it
            },
            row(),
        )
        content.addView(learningText(context, getString(R.string.learning_known_words_summary), 14f, true), row())
        content.addView(
            learningButton(context, getString(R.string.learning_known_words_import)) {
                knownWordListPicker.launch(arrayOf("text/*"))
            },
            row(),
        )
        content.addView(
            learningButton(context, getString(R.string.learning_known_words_clear)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    KnownWordsRepository.get(context).clearList()
                    refreshKnownWordStatus()
                }
            },
            row(),
        )
        knownWordStatus = learningText(context, "", 14f, true)
        content.addView(knownWordStatus, row())
        refreshKnownWordStatus()
    }

    private fun refreshKnownWordStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = KnownWordsRepository.get(requireContext()).importedCount()
            knownWordStatus?.text = if (count == 0) {
                getString(R.string.learning_known_words_none_imported)
            } else {
                getString(R.string.learning_known_words_imported, count)
            }
        }
    }

    private fun importKnownWords(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                KnownWordsRepository.get(requireContext()).importList(uri)
            } catch (_: IOException) {
                context?.let { Toast.makeText(it, R.string.learning_known_words_import_failed, Toast.LENGTH_SHORT).show() }
            } catch (_: SecurityException) {
                context?.let { Toast.makeText(it, R.string.learning_known_words_import_failed, Toast.LENGTH_SHORT).show() }
            }
            refreshKnownWordStatus()
        }
    }

    private fun addUpdateSettings(content: LinearLayout) {
        val context = requireContext()
        val updates = UpdatePreferences(context)
        content.addView(section(R.string.update_section), row())
        content.addView(
            learningSwitch(context, getString(R.string.update_check_enabled), updates.enabled) { updates.enabled = it },
            row(),
        )
        val addressButton = learningButton(context, updateAddressLabel(updates)) {}
        addressButton.setOnClickListener {
            editUpdateAddress(updates) { addressButton.text = updateAddressLabel(updates) }
        }
        addressButton.isVisible = updates.source == UpdateSource.CUSTOM
        content.addView(
            LearningChoice(context, getString(R.string.update_source)).apply {
                setItems(
                    listOf(getString(R.string.update_source_github), getString(R.string.update_source_custom)),
                    updates.source.ordinal,
                )
                onSelected = {
                    updates.source = UpdateSource.entries[it]
                    addressButton.isVisible = updates.source == UpdateSource.CUSTOM
                }
            },
            row(),
        )
        content.addView(addressButton, row())
        content.addView(
            learningButton(context, getString(R.string.update_check_now)) {
                val activity = requireActivity()
                viewLifecycleOwner.lifecycleScope.launch { UpdatePrompt.checkNow(activity) }
            },
            row(),
        )
    }

    private fun updateAddressLabel(updates: UpdatePreferences): String =
        "${getString(R.string.update_custom_url)}: ${updates.customUrl.ifEmpty { "—" }}"

    private fun editUpdateAddress(updates: UpdatePreferences, changed: () -> Unit) {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(updates.customUrl)
            hint = getString(R.string.update_custom_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.update_custom_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updates.customUrl = input.text.toString()
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun section(label: Int) = learningText(requireContext(), getString(label), 18f)

    private fun timeoutChoice(learning: LookupPreferences) =
        LearningChoice(requireContext(), getString(R.string.learning_controls_timeout)).apply {
            setItems(
                listOf(
                    getString(R.string.learning_timeout_2_5_seconds),
                    getString(R.string.learning_timeout_5_seconds),
                    getString(R.string.learning_timeout_10_seconds),
                    getString(R.string.learning_timeout_always),
                ),
                learning.learningControlsTimeout.ordinal,
            )
            onSelected = { learning.learningControlsTimeout = LearningControlsTimeout.entries[it] }
        }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = requireContext().learningDp(16)
    }
}
