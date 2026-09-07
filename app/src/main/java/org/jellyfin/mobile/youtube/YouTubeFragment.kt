package org.jellyfin.mobile.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.settings.LearningPage
import org.jellyfin.mobile.settings.LearningPalette
import org.jellyfin.mobile.settings.learningBackground
import org.jellyfin.mobile.settings.learningButton
import org.jellyfin.mobile.settings.learningDp
import org.jellyfin.mobile.settings.learningText
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.addFragment
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject
import timber.log.Timber

/** One small search/open page. Playback and all learning actions belong to PlayerFragment. */
class YouTubeFragment : Fragment() {
    private val api: ApiClient by inject()
    private val client = YouTubeClient()
    private lateinit var input: EditText
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var submit: Button
    private var entries = emptyList<YouTubeVideo>()
    private var operation: Job? = null
    private var initialConsumed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        initialConsumed = savedInstanceState?.getBoolean("shared_consumed") ?: initialConsumed
        savedInstanceState?.getString("results")?.let {
            entries = runCatching { YouTubeClient.json.decodeFromString<YouTubeSearch>(it).results }.getOrDefault(emptyList())
        }
        val page = LearningPage(context, "YouTube") { parentFragmentManager.popBackStack() }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.learningDp(20), context.learningDp(12), context.learningDp(20), context.learningDp(24))
        }
        input = field("Search or paste a YouTube URL", savedInstanceState?.getString("query") ?: arguments?.getString("query").orEmpty())
        content.addView(input, row())
        submit = learningButton(context, "Search / Open") { openInput(input.text.toString()) }
        content.addView(submit, row())
        address = field("YouTube resolver address", YouTubePreferences(context).address(api.baseUrl))
        address.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        address.visibility = View.GONE
        val server = learningButton(context, "Resolver address") {
            address.visibility = if (address.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        content.addView(server, row())
        content.addView(address, row())
        status = learningText(context, "Search for a video or open a link. Japanese captions are selected automatically.", 14f, true)
        content.addView(status, row())
        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, row())
        page.addView(NestedScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        render()
        val shared = arguments?.getString("query")
        if (!initialConsumed && !shared.isNullOrBlank()) {
            initialConsumed = true
            openInput(shared)
        }
    }

    private fun field(hintText: String, value: String) = EditText(requireContext()).apply {
        hint = hintText
        contentDescription = hintText
        setText(value)
        isSingleLine = true
        setTextColor(LearningPalette.get(context).text)
        setHintTextColor(LearningPalette.get(context).secondary)
        background = learningBackground(context, 16)
        setPadding(context.learningDp(16), context.learningDp(12), context.learningDp(16), context.learningDp(12))
    }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = requireContext().learningDp(12) }

    private fun openInput(value: String) {
        if (operation?.isActive == true) return
        val query = value.trim()
        if (query.isEmpty()) return
        val base = try {
            YouTubePreferences(requireContext()).saveAddress(address.text.toString())
        } catch (_: IllegalArgumentException) {
            address.visibility = View.VISIBLE
            status.text = "Enter the resolver address, for example http://192.168.1.10:8767."
            return
        }
        val id = YouTubeInput.videoId(query)
        if (id == null && (query.contains("://") || query.contains("youtu.be/"))) {
            status.text = "Paste a YouTube video link, not a playlist or channel."
            return
        }
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        operation = viewLifecycleOwner.lifecycleScope.launch {
            submit.isEnabled = false
            status.text = if (id == null) "Searching YouTube…" else "Opening video and Japanese captions…"
            try {
                if (id == null) {
                    entries = client.search(base, query)
                    render()
                    status.text = if (entries.isEmpty()) "No videos found." else "Choose a video."
                    Timber.i("YouTube search completed results=%d", entries.size)
                } else {
                    var playback = client.resolve(base, id)
                    if (playback.audioLanguageUnknown && playback.japaneseCaptions && confirmAudioLanguage()) {
                        playback = client.confirmJapanese(playback)
                    }
                    playback.warning?.let { requireContext().toast(it) }
                    Timber.i("YouTube resolved id=%s japanese_captions=%s", id, playback.japaneseCaptions)
                    val options = PlayOptions(emptyList(), null, 0, null, null, null, false,
                        YouTubeClient.json.encodeToString(playback))
                    parentFragmentManager.addFragment<PlayerFragment>(Bundle().apply {
                        putParcelable(Constants.EXTRA_MEDIA_PLAY_OPTIONS, options)
                    })
                    status.text = "Choose another video when you finish watching."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = if (error is java.io.IOException) error.message else "Could not read the YouTube response. Check the resolver version."
                Timber.w("YouTube operation failed type=%s", error.javaClass.simpleName)
            } finally {
                submit.isEnabled = true
            }
        }
    }

    private suspend fun confirmAudioLanguage(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Is the spoken audio Japanese?")
            .setMessage("YouTube has not labelled this audio track. Confirm Japanese to use it for sentence audio on your cards.")
            .setPositiveButton("Japanese") { _, _ -> if (continuation.isActive) continuation.resumeWith(Result.success(true)) }
            .setNegativeButton("Skip sentence audio") { _, _ -> if (continuation.isActive) continuation.resumeWith(Result.success(false)) }
            .setOnCancelListener { if (continuation.isActive) continuation.cancel() }.create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private fun render() {
        results.removeAllViews()
        for (entry in entries) {
            val duration = if (entry.durationMs > 0) " · ${entry.durationMs / 60000}:${(entry.durationMs / 1000 % 60).toString().padStart(2, '0')}" else ""
            val button = learningButton(requireContext(), "${entry.title}\n${entry.channel}$duration") { openInput(entry.id) }
            button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            button.setPadding(requireContext().learningDp(18), requireContext().learningDp(16), requireContext().learningDp(18), requireContext().learningDp(16))
            results.addView(button, row())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("shared_consumed", initialConsumed)
        outState.putString("query", input.text.toString())
        outState.putString("results", YouTubeClient.json.encodeToString(YouTubeSearch(entries)))
        super.onSaveInstanceState(outState)
    }
}
