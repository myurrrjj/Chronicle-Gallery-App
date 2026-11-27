package com.myurrrjj.chronicle.presentation.gallery

import com.myurrrjj.chronicle.domain.model.DetailedMediaInfo
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.MediaFace
import com.myurrrjj.chronicle.domain.model.MediaLocationInfo

sealed interface InfoSheetState {
    object Closed : InfoSheetState
    data class Loading(val media: MediaAsset) : InfoSheetState
    data class Success(
        val media: MediaAsset,
        val details: DetailedMediaInfo,
        val location: MediaLocationInfo? = null,
        val faces: List<MediaFace> = emptyList(),
        val facesIndexed: Boolean = false,
        val storagePath: String? = null
    ) : InfoSheetState
    data class Error(val media: MediaAsset, val message: String) : InfoSheetState
}