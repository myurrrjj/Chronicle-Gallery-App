package com.myurrrjj.chronicle.presentation.gallery

import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.myurrrjj.chronicle.core.indexing.FaceIndexingController
import com.myurrrjj.chronicle.core.presentation.mvi.BaseViewModel
import com.myurrrjj.chronicle.domain.model.GalleryViewMode
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.model.PersonCluster
import com.myurrrjj.chronicle.domain.repository.FaceRepository
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import com.myurrrjj.chronicle.domain.repository.SettingsRepository
import com.myurrrjj.chronicle.domain.usecase.GetFilteredMediaUseCase
import com.myurrrjj.chronicle.domain.usecase.GroupMediaUseCase
import com.myurrrjj.chronicle.presentation.gallery.components.DetailAction
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryEffect
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryEvent
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryState
import com.myurrrjj.chronicle.presentation.gallery.model.Album
import com.myurrrjj.chronicle.presentation.gallery.model.GalleryTab
import com.myurrrjj.chronicle.presentation.gallery.model.OthersSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val mediaRepository: MediaRepository,
    private val faceRepository: FaceRepository,
    private val faceIndexingController: FaceIndexingController,
    private val settingsRepository: SettingsRepository,
    private val groupMediaUseCase: GroupMediaUseCase,
    private val getFilteredMediaUseCase: GetFilteredMediaUseCase
) : BaseViewModel<GalleryEvent, GalleryState, GalleryEffect>() {
    private var isFirstSettingsLoad = true
    private var autoStartChecked = false

    private var regroupJob: Job? = null

    init {
        observeFavorites()
        observePeopleClusters()
        observeSettings()
        observeFaceIndexing()

    }

    override fun createInitialState(): GalleryState = GalleryState()

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val wasFirstLoad = isFirstSettingsLoad
                val previousMode = uiState.value.configState.currentViewMode
                val targetMode = if (wasFirstLoad) settings.defaultGalleryViewMode else previousMode
                isFirstSettingsLoad = false

                setState {

                    copy(
                        configState = configState.copy(
                            currentViewMode = targetMode,
                            autoPlayVideo = settings.autoPlayVideo
                        ),
                        faceIndexingOptions = settings.faceIndexingOptions
                    )
                }
                if (wasFirstLoad && targetMode != previousMode) {
                    regroupMedia(uiState.value.contentState.displayedMediaList, targetMode)
                }
            }
        }
    }

    private fun handleOthersSelectionChanged(selection: OthersSelection) {
        val currentState = uiState.value
        if (currentState.interactionState.othersSelection == selection) return

        setState {
            copy(
                interactionState = interactionState.copy(
                    othersSelection = selection,
                    selectionMode = false,
                    selectedMediaIds = persistentSetOf()
                )
            )
        }

        regroupWithFilter(viewMode = currentState.configState.currentViewMode) {
            getFilteredMediaUseCase(
                masterList = currentState.contentState.masterMediaList,
                favouriteList = currentState.contentState.favouriteMediaList,
                tab = currentState.interactionState.selectedTab,
                othersSelection = selection
            )
        }
    }

    override fun handleEvent(event: GalleryEvent) {
        when (event) {
            is GalleryEvent.LoadMedia -> fetchMedia()
            is GalleryEvent.PermissionResult -> handlePermission(event.isGranted)
            is GalleryEvent.MediaClicked -> navigateToDetail(event.mediaId)
            is GalleryEvent.OnTabSelected -> handleTabSelection(event.tab)
            is GalleryEvent.OnOthersSelectionChanged -> handleOthersSelectionChanged(event.selection)
            is GalleryEvent.OpenPerson -> openPerson(event.cluster)
            GalleryEvent.ClosePerson -> closePerson()
            is GalleryEvent.UpdatePersonName -> updatePersonName(event.clusterId, event.newName)

            is GalleryEvent.PerformMediaAction -> handleMediaAction(
                event.action, event.mediaList
            )

            is GalleryEvent.OpenInfoSheet -> fetchMediaDetails(event.media)
            is GalleryEvent.CloseInfoSheet -> {
                setState { copy(infoSheetState = InfoSheetState.Closed) }
            }

            is GalleryEvent.OpenAlbum -> {
                val album = event.album
                setState {
                    copy(interactionState = interactionState.copy(openedAlbum = album))
                }
                regroupWithFilter(viewMode = uiState.value.configState.currentViewMode) {
                    uiState.value.contentState.masterMediaList.filter {
                        (it.folderName ?: "Unknown") == album.name
                    }.toPersistentList()
                }
            }

            GalleryEvent.CloseAlbum -> {
                setState {
                    copy(interactionState = interactionState.copy(openedAlbum = null))
                }
                regroupWithFilter(viewMode = uiState.value.configState.currentViewMode) {
                    resolveTabMediaList(uiState.value)
                }
            }

            is GalleryEvent.EnterSelectionMode -> {
                setState {
                    copy(
                        interactionState = interactionState.copy(
                            selectionMode = true, selectedMediaIds = persistentSetOf()
                        )
                    )
                }
            }

            GalleryEvent.ExitSelectionMode -> {
                setState {
                    copy(
                        interactionState = interactionState.copy(
                            selectionMode = false, selectedMediaIds = persistentSetOf()
                        )
                    )
                }
            }

            GalleryEvent.SelectAll -> {
                viewModelScope.launch(Dispatchers.Default) {
                    val stateSnapshot = uiState.value
                    val allIds = when (stateSnapshot.interactionState.selectedTab) {
                        GalleryTab.ALBUMS -> persistentSetOf()
                        else -> stateSnapshot.contentState.displayedMediaList.map { it.id }
                            .toPersistentSet()

                    }

                    setState {
                        copy(interactionState = interactionState.copy(selectedMediaIds = allIds))
                    }
                }


            }

            GalleryEvent.ClearSelection -> {
                setState {
                    copy(interactionState = interactionState.copy(selectedMediaIds = persistentSetOf()))
                }
            }

            is GalleryEvent.ToggleMediaSelection -> {
                setState {
                    val currentSet = interactionState.selectedMediaIds as? PersistentSet<Long> ?: interactionState.selectedMediaIds.toPersistentSet()


                    val newSet = if (currentSet.contains(event.mediaId)) {
                        currentSet.remove(event.mediaId)
                    } else {
                        currentSet.add(event.mediaId)
                    }

                    val keepSelectionMode = newSet.isNotEmpty()
                    copy(
                        interactionState = interactionState.copy(
                            selectedMediaIds = newSet,
                            selectionMode = if (keepSelectionMode) interactionState.selectionMode else false
                        )
                    )
                }
            }

            is GalleryEvent.ChangeViewMode -> {
                if (uiState.value.configState.currentViewMode == event.viewMode) return


                setState {
                    copy(configState = configState.copy(currentViewMode = event.viewMode))
                }
                regroupMedia(uiState.value.contentState.displayedMediaList, event.viewMode)
            }

            GalleryEvent.OpenFaceIndexingSheet -> {
                setState { copy(showFaceIndexingSheet = true) }
            }

            GalleryEvent.DismissFaceIndexingSheet -> {
                setState { copy(showFaceIndexingSheet = false) }
            }

            is GalleryEvent.StartFaceIndexing -> {
                setState { copy(showFaceIndexingSheet = false) }
                viewModelScope.launch {
                    faceIndexingController.start(event.options)
                }
            }

            GalleryEvent.PauseFaceIndexing -> {
                viewModelScope.launch { faceIndexingController.pause() }
            }

            GalleryEvent.ResumeFaceIndexing -> {
                viewModelScope.launch { faceIndexingController.resume() }
            }

            GalleryEvent.ForceStopFaceIndexing -> {
                viewModelScope.launch { faceIndexingController.forceStop() }
            }

            GalleryEvent.ResetFaceIndexing -> {
                viewModelScope.launch { faceIndexingController.reset() }
            }

            GalleryEvent.OnSettingsClicked -> {
                setEffect { GalleryEffect.NavigateToSettings }
            }

            GalleryEvent.OnSearchClicked -> setEffect { GalleryEffect.NavigateToSearch }
            is GalleryEvent.SetSearchMediaList -> {
                setState {
                    copy(
                        contentState = contentState.copy(
                            displayedMediaList = event.results.toImmutableList()
                        )
                    )
                }
            }

            GalleryEvent.ClearSearchMediaList -> {
                regroupWithFilter(viewMode = uiState.value.configState.currentViewMode) {
                    resolveTabMediaList(uiState.value)
                }
            }

        }
    }

    private fun resolveTabMediaList(state: GalleryState): ImmutableList<MediaAsset> {
        return when (state.interactionState.selectedTab) {
            GalleryTab.VIDEOS -> state.contentState.masterMediaList
                .filter { it.isVideo }
                .toPersistentList()
            GalleryTab.OTHERS -> if (state.interactionState.othersSelection == OthersSelection.FAVOURITES) {
                state.contentState.favouriteMediaList
            } else {
                persistentListOf()
            }
            else -> state.contentState.masterMediaList
        }
    }

    private fun observePeopleClusters() {
        viewModelScope.launch {
            faceRepository.getAllClusters().collect { clusters ->
                val immutableClusters = withContext(Dispatchers.Default){
                    clusters.toImmutableList()
                }
                setState { copy(contentState = contentState.copy(peopleClusters = immutableClusters)) }
            }
        }
    }

    private fun observeFaceIndexing() {
        viewModelScope.launch {
            faceIndexingController.progress
                .distinctUntilChanged { old, new ->
                    old.status == new.status &&
                            old.processed == new.processed &&
                            old.total == new.total &&
                            old.facesFound == new.facesFound &&
                            old.message == new.message
                }
                .collect { progress ->
                    setState { copy(faceIndexing = progress) }
                }
        }
    }

    private fun openPerson(cluster: PersonCluster) {
        regroupJob?.cancel()
        regroupJob = viewModelScope.launch(Dispatchers.Default) {
            val mediaIds = faceRepository.getMediaIdsForCluster(cluster.id).toHashSet()
            val viewMode = uiState.value.configState.currentViewMode
            val personMedia = uiState.value.contentState.masterMediaList
                .filter { it.id in mediaIds }
                .toPersistentList()
            val grouped = groupMediaUseCase(personMedia, viewMode)

            setState {
                copy(
                    interactionState = interactionState.copy(openedPersonCluster = cluster),
                    contentState = contentState.copy(
                        displayedMediaList = personMedia,
                        groupedMedia = grouped
                    )
                )
            }
        }
    }

    private fun closePerson() {
        setState {
            copy(interactionState = interactionState.copy(openedPersonCluster = null))
        }
        regroupWithFilter(viewMode = uiState.value.configState.currentViewMode) {
            resolveTabMediaList(uiState.value)
        }
    }

    private fun updatePersonName(clusterId: Long, newName: String) {
        viewModelScope.launch {
            faceRepository.updateClusterName(clusterId, newName)
        }
    }

    private fun regroupMedia(mediaList: List<MediaAsset>, viewMode: GalleryViewMode) {
        regroupJob?.cancel()
        regroupJob = viewModelScope.launch(Dispatchers.Default) {
            val grouped = groupMediaUseCase(mediaList, viewMode)
            setState {
                copy(contentState = contentState.copy(groupedMedia = grouped))
            }
        }
    }

    private fun regroupWithFilter(
        viewMode: GalleryViewMode,
        filterBlock: () -> ImmutableList<MediaAsset>
    ) {
        regroupJob?.cancel()
        regroupJob = viewModelScope.launch(Dispatchers.Default) {
            val filtered = filterBlock()
            val grouped = groupMediaUseCase(filtered, viewMode)
            setState {
                copy(
                    contentState = contentState.copy(
                        displayedMediaList = filtered,
                        groupedMedia = grouped
                    )
                )
            }
        }
    }

    private fun handleMediaAction(action: DetailAction, explicitMedia: List<MediaAsset>) {
        viewModelScope.launch(Dispatchers.Default) {
            val targetMedia = explicitMedia.ifEmpty {
                val selectedIds = uiState.value.interactionState.selectedMediaIds
                uiState.value.contentState.masterMediaList.filter { it.id in selectedIds }
            }

            if (targetMedia.isEmpty()) return@launch

            when (action) {
                DetailAction.COPY -> {
                    val uris = targetMedia.map { it.uriString }
                    setEffect { GalleryEffect.CopyMedia(uris) }
                }

                DetailAction.SHARE -> {
                    val uris = targetMedia.map { it.uriString }
                    val mimeType = if (targetMedia.size == 1) targetMedia.first().mimeType else "*/*"
                    setEffect { GalleryEffect.ShareMedia(uris, mimeType) }
                }

                DetailAction.DELETE -> {
                    val uris = targetMedia.map { it.uriString }
                    setEffect { GalleryEffect.DeleteMedia(uris) }
                }

                DetailAction.FAVOURITE -> {
                    val favoriteIds = uiState.value.contentState.favoriteMediaIds
                    val allAreFavorites = targetMedia.all { it.id in favoriteIds }

                    targetMedia.forEach { media ->
                        mediaRepository.toggleFavourite(media, !allAreFavorites)
                    }
                    setEvent(GalleryEvent.ExitSelectionMode)
                }

                DetailAction.INFO -> {
                    if (targetMedia.size == 1) {
                        fetchMediaDetails(targetMedia.first())
                    }
                }

                DetailAction.EDIT -> {
                    if (targetMedia.size == 1) {
                        val media = targetMedia.first()
                        setEffect { GalleryEffect.EditMedia(media.uriString, media.mimeType) }
                    }
                }
            }
        }
    }

    private fun fetchMediaDetails(media: MediaAsset) {
        setState { copy(infoSheetState = InfoSheetState.Loading(media)) }
        viewModelScope.launch {
            try {
                val details = mediaRepository.getMediaDetails(media.uriString, media.isVideo)
                setState { copy(infoSheetState = InfoSheetState.Success(media, details)) }
            } catch (e: Exception) {
                setState {
                    copy(
                        infoSheetState = InfoSheetState.Error(
                            media, e.message ?: "Unknown Error"
                        )
                    )
                }
            }
        }
    }

    private fun handleTabSelection(tab: GalleryTab) {
        val currentState = uiState.value
        if (currentState.interactionState.selectedTab == tab) return

        setState {
            copy(
                interactionState = interactionState.copy(
                    selectedTab = tab,
                    selectionMode = false,
                    selectedMediaIds = persistentSetOf()
                )
            )
        }

        regroupWithFilter(viewMode = currentState.configState.currentViewMode) {
            getFilteredMediaUseCase(
                masterList = currentState.contentState.masterMediaList,
                favouriteList = currentState.contentState.favouriteMediaList,
                tab = tab,
                othersSelection = currentState.interactionState.othersSelection
            )
        }
    }

    private fun handlePermission(isGranted: Boolean) {
        setState { copy(configState = configState.copy(hasPermission = isGranted)) }
        if (isGranted) fetchMedia()
        else setState {
            copy(
                configState = configState.copy(
                    isLoading = false,
                    error = "Permission required to display media."
                )
            )
        }
    }

    private fun fetchMedia() {
        if (!uiState.value.configState.hasPermission) {
            setEffect { GalleryEffect.RequestPermission }
            return
        }

        viewModelScope.launch {
            mediaRepository.getAllMedia().onStart {
                setState {
                    copy(
                        configState = configState.copy(
                            isLoading = true,
                            error = null
                        )
                    )
                }
            }.catch { exception ->
                setState {
                    copy(
                        configState = configState.copy(
                            isLoading = false,
                            error = exception.message
                        )
                    )
                }
            }.collect { media ->
                val stateSnapshot = uiState.value
                val (immutableMedia, processedAlbums, newDisplayedList, initialGrouped) =
                    withContext(Dispatchers.Default) {
                        val immMedia = media.toImmutableList()
                        val albums = immMedia.groupBy { it.folderName ?: "Unknown" }
                            .map { (folderName, items) ->
                                Album(
                                    name = folderName,
                                    mediaCount = items.size,
                                    coverMedia = items.first()
                                )
                            }
                            .sortedBy { it.name }
                            .toImmutableList()

                        val displayed = when {
                            stateSnapshot.interactionState.openedAlbum != null -> {
                                immMedia.filter {
                                    (it.folderName ?: "Unknown") ==
                                            stateSnapshot.interactionState.openedAlbum.name
                                }.toImmutableList()
                            }
                            stateSnapshot.interactionState.openedPersonCluster != null -> {
                                stateSnapshot.contentState.displayedMediaList
                            }
                            else -> when (stateSnapshot.interactionState.selectedTab) {
                                GalleryTab.ALL -> immMedia
                                GalleryTab.VIDEOS -> immMedia.filter { it.isVideo }.toImmutableList()
                                GalleryTab.ALBUMS -> persistentListOf()
                                GalleryTab.OTHERS -> {
                                    if (stateSnapshot.interactionState.othersSelection == OthersSelection.FAVOURITES) {
                                        stateSnapshot.contentState.favouriteMediaList
                                    } else {
                                        persistentListOf()
                                    }
                                }
                            }
                        }

                        val grouped = groupMediaUseCase(
                            displayed,
                            stateSnapshot.configState.currentViewMode
                        )
                        Quadruple(immMedia, albums, displayed, grouped)
                    }

                setState {
                    copy(
                        configState = configState.copy(isLoading = false),
                        contentState = contentState.copy(
                            masterMediaList = immutableMedia,
                            displayedMediaList = newDisplayedList,
                            groupedMedia = initialGrouped,
                            albums = processedAlbums
                        )
                    )
                }

                if (!autoStartChecked) {
                    autoStartChecked = true
                    faceIndexingController.autoStartIfNeeded()
                }
            }
        }
    }

    private fun navigateToDetail(mediaId: Long) {
        setEffect { GalleryEffect.NavigateToDetail(mediaId) }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            mediaRepository.getFavorites().collect { favourites ->
                val stateSnapshot = uiState.value
                val isViewingFavourites =
                    stateSnapshot.interactionState.openedAlbum == null &&
                            stateSnapshot.interactionState.openedPersonCluster == null &&
                            stateSnapshot.interactionState.selectedTab == GalleryTab.OTHERS &&
                            stateSnapshot.interactionState.othersSelection == OthersSelection.FAVOURITES

                val (immutableFavourites, ids, groupedFavs) = withContext(Dispatchers.Default) {
                    val immFavs = favourites.toImmutableList()
                    val favIds = favourites.map { it.id }.toImmutableSet()
                    val grouped = if (isViewingFavourites) {
                        groupMediaUseCase(immFavs, stateSnapshot.configState.currentViewMode)
                    } else {
                        null
                    }
                    Triple(immFavs, favIds, grouped)
                }

                setState {
                    copy(
                        contentState = contentState.copy(
                            favouriteMediaList = immutableFavourites,
                            favoriteMediaIds = ids,
                            displayedMediaList = if (isViewingFavourites) immutableFavourites else contentState.displayedMediaList,
                            groupedMedia = groupedFavs ?: contentState.groupedMedia
                        )
                    )
                }
            }
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}