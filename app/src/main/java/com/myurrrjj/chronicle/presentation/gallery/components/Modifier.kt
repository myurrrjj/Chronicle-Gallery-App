package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.myurrrjj.chronicle.domain.model.GalleryViewMode

@Composable
fun Modifier.galleryZoomGesture(
    currentMode: GalleryViewMode,
    onViewModeChanged: (GalleryViewMode) -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val latestMode by rememberUpdatedState(currentMode)
    val latestOnModeChanged by rememberUpdatedState(onViewModeChanged)

    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)

            var zoomMultiplier = 1f

            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    val zoomChange = event.calculateZoom()
                    zoomMultiplier *= zoomChange

                    val activeMode = latestMode
                    if (zoomMultiplier > 1.3f) {
                        val newMode = when (activeMode) {
                            GalleryViewMode.YEAR -> GalleryViewMode.MONTH
                            GalleryViewMode.MONTH -> GalleryViewMode.DAY
                            GalleryViewMode.DAY -> GalleryViewMode.DAY
                        }
                        if (newMode != activeMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnModeChanged(newMode)
                            event.changes.forEach { it.consume() }
                            break
                        }
                    } else if (zoomMultiplier < 0.7f) {
                        val newMode = when (activeMode) {
                            GalleryViewMode.DAY -> GalleryViewMode.MONTH
                            GalleryViewMode.MONTH -> GalleryViewMode.YEAR
                            GalleryViewMode.YEAR -> GalleryViewMode.YEAR
                        }
                        if (newMode != activeMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnModeChanged(newMode)
                            event.changes.forEach { it.consume() }
                            break
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}