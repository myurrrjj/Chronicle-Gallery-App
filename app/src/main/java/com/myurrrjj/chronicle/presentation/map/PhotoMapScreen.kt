package com.myurrrjj.chronicle.presentation.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.myurrrjj.chronicle.core.util.ThumbnailSizes
import com.myurrrjj.chronicle.domain.model.PhotoMapCluster
import com.myurrrjj.chronicle.presentation.gallery.components.findActivity
import org.maplibre.compose.camera.CameraAnimation.Ease
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds

private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private val SHEET_PEEK = 148.dp
private val PIN_CELL = 48.dp
private val CLUSTER_CELL = 60.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMapScreen(
    onBackClick: () -> Unit,
    onPhotoClick: (Long, List<Long>) -> Unit,
    viewModel: PhotoMapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val activity = remember(context) { context.findActivity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val blocked = !granted && activity?.shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == false
        viewModel.setEvent(
            PhotoMapEvent.PermissionResult(
                permission = if (granted) GeoPermission.Granted else GeoPermission.Denied,
                permanentlyDenied = blocked
            )
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.setEvent(PhotoMapEvent.PermissionResult(currentGeoPermission(context)))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    animation = Ease(duration = 350.milliseconds)
                )

                is PhotoMapEffect.FitToBounds -> mapState.animateCameraToBounds(
                    boundingBox = BoundingBox(
                        west = effect.west,
                        south = effect.south,
                        east = effect.east,
                        north = effect.north
                    ),
                    cameraPadding = DpPadding(
                        top = 72.dp,
                        left = 32.dp,
                        right = 32.dp,
                        bottom = 16.dp
                    )
                )


                is PhotoMapEffect.OpenPhotoDetail -> onPhotoClick(effect.mediaId, effect.scopeIds)
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
                            west = b.west,
                            south = b.south,
                            east = b.east,
                            north = b.north,
                            centerLat = viewport.cameraPosition.target.latitude,
                            centerLng = viewport.cameraPosition.target.longitude,
                            zoom = viewport.cameraPosition.zoom,
                            cellPx = CLUSTER_CELL.value,
                            isSettled = !moving
                        )
                    )
                )
            }
    }

    LaunchedEffect(state.selection) {
        if (state.selection is MapSelection.Cluster) {
            scaffoldState.bottomSheetState.expand()
        }
    }


    val phase = state.phase()

    LaunchedEffect(phase) {
        if (phase == MapPhase.Ready) viewModel.setEvent(PhotoMapEvent.RequestFitToData)
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = SHEET_PEEK,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = { MapSheet(state, viewModel,scaffoldState) }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            MaplibreMap(
                state = mapState,
                viewportInsets = PaddingValues(bottom = SHEET_PEEK),
                modifier = Modifier.fillMaxSize()
            ) {
                include(MapOverlay.Default)

                state.clusters.forEach { cluster ->
                    key(cluster.id) {
                        ClusterPin(
                            cluster = cluster,
                            onClick = { viewModel.setEvent(PhotoMapEvent.ClusterTapped(cluster)) },
                            modifier = Modifier
                                .placedAt(
                                    Position(
                                        latitude = cluster.latitude,
                                        longitude = cluster.longitude
                                    )
                                )
                                .zIndex(if (cluster.count > 1) 1f else 0f)
                        )
                    }
                }


            }

            MapTopBar(onBackClick = onBackClick)

            if (phase != MapPhase.Ready) {
                MapStatusBanner(
                    phase = phase,
                    state = state,
                    onAllowLocation = {
                        if (state.permanentlyDenied) openAppSettings(context)
                        else permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    },
                    onRetry = { viewModel.setEvent(PhotoMapEvent.RetryIndexing) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                )
            }

            if (phase == MapPhase.Empty) {
                EmptyMapHint(state, viewModel)
            }

            MapThemeToggle(
                isDark = state.isDarkMode,
                onClick = { viewModel.setEvent(PhotoMapEvent.ToggleMapTheme) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = SHEET_PEEK + 64.dp)
            )
        }
    }
}

private fun currentGeoPermission(context: Context): GeoPermission = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> GeoPermission.NotRequired
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_MEDIA_LOCATION
    ) == PackageManager.PERMISSION_GRANTED -> GeoPermission.Granted
    else -> GeoPermission.Denied
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    (context.findActivity() ?: context).startActivity(
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun MapTopBar(onBackClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        }
    }
}

@Composable
private fun MapStatusBanner(
    phase: MapPhase,
    state: PhotoMapState,
    onAllowLocation: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (phase) {
                MapPhase.NeedsLocationAccess -> {
                    Icon(
                        Icons.Rounded.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (state.permanentlyDenied) "Location access is blocked"
                        else "Location access is off",
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(onClick = onAllowLocation) {
                        Text(if (state.permanentlyDenied) "Settings" else "Allow")
                    }
                }

                MapPhase.Indexing -> {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = if (state.indexing.total > 0) {
                            "Mapping ${state.indexing.processed} of ${state.indexing.total}"
                        } else {
                            "Checking your library…"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                MapPhase.Empty -> {
                    Text(
                        "No geotagged photos",
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = onRetry) { Text("Scan again") }
                }

                MapPhase.Ready -> Unit
            }
        }
    }
}

@Composable
private fun MapThemeToggle(
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = "Toggle map theme"
            )
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
private fun MapSheet(state: PhotoMapState, viewModel: PhotoMapViewModel,scaffoldState: BottomSheetScaffoldState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().fillMaxHeight(.75f).navigationBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = (state.selection as? MapSelection.Cluster)?.title
                        ?: "Photos in this area",
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
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(state.sheetPhotos, key = { it.mediaId }) { media ->
                    val request = remember(media.uriString) {
                        ImageRequest.Builder(context)
                            .data(media.uriString)
                            .size(256)
                            .memoryCacheKey(ThumbnailSizes.cacheKey(media.uriString, 256))
                            .setParameter("is_thumbnail", true)
                            .build()
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.setEvent(PhotoMapEvent.PhotoClicked(media.mediaId))
                            }
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(request),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (media.isVideo) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = "Video",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp)
                            )
                        }
                    }
                }            }
        }
    }
}

@Composable
private fun BoxScope.EmptyMapHint(state: PhotoMapState, viewModel: PhotoMapViewModel) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No photo locations yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "None of your photos carry GPS data yet. Screenshots and forwarded images usually don't.",
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
