package org.jellyfin.mobile.player.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

class AnkiDroidGateway private constructor(
    private val applicationContext: Context,
    private val preferences: AnkiMiningPreferences,
    private val api: AnkiApi,
    private val ioDispatcher: CoroutineDispatcher,
) : AnkiGateway {
    private val miningMutex = Mutex()

    override fun access(): AnkiAccess = when {
        !api.isAvailable() -> AnkiAccess.UNAVAILABLE
        ContextCompat.checkSelfPermission(applicationContext, ANKI_READ_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED -> AnkiAccess.PERMISSION_REQUIRED
        else -> AnkiAccess.AVAILABLE
    }

    override suspend fun getDecks(): List<AnkiCollectionItem> = queryCollection("deck") {
        api.getDeckList()
    }

    override suspend fun getModels(): List<AnkiCollectionItem> = queryCollection("note type") {
        api.getModelList()
    }

    override suspend fun getFields(modelId: Long): List<String> = withContext(ioDispatcher) {
        requireAvailableAccess()
        checkNotNull(api.getFieldList(modelId)) { "AnkiDroid did not return the note fields" }
    }

    override fun loadPreset(): AnkiMiningPreset? = preferences.load()

    override fun savePreset(preset: AnkiMiningPreset) = preferences.save(preset)

    override fun clearPreset() = preferences.clear()

    override fun suggestMappings(fieldNames: List<String>): List<AnkiFieldMapping> =
        AnkiMappingSuggester.suggest(fieldNames)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun mine(snapshot: AnkiMiningSnapshot): AnkiMineResult = withContext(ioDispatcher) {
        when (access()) {
            AnkiAccess.UNAVAILABLE -> return@withContext AnkiMineResult.Unavailable
            AnkiAccess.PERMISSION_REQUIRED -> return@withContext AnkiMineResult.PermissionRequired
            AnkiAccess.AVAILABLE -> Unit
        }
        val preset = preferences.load() ?: return@withContext AnkiMineResult.ConfigMissing

        try {
            miningMutex.withLock { AnkiMiningEngine(api).mine(preset, snapshot) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            when (access()) {
                AnkiAccess.PERMISSION_REQUIRED -> AnkiMineResult.PermissionRequired
                AnkiAccess.UNAVAILABLE -> AnkiMineResult.Unavailable
                AnkiAccess.AVAILABLE -> AnkiMineResult.Failed("AnkiDroid denied database access")
            }
        } catch (_: RuntimeException) {
            AnkiMineResult.Failed("The AnkiDroid request failed")
        } catch (_: IOException) {
            AnkiMineResult.Failed("The selected audio or picture could not be read")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun checkDuplicate(snapshot: AnkiMiningSnapshot): AnkiDuplicateStatus = withContext(ioDispatcher) {
        if (access() != AnkiAccess.AVAILABLE) return@withContext AnkiDuplicateStatus.UNKNOWN
        val preset = preferences.load() ?: return@withContext AnkiDuplicateStatus.UNKNOWN
        try {
            miningMutex.withLock { AnkiMiningEngine(api).checkDuplicate(preset, snapshot) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            AnkiDuplicateStatus.UNKNOWN
        }
    }

    private suspend fun queryCollection(
        itemName: String,
        query: () -> Map<Long, String>?,
    ): List<AnkiCollectionItem> = withContext(ioDispatcher) {
        requireAvailableAccess()
        checkNotNull(query()) { "AnkiDroid did not return its $itemName list" }
            .map { (id, name) -> AnkiCollectionItem(id, name) }
            .sortedWith(
                compareBy<AnkiCollectionItem> { it.name.lowercase(Locale.ROOT) }
                    .thenBy(AnkiCollectionItem::id),
            )
    }

    private fun requireAvailableAccess() {
        when (access()) {
            AnkiAccess.AVAILABLE -> Unit
            AnkiAccess.PERMISSION_REQUIRED -> throw SecurityException("AnkiDroid permission is required")
            AnkiAccess.UNAVAILABLE -> error("AnkiDroid is unavailable")
        }
    }

    companion object {
        @Volatile
        private var instance: AnkiDroidGateway? = null

        fun get(context: Context): AnkiDroidGateway = instance ?: synchronized(this) {
            val applicationContext = context.applicationContext
            instance ?: AnkiDroidGateway(
                applicationContext = applicationContext,
                preferences = AnkiMiningPreferences.get(applicationContext),
                api = AddContentAnkiApi(applicationContext),
                ioDispatcher = Dispatchers.IO,
            ).also { instance = it }
        }
    }
}

private class AddContentAnkiApi(
    private val context: Context,
) : AnkiApi {
    private val delegate by lazy { AddContentApi(context) }

    override fun isAvailable(): Boolean = AddContentApi.getAnkiDroidPackageName(context) != null

    override fun getDeckList(): Map<Long, String>? = context.contentResolver.query(
        FlashCardsContract.Deck.CONTENT_ALL_URI,
        arrayOf(
            FlashCardsContract.Deck.DECK_ID,
            FlashCardsContract.Deck.DECK_NAME,
            FlashCardsContract.Deck.DECK_DYN,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_NAME)
        val dynamicColumn = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_DYN)
        buildMap {
            while (cursor.moveToNext()) {
                if (cursor.getInt(dynamicColumn) == 0) {
                    put(cursor.getLong(idColumn), cursor.getString(nameColumn))
                }
            }
        }
    }

    override fun getModelList(): Map<Long, String>? = delegate.modelList

    override fun getFieldList(modelId: Long): List<String>? = delegate.getFieldList(modelId)?.toList()

    override fun countDuplicateNotes(modelId: Long, firstFieldValue: String): Int? {
        val duplicates = delegate.findDuplicateNotes(modelId, listOf(firstFieldValue)) ?: return null
        return duplicates[0]?.size ?: 0
    }

    override fun importMedia(media: AnkiMediaFile): String? {
        val directory = File(context.cacheDir, "anki-media").canonicalFile
        val file = media.file.canonicalFile
        require(file.parentFile == directory && file.isFile && file.length() > 0) {
            "Mining media must be a nonempty temporary file"
        }
        require(media.mimeType.startsWith("audio/") || media.mimeType.startsWith("image/")) {
            "Mining only supports audio and pictures"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.anki-media", file)
        val packageName = AddContentApi.getAnkiDroidPackageName(context) ?: return null
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            val values = ContentValues().apply {
                put(FlashCardsContract.AnkiMedia.FILE_URI, uri.toString())
                put(FlashCardsContract.AnkiMedia.PREFERRED_NAME, mediaName(file))
            }
            context.contentResolver.insert(FlashCardsContract.AnkiMedia.CONTENT_URI, values)?.lastPathSegment
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun mediaName(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return "zerifin_${digest.digest().joinToString("") { byte -> "%02x".format(byte) }}"
    }

    override fun addNote(
        modelId: Long,
        deckId: Long,
        fields: List<String>,
        tags: Set<String>,
    ): Long? = delegate.addNote(modelId, deckId, fields.toTypedArray(), tags)
}
