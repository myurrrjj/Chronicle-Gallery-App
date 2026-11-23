package com.myurrrjj.chronicle.presentation.map

import androidx.compose.runtime.Immutable
import com.myurrrjj.chronicle.core.indexing.LocationIndexingProgress
import com.myurrrjj.chronicle.core.presentation.mvi.ViewEvent
import com.myurrrjj.chronicle.core.presentation.mvi.ViewSideEffect
import com.myurrrjj.chronicle.core.presentation.mvi.ViewState
import com.myurrrjj.chronicle.data.local.dao.GeotaggedPhotoRow
import com.myurrrjj.chronicle.domain.model.PhotoMapCluster
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class MapViewPort(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val centerLat: Double,
    val centerLng: Double,
    val zoom: Double,
    val cellPx: Float,
    val isSettled: Boolean
)

@Immutable
sealed interface MapSelection {
    data object ViewPort : MapSelection
    data class Cluster(val id: String, val title: String, val mediaIds: Set<Long>) : MapSelection

}

@Immutable
data class PhotoMapState(
    val permission:GeoPermission = GeoPermission.Unknown,
    val isDarkMode : Boolean  = true,
    val geoTaggedCount :Int = 0,
    val indexing: LocationIndexingProgress = LocationIndexingProgress()
    ,
    val clusters: ImmutableList<PhotoMapCluster> = persistentListOf(),
    val sheetPhotos: ImmutableList<GeotaggedPhotoRow> = persistentListOf(),
    val selection: MapSelection = MapSelection.ViewPort,
    val isLoading:Boolean = true

): ViewState

enum class GeoPermission { Unknown, Granted, Denied, NotRequired }

sealed interface PhotoMapEvent : ViewEvent {
    data class PermissionResult(val permission: GeoPermission) : PhotoMapEvent
    data class ViewportChanged(val viewport: MapViewPort) : PhotoMapEvent
    data class ClusterTapped(val cluster: PhotoMapCluster) : PhotoMapEvent
    data object ClearSelection : PhotoMapEvent
    data object ToggleMapTheme : PhotoMapEvent
    data class PhotoClicked(val mediaId: Long) : PhotoMapEvent
    data object RetryIndexing : PhotoMapEvent
    data object RequestFitToData : PhotoMapEvent
}

sealed interface PhotoMapEffect : ViewSideEffect {
    data class AnimateTo(val latitude: Double, val longitude: Double, val zoom: Double) : PhotoMapEffect
    data class FitToBounds(val west: Double, val south: Double, val east: Double, val north: Double) : PhotoMapEffect
    data class OpenPhotoDetail(val mediaId: Long) : PhotoMapEffect
}