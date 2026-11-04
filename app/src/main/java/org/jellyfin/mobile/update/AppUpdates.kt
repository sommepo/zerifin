package org.jellyfin.mobile.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class UpdateSource { GITHUB, CUSTOM }

/** A newer build offered by the update source. */
data class AvailableUpdate(val version: String, val downloadUrl: String)

class UpdatePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("zerifin_updates", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit { putBoolean("enabled", value) } }

    var source: UpdateSource
        get() = UpdateSource.entries.firstOrNull { it.name == preferences.getString("source", null) } ?: UpdateSource.GITHUB
        set(value) { preferences.edit { putString("source", value.name) } }

    var customUrl: String
        get() = preferences.getString("custom_url", null).orEmpty()
        set(value) { preferences.edit { putString("custom_url", value.trim()) } }

    var lastCheckMs: Long
        get() = preferences.getLong("last_check", 0L)
        set(value) { preferences.edit { putLong("last_check", value) } }

    var skippedVersion: String?
        get() = preferences.getString("skipped_version", null)
        set(value) { preferences.edit { putString("skipped_version", value) } }

    val feedUrl: String?
        get() = when (source) {
            UpdateSource.GITHUB -> GITHUB_LATEST_RELEASE
            UpdateSource.CUSTOM -> customUrl.takeIf { it.toHttpUrlOrNull() != null }
        }

    companion object {
        const val GITHUB_LATEST_RELEASE = "https://api.github.com/repos/sommepo/zerifin/releases/latest"
    }
}

/** Reads a GitHub release or a SER9-style downloads index and compares it with this build. */
class UpdateChecker(private val client: OkHttpClient = defaultClient) {
    suspend fun check(feedUrl: String, currentVersion: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "Zerifin/$currentVersion")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update check failed (${response.code})")
            response.body?.string() ?: throw IOException("Empty update response")
        }
        val offered = UpdateFeed.parse(feedUrl, body) ?: return@withContext null
        offered.takeIf { AppVersion.compare(it.version, currentVersion) > 0 }
    }

    private companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

object UpdateFeed {
    private val json = Json { ignoreUnknownKeys = true }
    private const val DOWNLOADS_ITEM_ID = "zerifin-android"

    /** The offered version and APK address, or null when the feed names no APK. */
    fun parse(feedUrl: String, body: String): AvailableUpdate? {
        val root = json.parseToJsonElement(body)
        val release = root as? JsonObject
        return when {
            release?.containsKey("tag_name") == true -> parseGitHub(release)
            release?.get("items") is JsonArray -> parseDownloadsIndex(feedUrl, release["items"]!!.jsonArray)
            root is JsonArray -> parseDownloadsIndex(feedUrl, root)
            else -> null
        }
    }

    private fun parseGitHub(release: JsonObject): AvailableUpdate? {
        val version = release.string("tag_name") ?: return null
        val apk = (release["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?.string("browser_download_url")
        val url = apk ?: release.string("html_url") ?: return null
        return AvailableUpdate(version.removePrefix("v"), url)
    }

    private fun parseDownloadsIndex(feedUrl: String, items: JsonArray): AvailableUpdate? {
        val item = items.mapNotNull { it as? JsonObject }.firstOrNull { it.string("id") == DOWNLOADS_ITEM_ID }
            ?: return null
        val version = item.string("version") ?: return null
        val link = item.string("url") ?: return null
        val absolute = feedUrl.toHttpUrlOrNull()?.resolve(link)?.toString() ?: return null
        return AvailableUpdate(version.removePrefix("v"), absolute)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull
}

/** Compares versions like 0.1.2, v0.1.0-beta.7 and 0.0.0-dev.1 (a release outranks its pre-releases). */
object AppVersion {
    fun compare(left: String, right: String): Int {
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)
        for (index in 0 until maxOf(leftCore.size, rightCore.size)) {
            val difference = leftCore.getOrElse(index) { 0 }.compareTo(rightCore.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> comparePreRelease(leftPre, rightPre)
        }
    }

    private fun split(version: String): Pair<List<Int>, String?> {
        val trimmed = version.trim().removePrefix("v").substringBefore('+')
        val core = trimmed.substringBefore('-')
        val pre = trimmed.substringAfter('-', "").ifEmpty { null }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }

    private fun comparePreRelease(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val a = leftParts.getOrNull(index) ?: return -1
            val b = rightParts.getOrNull(index) ?: return 1
            val numberA = a.toIntOrNull()
            val numberB = b.toIntOrNull()
            val difference = if (numberA != null && numberB != null) numberA.compareTo(numberB) else a.compareTo(b)
            if (difference != 0) return difference
        }
        return 0
    }
}
