package com.myurrrjj.chronicle.domain.model

data class MediaLocationInfo(
    val latitude: Double,
    val longitude: Double,
    val placeName: String?
)

data class MediaDetailsBundle(
    val details: DetailedMediaInfo,
    val location: MediaLocationInfo?
)
