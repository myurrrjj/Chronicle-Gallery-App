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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.myurrrjj.chronicle.core.util.ThumbnailSizes
import com.myurrrjj.chronicle.domain.model.DetailedMediaInfo
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.MediaFace
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
import androidx.compose.ui.platform.LocalLocale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(sheetState: InfoSheetState, onDismiss: () -> Unit) {
    if (sheetState is InfoSheetState.Closed) return

    val modalState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            AnimatedContent(targetState = sheetState, label = "SheetContent") { currentState ->
                when (currentState) {
                    is InfoSheetState.Closed -> Spacer(Modifier.height(1.dp))

                    is InfoSheetState.Loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    is InfoSheetState.Error -> Box(
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

                    is InfoSheetState.Success -> DetailContent(state = currentState)
                }
            }
        }
    }
}

@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 8.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
    )
}

@Composable
private fun DetailContent(state: InfoSheetState.Success) {
    val media = state.media
    val context = LocalContext.current

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault()) }
    val formattedDate =
        remember(media.dateAdded) { dateFormatter.format(Date(media.dateAdded * 1000)) }
    val formattedSize = remember(media.size) { Formatter.formatShortFileSize(context, media.size) }

    val hasCameraData = state.details.hasAnyValue()

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

        StaggeredAnimatedSection(index = 0) {
            SheetHeader(media = media, subtitle = "$formattedSize • $formattedDate")
        }

        StaggeredAnimatedSection(index = 1) {
            InfoSection(title = "Location on device", icon = Icons.Rounded.Folder) {
                Text(
                    text = state.storagePath ?: fallbackPath(media),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        StaggeredAnimatedSection(index = 2) {
            PeopleSection(
                faces = state.faces,
                indexed = state.facesIndexed,
                isVideo = media.isVideo
            )
        }

        if (hasCameraData) {
            StaggeredAnimatedSection(index = 3) {
                InfoSection(
                    title = if (media.isVideo) "Video" else "Camera",
                    icon = if (media.isVideo) Icons.Rounded.Videocam else Icons.Rounded.CameraAlt
                ) {
                    CameraBlock(media = media, details = state.details)
                }
            }
        }

        StaggeredAnimatedSection(index = if (hasCameraData) 4 else 3) {
            InfoSection(title = "Where", icon = Icons.Rounded.Place) {
                LocationBlock(location = state.location)
            }
        }
    }
}

private fun DetailedMediaInfo.hasAnyValue(): Boolean =
    cameraMake != null || cameraModel != null || resolution != null ||
            megapixelCount != null || aperture != null || exposureTime != null ||
            iso != null || focalLength != null || frameRate != null ||
            bitrate != null || durationFormatted != null

private fun fallbackPath(media: MediaAsset): String =
    listOfNotNull(
        "Internal storage",
        media.folderName?.takeIf { it.isNotBlank() && it != "Unknown" },
        media.name
    ).joinToString(" / ")

@Composable
private fun SheetHeader(media: MediaAsset, subtitle: String) {
    val context = LocalContext.current
    val request = remember(media.uriString) {
        val cachedKey = ThumbnailSizes.findCachedThumbnailKey(context.imageLoader, media.uriString)
        val px = ThumbnailSizes.bucketPx(160)
        ImageRequest.Builder(context)
            .data(media.uriString)
            .size(px)
            .setParameter("is_thumbnail", true)
            .apply {
                if (cachedKey != null) {
                    memoryCacheKey(cachedKey)
                    placeholderMemoryCacheKey(cachedKey)
                } else {
                    memoryCacheKey(ThumbnailSizes.cacheKey(media.uriString, px))
                }
            }
            .build()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = request,
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (media.isVideo) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    icon: ImageVector,
    badge: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                badge()
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
private fun PeopleSection(faces: List<MediaFace>, indexed: Boolean, isVideo: Boolean) {
    InfoSection(
        title = "People",
        icon = Icons.Rounded.People,
        badge = if (faces.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${faces.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else null,
        contentPadding = if (faces.isEmpty()) PaddingValues(16.dp) else PaddingValues(vertical = 14.dp)
    ) {
        if (faces.isEmpty()) {
            Text(
                text = when {
                    isVideo -> "Videos are not face-indexed."
                    indexed -> "No people were detected in this photo."
                    else -> "This photo hasn't been through face indexing yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items = faces, key = { it.embeddingId }) { face ->
                    FaceBubble(face = face)
                }
            }
        }
    }
}

@Composable
private fun FaceBubble(face: MediaFace) {
    val context = LocalContext.current

    val request = remember(face.cropFile) {
        face.cropFile?.let { file ->
            ImageRequest.Builder(context)
                .data(file)
                .size(ThumbnailSizes.FACE_CROP_PX)
                .memoryCacheKey(
                    ThumbnailSizes.faceCacheKey(file.absolutePath, ThumbnailSizes.FACE_CROP_PX)
                )
                .build()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = face.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = face.displayName.take(1).uppercase(LocalLocale.current.platformLocale),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = face.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (!face.personLabel.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CameraBlock(media: MediaAsset, details: DetailedMediaInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        val deviceTitle = listOfNotNull(
            details.cameraMake?.takeIf { it.isNotBlank() },
            details.cameraModel?.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        if (deviceTitle.isNotEmpty()) {
            Text(
                text = deviceTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        val stats = buildList {
            details.resolution?.let { add("Resolution" to it) }
            if (media.isVideo) {
                details.durationFormatted?.let { add("Duration" to it) }
                details.frameRate?.let { add("Frame Rate" to "$it fps") }
                details.bitrate?.let { add("Bitrate" to it) }
            } else {
                details.megapixelCount?.let {
                    add("Megapixels" to String.format(Locale.US, "%.1f MP", it))
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
                if (rowStats.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LocationBlock(location: MediaLocationInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                location == null -> "No location data"
                location.placeName == null -> "Location unknown"
                else -> location.placeName
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (location != null) {
            Text(
                text = String.format(Locale.US, "%.4f, %.4f", location.latitude, location.longitude),
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
        Position(latitude = latitude, longitude = longitude)
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
