package com.myurrrjj.chronicle.domain.repository

import com.myurrrjj.chronicle.domain.model.DetailedMediaInfo
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.MediaDetailsBundle
import com.myurrrjj.chronicle.domain.model.MediaRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface MediaRepository {
    fun getAllMedia(
        forceRefresh: Boolean = false
    ): Flow<List<MediaAsset>>
    fun observeMediaChanges(): Flow<Unit>
    fun getFavorites(): Flow<List<MediaAsset>>

    suspend fun toggleFavourite(media: MediaAsset, isFavourite: Boolean)

    suspend fun getMediaDetails(uriString: String, isVideo: Boolean): DetailedMediaInfo
    fun observeImageCount(): Flow<Int>

    suspend fun getMediaRefs():List<MediaRef>
    suspend fun getMediaDetailsBundle(mediaId: Long, uriString: String, isVideo: Boolean): MediaDetailsBundle


}