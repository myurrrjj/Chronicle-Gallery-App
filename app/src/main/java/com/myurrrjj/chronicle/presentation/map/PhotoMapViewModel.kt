package com.myurrrjj.chronicle.presentation.map

import androidx.lifecycle.viewModelScope
import com.myurrrjj.chronicle.core.indexing.LocationIndexingController
import com.myurrrjj.chronicle.core.presentation.mvi.BaseViewModel
import com.myurrrjj.chronicle.data.local.dao.GeotaggedPhotoRow
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.domain.model.PhotoMapCluster
import com.myurrrjj.chronicle.domain.usecase.ClusterPhotoPinsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.round
import kotlin.math.tan

@OptIn(FlowPreview::class)
@HiltViewModel
class PhotoMapViewModel @Inject constructor(
    private val locationDao: LocationDao,
    private val indexingController: LocationIndexingController,
    private val clusterPhotoPins: ClusterPhotoPinsUseCase
) : BaseViewModel<PhotoMapEvent, PhotoMapState, PhotoMapEffect>() {

    private val viewportFlow = MutableStateFlow(
        MapViewPort(
            west = -180.0,
            south = -85.0,
            east = 180.0,
            north = 85.0,
            centerLat = 20.5937,
            centerLng = 78.9629,
            zoom = 3.5,
            cellPx = 48f,
            isSettled = true
        )
    )

    private val latestPhotos = MutableStateFlow<List<GeotaggedPhotoRow>>(emptyList())
    private val latestInView = MutableStateFlow<List<GeotaggedPhotoRow>>(emptyList())

    private val photosFlow = locationDao.observeAllGeotaggedPhotos().distinctUntilChanged()

    private var lastClusters: List<PhotoMapCluster> = emptyList()
    private var hasFitted = false

    override fun createInitialState(): PhotoMapState = PhotoMapState()

    init {
        indexingController.observeProgress()
            .onEach { progress -> setState { copy(indexing = progress) } }
            .launchIn(viewModelScope)

        combine(photosFlow, viewportFlow.debounce(120L)) { photos, viewport ->
            withContext(Dispatchers.Default) {
                val inView = if (viewport.west < -180.0 || viewport.east > 180.0) {
                    photos.filter { photo ->
                        val lng = photo.longitude + 360.0 * round(
                            (viewport.centerLng - photo.longitude) / 360.0
                        )
                        photo.latitude in viewport.south..viewport.north &&
                                lng in viewport.west..viewport.east
                    }
                } else {
                    locationDao.getPhotosInBounds(
                        minLat = viewport.south,
                        maxLat = viewport.north,
                        minLng = viewport.west,
                        maxLng = viewport.east
                    )
                }


                val clusters = if (!viewport.isSettled && lastClusters.isNotEmpty()) {
                    lastClusters
                } else {
                    clusterPhotoPins(
                        photos = inView,
                        zoom = viewport.zoom,
                        cellPx = viewport.cellPx,
                        centerLng = viewport.centerLng
                    ).also { lastClusters = it }
                }

                Triple(photos, inView, clusters)
            }
        }
            .onEach { (all, inView, clusters) ->
                latestPhotos.value = all
                latestInView.value = inView
                setState {
                    val sel = selection
                    val stillVisible = sel !is MapSelection.Cluster ||
                            clusters.any { it.id == sel.id }
                    copy(
                        isLoading = false,
                        geoTaggedCount = all.size,
                        clusters = clusters.toImmutableList(),
                        selection = if (stillVisible) sel else MapSelection.ViewPort,
                        sheetPhotos = when {
                            !stillVisible -> inView.toImmutableList()
                            sel is MapSelection.Cluster ->
                                all.filter { it.mediaId in sel.mediaIds }.toImmutableList()
                            else -> inView.toImmutableList()
                        }
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: PhotoMapEvent) {
        when (event) {
            is PhotoMapEvent.PermissionResult -> {
                setState {
                    copy(
                        permission = event.permission,
                        permanentlyDenied = event.permanentlyDenied ?: permanentlyDenied
                    )
                }
                if (event.permission == GeoPermission.Granted && !uiState.value.indexing.isRunning) {
                    indexingController.enqueue()
                }
            }

            is PhotoMapEvent.ViewportChanged -> viewportFlow.value = event.viewport

            is PhotoMapEvent.ClusterTapped -> {
                val cluster = event.cluster
                val viewport = viewportFlow.value
                val ids = cluster.mediaIds.toSet()

                if (cluster.count == 1){
                    openPhoto(cluster.coverMediaId)
                }
                else{
                    val targetZoom = zoomToSeparate(viewport,ids)
                    if (targetZoom - viewport.zoom >= MIN_ZOOM_STEP){
                        setEffect {
                            PhotoMapEffect.AnimateTo(
                                cluster.latitude,
                                cluster.longitude,
                                targetZoom
                            )
                        }
                    }
                    else{
                        setState {
                            copy(
                                selection = MapSelection.Cluster(
                                    id = cluster.id,
                                    title = cluster.placeName ?: "${cluster.count} photos",
                                    mediaIds = ids

                                ),
                                sheetPhotos = latestPhotos.value
                                    .filter { it.mediaId in ids }
                                    .toImmutableList()
                            )
                        }
                    }
                }


            }

            PhotoMapEvent.ClearSelection -> setState {
                copy(
                    selection = MapSelection.ViewPort,
                    sheetPhotos = latestInView.value.toImmutableList()
                )
            }

            PhotoMapEvent.ToggleMapTheme -> setState { copy(isDarkMode = !isDarkMode) }

            is PhotoMapEvent.PhotoClicked -> openPhoto(event.mediaId)

            PhotoMapEvent.RetryIndexing -> indexingController.enqueue()

            PhotoMapEvent.RequestFitToData -> {
                if (hasFitted) return
                val photos = latestPhotos.value
                if (photos.isEmpty()) return
                hasFitted = true

                val south = photos.minOf { it.latitude }
                val north = photos.maxOf { it.latitude }
                val west = photos.minOf { it.longitude }
                val east = photos.maxOf { it.longitude }
                val latPad = ((north - south) * FIT_PADDING_FRACTION).coerceAtLeast(MIN_FIT_PAD)
                val lngPad = ((east - west) * FIT_PADDING_FRACTION).coerceAtLeast(MIN_FIT_PAD)

                setEffect {
                    PhotoMapEffect.FitToBounds(
                        west = west - lngPad,
                        south = south - latPad,
                        east = east + lngPad,
                        north = north + latPad
                    )
                }
            }

            PhotoMapEvent.RecenterOnData -> {
                hasFitted = false
                handleEvent(PhotoMapEvent.RequestFitToData)
            }
        }
    }
    private fun zoomToSeparate(viewport: MapViewPort, ids: Set<Long>): Double {
        val points = latestPhotos.value.filter { it.mediaId in ids }
        if (points.size < 2) return (viewport.zoom + ZOOM_STEP).coerceAtMost(MAX_PIN_ZOOM)

        val lngSpan = (points.maxOf { it.longitude } - points.minOf { it.longitude })
            .coerceAtLeast(MIN_SPAN)
        val latSpan = (mercatorY(points.minOf { it.latitude }) - mercatorY(points.maxOf { it.latitude }))
            .coerceAtLeast(MIN_SPAN)
        val viewLngSpan = (viewport.east - viewport.west).coerceAtLeast(MIN_SPAN)
        val viewLatSpan = (mercatorY(viewport.south) - mercatorY(viewport.north)).coerceAtLeast(MIN_SPAN)

        val fitX = viewport.zoom + log2(viewLngSpan * FIT_FRACTION / lngSpan)
        val fitY = viewport.zoom + log2(viewLatSpan * FIT_FRACTION / latSpan)

        return minOf(fitX, fitY).coerceAtMost(MAX_PIN_ZOOM)
    }

    private fun mercatorY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
        return 0.5 - ln(tan(PI / 4.0 + clamped * PI / 360.0)) / (2.0 * PI)
    }


    private fun openPhoto(mediaId: Long) {
        val scope = when (val sel = uiState.value.selection) {
            is MapSelection.Cluster -> sel.mediaIds.toList()
            MapSelection.ViewPort -> {
                val ordered = latestInView.value
                val index = ordered.indexOfFirst { it.mediaId == mediaId }
                if (index < 0) {
                    listOf(mediaId)
                } else {
                    ordered.subList(
                        (index - SCOPE_RADIUS).coerceAtLeast(0),
                        (index + SCOPE_RADIUS + 1).coerceAtMost(ordered.size)
                    ).map { it.mediaId }
                }
            }
        }
        setEffect { PhotoMapEffect.OpenPhotoDetail(mediaId, scope) }
    }


    companion object {
        private const val FIT_FRACTION = 0.45
        private const val MIN_ZOOM_STEP = 0.75
        private const val MAX_PIN_ZOOM = 17.0
        private const val ZOOM_STEP = 2.0
        private const val MIN_SPAN = 1e-6
        private const val MERCATOR_MAX_LAT = 85.05112878
        private const val SCOPE_RADIUS = 250
        private const val FIT_PADDING_FRACTION = 0.02
        private const val MIN_FIT_PAD = 0.01
    }

}
