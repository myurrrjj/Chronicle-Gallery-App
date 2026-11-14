package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myurrrjj.chronicle.domain.model.GalleryViewMode
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.presentation.gallery.model.Album
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.AlbumDetailView(
    album: Album,
    groupedMedia: ImmutableMap<String, ImmutableList<MediaAsset>>,
    currentViewMode: GalleryViewMode,
    selectedMediaIds: ImmutableSet<Long> = persistentSetOf(),
    selectionMode: Boolean = false,
    onToggleSelection: (Long) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onViewModeChanged: (GalleryViewMode) -> Unit,
    tabAnimatedVisibilityScope: AnimatedVisibilityScope,
    albumAnimatedVisibilityScope: AnimatedVisibilityScope,
    onBackClick: () -> Unit,
    onMediaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 0.dp,
    activeSharedMediaId: Long? = null
) {
    val gridCacheWindow = remember {
        LazyLayoutCacheWindow(aheadFraction = 0.25f, behindFraction = 0.10f)
    }
    val gridState = rememberLazyGridState(cacheWindow = gridCacheWindow)


    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = album.name ?: "Unknown",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "album_title_${album.name}"),
                        animatedVisibilityScope = albumAnimatedVisibilityScope,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    ), maxLines = 2, overflow = TextOverflow.Ellipsis
//                    autoSize = TextAutoSize()
                )
            }
            Box(modifier = Modifier.weight(1f))
        }

        UniversalMediaGrid(
            groupedMedia = groupedMedia,
            currentViewMode = currentViewMode,
            gridState = gridState,
            selectionMode = selectionMode,
            selectedMediaIds = selectedMediaIds,
            onMediaClick = onMediaClick,
            onToggleSelection = onToggleSelection,
            onEnterSelectionMode = onEnterSelectionMode,
            onViewModeChanged = onViewModeChanged,
            sharedTransitionScope = this@AlbumDetailView,
            tabAnimatedVisibilityScope = tabAnimatedVisibilityScope,
            albumAnimatedVisibilityScope = albumAnimatedVisibilityScope,
            modifier = Modifier.weight(1f),
            contentBottomPadding = contentBottomPadding,
            activeSharedMediaId = activeSharedMediaId,
            openedAlbumCoverId = album.coverMedia.id

        )
    }
}