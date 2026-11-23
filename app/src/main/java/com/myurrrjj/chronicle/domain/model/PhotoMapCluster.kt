package com.myurrrjj.chronicle.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class PhotoMapCluster(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val coverMediaId: Long,
    val coverUriString: String,
    val count: Int,
    val placeName: String?,
    val newestTimestampSec: Long,
    val mediaIds: List<Long>
)