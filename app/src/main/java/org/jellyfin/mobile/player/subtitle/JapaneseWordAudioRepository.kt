package org.jellyfin.mobile.player.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class WordAudioClip(val file: File, val mimeType: String = "audio/mpeg")

/** Fetches pronunciation only for an explicit playback or mapped mining request. */
class JapaneseWordAudioRepository(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "word-audio")
    private val mutex = Mutex()
    private val client = JapaneseWordAudioRequest.client()

    suspend fun audio(entry: DictionaryEntry): WordAudioClip? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val url = JapaneseWordAudioRequest.url(entry) ?: return@withLock null
            val key = JapaneseWordAudioRequest.digest(url.toString().toByteArray(Charsets.UTF_8))
            val file = File(directory, "$key.mp3")
            try {
                if (file.isFile && file.length() <= MAX_AUDIO_BYTES && JapaneseWordAudioRequest.isValid(file.readBytes())) {
                    file.setLastModified(System.currentTimeMillis())
                    return@withLock WordAudioClip(file)
                }
                val bytes = download(url) ?: return@withLock null
                coroutineContext.ensureActive()
                if (!JapaneseWordAudioRequest.isValid(bytes)) return@withLock null
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException(
                        "Word audio cache could not be created"
                    )
                }
                val temporary = File.createTempFile("word-", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    coroutineContext.ensureActive()
                    if (!temporary.renameTo(file)) throw IOException("Word audio could not be saved")
                } finally {
                    temporary.delete()
                }
                trimCache(file)
                WordAudioClip(file)
            } catch (_: IOException) {
                null
            }
        }
    }

    private suspend fun download(url: HttpUrl): ByteArray? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "audio/mpeg").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = try {
                        response.use {
                            val body = response.body
                            if (!response.isSuccessful || body == null || body.contentLength() > MAX_AUDIO_BYTES) {
                                null
                            } else {
                                body.byteStream().use { input ->
                                    val data = input.readBytesLimited(MAX_AUDIO_BYTES + 1)
                                    data.takeIf { it.size <= MAX_AUDIO_BYTES }
                                }
                            }
                        }
                    } catch (_: IOException) {
                        null
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            },
        )
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun trimCache(keep: File) {
        var total = keep.length()
        directory.listFiles()?.filter { it != keep && it.extension == "mp3" }
            ?.sortedByDescending(File::lastModified)?.forEach { cached ->
                total += cached.length()
                if (total > MAX_CACHE_BYTES) cached.delete()
            }
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 2_097_152
        private const val MAX_CACHE_BYTES = 16_777_216L
    }
}

internal object JapaneseWordAudioRequest {
    // The dictionary endpoint redirects to its HTTPS audio CDN. Never follow a downgrade to HTTP.
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    private const val MISSING_AUDIO_SHA256 = "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"
    private const val ID3_HEADER_SIZE = 10
    private const val MPEG_HEADER_SIZE = 4

    fun url(entry: DictionaryEntry): HttpUrl? {
        val term = entry.term.trim()
        if (term.isEmpty()) return null
        val reading = entry.reading?.trim()?.takeIf(String::isNotEmpty) ?: term.takeIf(::isKana)
        if (reading == null || !isKana(reading)) return null
        return HttpUrl.Builder().scheme("https").host("assets.languagepod101.com")
            .addPathSegments("dictionary/japanese/audiomp3.php")
            .apply { if (!isKana(term)) addQueryParameter("kanji", term) }
            .addQueryParameter("kana", reading)
            .build()
    }

    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size < MPEG_HEADER_SIZE || digest(bytes) == MISSING_AUDIO_SHA256) return false
        val audioStart = if (bytes.size >= ID3_HEADER_SIZE && bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        ) {
            if ((6..9).any { bytes[it].toInt() and 0x80 != 0 }) return false
            val tagLength = (6..9).fold(0) { size, index -> (size shl 7) or (bytes[index].toInt() and 0x7f) }
            ID3_HEADER_SIZE + tagLength
        } else {
            0
        }
        if (audioStart > bytes.size - MPEG_HEADER_SIZE) return false
        val second = bytes[audioStart + 1].toInt() and 0xff
        val third = bytes[audioStart + 2].toInt() and 0xff
        return bytes[audioStart].toInt() and 0xff == 0xff && second and 0xe0 == 0xe0 &&
            second and 0x18 != 0x08 && second and 0x06 != 0 &&
            third and 0xf0 !in setOf(0, 0xf0) && third and 0x0c != 0x0c
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun isKana(text: String): Boolean = text.isNotEmpty() && text.all {
        it in '\u3041'..'\u3096' || it in '\u30a1'..'\u30fa' || it == '\u30fc' || it == '\u30fb' ||
            it in '\uff66'..'\uff9f'
    }
}
