package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.myurrrjj.chronicle.core.util.ThumbnailSizes
import com.myurrrjj.chronicle.domain.model.MediaAsset

private val CardCornerShape = RoundedCornerShape(12.dp)
private val AlbumOverlayShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemCard(
    isDense: Boolean = false,
    isMicroCell: Boolean = false,
    decodePx: Int = ThumbnailSizes.MAX_EDGE,
    transitionActive: Boolean = false,
    isSharedTarget: Boolean = false,
    isAlbumCoverTarget: Boolean = false,
    media: MediaAsset,
    isSelected: Boolean,
    selectionMode: Boolean,
    onMediaClick: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onEnterSelectionMode: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    tabAnimatedVisibilityScope: AnimatedVisibilityScope,
    albumAnimatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaId = media.id
    val imageRequest = remember(media.uriString, decodePx, isSharedTarget, transitionActive) {
        val thumbKey = ThumbnailSizes.cacheKey(media.uriString, decodePx)
        val fullKey = if (isSharedTarget) ThumbnailSizes.fullCacheKey(media.uriString) else null
        val hasFullInCache =
            fullKey != null && context.imageLoader.memoryCache?.get(MemoryCache.Key(fullKey)) != null



        if (hasFullInCache) {
            ImageRequest.Builder(context)
                .data(media.uriString)
                .memoryCacheKey(fullKey)
                .placeholderMemoryCacheKey(thumbKey)
                .build()
        } else {
            ImageRequest.Builder(context)
                .data(media.uriString)
                .size(decodePx)
                .memoryCacheKey(thumbKey)
                .setParameter("is_thumbnail", true)
                .build()
        }
    }

    val painter = rememberAsyncImagePainter(
        model = imageRequest,
        contentScale = ContentScale.Crop,
        filterQuality = if (isMicroCell && !isSharedTarget) FilterQuality.None else FilterQuality.Low
    )

    val clickHandler = remember(mediaId, selectionMode, onToggleSelection, onMediaClick) {
        {
            if (selectionMode) {
                onToggleSelection(mediaId)
            } else {
                onMediaClick(mediaId)
            }
        }
    }

    val longClickHandler =
        remember(mediaId, selectionMode, onEnterSelectionMode, onToggleSelection) {
            {
                if (!selectionMode) {
                    onEnterSelectionMode()
                    onToggleSelection(mediaId)
                }
            }
        }

    val indication = if (!isMicroCell) null else LocalIndication.current



    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .then(if (transitionActive) Modifier.zIndex(1f) else Modifier)
                .then(if (isMicroCell) Modifier else Modifier.clip(CardCornerShape))

                .combinedClickable(
                    interactionSource = null,
                    indication = indication,
                    onClick = clickHandler,
                    onLongClick = longClickHandler
                )
        ) {
            var imageModifier: Modifier = Modifier.fillMaxSize()
            if (isSharedTarget) {
                imageModifier = imageModifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "media_$mediaId"),
                    animatedVisibilityScope = tabAnimatedVisibilityScope,
                    renderInOverlayDuringTransition = true,
                    clipInOverlayDuringTransition = OverlayClip(
                        if (isMicroCell) RoundedCornerShape(2.dp) else CardCornerShape
                    )
                )
            }

            if (isAlbumCoverTarget) {
                imageModifier = imageModifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "media_local_$mediaId"),
                    animatedVisibilityScope = albumAnimatedVisibilityScope,
                    renderInOverlayDuringTransition = true,
                    clipInOverlayDuringTransition = OverlayClip(AlbumOverlayShape)
                )
            }

            if (media.isVideo && !isMicroCell) {
                imageModifier = imageModifier.drawWithCache {
                    val brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent, Color.Black.copy(alpha = 0.55f)
                        ), startY = size.height * 0.5f, endY = size.height
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = brush)
                    }
                }
            }


            Image(
                painter = painter,
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = imageModifier
            )


            if (media.isVideo) {
                if (!isMicroCell) {
                    val formattedDuration = remember(media.duration) {
                        media.duration?.let { formatDuration(it) }
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                    )
                    if (formattedDuration != null) {
                        Text(
                            text = formattedDuration,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomStart)
                            .padding(2.dp)
                    )
                }
            }

            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else Color.Black.copy(alpha = 0.1f)
                        )
                )
                if (!isMicroCell) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Unselected",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return buildString(5) {
        if (minutes < 10) append('0')
        append(minutes)
        append(':')
        if (seconds < 10) append('0')
        append(seconds)
    }
}