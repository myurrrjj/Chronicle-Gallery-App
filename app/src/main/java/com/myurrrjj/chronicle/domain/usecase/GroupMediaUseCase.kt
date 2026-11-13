package com.myurrrjj.chronicle.domain.usecase

import com.myurrrjj.chronicle.domain.model.GalleryViewMode
import com.myurrrjj.chronicle.domain.model.MediaAsset
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.time.ZoneId
import javax.inject.Inject

class GroupMediaUseCase @Inject constructor() {
    operator fun invoke(
        mediaList: List<MediaAsset>,
        viewMode: GalleryViewMode
    ): ImmutableMap<String, ImmutableList<MediaAsset>> {
        val zoneId = ZoneId.systemDefault()
        return mediaList
            .groupBy {viewMode.getGroupingKey(it.dateAdded,zoneId)}
            .mapValues { (_,items)->items.toImmutableList() }
            .toImmutableMap()
    }
}