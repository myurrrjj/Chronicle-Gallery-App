package com.myurrrjj.chronicle.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.myurrrjj.chronicle.core.util.FaceCropLocator
import com.myurrrjj.chronicle.core.util.MediaVolumeStamp
import com.myurrrjj.chronicle.core.util.hasFullMediaAccess
import com.myurrrjj.chronicle.core.util.readMediaStoreStamp
import com.myurrrjj.chronicle.data.local.dao.FaceDao
import com.myurrrjj.chronicle.data.local.dao.FavouriteDao
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.data.local.entity.toFavoriteEntity
import com.myurrrjj.chronicle.domain.model.DetailedMediaInfo
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.MediaDetailsBundle
import com.myurrrjj.chronicle.domain.model.MediaFace
import com.myurrrjj.chronicle.domain.model.MediaRef
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MediaStoreRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    private val favouriteDao: FavouriteDao,
    private val locationDao: LocationDao,
    private val faceDao: FaceDao,
    private val faceCropLocator: FaceCropLocator,
    @ApplicationContext private val appContext: Context,
) : MediaRepository {

    private data class CachedMedia(
        val stamp: List<MediaVolumeStamp>, val media: List<MediaAsset>, val createdAt: Long
    )

    private val mediaSnapshotMutex = Mutex()

    private var cachedMedia: CachedMedia? = null

    private val cacheMaxAgeMs = 5 * 60 * 1000L

    override fun getAllMedia(
        forceRefresh: Boolean
    ): Flow<List<MediaAsset>> = flow {

        val media = mediaSnapshotMutex.withLock {

            if (!hasFullMediaAccess(appContext)) {
                cachedMedia = null
                throw SecurityException("Full media access required")
            }

            val stampBefore = readMediaStoreStamp(appContext)

            val existing = cachedMedia

            if (!forceRefresh && stampBefore != null && existing != null && existing.stamp == stampBefore && SystemClock.elapsedRealtime() - existing.createdAt < cacheMaxAgeMs) {
                return@withLock existing.media
            }

            cachedMedia = null

            val freshMedia = queryAllMedia().first()

            if (!hasFullMediaAccess(appContext)) {
                throw SecurityException("Media access was revoked")
            }

            val stampAfter = readMediaStoreStamp(appContext)

            if (stampBefore != null && stampBefore == stampAfter) {
                cachedMedia = CachedMedia(
                    stamp = stampBefore,
                    media = freshMedia,
                    createdAt = SystemClock.elapsedRealtime()
                )
            }

            freshMedia
        }

        emit(media)

    }.flowOn(Dispatchers.IO)


    private fun queryAllMedia(): Flow<List<MediaAsset>> = flow {
        val mediaList = mutableListOf<MediaAsset>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        val cursor = contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        ) ?: throw IllegalStateException("MediaStore query returned no cursor")

        cursor.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateAddedColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mimeTypeColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthColumn = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
            val bucketColumn =
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: ""
                val dateAdded = cursor.getLong(dateAddedColumn)
                val mimeType = cursor.getString(mimeTypeColumn) ?: ""
                val size = cursor.getLong(sizeColumn)

                val width = if (widthColumn != -1 && !cursor.isNull(widthColumn)) cursor.getInt(
                    widthColumn
                ) else null
                val height = if (heightColumn != -1 && !cursor.isNull(heightColumn)) cursor.getInt(
                    heightColumn
                ) else null
                val folderName = cursor.getString(bucketColumn) ?: "Unknown"
                val duration =
                    if (durationColumn != -1 && !cursor.isNull(durationColumn)) cursor.getLong(
                        durationColumn
                    ) else null

                val contentUri = ContentUris.withAppendedId(
                    if (mimeType.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id

                )
                mediaList.add(
                    MediaAsset(
                        id = id,
                        uriString = contentUri.toString(),
                        name = name,
                        dateAdded = dateAdded,
                        mimeType = mimeType,
                        size = size,
                        width = width,
                        height = height,
                        duration = duration,
                        folderName = folderName

                    )
                )
            }

        }

        val masterIds = mediaList.map { it.id }.toSet()
        val currentFavs = favouriteDao.getFavourites().first()
        for (fav in currentFavs) {
            if (fav.id !in masterIds) {
                favouriteDao.removeFavourite(fav.id)
            }
        }
        emit(mediaList)
    }.flowOn(Dispatchers.IO)

    override fun observeMediaChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

        }
        contentResolver.registerContentObserver(
            "content://${MediaStore.AUTHORITY}".toUri(), true, observer

        )
        trySend(Unit)
        awaitClose {
            contentResolver.unregisterContentObserver(observer)

        }
    }.conflate().flowOn(Dispatchers.IO)

    override fun getFavorites(): Flow<List<MediaAsset>> {
        return favouriteDao.getFavourites().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun toggleFavourite(
        media: MediaAsset, isFavourite: Boolean
    ) {
        if (isFavourite) {
            favouriteDao.insertFavourite(media.toFavoriteEntity())
        } else {
            favouriteDao.removeFavourite(media.id)
        }
    }

    override suspend fun getMediaDetails(
        uriString: String, isVideo: Boolean
    ): DetailedMediaInfo = withContext(Dispatchers.IO) {
        val uri = uriString.toUri()
        var info = DetailedMediaInfo()
        try {
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    val width =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toFloatOrNull()
                    val height =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toFloatOrNull()
                    val frameRate =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                            ?.toFloatOrNull()

                    val bitrate =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                            ?.toFloatOrNull()
                    val durationMs =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()

//                    val format = retriever.extractMetadata(MediaMetadataRetriever.)
                    val durationFormatted = durationMs?.let {
                        val seconds = (it / 1000) % 60
                        val minutes = (it / (1000 * 60)) % 60
                        String.format("%02d:%02d", minutes, seconds)

                    }
                    info = info.copy(
                        resolution = if (width != null && height != null) "${width}x${height}" else null,
                        frameRate = frameRate,
                        bitrate = bitrate?.let { "${it / 1000} kbps" },
                        durationFormatted = durationFormatted
                    )

                }
                retriever.release()

            } else {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    val exposureNum = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    info = info.copy(
                        resolution = if (width > 0 && height > 0) "${width}x${height}" else null,
                        megapixelCount = if (width > 0 && height > 0) (width * height) / 1000000f else null,
                        cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
                        cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
                        aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                        exposureTime = if (exposureNum > 0) "1/${(1 / exposureNum).toInt()}s" else null,
                        iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                            ?.let { "ISO $it" },
                        focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                            .let {
                                if (it > 0) "${it}mm" else null
                            })
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }


        return@withContext info
    }


    override fun observeImageCount(): Flow<Int> = flow {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
        )
        val count = contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            cursor.count
        } ?: 0
        emit(count)
    }.flowOn(Dispatchers.IO)


    override suspend fun getMediaRefs(): List<MediaRef> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        val out = ArrayList<MediaRef>(2048)
        contentResolver.query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val volumeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                } else -1

                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol) ?: continue
                    val isVideo = mime.startsWith("video/")
                    val id = cursor.getLong(idCol)

                    val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val volume =
                            cursor.getString(volumeCol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        if (isVideo) MediaStore.Video.Media.getContentUri(volume)
                        else MediaStore.Images.Media.getContentUri(volume)
                    } else if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    out += MediaRef(
                        id = id,
                        uriString = ContentUris.withAppendedId(base, id).toString(),
                        isVideo = isVideo,
                        dateAddedSec = cursor.getLong(dateCol)
                    )
                }
            }
        out
    }

    override suspend fun getMediaDetailsBundle(
        mediaId: Long, uriString: String, isVideo: Boolean
    ): MediaDetailsBundle = withContext(Dispatchers.IO) {
        val faceRows = if (isVideo) {
            emptyList()
        } else {
            runCatching { faceDao.getFacesForMedia(mediaId) }.getOrDefault(emptyList())
        }
        val facesIndexed = !isVideo && runCatching { faceDao.isMediaProcessed(mediaId) }.getOrDefault(false)

        MediaDetailsBundle(
            details = getMediaDetails(uriString, isVideo),
            location = locationDao.getLocationFor(mediaId),
            faces = faceRows.map { row ->
                MediaFace(
                    embeddingId = row.embeddingId,
                    faceIndex = row.faceIndex,
                    clusterId = row.clusterId,
                    personLabel = row.personLabel,
                    cropFile = faceCropLocator.existingCropFile(
                        mediaId = mediaId, faceIndex = row.faceIndex, embeddingId = row.embeddingId
                    )
                )
            },
            facesIndexed = facesIndexed,
            storagePath = resolveStoragePath(uriString)
        )
    }


    private fun resolveStoragePath(uriString: String): String? {
        val uri = uriString.toUri()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.VOLUME_NAME
                )
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val volCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)

                    val name = if (nameCol != -1) cursor.getString(nameCol) else null
                    if (name.isNullOrBlank()) return@use null

                    val relative = if (relCol != -1) cursor.getString(relCol) else null
                    val volume = if (volCol != -1) cursor.getString(volCol) else null
                    val root = if (volume == null || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        "Internal storage"
                    } else {
                        "SD card"
                    }
                    joinPath(root, relative, name)
                }
            } else {
                @Suppress("DEPRECATION") val projection = arrayOf(MediaStore.MediaColumns.DATA)
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    @Suppress("DEPRECATION") val dataCol =
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val absolute = if (dataCol != -1) cursor.getString(dataCol) else null
                    if (absolute.isNullOrBlank()) null else fromAbsolutePath(absolute)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun joinPath(root: String, relativePath: String?, name: String): String {
        val segments = ArrayList<String>(6)
        segments.add(root)
        relativePath?.split('/')?.forEach { if (it.isNotBlank()) segments.add(it) }
        segments.add(name)
        return segments.joinToString(" / ")
    }

    private fun fromAbsolutePath(absolute: String): String {
        val normalised = absolute.replace('\\', '/')

        PRIMARY_STORAGE_REGEX.find(normalised)?.let { match ->
            return tidyPath("Internal storage / ${match.groupValues[1]}")
        }

        REMOVABLE_STORAGE_REGEX.find(normalised)?.let { match ->
            return tidyPath("SD card / ${match.groupValues[2]}")
        }

        return normalised
    }

    private fun tidyPath(path: String): String =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    companion object {
        private val PRIMARY_STORAGE_REGEX = Regex("^/storage/emulated/\\d+/(.*)$")
        private val REMOVABLE_STORAGE_REGEX = Regex("^/storage/([^/]+)/(.*)$")
    }


}

