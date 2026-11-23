package com.myurrrjj.chronicle.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myurrrjj.chronicle.data.local.entity.MediaLocationEntity
import com.myurrrjj.chronicle.data.local.entity.PlaceCacheEntity
import com.myurrrjj.chronicle.domain.model.MediaLocationInfo
import kotlinx.coroutines.flow.Flow

data class GeotaggedPhotoRow(
    val mediaId: Long,
    val uriString: String,
    val isVideo: Boolean,
    val timestampSec: Long,
    val latitude: Double,
    val longitude: Double,
    val bucketKey: String?,
    val placeName: String?
)

data class LatLngSample(
    val latitude: Double,
    val longitude: Double
)

data class IndexedMediaRef(
    val mediaId: Long,
    val uriString: String,
    val extractorVersion: Int
)

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(items: List<MediaLocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlace(place: PlaceCacheEntity)

    @Query("UPDATE media_location SET uriString = :uriString WHERE mediaId = :mediaId")
    suspend fun updateUri(mediaId: Long, uriString: String)

    @Query("DELETE FROM media_location WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByIds(mediaIds: List<Long>)

    @Query("SELECT mediaId, uriString, extractorVersion FROM media_location")
    suspend fun getIndexedMedia(): List<IndexedMediaRef>

    @Query(
        "SELECT DISTINCT bucketKey FROM media_location WHERE bucketKey IS NOT NULL " +
                "AND bucketKey NOT IN (SELECT bucketKey FROM place_cache)"
    )
    suspend fun getUncachedBucketKeys(): List<String>

    @Query(
        "SELECT latitude, longitude FROM media_location " +
                "WHERE bucketKey = :bucketKey AND hasLocation = 1 LIMIT 1"
    )
    suspend fun getSamplePointForBucket(bucketKey: String): LatLngSample?

    @Query(
        """
        SELECT m.mediaId, m.uriString, m.isVideo, m.timestampSec,
               m.latitude, m.longitude, m.bucketKey, p.displayName AS placeName
        FROM media_location m
        LEFT JOIN place_cache p ON m.bucketKey = p.bucketKey
        WHERE m.hasLocation = 1 AND m.latitude IS NOT NULL AND m.longitude IS NOT NULL
        ORDER BY m.timestampSec DESC
        """
    )
    fun observeAllGeotaggedPhotos(): Flow<List<GeotaggedPhotoRow>>

    @Query(
        """
        SELECT m.latitude, m.longitude, p.displayName AS placeName
        FROM media_location m
        LEFT JOIN place_cache p ON m.bucketKey = p.bucketKey
        WHERE m.mediaId = :mediaId AND m.hasLocation = 1
        LIMIT 1
        """
    )
    suspend fun getLocationFor(mediaId: Long): MediaLocationInfo?

    @Query(
        """
        SELECT m.mediaId, m.uriString, m.isVideo, m.timestampSec,
               m.latitude, m.longitude, m.bucketKey, p.displayName AS placeName
        FROM media_location m
        LEFT JOIN place_cache p ON m.bucketKey = p.bucketKey
        WHERE m.hasLocation = 1
          AND m.latitude BETWEEN :minLat AND :maxLat
          AND m.longitude BETWEEN :minLng AND :maxLng
        ORDER BY m.timestampSec DESC
        """
    )
    suspend fun getPhotosInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<GeotaggedPhotoRow>
}
