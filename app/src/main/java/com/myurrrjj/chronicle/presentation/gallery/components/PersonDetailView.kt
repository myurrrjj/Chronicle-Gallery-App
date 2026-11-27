package com.myurrrjj.chronicle.presentation.gallery.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.myurrrjj.chronicle.domain.model.GalleryViewMode
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.PersonCluster
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.PersonDetailView(
    cluster: PersonCluster,
    groupedMedia: ImmutableMap<String, ImmutableList<MediaAsset>>,
    currentViewMode: GalleryViewMode,
    selectedMediaIds: ImmutableSet<Long> = persistentSetOf(),
    selectionMode: Boolean = false,
    onToggleSelection: (Long) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onViewModeChanged: (GalleryViewMode) -> Unit,
    onUpdateName: (Long, String) -> Unit,
    tabAnimatedVisibilityScope: AnimatedVisibilityScope,

    personAnimatedVisibilityScope: AnimatedVisibilityScope,
    onBackClick: () -> Unit,
    onMediaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp = 0.dp,
    activeSharedMediaId: Long? = null,

    ) {
    var showEditDialog by remember { mutableStateOf(false) }
    var displayName by remember(cluster.id) { mutableStateOf(cluster.name) }

    LaunchedEffect(cluster.name) {
        if (!showEditDialog) displayName = cluster.name
    }

    val gridCacheWindow = remember {
        LazyLayoutCacheWindow(aheadFraction = 0.25f, behindFraction = 0.10f)
    }
    val gridState = rememberLazyGridState(cacheWindow = gridCacheWindow)

    if (showEditDialog) {
        RenamePersonDialog(
            initialName = displayName,
            onDismiss = { showEditDialog = false },
            onConfirm = { newName ->
                displayName = newName
                showEditDialog = false
                onUpdateName(cluster.id, newName)
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "person_title_${cluster.id}"),
                        animatedVisibilityScope = personAnimatedVisibilityScope,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    )
            )
            IconButton(onClick = { showEditDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit Name",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
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
            sharedTransitionScope = this@PersonDetailView,
            tabAnimatedVisibilityScope = tabAnimatedVisibilityScope,
            albumAnimatedVisibilityScope = personAnimatedVisibilityScope,
            modifier = Modifier.weight(1f),
            contentBottomPadding = contentBottomPadding, activeSharedMediaId = activeSharedMediaId

        )
    }
}


@Composable
private fun RenamePersonDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialName) { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Rename person",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(value.trim()) },
                        enabled = value.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}