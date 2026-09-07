package org.jellyfin.mobile.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences

/** Direct, compact settings for the learning controls used during playback. */
class LearningSettingsFragment : Fragment() {
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

        page.addView(
            NestedScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return page
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
