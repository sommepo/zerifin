package org.jellyfin.mobile.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import timber.log.Timber
import java.io.IOException

/** Checks for a newer Zerifin build when the app opens (at most daily) or on request. */
object UpdatePrompt {
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    suspend fun checkOnLaunch(activity: FragmentActivity) {
        val preferences = UpdatePreferences(activity)
        // Debug builds are development snapshots without a comparable version.
        if (BuildConfig.DEBUG || !preferences.enabled) return
        val now = System.currentTimeMillis()
        if (now - preferences.lastCheckMs < CHECK_INTERVAL_MS) return
        val update = runCheck(preferences) ?: return
        preferences.lastCheckMs = now
        if (update.version != preferences.skippedVersion && !activity.isFinishing) show(activity, update, preferences)
    }

    /** From settings: always checks and reports the result. */
    suspend fun checkNow(activity: FragmentActivity) {
        val preferences = UpdatePreferences(activity)
        val feed = preferences.feedUrl
        if (feed == null) {
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val update = UpdateChecker().check(feed, BuildConfig.VERSION_NAME)
            preferences.lastCheckMs = System.currentTimeMillis()
            if (update == null) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_none, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                show(activity, update, preferences)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Timber.w(error, "Update check failed")
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
        } catch (error: IllegalArgumentException) {
            Timber.w(error, "Update feed was not understood")
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns a newer build, or null when there is none or the check failed quietly. */
    private suspend fun runCheck(preferences: UpdatePreferences): AvailableUpdate? {
        val feed = preferences.feedUrl ?: return null
        return try {
            val result = UpdateChecker().check(feed, BuildConfig.VERSION_NAME)
            if (result == null) preferences.lastCheckMs = System.currentTimeMillis()
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Timber.d(error, "Launch update check failed")
            null
        } catch (error: IllegalArgumentException) {
            Timber.d(error, "Launch update feed was not understood")
            null
        }
    }

    private fun show(activity: FragmentActivity, update: AvailableUpdate, preferences: UpdatePreferences) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, update.version))
            .setMessage(activity.getString(R.string.update_available_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_download) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.update_skip) { _, _ -> preferences.skippedVersion = update.version }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
}
