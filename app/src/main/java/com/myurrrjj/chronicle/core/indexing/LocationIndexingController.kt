package com.myurrrjj.chronicle.core.indexing

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.myurrrjj.chronicle.worker.LocationIndexingWorker
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class LocationIndexingController @Inject constructor(
    private val workManager: WorkManager,
    private val tracker : LocationIndexingProgressTracker
) {
    fun enqueue(){
        val request = OneTimeWorkRequestBuilder<LocationIndexingWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            LocationIndexingWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )


    }

    fun ensurePeriodicRefresh(){
        val request = PeriodicWorkRequestBuilder<LocationIndexingWorker>(12, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun observeProgress(): Flow<LocationIndexingProgress> = tracker.state




    companion object {
        private const val PERIODIC_WORK_NAME = "chronicle_location_indexing_periodic"
    }
}