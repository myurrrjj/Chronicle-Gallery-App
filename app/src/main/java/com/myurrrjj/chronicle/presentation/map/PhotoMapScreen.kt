package com.myurrrjj.chronicle.presentation.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.myurrrjj.chronicle.core.util.ThumbnailSizes
import com.myurrrjj.chronicle.domain.model.PhotoMapCluster
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private val SHEET_PEEK = 148.dp
private val PIN_CELL = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMapScreen(
    onBackClick: () -> Unit,
    onPhotoClick: (Long) -> Unit,
    viewModel: PhotoMapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val scaffoldState = rememberBottomSheetScaffoldState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setEvent(
            PhotoMapEvent.PermissionResult(
                if (granted) GeoPermission.Granted else GeoPermission.Denied
            )
        )
    }

    LaunchedEffect(Unit) {
        viewModel.setEvent(
            PhotoMapEvent.PermissionResult(
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> GeoPermission.NotRequired
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_MEDIA_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED -> GeoPermission.Granted
                    else -> GeoPermission.Denied
                }
            )
        )
    }

    val styleUrl = if (state.isDarkMode) STYLE_DARK else STYLE_LIGHT

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(styleUrl),
        initialCameraPosition = CameraPosition(
            target = Position(latitude = 20.5937, longitude = 78.9629),
            zoom = 3.5
        )
    )

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is PhotoMapEffect.AnimateTo -> mapState.animateCamera(
                    update = CameraUpdate(
                        target = Position(latitude = effect.latitude, longitude = effect.longitude),
                        zoom = effect.zoom
                    ),
                    animation = CameraAnimation.Ease(duration = 350.milliseconds)
                )

                is PhotoMapEffect.FitToBounds -> mapState.animateCameraToBounds(
                    boundingBox = BoundingBox(
                        west = effect.west, south = effect.south,
                        east = effect.east, north = effect.north
                    )
                )

                is PhotoMapEffect.OpenPhotoDetail -> onPhotoClick(effect.mediaId)
            }
        }
    }

    LaunchedEffect(mapState) {
        snapshotFlow { mapState.viewport to mapState.isCameraMoving }
            .collect { (viewport, moving) ->
                viewport ?: return@collect
                val b = viewport.visibleBounds
                viewModel.setEvent(
                    PhotoMapEvent.ViewportChanged(
                        MapViewPort(
                            west = b.west, south = b.south, east = b.east, north = b.north,
                            centerLat = viewport.cameraPosition.target.latitude,
                            centerLng = viewport.cameraPosition.target.longitude,
                            zoom = viewport.cameraPosition.zoom,
                            cellPx = with(density) { PIN_CELL.toPx() },
                            isSettled = !moving
                        )
                    )
                )
            }
    }

    LaunchedEffect(state.geoTaggedCount > 0) {
        if (state.geoTaggedCount > 0) viewModel.setEvent(PhotoMapEvent.RequestFitToData)
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = SHEET_PEEK,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = { MapSheet(state, viewModel) }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            MaplibreMap(
                state = mapState,
                viewportInsets = PaddingValues(bottom = SHEET_PEEK),
                modifier = Modifier.fillMaxSize()
            ) {
                include(MapOverlay.Default)

                state.clusters.forEach { cluster ->
                    ClusterPin(
                        cluster = cluster,
                        onClick = { viewModel.setEvent(PhotoMapEvent.ClusterTapped(cluster)) },
                        modifier = Modifier.placedAt(
                            Position(latitude = cluster.latitude, longitude = cluster.longitude)
                        )
                    )
                }
            }

            MapTopBar(state, onBackClick, viewModel, permissionLauncher)

            if (!state.isLoading && state.geoTaggedCount == 0) {
                EmptyMapHint(state, viewModel)
            }
        }
    }
}

@Composable
private fun ClusterPin(
    cluster: PhotoMapCluster,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(cluster.coverUriString) {
        ImageRequest.Builder(context)
            .data(cluster.coverUriString)
            .size(128)
            .memoryCacheKey(ThumbnailSizes.cacheKey(cluster.coverUriString, 128))
            .setParameter("is_thumbnail", true)
            .build()
    }

    Box(modifier = modifier.clickable(onClick = onClick)) {
        Image(
            painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop),
            contentDescription = cluster.placeName?.let { "${cluster.count} photos at $it" }
                ?: "${cluster.count} photos here",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        )

        if (cluster.count > 1) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (cluster.count > 999) "999+" else cluster.count.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSheet(state: PhotoMapState, viewModel: PhotoMapViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = (state.selection as? MapSelection.Cluster)?.title ?: "Photos in this area",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${state.sheetPhotos.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.selection is MapSelection.Cluster) {
                IconButton(onClick = { viewModel.setEvent(PhotoMapEvent.ClearSelection) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Show all in area")
                }
            }
        }

        if (state.sheetPhotos.isEmpty()) {
            Text(
                text = "No photos in this area yet. Pan the map, or zoom out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f)
            ) {
                items(state.sheetPhotos, key = { it.mediaId }) { photo ->
                    val request = remember(photo.uriString) {
                        ImageRequest.Builder(context)
                            .data(photo.uriString)
                            .size(256)
                            .memoryCacheKey(ThumbnailSizes.cacheKey(photo.uriString, 256))
                            .setParameter("is_thumbnail", true)
                            .build()
                    }
                    Image(
                        painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.setEvent(PhotoMapEvent.PhotoClicked(photo.mediaId))
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun MapTopBar(
    state: PhotoMapState,
    onBack: () -> Unit,
    viewModel: PhotoMapViewModel,
    permissionLauncher: ManagedActivityResultLauncher<String, Boolean>
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }

        when {
            state.indexing.isRunning -> IndexingPill(state.indexing.processed, state.indexing.total)

            state.permission == GeoPermission.Denied -> Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp
            ) {
                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
                ) {
                    Text("Allow photo locations", style = MaterialTheme.typography.labelMedium)
                }
            }

            else -> Spacer(Modifier.size(1.dp))
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp
        ) {
            IconButton(onClick = { viewModel.setEvent(PhotoMapEvent.ToggleMapTheme) }) {
                Icon(
                    imageVector = if (state.isDarkMode) Icons.Rounded.LightMode
                    else Icons.Rounded.DarkMode,
                    contentDescription = "Toggle map theme"
                )
            }
        }
    }
}

@Composable
private fun IndexingPill(processed: Int, total: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = if (total > 0) "Mapping $processed/$total" else "Checking your library…",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun BoxScope.EmptyMapHint(state: PhotoMapState, viewModel: PhotoMapViewModel) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        modifier = Modifier.align(Alignment.Center).padding(24.dp)
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No photo locations yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.permission == GeoPermission.Denied) {
                    "Chronicle needs photo-location access to place your photos on the map."
                } else {
                    "None of your photos carry GPS data yet. Screenshots and forwarded images usually don't."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { viewModel.setEvent(PhotoMapEvent.RetryIndexing) }) {
                Text("Scan again")
            }
        }
    }
}

//@Preview(showBackground = true, name = "Cluster Pin")
//@Composable
//private fun ClusterPinPreview() {
//    MaterialTheme {
//        Box(Modifier.padding(24.dp)) {
//            ClusterPin(
//                cluster = PhotoMapCluster(
//                    latitude = 20.5937,
//                    longitude = 78.9629,
//                    count = 12,
//                    coverUriString = "file:///preview/cover.jpg",
//                    placeName = "Bengaluru"
//                ),
//                onClick = {}
//            )
//        }
//    }
//}
//
//@Preview(showBackground = true, name = "Indexing Pill")
//@Composable
//private fun IndexingPillPreview() {
//    MaterialTheme {
//        Box(Modifier.padding(16.dp)) {
//            IndexingPill(processed = 42, total = 100)
//        }
//    }
//}