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
    val latitude: Double, val longitude: Double
)

data class IndexedMediaRef(
    val mediaId:Long,
    val uriString: String
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

    @Query("SELECT mediaId, uriString FROM media_location")
    suspend fun getIndexedMedia(): List<IndexedMediaRef>

    @Query("SELECT COUNT(*) FROM media_location WHERE hasLocation = 0")
    suspend fun countWithoutLocation(): Int

    @Query("DELETE FROM media_location WHERE hasLocation = 0")
    suspend fun clearRowsWithoutLocation()

    @Query("select mediaId from media_location")
    suspend fun getAllIndexedMediaIds(): List<Long>

    @Query("select distinct bucketKey from media_location where bucketKey is not null and bucketKey not in (select bucketKey from place_cache)")
    suspend fun getUncachedBucketKeys(): List<String>

    @Query("select latitude,longitude from media_location where bucketKey = :bucketKey and hasLocation=1 limit 1")
    suspend fun getSamplePointForBucket(bucketKey: String): LatLngSample?

    @Query(
        """
            select m.mediaId, m.uriString, m.IsVideo, m.timestampSec, m.latitude, m.longitude, m.bucketKey,p.displayName as placeName
            from media_Location m
            left join place_cache p on m.bucketKey = p.bucketKey
            where m.hasLocation =1 and m.latitude is not null and m.longitude is not null
            order by m.timestampSec desc
        """
    )
    fun observeAllGeotaggedPhotos(): Flow<List<GeotaggedPhotoRow>>

    @Query("""
    SELECT m.latitude, m.longitude, p.displayName AS placeName
    FROM media_location m
    LEFT JOIN place_cache p ON m.bucketKey = p.bucketKey
    WHERE m.mediaId = :mediaId AND m.hasLocation = 1
    LIMIT 1
""")
    suspend fun getLocationFor(mediaId: Long): MediaLocationInfo?


    @Query(
        """
        SELECT m.mediaId, m.uriString, m.isVideo, m.timestampSec, m.latitude, m.longitude, m.bucketKey, p.displayName AS placeName
        FROM media_location m
        LEFT JOIN place_cache p ON m.bucketKey = p.bucketKey
        WHERE m.hasLocation = 1
          AND m.latitude BETWEEN :minLat AND :maxLat
          AND m.longitude BETWEEN :minLng AND :maxLng
        ORDER BY m.timestampSec DESC
        """
    )
    suspend fun getPhotosInBounds(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double
    ): List<GeotaggedPhotoRow>


    @Query("select count(*) from media_location where hasLocation = 1")
    fun observeGeotaggedCount(): Flow<Int>

    @Query("delete from media_location where mediaId not in (:existingIds)")
    suspend fun pruneDeletedMedia(existingIds:List<Long>)








}