package org.jellyfin.mobile.youtube

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.net.ConnectException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class YouTubeClient {
    suspend fun health(base: String): YouTubeResolverHealth =
        json.decodeFromString(bytes(Request.Builder().url("${base.trimEnd('/')}/health").build()).toString(Charsets.UTF_8))

    suspend fun search(base: String, query: String): List<YouTubeVideo> =
        json.decodeFromString<YouTubeSearch>(post(base, "search", query)).results

    suspend fun resolve(base: String, query: String): YouTubePlayback =
        json.decodeFromString<YouTubePlayback>(post(base, "resolve", query)).copy(resolverUrl = base)

    suspend fun confirmJapanese(playback: YouTubePlayback): YouTubePlayback {
        bytes(Request.Builder().url(playback.resource("japanese-audio"))
            .post(ByteArray(0).toRequestBody(null)).build())
        return playback.copy(japaneseAudio = true, audioLanguageUnknown = false)
    }

    private suspend fun post(base: String, endpoint: String, query: String): String {
        val body = buildJsonObject { put("query", query) }.toString()
        return bytes(Request.Builder().url("${base.trimEnd('/')}/$endpoint")
            .post(body.toRequestBody("application/json".toMediaType())).build()).toString(Charsets.UTF_8)
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS).build()

        suspend fun get(url: String): ByteArray = bytes(Request.Builder().url(url).build())

        private suspend fun bytes(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        resolverFailure(call.request(), e)
                    )
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val stream = requireNotNull(it.body).byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= 3 * 1024 * 1024)
                                output.write(buffer, 0, read)
                            }
                            val data = output.toByteArray()
                            if (!it.isSuccessful) {
                                val message = runCatching {
                                    json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject["message"]?.jsonPrimitive?.content
                                }.getOrNull()?.take(300) ?: "The YouTube resolver returned HTTP ${it.code}."
                                throw IOException(message)
                            }
                            data
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is IOException) error else IOException("The YouTube resolver returned an unsupported response.")
                        )
                    }
                }
            })
        }

        private fun resolverFailure(request: Request, error: IOException): IOException {
            val endpoint = "${request.url.host}:${request.url.port}"
            return when (error) {
                is UnknownHostException -> IOException(
                    "Could not find ${request.url.host}. Connect Tailscale or use the resolver's Tailscale IP.",
                    error,
                )
                is ConnectException -> IOException("Nothing answered at $endpoint. Start the resolver and try again.", error)
                is SocketTimeoutException -> IOException("The resolver at $endpoint timed out.", error)
                else -> IOException("Could not reach the YouTube resolver at $endpoint.", error)
            }
        }
    }
}

class YouTubePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    fun address(jellyfinUrl: String?): String = prefs.getString("resolver", null) ?: runCatching {
        jellyfinUrl!!.toHttpUrl().newBuilder().scheme("http").port(8767).encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    }.getOrDefault("http://127.0.0.1:8767")

    fun saveAddress(value: String): String {
        val normalized = normalizeResolverAddress(value)
        prefs.edit().putString("resolver", normalized).apply()
        return normalized
    }
}

internal fun normalizeResolverAddress(value: String): String {
    val supplied = value.trim().let { if (it.contains("://")) it else "http://$it" }
    val uri = runCatching { URI(supplied) }.getOrElse { throw IllegalArgumentException("Invalid resolver address") }
    val url = supplied.toHttpUrl()
    require(url.scheme == "http" || url.scheme == "https")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    require(url.encodedPath == "/")
    return (if (uri.port == -1 && url.scheme == "http") url.newBuilder().port(8767).build() else url)
        .toString().trimEnd('/')
}
