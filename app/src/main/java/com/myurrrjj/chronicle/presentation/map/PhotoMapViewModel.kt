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
import kotlin.math.round

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
                val inView = photos.filter { photo ->
                    val lng = photo.longitude + 360.0 * round(
                        (viewport.centerLng - photo.longitude) / 360.0
                    )
                    photo.latitude in viewport.south..viewport.north &&
                            lng in viewport.west..viewport.east
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
                if (event.permission == GeoPermission.Granted) indexingController.enqueue()
            }

            is PhotoMapEvent.ViewportChanged -> viewportFlow.value = event.viewport

            is PhotoMapEvent.ClusterTapped -> {
                val cluster = event.cluster
                when {
                    cluster.count == 1 -> openPhoto(cluster.coverMediaId)

                    viewportFlow.value.zoom < MAX_ZOOM_IN_ON_TAP -> setEffect {
                        PhotoMapEffect.AnimateTo(
                            latitude = cluster.latitude,
                            longitude = cluster.longitude,
                            zoom = (viewportFlow.value.zoom + 2.0)
                                .coerceAtMost(MAX_ZOOM_IN_ON_TAP)
                        )
                    }

                    else -> {
                        val ids = cluster.mediaIds.toSet()
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
                val clusters = uiState.value.clusters
                if (clusters.isEmpty()) return
                hasFitted = true
                setEffect {
                    PhotoMapEffect.FitToBounds(
                        west = clusters.minOf { it.longitude },
                        south = clusters.minOf { it.latitude },
                        east = clusters.maxOf { it.longitude },
                        north = clusters.maxOf { it.latitude }
                    )
                }
            }

            PhotoMapEvent.RecenterOnData -> {
                hasFitted = false
                handleEvent(PhotoMapEvent.RequestFitToData)
            }
        }
    }

    private fun openPhoto(mediaId: Long) {
        val scope = when (val sel = uiState.value.selection) {
            is MapSelection.Cluster -> sel.mediaIds.toList()
            MapSelection.ViewPort -> latestInView.value.map { it.mediaId }
        }
        setEffect { PhotoMapEffect.OpenPhotoDetail(mediaId, scope) }
    }

    companion object {
        private const val MAX_ZOOM_IN_ON_TAP = 15.0
    }
}
