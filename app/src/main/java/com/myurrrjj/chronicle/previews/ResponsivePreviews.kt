package com.myurrrjj.chronicle.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.myurrrjj.chronicle.core.presentation.indexing.FaceIndexingProgressCard
import com.myurrrjj.chronicle.core.presentation.indexing.FaceIndexingStartSheet
//import com.myurrrjj.chronicle.core.presentation.indexing.FaceIndexingStartSheetContent
import com.myurrrjj.chronicle.domain.model.FaceIndexingOptions
import com.myurrrjj.chronicle.domain.model.FaceIndexingProgress
import com.myurrrjj.chronicle.domain.model.FaceIndexingStatus
import com.myurrrjj.chronicle.presentation.gallery.components.EmptyStateView
import com.myurrrjj.chronicle.presentation.gallery.components.FloatingGalleryBar
import com.myurrrjj.chronicle.presentation.gallery.model.GalleryTab
import kotlinx.coroutines.Job

private fun runningProgress() = FaceIndexingProgress(
    status = FaceIndexingStatus.RUNNING,
    processed = 120,
    total = 400,
    facesFound = 87,
    currentItem = "IMG_2031.jpg"
)

@Preview(name = "Sheet 400x400 @200%", widthDp = 400, heightDp = 400, fontScale = 2f)
@Preview(name = "Sheet 360x800 @100%", widthDp = 360, heightDp = 800, fontScale = 1f)
@Preview(name = "Sheet 1000x700 @100%", widthDp = 1000, heightDp = 700, fontScale = 1f)
@Composable
private fun FaceIndexingStartSheetPreview() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            FaceIndexingStartSheet(
                initialOptions = FaceIndexingOptions(),
                progress = FaceIndexingProgress(),
                onDismiss = {},
                onStart = {}
            )
        }
    }
}

@Preview(name = "Card 320dp @200%", widthDp = 320, fontScale = 2f)
@PreviewFontScale
@Composable
private fun FaceIndexingProgressCardPreview() {
    MaterialTheme {
        FaceIndexingProgressCard(
            progress = runningProgress(),
            onPause = {},
            onResume = {},
            onForceStop = {},
            onReset = {}
        )
    }
}

@PreviewScreenSizes
@PreviewFontScale
@Composable
private fun EmptyStateViewPreview() {
    MaterialTheme {
        EmptyStateView(
            title = "No Content Found",
            description = "Your gallery section is empty. Take some photos to get started!",
            buttonText = "Refresh",
            onClick = {}
        )
    }
}

@Preview(name = "Bar 320dp @200%", widthDp = 320, heightDp = 200, fontScale = 2f)
@PreviewScreenSizes
@Composable
private fun FloatingGalleryBarPreview() {
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            FloatingGalleryBar(
                selectedTab = GalleryTab.ALL,
                onTabSelected = {},
                onClick = { Job() }
            )
        }
    }
}

@Preview(name = "Bar rail", widthDp = 320, heightDp = 400, fontScale = 2f)
@Composable
private fun FloatingGalleryBarRailPreview() {
    MaterialTheme {
        Box(Modifier.size(width = 120.dp, height = 400.dp)) {
            FloatingGalleryBar(
                selectedTab = GalleryTab.ALBUMS,
                onTabSelected = {},
                isVerticalRail = true,
                onClick = { Job() }
            )
        }
    }
}