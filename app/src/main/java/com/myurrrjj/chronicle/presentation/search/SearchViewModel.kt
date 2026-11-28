package com.myurrrjj.chronicle.presentation.search

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.myurrrjj.chronicle.core.indexing.SearchIndexingController
import com.myurrrjj.chronicle.core.presentation.mvi.BaseViewModel
import com.myurrrjj.chronicle.domain.model.MediaAsset
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import com.myurrrjj.chronicle.domain.usecase.GroupMediaUseCase
import com.myurrrjj.chronicle.presentation.search.contract.SearchEffect
import com.myurrrjj.chronicle.presentation.search.contract.SearchEvent
import com.myurrrjj.chronicle.presentation.search.contract.SearchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val groupMediaUseCase: GroupMediaUseCase,
    private val searchIndexingController: SearchIndexingController
) : BaseViewModel<SearchEvent, SearchState, SearchEffect>() {

    private val searchQueryFlow = MutableStateFlow("")

    private val mediaFlow =
        MutableStateFlow<List<MediaAsset>>(emptyList())

    init {
        observeSearchExecution()
        observeIndexingStatus()

    }

    override fun createInitialState(): SearchState = SearchState()

    fun updateMedia(media: List<MediaAsset>) {
        mediaFlow.value = media
    }

    override fun handleEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.OnQueryChanged -> {
                setState {
                    copy(
                        query = event.query,
                        searchError = null
                    )
                }

                searchQueryFlow.value = event.query
            }

            SearchEvent.ClearSearch -> {
                searchQueryFlow.value = ""

                setState {
                    copy(
                        query = "",
                        groupedResults = persistentMapOf(),
                        isSearching = false,
                        searchError = null
                    )
                }
            }

            is SearchEvent.OnMediaClicked -> {
                setEffect {
                    SearchEffect.NavigateToDetail(event.mediaId)
                }
            }

            SearchEvent.OnBackClicked -> {
                setEffect { SearchEffect.NavigateBack }
            }

            is SearchEvent.ChangeViewMode -> {
                setState {
                    copy(currentViewMode = event.viewMode)
                }
            }

            SearchEvent.RetryIndexing -> {
                retryIndexing()
            }
        }
    }

    private fun observeSearchExecution() {
        viewModelScope.launch {
            combine(
                searchQueryFlow,
                mediaFlow,
                searchRepository.observeIndexChanges(),
                uiState
                    .map { it.currentViewMode }
                    .distinctUntilChanged()
            ) { query, media, _, viewMode ->
                Triple(query, media, viewMode)
            }.collectLatest { (query, media, viewMode) ->

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

                setState {
                    copy(
                        isSearching = true,
                        searchError = null
                    )
                }

                try {
                    val matchedIds = searchRepository
                        .searchMediaIds(query)
                        .toSet()

                    val grouped = withContext(Dispatchers.Default) {
                        val matchedMedia = media.filter {
                            it.id in matchedIds
                        }

                        groupMediaUseCase(
                            matchedMedia,
                            viewMode
                        )
                    }

                    setState {
                        copy(
                            groupedResults = grouped,
                            isSearching = false
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
                            searchError = "Couldn't complete the search."
                        )
                    }
                }
            }
        }
    }
    private fun observeIndexingStatus() {
        viewModelScope.launch {
            try {
                searchIndexingController.observeStatus()
                    .collect { status ->
                        setState {
                            copy(
                                isIndexing = status.isRunning,
                                indexingError = status.error
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Index observation failed", e)

                setState {
                    copy(
                        isIndexing = false,
                        indexingError = "Couldn't observe search indexing."
                    )
                }
            }
        }
    }

    private fun retryIndexing() {
        viewModelScope.launch {
            try {
                searchIndexingController.enqueue(forceScan = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Couldn't schedule indexing", e)

                setState {
                    copy(
                        indexingError = "Couldn't schedule search indexing."
                    )
                }
            }
        }
    }}