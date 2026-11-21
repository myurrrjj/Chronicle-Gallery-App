package com.myurrrjj.chronicle.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "place_cache")
data class PlaceCacheEntity(
    @PrimaryKey val bucketKey: String,
    val city: String?,
    val state: String?,
    val country: String?,
    val displayName: String,
    val updatedAtMs: Long
)
