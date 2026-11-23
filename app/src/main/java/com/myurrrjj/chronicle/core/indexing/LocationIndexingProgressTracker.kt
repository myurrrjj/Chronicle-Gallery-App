package com.myurrrjj.chronicle.core.indexing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class LocationIndexingProgress(
    val isRunning:Boolean = false,
    val processed:Int = 0,
    val total:Int = 0,
    val message:String? = null
)


@Singleton
class LocationIndexingProgressTracker @Inject constructor(){
    private val _state = MutableStateFlow(LocationIndexingProgress())
    val state = _state.asStateFlow()


    fun begin(total:Int, processed:Int) = _state.update {
        it.copy(isRunning = true, total = total, processed = processed, message = null)
    }
    fun report(processed: Int, total: Int) = _state.update {
        it.copy(isRunning = true, processed = processed, total = total)
    }
    fun finish() = _state.update { it.copy(isRunning = false, processed = it.total, message = null) }

    fun fail(message: String) = _state.update { it.copy(isRunning = false, message = message) }

}