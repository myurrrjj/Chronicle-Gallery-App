package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myurrrjj.chronicle.core.presentation.components.bouncyClick
import com.myurrrjj.chronicle.presentation.gallery.model.GalleryTab
import kotlinx.coroutines.Job

@Composable
fun FloatingGalleryBar(
    selectedTab: GalleryTab,
    onTabSelected: (GalleryTab) -> Unit,
    modifier: Modifier = Modifier,
    isVerticalRail: Boolean = false,
    onClick: () -> Job
) {
    val baseModifier = modifier
        .padding(horizontal = 16.dp)
        .then(if (isVerticalRail) Modifier else Modifier.widthIn(max = 400.dp))
        .shadow(
            elevation = 16.dp,
            shape = CircleShape,
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = .25f)
        )
        .background(MaterialTheme.colorScheme.surface, CircleShape)
        .padding(6.dp)

    if (isVerticalRail) {
        Column(
            modifier = baseModifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GalleryTab.entries.forEach { tab ->
                GalleryTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTabSelected = onTabSelected,
                    onClick = onClick
                )
            }
        }
    } else {
        Row(
            modifier = baseModifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryTab.entries.forEach { tab ->
                GalleryTabItem(
                    tab = tab,
                    isSelected = selectedTab == tab,
                    onTabSelected = onTabSelected,
                    onClick = onClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GalleryTabItem(
    tab: GalleryTab,
    isSelected: Boolean,
    onTabSelected: (GalleryTab) -> Unit,
    onClick: () -> Job,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "TabColorAnimation"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "TabTextColorAnimation"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .bouncyClick {
                onTabSelected(tab)
                if (isSelected) {
                    onClick()
                }
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tab.title,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp)
        )
    }
}