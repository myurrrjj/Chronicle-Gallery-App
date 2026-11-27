package com.myurrrjj.chronicle.presentation.search

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.myurrrjj.chronicle.core.presentation.mvi.BaseViewModel
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import com.myurrrjj.chronicle.domain.usecase.GroupMediaUseCase
import com.myurrrjj.chronicle.presentation.search.contract.SearchEffect
import com.myurrrjj.chronicle.presentation.search.contract.SearchEvent
import com.myurrrjj.chronicle.presentation.search.contract.SearchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val mediaRepository: MediaRepository,
    private val groupMediaUseCase: GroupMediaUseCase
) : BaseViewModel<SearchEvent, SearchState, SearchEffect>() {

    private val searchQueryFlow = MutableStateFlow("")
    private var cachedMediaList: List<MediaAsset> = emptyList()

    init {
        initializeSearchIndex()
        observeSearchExecution()
    }

    private fun initializeSearchIndex() {
        viewModelScope.launch {
            setState {
                copy(
                    isIndexing = true, indexingProgress = 0f, indexingError = null
                )
            }
            try {
                val media = mediaRepository.getAllMedia().first()
                val favs = mediaRepository.getFavorites().first().map { it.id }.toSet()
                cachedMediaList = media
                searchRepository.syncSearchIndex(media, favs).collect { progress ->
                    setState {
                        copy(indexingProgress = progress)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Indexing failed", e)

                setState {
                    copy(
                        indexingError = "Couldn't prepare the search index."
                    )
                }
            } finally {
                setState {
                    copy(isIndexing = false)
                }
            }
        }
    }

    override fun createInitialState(): SearchState = SearchState()

    override fun handleEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.OnQueryChanged -> {
                setState { copy(query = event.query,searchError = null) }
                searchQueryFlow.value = event.query
            }

            SearchEvent.ClearSearch -> {
                setState {
                    copy(
                        query = "", groupedResults = persistentMapOf(), isSearching = false, searchError = null
                    )
                }
                searchQueryFlow.value = ""
            }

            is SearchEvent.OnMediaClicked -> setEffect { SearchEffect.NavigateToDetail(event.mediaId) }
            SearchEvent.OnBackClicked -> setEffect { SearchEffect.NavigateBack }
            is SearchEvent.ChangeViewMode -> {
                setState { copy(currentViewMode = event.viewMode) }
                regroupResults()
            }

            SearchEvent.RetryIndexing -> {
                if (!uiState.value.isIndexing) {
                    searchQueryFlow.value = ""

                    setState {
                        copy(
                            query = "",
                            groupedResults = persistentMapOf(),
                            searchError = null,
                            isSearching = false
                        )
                    }

                    initializeSearchIndex()
                }
            }
        }
    }

    private fun observeSearchExecution() {
        viewModelScope.launch {
            searchQueryFlow.collectLatest { query ->

                if (query.isBlank()) {
                    setState {
                        copy(
                            groupedResults = persistentMapOf(),
                            isSearching = false,
                            searchError = null
                        )
                    }
                    return@collectLatest
                }

                delay(300L)

                if (uiState.value.isIndexing || uiState.value.indexingError != null) {
                    return@collectLatest
                }

                setState {
                    copy(
                        isSearching = true, searchError = null
                    )
                }

                try {
                    val matchedIds = searchRepository.searchMediaIds(query).toSet()

                    val matchedMedia = cachedMediaList.filter {
                        it.id in matchedIds
                    }

                    val grouped = groupMediaUseCase(
                        matchedMedia, uiState.value.currentViewMode
                    )

                    setState {
                        copy(
                            groupedResults = grouped, isSearching = false
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "Search failed", e)

                    setState {
                        copy(
                            groupedResults = persistentMapOf(),
                            isSearching = false,
                            searchError = "Couldn't complete the search. Try again."
                        )
                    }
                }
            }
        }
    }

    private fun regroupResults() {
        val currentResults = uiState.value.groupedResults.values.flatten()
        if (currentResults.isEmpty()) return
        val newGrouped = groupMediaUseCase(currentResults, uiState.value.currentViewMode)
        setState { copy(groupedResults = newGrouped) }
    }
}