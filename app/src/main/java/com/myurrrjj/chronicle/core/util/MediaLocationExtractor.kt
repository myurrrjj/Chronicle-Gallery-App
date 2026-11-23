package com.myurrrjj.chronicle.core.util

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.net.toUri

data class ExtractedCoordinates(
    val latitude: Double,
    val longitude: Double,
    val bucketKey: String
)


@Singleton
class MediaLocationExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver
) {

    fun extract(uriString: String, isVideo: Boolean): ExtractedCoordinates? {
        val rawUri = uriString.toUri()
        val uri = if (!isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.setRequireOriginal(rawUri) }.getOrDefault(rawUri)
        } else {
            rawUri
        }

        val coords = if (isVideo) extractVideoCoords(uri) else extractPhotoCoords(uri)
        val (lat, lng) = coords ?: return null
        if (!isValidCoordinate(lat, lng)) return null

        return ExtractedCoordinates(
            latitude = lat,
            longitude = lng,
            bucketKey = toBucketKey(lat, lng)
        )
    }

    private fun extractPhotoCoords(uri: Uri): Pair<Double, Double>? {
        return runCatching {
            contentResolver.openFileDescriptor(uri,"r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val latLong = exif.latLong ?: return@use null
                latLong[0] to latLong[1]
            }
        }.getOrNull()
    }


    private fun extractVideoCoords(uri:Uri): Pair<Double, Double>?{
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context,uri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?:return null
            parseIso6709(raw)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private val ISO_6709_REGEX = Regex(
            """^([+-]\d{1,3}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?/?$"""
        )
        fun parseIso6709(raw: String): Pair<Double, Double>? {
            val match = ISO_6709_REGEX.find(raw.trim()) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lng = match.groupValues[2].toDoubleOrNull() ?: return null
            return lat to lng
        }

        fun isValidCoordinate(lat: Double, lng: Double): Boolean {
            if (!lat.isFinite() || !lng.isFinite()) return false
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
            if (abs(lat) < 0.0001 && abs(lng) < 0.0001) return false
            return true
        }

        fun toBucketKey(lat: Double, lng: Double): String {
            val latBucket = (lat * 20.0).roundToInt()
            val lngBucket = (lng * 20.0).roundToInt()
            return "${latBucket}_${lngBucket}"
        }
    }
}