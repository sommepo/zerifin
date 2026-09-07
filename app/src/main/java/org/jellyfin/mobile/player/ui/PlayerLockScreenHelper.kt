package org.jellyfin.mobile.player.ui

import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.extensions.lockOrientation
import org.jellyfin.mobile.utils.isAutoRotateOn

class PlayerLockScreenHelper(
    private val playerFragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val orientationListener: OrientationEventListener,
) {
    private val playerView: PlayerView by playerBinding::playerView
    private val unlockScreenButton: ImageButton by playerBinding::unlockScreenButton
    var isLocked = false
        private set

    init {
        // Handle unlock action
        unlockScreenButton.setOnClickListener {
            unlockScreen()
        }
    }

    fun lockScreen() {
        isLocked = true
        playerView.useController = false
        orientationListener.disable()
        playerFragment.requireActivity().lockOrientation()
        peekUnlockButton()
    }

    private fun unlockScreen() {
        isLocked = false
        hideUnlockButton()
        val activity = playerFragment.requireActivity()
        if (activity.isAutoRotateOn()) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        orientationListener.enable()
        if (!AndroidVersion.isAtLeastN || !activity.isInPictureInPictureMode) {
            playerView.useController = true
            playerView.apply {
                if (!isControllerFullyVisible) showController()
            }
        }
    }

    fun peekUnlockButton() {
        unlockScreenButton.isVisible = true
        playerFragment.peekLearningControls()
    }

    fun hideUnlockButton() {
        unlockScreenButton.isVisible = false
    }
}
