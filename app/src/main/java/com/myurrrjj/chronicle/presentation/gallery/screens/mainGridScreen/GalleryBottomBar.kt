package com.myurrrjj.chronicle.presentation.gallery.screens.mainGridScreen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.presentation.gallery.components.DetailAction
import com.myurrrjj.chronicle.presentation.gallery.components.DetailFloatingBar
import com.myurrrjj.chronicle.presentation.gallery.components.FloatingGalleryBar
import com.myurrrjj.chronicle.presentation.gallery.model.GalleryTab
import com.myurrrjj.chronicle.presentation.navigation.WindowLayout
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.Job

@Composable
fun SharedTransitionScope.GalleryBottomBar(
    selectionMode: Boolean,
    isMainGallery: Boolean,
    hasContent: Boolean,
    isScrollingUp: State<Boolean>,
    selectedMediaIds: ImmutableSet<Long>,
    masterMediaList: ImmutableList<MediaAsset>,
    selectedTab: GalleryTab,
    layout: WindowLayout,
    animatedVisibilityScope: AnimatedContentScope,
    onActionClick: (DetailAction, List<MediaAsset>) -> Unit,
    onTabClick: () -> Job,
    onTabSelected: (GalleryTab) -> Unit,
    onBarSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    val shouldShowBar by remember(hasContent) {
        derivedStateOf { !hasContent || isScrollingUp.value }
    }
    val bottomBarVisible = selectionMode || (isMainGallery && shouldShowBar)

    val barOffset by animateDpAsState(
        targetValue = if (bottomBarVisible) 0.dp else 150.dp, label = "barOffset"
    )
    val barAlpha by animateFloatAsState(
        targetValue = if (bottomBarVisible) 1f else 0f, label = "barAlpha"
    )

    Box(
        contentAlignment = if (layout.useNavigationRail) Alignment.CenterStart else Alignment.BottomCenter,
        modifier = modifier
            .fillMaxSize()
            .renderInSharedTransitionScopeOverlay(2f)
            .then(if (layout.useNavigationRail) Modifier else Modifier.navigationBarsPadding())
            .graphicsLayer {
                translationY = barOffset.toPx()
                alpha = barAlpha
            }
            .zIndex(1f)
    ) {
        AnimatedContent(
            targetState = selectionMode to layout.useNavigationRail, label = "BottomBarSwap"
        ) { (isSelectionMode, isRail) ->
            if (isSelectionMode) {
                val selectedMedia = masterMediaList.filter { it.id in selectedMediaIds }
                DetailFloatingBar(
                    isFavorite = false,
                    onActionClick = { action ->
                        onActionClick(action, selectedMedia)
                    },
                    allowedActions = listOfNotNull(
                        DetailAction.SHARE,
                        DetailAction.FAVOURITE,
                        DetailAction.DELETE,
                        if (selectedMedia.none { it.isVideo }) {
                            DetailAction.COPY
                        } else null
                    ),
                    modifier = Modifier.onSizeChanged(onBarSizeChanged)
                )
            } else if (isMainGallery) {
                FloatingGalleryBar(
                    selectedTab = selectedTab,
                    onClick = onTabClick,
                    onTabSelected = onTabSelected,
                    modifier = Modifier
                        .onSizeChanged(onBarSizeChanged)
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "floating_navigation_bar"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            zIndexInOverlay = 1f
                        ),
                    isVerticalRail = isRail
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}
