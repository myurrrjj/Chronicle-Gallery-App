package com.myurrrjj.chronicle.presentation.navigation

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

data class WindowLayout(
    val sizeClass: WindowSizeClass,
    val isCompactWidth: Boolean,
    val isCompactHeight: Boolean,
    val useNavigationRail: Boolean
)

@Composable
fun rememberWindowLayout(): WindowLayout {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass

    val isCompactWidth = !sizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
    )
    val isCompactHeight = !sizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
    )
    val isExpandedWidth = sizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    )

    return WindowLayout(
        sizeClass = sizeClass,
        isCompactWidth = isCompactWidth,
        isCompactHeight = isCompactHeight,
        useNavigationRail = isExpandedWidth && !isCompactHeight
    )
}
