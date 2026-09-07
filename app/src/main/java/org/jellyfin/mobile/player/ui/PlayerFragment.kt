package org.jellyfin.mobile.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.SQLException
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.anki.ANKI_READ_WRITE_PERMISSION
import org.jellyfin.mobile.player.anki.AnkiAccess
import org.jellyfin.mobile.player.anki.AnkiDroidGateway
import org.jellyfin.mobile.player.anki.AnkiFieldSource
import org.jellyfin.mobile.player.anki.AnkiMediaFile
import org.jellyfin.mobile.player.anki.AnkiMineResult
import org.jellyfin.mobile.player.anki.AnkiMiningSnapshot
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.mining.MiningFrameCapture
import org.jellyfin.mobile.player.mining.MiningMediaContext
import org.jellyfin.mobile.player.mining.PlayerMiningMedia
import org.jellyfin.mobile.player.subtitle.DictionaryEntry
import org.jellyfin.mobile.player.subtitle.DictionaryLookupResult
import org.jellyfin.mobile.player.subtitle.InteractiveSubtitleCuePolicy
import org.jellyfin.mobile.player.subtitle.JapaneseTextCandidateGenerator
import org.jellyfin.mobile.player.subtitle.JapaneseWordAudioRepository
import org.jellyfin.mobile.player.subtitle.SubtitleMiningState
import org.jellyfin.mobile.player.subtitle.SubtitleTap
import org.jellyfin.mobile.player.subtitle.YomitanDictionaryRepository
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.settings.LookupPreferences
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.DEFAULT_CONTROLS_TIMEOUT_MS
import org.jellyfin.mobile.utils.Constants.PIP_MAX_RATIONAL
import org.jellyfin.mobile.utils.Constants.PIP_MIN_RATIONAL
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.math.max
import androidx.media3.ui.R as Media3R

@Suppress("LargeClass", "TooManyFunctions")
class PlayerFragment : Fragment(), BackPressInterceptor {
    private val appPreferences: AppPreferences by inject()
    private val assHandler: AssHandler by inject()
    private val viewModel: PlayerViewModel by viewModels()
    private var _playerBinding: FragmentPlayerBinding? = null
    private val playerBinding: FragmentPlayerBinding get() = _playerBinding!!
    private val playerView: PlayerView get() = playerBinding.playerView
    private val playerOverlay: View get() = playerBinding.playerOverlay
    private val loadingIndicator: View get() = playerBinding.loadingIndicator
    private var _playerControlsBinding: ExoPlayerControlViewBinding? = null
    private val playerControlsBinding: ExoPlayerControlViewBinding get() = _playerControlsBinding!!
    private val playerControlsView: View get() = playerControlsBinding.root
    private val toolbar: Toolbar get() = playerControlsBinding.toolbar
    private val fullscreenSwitcher: ImageButton get() = playerControlsBinding.fullscreenSwitcher
    private var playerMenus: PlayerMenus? = null
    private val dictionaryRepository by lazy { YomitanDictionaryRepository.get(requireContext()) }
    private val ankiGateway by lazy { AnkiDroidGateway.get(requireContext()) }
    private val apiClient: ApiClient by inject()
    private val miningMedia by lazy { PlayerMiningMedia(requireContext(), apiClient) }
    private val wordAudio by lazy { JapaneseWordAudioRepository(requireContext()) }
    private var activeMiningContext: MiningMediaContext? = null
    private var activeSourceTitle: String? = null
    private var duplicateJob: Job? = null
    private var wordAudioJob: Job? = null
    private var wordAudioPlayer: MediaPlayer? = null
    private val interactiveSubtitleCodecs = setOf("srt", "subrip", "vtt", "webvtt")
    private var subtitlePlayer: Player? = null
    private var lookupJob: Job? = null
    private var lookupPlayer: Player? = null
    private var playerPausedForLookup: Player? = null
    private var pauseForCurrentLookup = true
    private var previousControllerAutoShow: Boolean? = null
    private var englishVisible = false
    private var englishJob: Job? = null
    private var subtitleSeekJob: Job? = null
    private var lookupRequestId = 0
    private var activeSubtitleTap: SubtitleTap? = null
    private var ankiMiningJob: Job? = null
    private var pendingAnkiMining: PendingAnkiMining? = null
    private var subtitleControllerVisible = false
    private var interactiveSubtitleBottomPaddingFraction =
        SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    private var interactiveSubtitleHost: View? = null
    private val interactiveSubtitleHostLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateInteractiveSubtitleBottomMargin(subtitleControllerVisible)
        }
    private val interactiveSubtitleOverlayLayoutListener =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.post(::updateActiveSubtitleLookupAnchor)
        }

    private val ankiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingAnkiMining
            pendingAnkiMining = null
            if ((granted || ankiGateway.access() == AnkiAccess.AVAILABLE) && pending != null) {
                startAnkiMining(pending)
            } else if (pending != null) {
                _playerBinding?.subtitleLookupPopup?.setMiningState(
                    pending.entry,
                    SubtitleMiningState.ERROR,
                )
                context?.toast(R.string.subtitle_anki_permission_denied)
            }
        }

    private val subtitleCueListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            updateInteractiveSubtitles(cueGroup.cues)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                subtitleSeekJob?.cancel()
                subtitleSeekJob = null
                _playerBinding?.previousSubtitleButton?.isEnabled = true
                hideEnglishSubtitle()
            } else if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) && englishVisible) showEnglishSubtitle()
            if (
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) &&
                lookupPlayer === player
            ) {
                dismissSubtitleLookupWithoutResume()
                return
            }
            if (lookupPlayer === player && pauseForCurrentLookup && player.playWhenReady) {
                player.pause()
            }
        }
    }

    private val controllerVisibilityListener = PlayerView.ControllerVisibilityListener { visibility ->
        updateInteractiveSubtitleBottomMargin(visibility == View.VISIBLE)
        updateEnglishButton()
    }

    private lateinit var playerFullscreenHelper: PlayerFullscreenHelper
    lateinit var playerLockScreenHelper: PlayerLockScreenHelper
    lateinit var playerGestureHelper: PlayerGestureHelper

    private val currentVideoStream: MediaStream?
        get() = viewModel.mediaSourceOrNull?.selectedVideoStream

    /**
     * Listener that watches the current device orientation.
     * It makes sure that the orientation sensor can still be used (if enabled)
     * after toggling the orientation through the fullscreen button.
     *
     * If the requestedOrientation was reset directly after setting it in the fullscreenSwitcher click handler,
     * the orientation would get reverted before the user had any chance to rotate the device to the desired position.
     */
    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(requireActivity()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAnkiMining = savedInstanceState?.restorePendingAnkiMining()

        val window = requireActivity().window
        playerFullscreenHelper = PlayerFullscreenHelper(window)

        // Observe ViewModel
        viewModel.player.observe(this) { player ->
            if (subtitlePlayer !== player) {
                dismissSubtitleLookupWithoutResume()
            }
            subtitlePlayer?.removeListener(subtitleCueListener)
            playerView.player = player
            subtitlePlayer = player
            player?.addListener(subtitleCueListener)
            updateInteractiveSubtitles(player?.currentCues?.cues.orEmpty())
            // Automatically close fragment, unless we're in PiP mode
            if (player == null && !(AndroidVersion.isAtLeastN && requireActivity().isInPictureInPictureMode)) {
                parentFragmentManager.popBackStack()
            }
        }
        viewModel.playerState.observe(this) { playerState ->
            val isPlaying = viewModel.playerOrNull?.isPlaying == true
            requireActivity().window.keepScreenOn = isPlaying
            loadingIndicator.isVisible = playerState == Player.STATE_BUFFERING
        }
        viewModel.decoderType.observe(this) { type ->
            playerMenus?.updatedSelectedDecoder(type)
        }
        viewModel.error.observe(this) { message ->
            val safeMessage = message.ifEmpty { requireContext().getString(R.string.player_error_unspecific_exception) }
            requireContext().toast(safeMessage)
        }
        viewModel.queueManager.currentMediaSource.observe(this) { mediaSource ->
            if (mediaSource.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            // Update title and player menus
            toolbar.title = mediaSource.getName(requireContext())
            playerMenus?.onQueueItemChanged(mediaSource, viewModel.queueManager.hasNext())
        }

        // Handle fragment arguments, extract playback options and start playback
        lifecycleScope.launch {
            val context = requireContext()
            val playOptions = requireArguments().getParcelableCompat<PlayOptions>(Constants.EXTRA_MEDIA_PLAY_OPTIONS)
            if (playOptions == null) {
                context.toast(R.string.player_error_invalid_play_options)
                return@launch
            }
            when (viewModel.queueManager.initializePlaybackQueue(playOptions)) {
                is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                null -> Unit // success
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAnkiMining?.let { pending ->
            outState.putString(STATE_ANKI_WORD, pending.snapshot.word)
            outState.putString(STATE_ANKI_READING, pending.snapshot.reading)
            outState.putString(STATE_ANKI_DEFINITION, pending.snapshot.definition)
            outState.putString(STATE_ANKI_SUBTITLE, pending.snapshot.subtitle)
            pending.snapshot.subtitleHighlightStart?.let { outState.putInt(STATE_ANKI_HIGHLIGHT_START, it) }
            pending.snapshot.subtitleHighlightLength?.let { outState.putInt(STATE_ANKI_HIGHLIGHT_LENGTH, it) }
            outState.putString(STATE_ANKI_SOURCE, pending.snapshot.sourceTitle)
            outState.putString(STATE_ANKI_FREQUENCY, pending.snapshot.frequency)
            outState.putString(STATE_ANKI_CONTEXT, pending.mediaContext?.let { Json.encodeToString(it) })
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _playerBinding = FragmentPlayerBinding.inflate(layoutInflater)
        _playerControlsBinding = ExoPlayerControlViewBinding.bind(playerBinding.root.findViewById(R.id.player_controls))
        return playerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpInteractiveSubtitleOverlay()

        // Insets handling
        ViewCompat.setOnApplyWindowInsetsListener(playerBinding.root) { _, insets ->
            playerFullscreenHelper.onWindowInsetsChanged(insets)

            val systemInsets = when {
                AndroidVersion.isAtLeastR -> insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                else -> insets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            if (playerFullscreenHelper.isFullscreen) {
                playerView.setPadding(0)
                playerControlsView.updatePadding(
                    left = max(insets.displayCutout?.safeInsetLeft ?: 0, systemInsets.left),
                    top = max(insets.displayCutout?.safeInsetTop ?: 0, systemInsets.top),
                    right = max(insets.displayCutout?.safeInsetRight ?: 0, systemInsets.right),
                    bottom = max(insets.displayCutout?.safeInsetBottom ?: 0, systemInsets.bottom),
                )
            } else {
                playerView.updatePadding(
                    left = systemInsets.left,
                    top = systemInsets.top,
                    right = systemInsets.right,
                    bottom = systemInsets.bottom,
                )
                playerControlsView.setPadding(0) // Padding is handled by PlayerView
            }
            playerOverlay.updatePadding(
                left = systemInsets.left,
                top = systemInsets.top,
                right = systemInsets.right,
                bottom = systemInsets.bottom,
            )
            updateInteractiveSubtitleBottomMargin(playerView.isControllerFullyVisible)

            // Update fullscreen switcher icon
            val fullscreenDrawable = when {
                playerFullscreenHelper.isFullscreen -> R.drawable.ic_fullscreen_exit_white_32dp
                else -> R.drawable.ic_fullscreen_enter_white_32dp
            }
            fullscreenSwitcher.setImageResource(fullscreenDrawable)

            insets
        }

        // Handle toolbar back button
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        // Create playback menus
        playerMenus = PlayerMenus(this, playerBinding, playerControlsBinding)

        // Set controller timeout
        suppressControllerAutoHide(false)

        // Disable controller animations
        playerView.setControllerAnimationEnabled(false)

        if (appPreferences.exoPlayerDirectPlayAss) {
            playerView.subtitleView?.apply {
                addView(AssSubtitleView(this.context, assHandler))
            }
        }

        playerLockScreenHelper = PlayerLockScreenHelper(this, playerBinding, orientationListener)
        playerGestureHelper = PlayerGestureHelper(this, playerBinding, playerLockScreenHelper)
        playerBinding.interactiveSubtitleOverlay.onPlayerGesture = ::onSubtitlePlayerGesture

        // Handle fullscreen switcher
        fullscreenSwitcher.setOnClickListener {
            toggleFullscreen()
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()

        // When returning from another app, fullscreen mode for landscape orientation has to be set again
        if (isLandscape()) {
            playerFullscreenHelper.enableFullscreen()
        }

        // If playback ended during picture in picture we'll return to the main app when PiP is closed
        if (viewModel.playerOrNull == null) {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Handle current orientation and update fullscreen state and switcher icon
     */
    private fun updateFullscreenState(configuration: Configuration) {
        // Do not handle any orientation changes while being in Picture-in-Picture mode
        if (AndroidVersion.isAtLeastN && activity?.isInPictureInPictureMode == true) {
            return
        }

        when {
            isLandscape(configuration) -> {
                // Landscape orientation is always fullscreen
                playerFullscreenHelper.enableFullscreen()
            }
            currentVideoStream?.isLandscape != false -> {
                // Disable fullscreen for landscape video in portrait orientation
                playerFullscreenHelper.disableFullscreen()
            }
        }
    }

    /**
     * Toggle fullscreen.
     *
     * If playing a portrait video, this just hides the status and navigation bars.
     * For landscape videos, additionally the screen gets rotated.
     */
    private fun toggleFullscreen() {
        val videoTrack = currentVideoStream
        if (videoTrack == null || videoTrack.isLandscape) {
            val current = resources.configuration.orientation
            requireActivity().requestedOrientation = when (current) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // No need to call playerFullscreenHelper in this case,
            // since the configuration change triggers updateFullscreenState,
            // which does it for us.
        } else {
            playerFullscreenHelper.toggleFullscreen()
        }
    }

    /**
     * If true, the player controls will show indefinitely
     */
    fun suppressControllerAutoHide(suppress: Boolean) {
        playerView.controllerShowTimeoutMs = if (suppress) -1 else DEFAULT_CONTROLS_TIMEOUT_MS
    }

    fun isLandscape(configuration: Configuration = resources.configuration) =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun onRewind() = viewModel.rewind()

    fun onFastForward() = viewModel.fastForward()

    fun onSeekByOffset(offsetMs: Long) = viewModel.seekByOffset(offsetMs)

    fun onPreviousChapter() = viewModel.previousChapter()

    fun onNextChapter() = viewModel.nextChapter()

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onAudioTrackSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectAudioTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onSubtitleSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectSubtitleTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * Toggle subtitles, selecting the first by [MediaStream.index] if there are multiple.
     *
     * @return true if subtitles are enabled now, false if not
     */
    fun toggleSubtitles(callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.trackSelectionHelper.toggleSubtitles())
    }

    fun onBitrateChanged(bitrate: Int?, callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.changeBitrate(bitrate))
    }

    /**
     * @return true if the playback speed was changed
     */
    fun onSpeedSelected(speed: Float): Boolean {
        return viewModel.setPlaybackSpeed(speed)
    }

    fun onPressSpeedUp(isPressing: Boolean): Boolean {
        return viewModel.setPressSpeedUp(isPressing, Constants.HOLD_SPEEDUP_MULTIPLIER)
    }

    fun onDecoderSelected(type: DecoderType) {
        viewModel.updateDecoderType(type)
    }

    fun onSkipToPrevious() {
        viewModel.skipToPrevious()
    }

    fun onSkipToNext() {
        viewModel.skipToNext()
    }

    fun onSkipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        viewModel.skipMediaSegment(mediaSegmentDto)
    }

    fun onPopupDismissed() {
        if (!AndroidVersion.isAtLeastR) {
            updateFullscreenState(resources.configuration)
        }
    }

    private fun setUpInteractiveSubtitleOverlay() {
        val overlay = playerBinding.interactiveSubtitleOverlay
        overlay.addOnLayoutChangeListener(interactiveSubtitleOverlayLayoutListener)
        playerBinding.subtitleLookupPopup.onDismissRequested = {
            finishSubtitleLookup(resumePlayback = true)
        }
        playerBinding.subtitleLookupBackdrop.setOnClickListener {
            playerView.hideController()
            finishSubtitleLookup(resumePlayback = true)
        }
        playerBinding.subtitleLookupPopup.onMineRequested = ::onSubtitleMineRequested
        playerBinding.subtitleLookupPopup.onAudioRequested = ::onWordAudioRequested
        playerBinding.englishSubtitleButton.setOnClickListener {
            if (englishVisible) hideEnglishSubtitle() else showEnglishSubtitle()
        }
        playerBinding.previousSubtitleButton.setOnClickListener { seekToPreviousSubtitle() }
        playerView.findViewById<ViewGroup>(Media3R.id.exo_content_frame)?.let { subtitleHost ->
            interactiveSubtitleHost?.removeOnLayoutChangeListener(
                interactiveSubtitleHostLayoutListener,
            )
            interactiveSubtitleHost = subtitleHost
            subtitleHost.addOnLayoutChangeListener(interactiveSubtitleHostLayoutListener)
            overlay.setSubtitleViewport(subtitleHost)
        }
        // Keep glyph lookup available while controls are hidden or locked. This view lives in
        // player_overlay, above PlayerView, so the controller surface cannot steal its first tap.
        overlay.isSubtitleInteractionEnabled = { _playerBinding != null }
        overlay.onSubtitleTapped = ::onSubtitleTapped
        playerView.setControllerVisibilityListener(controllerVisibilityListener)
        updateInteractiveSubtitleBottomMargin(playerView.isControllerFullyVisible)
    }

    private fun updateInteractiveSubtitleBottomMargin(controllerVisible: Boolean) {
        subtitleControllerVisible = controllerVisible
        val binding = _playerBinding ?: return
        val overlay = binding.interactiveSubtitleOverlay
        overlay.post {
            if (_playerBinding?.interactiveSubtitleOverlay !== overlay) return@post

            val layoutParams = overlay.layoutParams as? ViewGroup.MarginLayoutParams ?: return@post
            val bottomMargin = calculateInteractiveSubtitleBottomMargin(binding)
            val subtitleWidth = interactiveSubtitleHost?.width?.takeIf { it > 0 }
                ?: ViewGroup.LayoutParams.MATCH_PARENT
            if (layoutParams.bottomMargin != bottomMargin || layoutParams.width != subtitleWidth) {
                layoutParams.bottomMargin = bottomMargin
                layoutParams.width = subtitleWidth
                overlay.layoutParams = layoutParams
            }
        }
    }

    private fun onSubtitleMineRequested(entry: DictionaryEntry) {
        val subtitle = activeSubtitleTap?.subtitleText ?: return
        val binding = _playerBinding ?: return
        val pending = PendingAnkiMining(
            entry = entry,
            snapshot = miningSnapshot(entry, subtitle),
            mediaContext = activeMiningContext,
        )

        if (ankiGateway.loadPreset() == null) {
            binding.subtitleLookupPopup.setMiningState(entry, SubtitleMiningState.ERROR)
            requireContext().toast(R.string.subtitle_anki_setup_required)
            return
        }
        if (ankiMiningJob?.isActive == true || pendingAnkiMining != null) {
            requireContext().toast(R.string.subtitle_anki_busy)
            return
        }

        when (ankiGateway.access()) {
            AnkiAccess.AVAILABLE -> startAnkiMining(pending)
            AnkiAccess.PERMISSION_REQUIRED -> {
                binding.subtitleLookupPopup.setMiningState(entry, SubtitleMiningState.ADDING)
                pendingAnkiMining = pending
                ankiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
            }
            AnkiAccess.UNAVAILABLE -> {
                binding.subtitleLookupPopup.setMiningState(entry, SubtitleMiningState.ERROR)
                requireContext().toast(R.string.subtitle_anki_unavailable)
            }
        }
    }

    private fun startAnkiMining(pending: PendingAnkiMining) {
        if (ankiMiningJob?.isActive == true) return
        _playerBinding?.subtitleLookupPopup?.setMiningState(
            pending.entry,
            SubtitleMiningState.ADDING,
        )
        ankiMiningJob = lifecycleScope.launch {
            var prepared = pending.snapshot
            val result = try {
                val preset = ankiGateway.loadPreset()
                prepared = prepareMiningSnapshot(pending)
                if (ankiGateway.loadPreset() == preset) {
                    ankiGateway.mine(prepared)
                } else {
                    AnkiMineResult.Failed("Anki mapping changed while preparing the card")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (failure: MiningPreparationException) {
                AnkiMineResult.Failed(failure.message.orEmpty())
            } catch (_: IOException) {
                AnkiMineResult.Failed("Card media unavailable")
            } catch (_: RuntimeException) {
                AnkiMineResult.Failed("Card preparation failed")
            } finally {
                listOfNotNull(prepared.sentenceAudio, prepared.wordAudio, prepared.image).forEach { it.file.delete() }
                ankiMiningJob = null
            }
            showAnkiMiningResult(pending.copy(snapshot = prepared), result)
        }
    }

    private fun showAnkiMiningResult(
        pending: PendingAnkiMining,
        result: AnkiMineResult,
    ) {
        val popup = _playerBinding?.subtitleLookupPopup
        when (result) {
            AnkiMineResult.ConfigMissing -> {
                popup?.setMiningState(pending.entry, SubtitleMiningState.ERROR)
                context?.toast(R.string.subtitle_anki_setup_required)
            }
            AnkiMineResult.PermissionRequired -> {
                popup?.setMiningState(pending.entry, SubtitleMiningState.ADDING)
                pendingAnkiMining = pending
                ankiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
            }
            AnkiMineResult.Unavailable -> {
                popup?.setMiningState(pending.entry, SubtitleMiningState.ERROR)
                context?.toast(R.string.subtitle_anki_unavailable)
            }
            is AnkiMineResult.Duplicate -> {
                popup?.setMiningState(pending.entry, SubtitleMiningState.DUPLICATE)
                context?.toast(R.string.subtitle_anki_duplicate)
            }
            is AnkiMineResult.Added -> {
                org.jellyfin.mobile.player.anki.AnkiMiningPreferences.get(requireContext()).lastFailure = null
                popup?.setMiningState(pending.entry, SubtitleMiningState.ADDED)
                val unavailable = unavailableMappedMedia(pending.snapshot)
                if (unavailable.isEmpty()) {
                    context?.toast(R.string.subtitle_anki_added)
                } else {
                    context?.toast(getString(R.string.subtitle_anki_added_partial, unavailable.joinToString(", ")))
                }
            }
            is AnkiMineResult.Failed -> {
                popup?.setMiningState(pending.entry, SubtitleMiningState.ERROR)
                org.jellyfin.mobile.player.anki.AnkiMiningPreferences.get(requireContext()).lastFailure = result.message
                context?.toast(getString(R.string.anki_failure_detail, result.message))
            }
        }
    }

    private fun miningSnapshot(entry: DictionaryEntry, subtitle: String): AnkiMiningSnapshot = AnkiMiningSnapshot(
        word = entry.term,
        reading = entry.reading,
        definition = entry.definition,
        subtitle = subtitle,
        subtitleHighlightStart = entry.matchedSourceStart,
        subtitleHighlightLength = entry.matchedSourceLength,
        sourceTitle = activeSourceTitle,
        frequency = entry.frequencies.joinToString(
            "; "
        ) { "${it.dictionaryTitle}: ${it.displayValue}" }.ifBlank { null },
    )

    private suspend fun prepareMiningSnapshot(pending: PendingAnkiMining): AnkiMiningSnapshot {
        val sources = ankiGateway.loadPreset()?.fields?.map { it.source }.orEmpty()
        val temporary = mutableListOf<AnkiMediaFile>()
        try {
            var snapshot = pending.snapshot.copy(sentenceAudio = null, wordAudio = null, image = null)
            if (AnkiFieldSource.IMAGE in sources) {
                // Never substitute a later frame after a permission dialog or media transition.
                val media = pending.mediaContext
                val player = viewModel.playerOrNull
                if (media != null && viewModel.mediaSourceOrNull?.id == media.sourceId &&
                    player != null && kotlin.math.abs(player.currentPosition - media.positionMs) < 100
                ) {
                    val image = _playerBinding?.playerView?.let { MiningFrameCapture.capture(it) }
                    image?.let(temporary::add)
                    snapshot = snapshot.copy(image = image)
                }
            }
            val media = pending.mediaContext
            if (media != null && (AnkiFieldSource.ENGLISH_SUBTITLE in sources || AnkiFieldSource.SENTENCE_AUDIO in sources)) {
                val sentence = try {
                    miningMedia.sentence(media, snapshot.subtitle, AnkiFieldSource.ENGLISH_SUBTITLE in sources)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw MiningPreparationException("Could not read subtitle timing from the playback server. Check the subtitle track or reopen the video.")
                }
                snapshot = snapshot.copy(englishSubtitle = sentence.english)
                if (AnkiFieldSource.SENTENCE_AUDIO in sources && sentence.cue != null) {
                    val audio = try {
                        miningMedia.sentenceAudio(media, sentence.cue)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw MiningPreparationException("Could not extract Japanese sentence audio. Check ffmpeg on the playback server or reopen the video.")
                    }
                    audio?.let(temporary::add)
                    snapshot = snapshot.copy(sentenceAudio = audio)
                }
            }
            if (AnkiFieldSource.WORD_AUDIO in sources) {
                val clip = wordAudio.audio(pending.entry)
                if (clip != null) {
                    val directory = File(requireContext().applicationContext.cacheDir, "anki-media").apply { mkdirs() }
                    val audio = AnkiMediaFile(File.createTempFile("word-", ".mp3", directory), clip.mimeType)
                    temporary.add(audio)
                    withContext(Dispatchers.IO) {
                        clip.file.copyTo(audio.file, overwrite = true)
                    }
                    snapshot = snapshot.copy(wordAudio = audio)
                }
            }
            return snapshot
        } catch (error: Exception) {
            temporary.forEach { it.file.delete() }
            throw error
        }
    }

    private fun unavailableMappedMedia(snapshot: AnkiMiningSnapshot): List<String> {
        val missing = mapOf(
            AnkiFieldSource.ENGLISH_SUBTITLE to (snapshot.englishSubtitle.isNullOrBlank() to R.string.subtitle_anki_media_translation),
            AnkiFieldSource.SENTENCE_AUDIO to ((snapshot.sentenceAudio == null) to R.string.subtitle_anki_media_sentence),
            AnkiFieldSource.WORD_AUDIO to ((snapshot.wordAudio == null) to R.string.subtitle_anki_media_word),
            AnkiFieldSource.IMAGE to ((snapshot.image == null) to R.string.subtitle_anki_media_image),
        )
        return ankiGateway.loadPreset()?.fields.orEmpty().mapNotNull { field ->
            missing[field.source]?.takeIf { it.first }?.let { getString(it.second) }
        }.distinct()
    }

    private fun checkPopupDuplicates(entries: List<DictionaryEntry>) {
        duplicateJob?.cancel()
        val requestId = lookupRequestId
        val subtitle = activeSubtitleTap?.subtitleText ?: return
        val media = activeMiningContext
        duplicateJob = viewLifecycleOwner.lifecycleScope.launch {
            val translation = if (ankiGateway.loadPreset()?.fields?.firstOrNull()?.source == AnkiFieldSource.ENGLISH_SUBTITLE && media != null) {
                try {
                    miningMedia.sentence(media, subtitle, includeEnglish = true).english
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            for (entry in entries) {
                val status = ankiGateway.checkDuplicate(miningSnapshot(entry, subtitle).copy(englishSubtitle = translation))
                if (requestId != lookupRequestId) return@launch
                _playerBinding?.subtitleLookupPopup?.setDuplicateStatus(entry, status)
            }
        }
    }

    private fun onWordAudioRequested(entry: DictionaryEntry) {
        stopWordAudio()
        val requestId = lookupRequestId
        _playerBinding?.subtitleLookupPopup?.setAudioLoading(entry, true)
        wordAudioJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val clip = wordAudio.audio(entry)
                if (requestId != lookupRequestId) return@launch
                if (clip == null) {
                    context?.toast(R.string.subtitle_word_audio_missing)
                } else {
                    startWordAudioPlayback(clip.file, requestId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                context?.toast(R.string.subtitle_word_audio_missing)
            } finally {
                if (requestId == lookupRequestId) {
                    _playerBinding?.subtitleLookupPopup?.setAudioLoading(entry, false)
                }
            }
        }
    }

    private fun startWordAudioPlayback(file: File, requestId: Int) {
        val clipPlayer = MediaPlayer()
        wordAudioPlayer = clipPlayer
        try {
            clipPlayer.setDataSource(file.absolutePath)
            clipPlayer.setOnPreparedListener { prepared ->
                if (wordAudioPlayer === prepared && requestId == lookupRequestId) {
                    try {
                        prepared.start()
                    } catch (_: IllegalStateException) {
                        stopWordAudio()
                        context?.toast(R.string.subtitle_word_audio_missing)
                    }
                }
            }
            clipPlayer.setOnCompletionListener { completed ->
                if (wordAudioPlayer === completed) stopWordAudio()
            }
            clipPlayer.setOnErrorListener { failed, _, _ ->
                if (wordAudioPlayer === failed) {
                    stopWordAudio()
                    if (requestId == lookupRequestId) context?.toast(R.string.subtitle_word_audio_missing)
                }
                true
            }
            clipPlayer.prepareAsync()
        } catch (error: Exception) {
            if (wordAudioPlayer === clipPlayer) wordAudioPlayer = null
            clipPlayer.release()
            throw error
        }
    }

    private fun stopWordAudio() {
        wordAudioJob?.cancel()
        wordAudioJob = null
        val player = wordAudioPlayer
        wordAudioPlayer = null
        player?.release()
    }

    private fun calculateInteractiveSubtitleBottomMargin(binding: FragmentPlayerBinding): Int {
        val fallbackBaseMargin =
            resources.getDimensionPixelSize(R.dimen.interactive_subtitle_bottom_margin)
        val subtitleHost = interactiveSubtitleHost ?: return fallbackBaseMargin
        val overlayParent = binding.interactiveSubtitleOverlay.parent as? View
            ?: return fallbackBaseMargin
        val parentLocation = IntArray(2)
        val subtitleHostLocation = IntArray(2)
        overlayParent.getLocationOnScreen(parentLocation)
        subtitleHost.getLocationOnScreen(subtitleHostLocation)
        val parentBottom = parentLocation[1] + overlayParent.height
        val subtitleHostBottom = subtitleHostLocation[1] + subtitleHost.height
        val contentFrameOffset = (parentBottom - subtitleHostBottom).coerceAtLeast(0)
        val baseMargin = subtitleHost.height
            .takeIf { it > 0 }
            ?.let {
                contentFrameOffset + (it * interactiveSubtitleBottomPaddingFraction).toInt()
            }
            ?: fallbackBaseMargin
        if (!subtitleControllerVisible) return baseMargin

        val fallbackMargin = max(
            baseMargin,
            resources.getDimensionPixelSize(
                R.dimen.interactive_subtitle_controls_fallback_margin,
            ),
        )
        val seekBar = playerControlsBinding.seekBarContainer
        if (subtitleHost.height == 0 || seekBar.height == 0) return fallbackMargin

        val seekBarLocation = IntArray(2)
        seekBar.getLocationOnScreen(seekBarLocation)
        if (seekBarLocation[1] >= subtitleHostBottom) return baseMargin

        val controlsGap =
            resources.getDimensionPixelSize(R.dimen.interactive_subtitle_controls_gap)
        return max(baseMargin, parentBottom - seekBarLocation[1] + controlsGap)
    }

    private fun updateInteractiveSubtitles(cues: List<Cue>) {
        val binding = _playerBinding ?: return
        val selectedCodec = viewModel.mediaSourceOrNull?.selectedSubtitleStream?.codec
        val supportsInteractiveCodec =
            interactiveSubtitleCodecs.any { selectedCodec.equals(it, ignoreCase = true) }
        val bottomPaddingFraction = if (supportsInteractiveCodec) {
            InteractiveSubtitleCuePolicy.bottomPaddingFraction(cues)
        } else {
            null
        }
        if (
            bottomPaddingFraction != null &&
            bottomPaddingFraction != interactiveSubtitleBottomPaddingFraction
        ) {
            interactiveSubtitleBottomPaddingFraction = bottomPaddingFraction
            updateInteractiveSubtitleBottomMargin(subtitleControllerVisible)
        }
        val interactiveSubtitleVisible = binding.interactiveSubtitleOverlay.submitCues(
            if (bottomPaddingFraction != null) cues else emptyList(),
        )
        binding.playerView.subtitleView?.visibility = when {
            interactiveSubtitleVisible -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    private fun onSubtitleTapped(tap: SubtitleTap): Boolean {
        val hasJapaneseCandidate =
            JapaneseTextCandidateGenerator.generate(
                tap.subtitleText,
                tap.characterOffset,
            ).isNotEmpty()
        if (!hasJapaneseCandidate) {
            if (lookupPlayer == null) return false
            playerView.hideController()
            finishSubtitleLookup(resumePlayback = true)
            return true
        }
        val player = viewModel.playerOrNull ?: return false
        val binding = _playerBinding ?: return false
        val anchorBounds = tap.toLookupAnchorBounds(binding)

        if (lookupPlayer == null) {
            binding.interactiveSubtitleOverlay.setCuesFrozen(true)
            lookupPlayer = player
            pauseForCurrentLookup = LookupPreferences(requireContext()).pauseOnLookup
            previousControllerAutoShow = playerView.controllerAutoShow
            playerView.controllerAutoShow = false
            playerView.hideController()
            playerPausedForLookup = player.takeIf { pauseForCurrentLookup && it.playWhenReady }
            playerPausedForLookup?.pause()
            activeMiningContext = miningMedia.capture(viewModel.mediaSourceOrNull, player.currentPosition)
            activeSourceTitle = viewModel.mediaSourceOrNull?.getName(requireContext())
        } else if (lookupPlayer !== player) {
            return false
        }

        lookupJob?.cancel()
        duplicateJob?.cancel()
        stopWordAudio()
        val requestId = ++lookupRequestId
        activeSubtitleTap = tap
        binding.interactiveSubtitleOverlay.setLookupHighlight(
            sourceStart = tap.characterOffset,
            sourceLength = tappedCharacterLength(tap),
        )
        binding.subtitleLookupBackdrop.isVisible = true
        binding.subtitleLookupPopup.hide()
        updateEnglishButton()
        if (englishVisible) showEnglishSubtitle()

        lookupJob = lifecycleScope.launch {
            performSubtitleLookup(player, requestId, tap, anchorBounds)
        }
        return true
    }

    private suspend fun performSubtitleLookup(
        player: Player,
        requestId: Int,
        tap: SubtitleTap,
        anchorBounds: RectF,
    ) {
        try {
            val result = dictionaryRepository.lookup(tap.subtitleText, tap.characterOffset)
            if (lookupPlayer !== player || requestId != lookupRequestId) return
            showSubtitleLookupResult(anchorBounds, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLException) {
            showSubtitleLookupFailure(player, requestId, anchorBounds, error)
        } catch (error: IllegalArgumentException) {
            showSubtitleLookupFailure(player, requestId, anchorBounds, error)
        } catch (error: IllegalStateException) {
            showSubtitleLookupFailure(player, requestId, anchorBounds, error)
        } finally {
            if (requestId == lookupRequestId) lookupJob = null
        }
    }

    private fun showSubtitleLookupResult(
        anchorBounds: RectF,
        result: DictionaryLookupResult,
    ) {
        val binding = _playerBinding ?: return
        val currentAnchorBounds = currentSubtitleLookupAnchor(binding, anchorBounds) ?: anchorBounds
        val highlightStart = result.matchedSourceStart
        val highlightLength = result.matchedSourceLength
        if (highlightStart != null && highlightLength != null) {
            binding.interactiveSubtitleOverlay.setLookupHighlight(
                highlightStart,
                highlightLength,
            )
        }
        if (result.entries.isEmpty()) {
            binding.subtitleLookupPopup.showEmpty(currentAnchorBounds)
        } else {
            binding.subtitleLookupPopup.showResults(currentAnchorBounds, result.entries)
            checkPopupDuplicates(result.entries)
        }
    }

    private fun showSubtitleLookupFailure(
        player: Player,
        requestId: Int,
        anchorBounds: RectF,
        error: RuntimeException,
    ) {
        if (lookupPlayer !== player || requestId != lookupRequestId) return
        Timber.e(error, "Subtitle dictionary lookup failed")
        val binding = _playerBinding ?: return
        binding.subtitleLookupPopup.showError(
            currentSubtitleLookupAnchor(binding, anchorBounds) ?: anchorBounds,
        )
    }

    private fun tappedCharacterLength(tap: SubtitleTap): Int {
        if (tap.characterOffset !in tap.subtitleText.indices) return 1
        return Character.charCount(tap.subtitleText.codePointAt(tap.characterOffset))
    }

    private fun SubtitleTap.toLookupAnchorBounds(binding: FragmentPlayerBinding): RectF {
        val subtitleLocation = IntArray(2)
        val playerOverlayLocation = IntArray(2)
        binding.interactiveSubtitleOverlay.getLocationOnScreen(subtitleLocation)
        binding.playerOverlay.getLocationOnScreen(playerOverlayLocation)
        val offsetX = subtitleLocation[0] - playerOverlayLocation[0] - binding.playerOverlay.paddingLeft
        val offsetY = subtitleLocation[1] - playerOverlayLocation[1] - binding.playerOverlay.paddingTop
        return RectF(
            characterBounds.left + offsetX.toFloat(),
            characterBounds.top + offsetY.toFloat(),
            characterBounds.right + offsetX.toFloat(),
            characterBounds.bottom + offsetY.toFloat(),
        )
    }

    private fun updateActiveSubtitleLookupAnchor() {
        val binding = _playerBinding ?: return
        binding.subtitleLookupPopup.updateAnchor(currentSubtitleLookupAnchor(binding, null) ?: return)
    }

    private fun currentSubtitleLookupAnchor(
        binding: FragmentPlayerBinding,
        fallback: RectF?,
    ): RectF? {
        val tap = activeSubtitleTap ?: return fallback
        val currentBounds = binding.interactiveSubtitleOverlay.characterBoundsFor(
            tap.subtitleText,
            tap.characterOffset,
        ) ?: return fallback
        val updatedTap = tap.copy(characterBounds = currentBounds)
        activeSubtitleTap = updatedTap
        return updatedTap.toLookupAnchorBounds(binding)
    }

    private fun onSubtitlePlayerGesture(event: MotionEvent) {
        val binding = _playerBinding ?: return
        val subtitleLocation = IntArray(2)
        val playerLocation = IntArray(2)
        binding.interactiveSubtitleOverlay.getLocationOnScreen(subtitleLocation)
        binding.playerView.getLocationOnScreen(playerLocation)

        val playerEvent = MotionEvent.obtain(event)
        playerEvent.offsetLocation(
            (subtitleLocation[0] - playerLocation[0]).toFloat(),
            (subtitleLocation[1] - playerLocation[1]).toFloat(),
        )
        playerGestureHelper.handleTouchEvent(playerEvent)
        playerEvent.recycle()
    }

    private fun finishSubtitleLookup(resumePlayback: Boolean) {
        hideEnglishSubtitle()
        activeSubtitleTap = null
        activeMiningContext = null
        activeSourceTitle = null
        duplicateJob?.cancel()
        duplicateJob = null
        stopWordAudio()
        val binding = _playerBinding
        if (binding != null) {
            binding.subtitleLookupBackdrop.isVisible = false
            binding.subtitleLookupPopup.hide()
            binding.interactiveSubtitleOverlay.clearLookupHighlight()
            val interactiveSubtitleVisible = binding.interactiveSubtitleOverlay.setCuesFrozen(false)
            binding.playerView.subtitleView?.visibility = when {
                interactiveSubtitleVisible -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }

        val playerToResume = playerPausedForLookup
        lookupRequestId++
        lookupJob?.cancel()
        lookupJob = null
        playerPausedForLookup = null
        lookupPlayer = null
        if (resumePlayback && playerToResume === viewModel.playerOrNull) {
            playerToResume?.play()
        }
        previousControllerAutoShow?.let { binding?.playerView?.controllerAutoShow = it }
        previousControllerAutoShow = null
        updateEnglishButton()
    }

    private fun updateEnglishButton() {
        val binding = _playerBinding ?: return
        // One overlay control is shared by both states, so its position never jumps on lookup.
        val learningControlsVisible = subtitleControllerVisible || lookupPlayer != null || englishVisible
        binding.englishSubtitleButton.isVisible = learningControlsVisible
        binding.previousSubtitleButton.isVisible = learningControlsVisible
        binding.englishSubtitleButton.isSelected = englishVisible
        binding.englishSubtitleButton.setTextColor(
            if (englishVisible) {
                android.graphics.Color.rgb(80, 213, 151)
            } else {
                android.graphics.Color.WHITE
            }
        )
        binding.englishSubtitleButton.setContentDescription(
            getString(
                if (englishVisible) R.string.learning_english_hide else R.string.learning_english_show
            )
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun seekToPreviousSubtitle() {
        val player = viewModel.playerOrNull ?: return
        val media = miningMedia.capture(viewModel.mediaSourceOrNull, player.currentPosition) ?: run {
            context?.toast(R.string.learning_previous_subtitle_missing)
            return
        }
        subtitleSeekJob?.cancel()
        _playerBinding?.previousSubtitleButton?.isEnabled = false
        subtitleSeekJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val target = miningMedia.previousSubtitlePosition(media)
                if (target == null) {
                    context?.toast(R.string.learning_previous_subtitle_missing)
                    return@launch
                }
                if (lookupPlayer != null) dismissSubtitleLookupWithoutResume()
                playerView.hideController()
                player.seekTo(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Previous subtitle seek failed")
                context?.toast(R.string.learning_previous_subtitle_missing)
            } finally {
                _playerBinding?.previousSubtitleButton?.isEnabled = true
                subtitleSeekJob = null
            }
        }
    }

    private fun hideEnglishSubtitle() {
        englishVisible = false
        englishJob?.cancel()
        englishJob = null
        _playerBinding?.englishSubtitleText?.isVisible = false
        updateEnglishButton()
    }

    private fun showEnglishSubtitle() {
        englishJob?.cancel()
        englishVisible = true
        _playerBinding?.englishSubtitleText?.isVisible = false
        updateEnglishButton()
        englishJob = viewLifecycleOwner.lifecycleScope.launch {
            while (englishVisible) {
                val player = viewModel.playerOrNull ?: break
                val media = activeMiningContext ?: miningMedia.capture(viewModel.mediaSourceOrNull, player.currentPosition)
                val text = try {
                    val english = media?.let { captured ->
                        miningMedia.englishAt(captured)
                        // Playback can advance while the initial subtitle window is downloading.
                        val current = if (lookupPlayer == null) captured.copy(positionMs = player.currentPosition) else captured
                        miningMedia.englishAt(current)
                    }
                    english ?: getString(R.string.learning_english_missing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    getString(R.string.learning_english_error)
                }
                _playerBinding?.englishSubtitleText?.apply {
                    this.text = text
                    isVisible = true
                }
                if (lookupPlayer != null) break
                delay(300)
            }
        }
    }

    private fun dismissSubtitleLookupWithoutResume() {
        finishSubtitleLookup(resumePlayback = false)
    }

    override fun onInterceptBackPressed(): Boolean {
        if (lookupPlayer == null) {
            if (!englishVisible) return false
            hideEnglishSubtitle()
            return true
        }
        finishSubtitleLookup(resumePlayback = true)
        return true
    }

    fun onUserLeaveHint() {
        if (
            AndroidVersion.isAtLeastN &&
            viewModel.playerOrNull != null &&
            lookupPlayer == null
        ) {
            requireActivity().enterPictureInPicture()
        }
    }

    @Suppress("NestedBlockDepth")
    @RequiresApi(Build.VERSION_CODES.N)
    private fun Activity.enterPictureInPicture() {
        if (AndroidVersion.isAtLeastO) {
            val params = PictureInPictureParams.Builder().apply {
                val aspectRational = currentVideoStream?.aspectRational?.let { aspectRational ->
                    when {
                        aspectRational < PIP_MIN_RATIONAL -> PIP_MIN_RATIONAL
                        aspectRational > PIP_MAX_RATIONAL -> PIP_MAX_RATIONAL
                        else -> aspectRational
                    }
                }
                setAspectRatio(aspectRational)
                val contentFrame: View = playerView.findViewById(Media3R.id.exo_content_frame)
                val contentRect = with(contentFrame) {
                    val (x, y) = intArrayOf(0, 0).also(::getLocationInWindow)
                    Rect(x, y, x + width, y + height)
                }
                setSourceRectHint(contentRect)
            }.build()
            enterPictureInPictureMode(params)
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerMenus?.dismissPlaybackInfo()
            playerLockScreenHelper.hideUnlockButton()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post

            updateFullscreenState(newConfig)
            playerGestureHelper.handleConfiguration(newConfig)
            playerBinding.interactiveSubtitleOverlay.refreshCaptionPreferences()
            updateInteractiveSubtitleBottomMargin(playerView.isControllerFullyVisible)
        }
    }

    override fun onStop() {
        super.onStop()
        stopWordAudio()
        hideEnglishSubtitle()
        orientationListener.disable()
    }

    override fun onDestroyView() {
        subtitleSeekJob?.cancel()
        subtitleSeekJob = null
        finishSubtitleLookup(resumePlayback = activity?.isChangingConfigurations == true)
        subtitleControllerVisible = false
        interactiveSubtitleHost?.removeOnLayoutChangeListener(
            interactiveSubtitleHostLayoutListener,
        )
        interactiveSubtitleHost = null
        subtitlePlayer?.removeListener(subtitleCueListener)
        subtitlePlayer = null
        playerView.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
        playerBinding.interactiveSubtitleOverlay.isSubtitleInteractionEnabled = { false }
        playerBinding.interactiveSubtitleOverlay.removeOnLayoutChangeListener(
            interactiveSubtitleOverlayLayoutListener,
        )
        playerBinding.interactiveSubtitleOverlay.setSubtitleViewport(null)
        playerBinding.interactiveSubtitleOverlay.onSubtitleTapped = null
        playerBinding.interactiveSubtitleOverlay.onPlayerGesture = null
        playerBinding.subtitleLookupBackdrop.setOnClickListener(null)
        playerBinding.subtitleLookupPopup.onDismissRequested = null
        playerBinding.subtitleLookupPopup.onMineRequested = null
        playerBinding.subtitleLookupPopup.onAudioRequested = null
        // Detach player from PlayerView
        playerView.player = null

        // Set binding references to null
        _playerBinding = null
        _playerControlsBinding = null
        playerMenus = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        with(requireActivity()) {
            // Reset screen orientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreenHelper.disableFullscreen()
            // Reset screen brightness
            window.brightness = BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        viewModel.setPlayerMenuHelper(menuHelper)
    }

    private fun Bundle.restorePendingAnkiMining(): PendingAnkiMining? {
        val word = getString(STATE_ANKI_WORD) ?: return null
        val definition = getString(STATE_ANKI_DEFINITION) ?: return null
        val subtitle = getString(STATE_ANKI_SUBTITLE) ?: return null
        val reading = getString(STATE_ANKI_READING)
        val entry = DictionaryEntry(term = word, definition = definition, reading = reading)
        return PendingAnkiMining(
            entry = entry,
            snapshot = AnkiMiningSnapshot(
                word = word,
                reading = reading,
                definition = definition,
                subtitle = subtitle,
                subtitleHighlightStart = if (containsKey(STATE_ANKI_HIGHLIGHT_START)) getInt(STATE_ANKI_HIGHLIGHT_START) else null,
                subtitleHighlightLength = if (containsKey(STATE_ANKI_HIGHLIGHT_LENGTH)) getInt(STATE_ANKI_HIGHLIGHT_LENGTH) else null,
                sourceTitle = getString(STATE_ANKI_SOURCE),
                frequency = getString(STATE_ANKI_FREQUENCY),
            ),
            mediaContext = getString(STATE_ANKI_CONTEXT)?.let { encoded ->
                runCatching { Json.decodeFromString<MiningMediaContext>(encoded) }.getOrNull()
            },
        )
    }

    private data class PendingAnkiMining(
        val entry: DictionaryEntry,
        val snapshot: AnkiMiningSnapshot,
        val mediaContext: MiningMediaContext? = null,
    )

    private companion object {
        const val STATE_ANKI_WORD = "anki_word"
        const val STATE_ANKI_READING = "anki_reading"
        const val STATE_ANKI_DEFINITION = "anki_definition"
        const val STATE_ANKI_SUBTITLE = "anki_subtitle"
        const val STATE_ANKI_SOURCE = "anki_source"
        const val STATE_ANKI_FREQUENCY = "anki_frequency"
        const val STATE_ANKI_HIGHLIGHT_START = "anki_highlight_start"
        const val STATE_ANKI_HIGHLIGHT_LENGTH = "anki_highlight_length"
        const val STATE_ANKI_CONTEXT = "anki_media_context"
    }
}

private class MiningPreparationException(message: String) : IOException(message)
