package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myurrrjj.chronicle.core.util.ThumbnailSizes
import com.myurrrjj.chronicle.domain.model.GalleryViewMode
import com.myurrrjj.chronicle.domain.model.MediaAsset
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet

@Composable
fun UniversalMediaGrid(
    groupedMedia: ImmutableMap<String, ImmutableList<MediaAsset>>,
    currentViewMode: GalleryViewMode,
    gridState: LazyGridState,
    selectionMode: Boolean,
    selectedMediaIds: ImmutableSet<Long>,
    onMediaClick: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onViewModeChanged: (GalleryViewMode) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    tabAnimatedVisibilityScope: AnimatedVisibilityScope,
    albumAnimatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    contentTopPadding: Dp = 8.dp,
    contentBottomPadding: Dp = 0.dp,
    contentStartPadding: Dp = 0.dp,
    activeSharedMediaId: Long? = null,
    openedAlbumCoverId: Long? = null

) {
    val spacing = 2.dp
    val sidePadding = 2.dp
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val availableWidthDp = screenWidthDp - (sidePadding * 2) - contentStartPadding


    val decodePx = remember(density, availableWidthDp, currentViewMode) {
        ThumbnailSizes.decodePxForGrid(
            density = density,
            availableWidthDp = availableWidthDp,
            minCellDp = currentViewMode.minCellSizeDp.dp,
            spacingDp = spacing
        )
    }


    val isDense = currentViewMode.isDense
    val isMicroCell = currentViewMode.isMicroCell
    val transitionActive = !isDense && sharedTransitionScope.isTransitionActive

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = currentViewMode.minCellSizeDp.dp),
        contentPadding = PaddingValues(
            top = contentTopPadding,
            start = sidePadding + contentStartPadding,
            bottom = contentBottomPadding,
            end = sidePadding
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = modifier
            .fillMaxSize()
            .galleryZoomGesture(
                currentMode = currentViewMode, onViewModeChanged = onViewModeChanged
            )
    ) {
        groupedMedia.forEach { (groupKey, mediaItems) ->
            if (mediaItems.isNotEmpty()) {
                item(
                    key = "header_$groupKey",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "contentType1"
                ) {
                    val firstTimestamp = mediaItems.first().dateAdded
                    val headerText = remember(groupKey, currentViewMode, firstTimestamp) {
                        currentViewMode.formatHeader(firstTimestamp)
                    }
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 24.dp)
                            .then(if (isMicroCell) Modifier else Modifier.animateItem())
                    )
                }
                items(
                    count = mediaItems.size,
                    contentType = { index -> if (mediaItems[index].isVideo) "video" else "photo" },
                    key = { index -> mediaItems[index].id }) { index ->
                    val mediaItem = mediaItems[index]
                    val isSelected = selectedMediaIds.contains(mediaItem.id)
                    val itemId = mediaItem.id
                    val isSharedTarget  = itemId == activeSharedMediaId
                    val isAlbumCoverTarget = itemId == openedAlbumCoverId

                    MediaItemCard(
                        isDense = isDense,
                        isMicroCell = isMicroCell,
                        decodePx = decodePx,
                        transitionActive = transitionActive && (isSharedTarget || isAlbumCoverTarget),
                        media = mediaItem,
                        isSelected = isSelected,
                        selectionMode = selectionMode,
                        onEnterSelectionMode = onEnterSelectionMode,
                        sharedTransitionScope = sharedTransitionScope,
                        tabAnimatedVisibilityScope = tabAnimatedVisibilityScope,
                        albumAnimatedVisibilityScope = albumAnimatedVisibilityScope,
                        modifier = if (isMicroCell) Modifier else Modifier.animateItem(),
                        isAlbumCoverTarget = isAlbumCoverTarget,
                        isSharedTarget = isSharedTarget,
                        onMediaClick = onMediaClick,
                        onToggleSelection = onToggleSelection,
                    )
                }
            }
        }
    }
}
