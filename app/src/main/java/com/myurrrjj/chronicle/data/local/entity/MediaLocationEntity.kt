package com.myurrrjj.chronicle.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_location",
    indices = [
        Index(value = ["hasLocation", "latitude", "longitude"]),
        Index(value = ["bucketKey"]),
        Index(value = ["timestampSec"])
    ]
)
data class MediaLocationEntity(
    @PrimaryKey val mediaId: Long,
    val uriString: String,
    val isVideo: Boolean,
    val timestampSec: Long,
    val hasLocation: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val bucketKey: String?
)
