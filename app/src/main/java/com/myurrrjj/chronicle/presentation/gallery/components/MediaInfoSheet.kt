package com.myurrrjj.chronicle.presentation.gallery.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myurrrjj.chronicle.domain.model.DetailedMediaInfo
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.MediaLocationInfo
import com.myurrrjj.chronicle.presentation.gallery.InfoSheetState
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(sheetState: InfoSheetState, onDismiss: () -> Unit) {
    if (sheetState is InfoSheetState.Closed) return

    val modalState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(
                    top = 24.dp, bottom = 24.dp
                )
        ) {
            AnimatedContent(targetState = sheetState, label = "SheetContent") { currentState ->
                when (currentState) {
                    is InfoSheetState.Closed -> Spacer(Modifier.height(1.dp))

                    is InfoSheetState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    is InfoSheetState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Failed to load details: ${currentState.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is InfoSheetState.Success -> {
                        AnimatedDetailContent(
                            media = currentState.media,
                            details = currentState.details,
                            location = currentState.location
                        )
                    }

                }
            }
        }
    }
}

@Composable
fun AnimatedDetailContent(
    media: MediaAsset,
    details: DetailedMediaInfo,
    location: MediaLocationInfo?
) {
    val context = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault()) }
    val formattedDate =
        remember(media.dateAdded) { dateFormatter.format(Date(media.dateAdded * 1000)) }
    val formattedSize = remember(media.size) { Formatter.formatShortFileSize(context, media.size) }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

        StaggeredAnimatedSection(index = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (media.isVideo) Icons.Rounded.VideoFile else Icons.AutoMirrored.Rounded.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$formattedSize • $formattedDate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        StaggeredAnimatedSection(index = 1) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = media.uriString.substringAfterLast("%3A").replace("%2F", "/"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        StaggeredAnimatedSection(index = 2) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    details.cameraMake?.let { make ->
                        val model = details.cameraModel ?: ""
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$make $model".trim(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    val stats = buildList {
                        details.resolution?.let { add("Resolution" to it) }
                        if (media.isVideo) {
                            details.durationFormatted?.let { add("Duration" to it) }
                            details.frameRate?.let { add("Frame Rate" to "${it} fps") }
                            details.bitrate?.let { add("Bitrate" to it) }
                        } else {
                            details.megapixelCount?.let {
                                add(
                                    "Megapixels" to String.format(
                                        Locale.US,
                                        "%.1f MP",
                                        it
                                    )
                                )
                            }
                            details.aperture?.let { add("Aperture" to it) }
                            details.exposureTime?.let { add("Exposure" to it) }
                            details.iso?.let { add("ISO" to it) }
                            details.focalLength?.let { add("Focal Length" to it) }
                        }
                    }

                    stats.chunked(2).forEach { rowStats ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowStats.forEach { stat ->
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stat.first,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stat.second,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            if (rowStats.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        StaggeredAnimatedSection(index = 3) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = location?.placeName ?: "No location data",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (location != null) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.4f, %.4f",
                                location.latitude,
                                location.longitude
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MiniLocationMap(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
        }

    }
}

@Composable
fun StaggeredAnimatedSection(index: Int, content: @Composable () -> Unit) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(index * 75L)
        isVisible = true
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { 60 },
            animationSpec = tween(durationMillis = 500, easing = EaseOutQuart)
        ) + fadeIn(animationSpec = tween(durationMillis = 400)),
    ) {
        content()
    }
}

@Composable
private fun MiniLocationMap(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    val position = remember(latitude, longitude) {
        Position(
            latitude = latitude, longitude = longitude
        )
    }
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(
            if (isSystemInDarkTheme()) "https://tiles.openfreemap.org/styles/dark"
            else "https://tiles.openfreemap.org/styles/liberty"
        ), initialCameraPosition = CameraPosition(target = position, zoom = 14.0)
    )

    MaplibreMap(
        state = mapState, interactions = MapInteractions.None, modifier = modifier
    ) {
        include(MapOverlay.AttributionOnly)
        Box(
            Modifier
                .size(14.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, Color.White, CircleShape)
                .placedAt(position)
        )
    }
}


