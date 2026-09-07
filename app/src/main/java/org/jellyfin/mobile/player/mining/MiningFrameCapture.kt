package org.jellyfin.mobile.player.mining

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.player.anki.AnkiMediaFile
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object MiningFrameCapture {
    suspend fun capture(playerView: PlayerView): AnkiMediaFile? {
        val surface = playerView.videoSurfaceView ?: return null
        val (width, height) = dimensions(surface.width, surface.height) ?: return null
        val bitmap = when {
            surface is TextureView -> if (surface.isAvailable) surface.getBitmap(width, height) else null
            surface is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                suspendCancellableCoroutine { continuation ->
                    try {
                        PixelCopy.request(surface, target, { result ->
                            if (result == PixelCopy.SUCCESS && continuation.isActive) {
                                continuation.resume(target) { _, value, _ -> value.recycle() }
                            } else {
                                target.recycle()
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }, Handler(Looper.getMainLooper()))
                    } catch (_: IllegalArgumentException) {
                        target.recycle()
                        continuation.resume(null)
                    }
                }
            }
            else -> null
        } ?: return null
        var output: File? = null
        return try {
            withContext(Dispatchers.IO) {
                val directory = File(playerView.context.applicationContext.cacheDir, "anki-media").apply { mkdirs() }
                val file = File.createTempFile("frame-", ".jpg", directory)
                output = file
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                AnkiMediaFile(file, "image/jpeg")
            }
        } catch (error: Exception) {
            output?.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    internal fun dimensions(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1f, MAX_FRAME_EDGE.toFloat() / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private const val MAX_FRAME_EDGE = 960
}
