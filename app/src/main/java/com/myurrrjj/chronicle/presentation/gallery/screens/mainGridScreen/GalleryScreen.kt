package com.myurrrjj.chronicle.presentation.gallery.screens.mainGridScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myurrrjj.chronicle.core.presentation.indexing.FaceIndexingStartSheet
import com.myurrrjj.chronicle.core.util.rememberScrollingUp
import com.myurrrjj.chronicle.domain.model.PersonCluster
import com.myurrrjj.chronicle.presentation.gallery.GalleryViewModel
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryEvent
import com.myurrrjj.chronicle.presentation.gallery.model.Album
import com.myurrrjj.chronicle.presentation.gallery.model.GalleryTab
import com.myurrrjj.chronicle.presentation.gallery.model.OthersSelection
import com.myurrrjj.chronicle.presentation.navigation.rememberWindowLayout
import kotlinx.coroutines.launch
import kotlin.math.max

data class ScreenTarget(
    val album: Album?, val person: PersonCluster?
)


@Suppress("EffectKeys")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedContentScope,
    viewModel: GalleryViewModel = hiltViewModel(),
    onRequestPermission: () -> Unit
) {
    val gridCacheWindow = remember{
        LazyLayoutCacheWindow(aheadFraction = .25f, behindFraction = .1f)

    }
    val gridState = rememberLazyGridState(cacheWindow =gridCacheWindow)
    val isScrollingUp = rememberScrollingUp(gridState)
    val coroutineScope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentState = state.contentState
    val configState = state.configState
    val interactionState = state.interactionState

    val layout = rememberWindowLayout()

    var bottomBarSize by remember { mutableStateOf(IntSize.Zero) }
    var maxTopBarHeightPx by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val bottomBarHeight = with(density) { bottomBarSize.height.toDp() }
    val bottomBarWidth = with(density) { bottomBarSize.width.toDp() }
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeightDp = if (maxTopBarHeightPx > 0) {
        with(density) { maxTopBarHeightPx.toDp() }
    } else {
        statusBarInset + 64.dp
    }
    val contentTopPadding = topBarHeightDp + 8.dp

    val contentBottomPadding = if (layout.useNavigationRail) {
        navigationBarInset + 16.dp
    } else {
        bottomBarHeight + navigationBarInset + 16.dp
    }
    val contentStartPadding = if (layout.useNavigationRail) {
        bottomBarWidth + 8.dp
    } else {
        0.dp
    }

    BackHandler(
        enabled = interactionState.selectionMode || interactionState.openedAlbum != null || interactionState.openedPersonCluster != null
    ) {
        if (interactionState.selectionMode) {
            viewModel.setEvent(GalleryEvent.ExitSelectionMode)
        } else if (interactionState.openedAlbum != null) {
            viewModel.setEvent(GalleryEvent.CloseAlbum)
        } else if (interactionState.openedPersonCluster != null) {
            viewModel.setEvent(GalleryEvent.ClosePerson)
        }
    }
    val scrollBehaviour = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val mediaCount = animateIntAsState(
        targetValue = when (interactionState.selectedTab) {
            GalleryTab.ALBUMS -> contentState.albums.size
            else -> contentState.displayedMediaList.size
        }, animationSpec = tween(200)
    )

    LaunchedEffect(Unit) {
        if (contentState.masterMediaList.isEmpty()) {
            viewModel.setEvent(GalleryEvent.LoadMedia)
        }
    }

    val onEvent = remember(viewModel){viewModel::setEvent}




        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .nestedScroll(scrollBehaviour.nestedScrollConnection)
        ) {
            AnimatedContent(
                targetState = ScreenTarget(
                    interactionState.openedAlbum, interactionState.openedPersonCluster
                ), transitionSpec = {
                    val isOpeningDetail = targetState.album != null || targetState.person != null

                    if (isOpeningDetail) {
                        (slideInHorizontally { width -> width } + fadeIn()) togetherWith (slideOutHorizontally { width -> -width } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()) togetherWith (slideOutHorizontally { width -> width } + fadeOut())
                    }
                }, label = "GalleryToDetail"
            ) { target ->
                val galleryToDetailScope = this@AnimatedContent

                if (target.album != null || target.person != null) {
                    with(sharedTransitionScope) {
                        GalleryDetailContent(
                            target = target,
                            selectionMode = interactionState.selectionMode,
                            groupedMedia = contentState.groupedMedia,
                            currentViewMode = configState.currentViewMode,
                            selectedMediaIds = interactionState.selectedMediaIds,
                            activeSharedMediaId = interactionState.activeSharedMediaId,
                            onEvent = onEvent,
                            galleryToDetailScope = galleryToDetailScope,
                            tabAnimatedVisibilityScope = animatedVisibilityScope,
                            contentTopPadding = contentTopPadding,
                            contentBottomPadding = contentBottomPadding
                        )
                    }
                } else {
                    with(sharedTransitionScope) {
                        GalleryGridContent(
                            state = state,
                            gridState = gridState,
                            onRequestPermission = onRequestPermission,
                            onEvent = onEvent,
                            galleryToDetailScope = galleryToDetailScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            contentTopPadding = contentTopPadding,
                            contentBottomPadding = contentBottomPadding,
                            contentStartPadding = contentStartPadding
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(1f)
                    .onSizeChanged { size ->
                        if (size.height > maxTopBarHeightPx) {
                            maxTopBarHeightPx = max(maxTopBarHeightPx, size.height)
                        }
                    }
            ) {
                GalleryTopAppBar(
                    selectionMode = interactionState.selectionMode,
                    selectedMediaCount = interactionState.selectedMediaIds.size,
                    totalDisplayedCount = contentState.displayedMediaList.size,
                    mediaCount = mediaCount.value,
                    scrollBehaviour = scrollBehaviour,
                    onEvent = onEvent
                )
            }


            val isMainGallery =
                interactionState.openedAlbum == null && interactionState.openedPersonCluster == null
            val hasContent = if (interactionState.selectedTab == GalleryTab.ALBUMS) {
                contentState.albums.isNotEmpty()
            } else if (interactionState.selectedTab == GalleryTab.OTHERS && interactionState.othersSelection == OthersSelection.PEOPLE) {
                contentState.peopleClusters.isNotEmpty()
            } else {
                contentState.displayedMediaList.isNotEmpty()
            }

            with(sharedTransitionScope) {
                GalleryBottomBar(
                    selectionMode = interactionState.selectionMode,
                    isMainGallery = isMainGallery,
                    hasContent = hasContent,
                    isScrollingUp = isScrollingUp,
                    selectedMediaIds = interactionState.selectedMediaIds,
                    masterMediaList = contentState.masterMediaList,
                    selectedTab = interactionState.selectedTab,
                    layout = layout,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onActionClick = { action, selectedMedia ->
                        onEvent(GalleryEvent.PerformMediaAction(action, selectedMedia))
                    },
                    onTabClick = {
                        coroutineScope.launch {
                            gridState.animateScrollToItem(0)
                        }
                    },
                    onTabSelected = { tab ->
                        onEvent(GalleryEvent.OnTabSelected(tab))
                    },
                    onBarSizeChanged = { bottomBarSize = it })
            }
        }



    if (state.showFaceIndexingSheet) {
        FaceIndexingStartSheet(
            initialOptions = state.faceIndexingOptions,
            progress = state.faceIndexing,
            onDismiss = { viewModel.setEvent(GalleryEvent.DismissFaceIndexingSheet) },
            onStart = { options ->
                viewModel.setEvent(GalleryEvent.StartFaceIndexing(options))
            })
    }
}
