package com.myurrrjj.chronicle.core.indexing

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import com.myurrrjj.chronicle.worker.SearchIndexingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SearchIndexingStatus(
    val isRunning: Boolean = false,
    val error: String? = null
)

@Singleton
class SearchIndexingController @Inject constructor(
    private val workManager: WorkManager,
    @ApplicationContext context: Context
) {

    private val enqueueMutex = Mutex()

    private val preferences = context.getSharedPreferences(
        SearchIndexingWorker.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    suspend fun enqueue(forceScan: Boolean = false) {
        enqueueMutex.withLock {
            val pendingQuery = WorkQuery.Builder
                .fromUniqueWorkNames(
                    listOf(SearchIndexingWorker.WORK_NAME)
                )
                .addStates(
                    listOf(
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED
                    )
                )
                .build()

            val pendingWork = workManager
                .getWorkInfos(pendingQuery)
                .await()

            if (pendingWork.isNotEmpty()) {
                return@withLock
            }

            val request =
                OneTimeWorkRequestBuilder<SearchIndexingWorker>()
                    .setInputData(
                        workDataOf(
                            SearchIndexingWorker.KEY_FORCE_SCAN to forceScan
                        )
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .build()

            workManager.enqueueUniqueWork(
                SearchIndexingWorker.WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            ).result.await()
        }
    }

    suspend fun ensurePeriodicRefresh() {
        val request =
            PeriodicWorkRequestBuilder<SearchIndexingWorker>(
                12,
                TimeUnit.HOURS
            )
                .setInitialDelay(12, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        SearchIndexingWorker.KEY_FORCE_SCAN to true
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        ).result.await()
    }

    fun observeStatus(): Flow<SearchIndexingStatus> {
        return combine(
            workManager.getWorkInfosForUniqueWorkFlow(
                SearchIndexingWorker.WORK_NAME
            ),
            workManager.getWorkInfosForUniqueWorkFlow(
                PERIODIC_WORK_NAME
            ),
            observeLastError()
        ) { immediateWork, periodicWork, error ->

            val isRunning =
                (immediateWork + periodicWork).any { work ->
                    work.state == WorkInfo.State.RUNNING &&
                            work.progress.getBoolean(
                                SearchIndexingWorker.KEY_INDEXING,
                                false
                            )
                }

            SearchIndexingStatus(
                isRunning = isRunning,
                error = error
            )

        }.distinctUntilChanged()
    }

    private fun observeLastError(): Flow<String?> = callbackFlow {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == SearchIndexingWorker.KEY_ERROR) {
                    trySend(
                        preferences.getString(
                            SearchIndexingWorker.KEY_ERROR,
                            null
                        )
                    )
                }
            }

        preferences.registerOnSharedPreferenceChangeListener(listener)

        trySend(
            preferences.getString(
                SearchIndexingWorker.KEY_ERROR,
                null
            )
        )

        awaitClose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    companion object {
        const val PERIODIC_WORK_NAME =
            "chronicle_search_indexing_periodic"
    }
}