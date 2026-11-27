package com.myurrrjj.chronicle.data.local.entity


import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_index_fingerprints")
data class SearchIndexFingerprintEntity(
    @PrimaryKey
    val mediaId: Long,
    val fingerprint: Long
)