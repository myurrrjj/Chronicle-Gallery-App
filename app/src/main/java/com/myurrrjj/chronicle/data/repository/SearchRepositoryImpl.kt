package com.myurrrjj.chronicle.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.room.withTransaction
import com.myurrrjj.chronicle.data.local.GalleryDatabase
import com.myurrrjj.chronicle.data.local.dao.SearchDao
import com.myurrrjj.chronicle.data.local.entity.SearchIndexEntity
import com.myurrrjj.chronicle.di.qualifiers.SearchIndexStore
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao,
    private val database: GalleryDatabase,
    @SearchIndexStore private val indexStateStore: DataStore<Preferences>
) : SearchRepository {

    private val syncMutex = Mutex()

    @Volatile
    private var lastCommittedSignature: Long? = null

    override suspend fun syncSearchIndex(
        mediaList: List<MediaAsset>, favouriteIds: Set<Long>
    ): Flow<Float> = flow {
        syncMutex.withLock {
            val zoneId = ZoneId.systemDefault()
            val signature = indexSignature(mediaList, favouriteIds, zoneId)

            if (isIndexUpToDate(signature, mediaList.size)) {
                emit(1f)
                return@withLock
            }

            if (mediaList.isEmpty()) {
                searchDao.clearIndex()
                commitSignature(signature)
                emit(1f)
                return@withLock
            }

            val dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN)

            val total = mediaList.size
            var processed = 0

            database.withTransaction {
                searchDao.clearIndex()

                for (batch in mediaList.chunked(CHUNK_SIZE)) {
                    yield()

                    val indices = batch.map { media ->
                        media.toIndexRow(favouriteIds, dateFormatter, zoneId)
                    }

                    searchDao.insertAll(indices)
                    processed += batch.size

                    emit(processed.toFloat() / total.toFloat())
                }
            }

            commitSignature(signature)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun searchMediaIds(query: String): List<Long> = withContext(Dispatchers.IO) {
        val matchQuery = buildPrefixMatchQuery(query)
        if (matchQuery.isBlank()) emptyList() else searchDao.searchMediaIds(matchQuery)
    }

    private suspend fun isIndexUpToDate(signature: Long, expectedRows: Int): Boolean {
        val committed = lastCommittedSignature ?: readSignature() ?: return false
        if (committed != signature) return false
        lastCommittedSignature = committed
        return searchDao.indexedCount() == expectedRows
    }

    private fun indexSignature(
        mediaList: List<MediaAsset>, favouriteIds: Set<Long>, zoneId: ZoneId
    ): Long {
        var hash = FNV_OFFSET_BASIS
        hash = hash.mix(INDEX_FORMAT_VERSION.toLong())
        hash = hash.mix(zoneId.id)
        hash = hash.mix(Locale.getDefault().toLanguageTag())
        hash = hash.mix(mediaList.size.toLong())

        for (media in mediaList.sortedBy { it.id }) {
            hash = hash.mix(media.id)
            hash = hash.mix(media.name)
            hash = hash.mix(media.folderName ?: UNKNOWN_FOLDER)
            hash = hash.mix(media.dateAdded)
            hash = hash.mix(if (media.isVideo) 1L else 0L)
            hash = hash.mix(if (media.id in favouriteIds) 1L else 0L)
        }

        return hash
    }

    private suspend fun readSignature(): Long? {
        return indexStateStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .first()[SIGNATURE_KEY]
    }

    private suspend fun commitSignature(value: Long) {
        lastCommittedSignature = value
        try {
            indexStateStore.edit { preferences ->
                preferences[SIGNATURE_KEY] = value
            }
        } catch (e: IOException) {
        }
    }

    private fun MediaAsset.toIndexRow(
        favouriteIds: Set<Long>, dateFormatter: DateTimeFormatter, zoneId: ZoneId
    ): SearchIndexEntity {
        val searchableTerms = buildString {
            append(name).append(' ')
            append(folderName ?: UNKNOWN_FOLDER).append(' ')
            if (isVideo) append("video mp4 ") else append("photo image ")
            if (id in favouriteIds) append("favorite favourites ")

            val dateStr =
                Instant.ofEpochSecond(dateAdded).atZone(zoneId).format(dateFormatter)
            append(dateStr)
        }

        return SearchIndexEntity(mediaId = id, terms = searchableTerms.lowercase())
    }

    private fun buildPrefixMatchQuery(raw: String): String {
        return raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            .joinToString(" ") { "${it}*" }
    }

    private fun Long.mix(value: Long): Long {
        var hash = this
        repeat(Long.SIZE_BYTES) { index ->
            hash = (hash xor ((value ushr (index * Byte.SIZE_BITS)) and 0xFF)) * FNV_PRIME
        }
        return hash
    }

    private fun Long.mix(value: String): Long {
        var hash = this.mix(value.length.toLong())
        for (char in value) {
            hash = (hash xor (char.code and 0xFF).toLong()) * FNV_PRIME
            hash = (hash xor ((char.code ushr 8) and 0xFF).toLong()) * FNV_PRIME
        }
        return hash
    }

    private companion object {
        const val INDEX_FORMAT_VERSION = 1

        const val DATE_PATTERN = "MMMM yyyy d"
        const val CHUNK_SIZE = 500
        const val UNKNOWN_FOLDER = "Unknown"

        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L

        val SIGNATURE_KEY = longPreferencesKey("search_index_signature")
    }
}
