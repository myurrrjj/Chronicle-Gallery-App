package com.myurrrjj.chronicle.data.repository

import androidx.room.withTransaction
import com.myurrrjj.chronicle.data.local.GalleryDatabase
import com.myurrrjj.chronicle.data.local.dao.SearchDao
import com.myurrrjj.chronicle.data.local.entity.SearchIndexEntity
import com.myurrrjj.chronicle.data.local.entity.SearchIndexFingerprintEntity
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.repository.SEARCH_INDEX_FORMAT_VERSION
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao,
    private val database: GalleryDatabase,
) : SearchRepository {

    private val syncMutex = Mutex()


    override suspend fun syncSearchIndex(
        mediaList: List<MediaAsset>, favouriteIds: Set<Long>
    ): Flow<Float> = flow {

        emit(0f)

        syncMutex.withLock {
            val zoneId = ZoneId.systemDefault()
            val locale = Locale.getDefault(Locale.Category.FORMAT)

            val dateFormatter = DateTimeFormatter.ofPattern(
                DATE_PATTERN, locale
            )

            val currentMedia = mediaList.associateBy { it.id }

            val currentFingerprints = HashMap<Long, Long>(
                currentMedia.size
            )

            for ((mediaId, media) in currentMedia) {
                currentCoroutineContext().ensureActive()

                currentFingerprints[mediaId] = mediaFingerprint(
                    media = media,
                    isFavourite = mediaId in favouriteIds,
                    zoneId = zoneId,
                    locale = locale
                )
            }

            database.withTransaction {

                val savedFingerprints =
                    searchDao.getAllFingerprints().associate { it.mediaId to it.fingerprint }

                val indexedRows = searchDao.getIndexRowRefs()

                val indexedRowsByMediaId = indexedRows.associateBy { it.mediaId }

                val needsReset =
                    indexedRows.size != indexedRowsByMediaId.size || indexedRowsByMediaId.keys != savedFingerprints.keys

                if (needsReset) {
                    searchDao.clearIndex()
                    searchDao.clearFingerprints()
                }

                val previousFingerprints = if (needsReset) {
                    emptyMap()
                } else {
                    savedFingerprints
                }

                val previousIndexRows = if (needsReset) {
                    emptyMap()
                } else {
                    indexedRowsByMediaId
                }

                val removedMediaIds =
                    previousFingerprints.keys.filterNot { it in currentFingerprints }

                val changedMedia = currentMedia.values.filter { media ->
                    previousFingerprints[media.id] != currentFingerprints.getValue(media.id)
                }

                val obsoleteRowIds =
                    (removedMediaIds + changedMedia.map { it.id }).mapNotNull { mediaId ->
                            previousIndexRows[mediaId]?.rowId
                        }

                for (batch in obsoleteRowIds.chunked(CHUNK_SIZE)) {
                    currentCoroutineContext().ensureActive()

                    searchDao.deleteIndexRowsByRowIds(batch)
                }

                for (batch in removedMediaIds.chunked(CHUNK_SIZE)) {
                    currentCoroutineContext().ensureActive()

                    searchDao.deleteFingerprintsByMediaIds(batch)
                }

                for (batch in changedMedia.chunked(CHUNK_SIZE)) {
                    currentCoroutineContext().ensureActive()

                    val indexRows = batch.map { media ->
                        media.toIndexRow(
                            favouriteIds, dateFormatter, zoneId
                        )
                    }

                    val fingerprints = batch.map { media ->
                        SearchIndexFingerprintEntity(
                            mediaId = media.id, fingerprint = currentFingerprints.getValue(
                                media.id
                            )
                        )
                    }

                    searchDao.insertAll(indexRows)
                    searchDao.upsertFingerprints(fingerprints)
                }
            }
        }

        emit(1f)

    }.flowOn(Dispatchers.IO)


    override suspend fun searchMediaIds(query: String): List<Long> = withContext(Dispatchers.IO) {
        val matchQuery = buildPrefixMatchQuery(query)
        if (matchQuery.isBlank()) emptyList() else searchDao.searchMediaIds(matchQuery)
    }

    override fun observeIndexChanges(): Flow<Unit> {
        return database.invalidationTracker
            .createFlow("search_index_fingerprints")
            .map { Unit }
    }


    private fun mediaFingerprint(
        media: MediaAsset, isFavourite: Boolean, zoneId: ZoneId, locale: Locale
    ): Long {
        var hash = FNV_OFFSET_BASIS

        hash = hash.mix(SEARCH_INDEX_FORMAT_VERSION.toLong())
        hash = hash.mix(zoneId.id)
        hash = hash.mix(locale.toLanguageTag())

        hash = hash.mix(media.id)
        hash = hash.mix(media.name)
        hash = hash.mix(media.folderName ?: UNKNOWN_FOLDER)
        hash = hash.mix(media.dateAdded)
        hash = hash.mix(if (media.isVideo) 1L else 0L)
        hash = hash.mix(if (isFavourite) 1L else 0L)

        return hash
    }


    private fun MediaAsset.toIndexRow(
        favouriteIds: Set<Long>,
        dateFormatter: DateTimeFormatter,
        zoneId: ZoneId
    ): SearchIndexEntity {

        val date = Instant.ofEpochSecond(dateAdded)
            .atZone(zoneId)
            .toLocalDate()

        val searchableTerms = buildList {
            add(name)
            add(folderName ?: UNKNOWN_FOLDER)

            if (isVideo) {
                add("video mp4 videos")
            } else {
                add("photo image images photos")
            }

            if (id in favouriteIds) {
                add("favorite favorites favourites favourite")
            }

            add(date.format(dateFormatter))
            add(date.toSearchDateToken())
        }.joinToString(" ").lowercase(Locale.ROOT)

        return SearchIndexEntity(
            mediaId = id,
            terms = searchableTerms
        )
    }
    private fun buildPrefixMatchQuery(raw: String): String {
        val tokens = mutableListOf<String>()

        for (match in QUERY_TOKEN_REGEX.findAll(raw)) {
            val token = match.value.lowercase(Locale.ROOT)

            if ('-' in token || '/' in token) {
                val date = parseSearchDate(token) ?: return ""

                tokens.add(date.toSearchDateToken())
            } else {
                tokens.add("\"${token}*\"")
            }
        }

        return tokens.joinToString(" ")
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

    private fun LocalDate.toSearchDateToken(): String {
        return "chrondate${format(DateTimeFormatter.BASIC_ISO_DATE)}"
    }
    private fun parseSearchDate(raw: String): LocalDate? {
        return try {
            when {
                '-' in raw -> {
                    LocalDate.parse(raw)
                }

                '/' in raw -> {
                    val parts = raw.split('/')

                    LocalDate.of(
                        parts[2].toInt(),
                        parts[1].toInt(),
                        parts[0].toInt()
                    )
                }

                else -> null
            }
        } catch (e: DateTimeException) {
            null
        }
    }

    private companion object {
        const val INDEX_FORMAT_VERSION = 4

        const val DATE_PATTERN = "MMMM yyyy d"
        const val CHUNK_SIZE = 500
        const val UNKNOWN_FOLDER = "Unknown"

        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L

        val QUERY_TOKEN_REGEX = Regex(
            """(?<![\p{L}\p{N}])(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4})(?![\p{L}\p{N}])|[\p{L}\p{N}]+"""
        )

    }
}
