package com.myurrrjj.chronicle.worker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myurrrjj.chronicle.core.util.hasFullMediaAccess
import com.myurrrjj.chronicle.core.util.readMediaStoreStamp
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import com.myurrrjj.chronicle.domain.repository.SEARCH_INDEX_FORMAT_VERSION
import com.myurrrjj.chronicle.domain.repository.SearchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale

@HiltWorker
class SearchIndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val searchRepository: SearchRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                synchronizeIndex()
            }
        }

    private suspend fun synchronizeIndex(): Result {
        val preferences = applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        try {
            if (!hasFullMediaAccess()) {
                return Result.success()
            }

            val favouriteIds = mediaRepository.getFavorites()
                .first()
                .map { it.id }
                .toSet()

            val checkpoint = createCheckpoint(favouriteIds)

            val previousCheckpoint =
                preferences.getString(KEY_CHECKPOINT, null)

            val forceScan = inputData.getBoolean(KEY_FORCE_SCAN, false)

            if (
                !forceScan &&
                checkpoint != null &&
                checkpoint == previousCheckpoint
            ) {
                preferences.edit().remove(KEY_ERROR).apply()

                Log.d(TAG, "Index unchanged. Skipping scan.")

                return Result.success()
            }

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                MediaStore.getExternalVolumeNames(applicationContext).isEmpty()
            ) {
                return Result.success()
            }

            setProgress(
                workDataOf(KEY_INDEXING to true)
            )

            val media = mediaRepository
                .getAllMedia(forceRefresh = forceScan)
                .first()

            if (!hasFullMediaAccess()) {
                return Result.success()
            }

            check(
                preferences.edit()
                    .remove(KEY_CHECKPOINT)
                    .commit()
            )

            searchRepository.syncSearchIndex(
                media,
                favouriteIds
            ).collect { }

            if (
                checkpoint != null &&
                checkpoint == createCheckpoint(favouriteIds) &&
                hasFullMediaAccess()
            ) {
                check(
                    preferences.edit()
                        .putString(KEY_CHECKPOINT, checkpoint)
                        .commit()
                )
            }

            preferences.edit()
                .remove(KEY_ERROR)
                .apply()

            Log.d(TAG, "Search synchronization completed")

            return Result.success()

        } catch (e: CancellationException) {
            throw e

        } catch (e: SecurityException) {
            Log.w(TAG, "Media permission unavailable", e)
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Search indexing failed", e)

            return if (runAttemptCount < 2) {
                Result.retry()
            } else {
                preferences.edit {
                    putString(
                        KEY_ERROR,
                        "Couldn't update the search index."
                    )
                }

                Result.failure()
            }
        }
    }

    private fun createCheckpoint(
        favouriteIds: Set<Long>
    ): String? {
        val volumes = readMediaStoreStamp(applicationContext)
            ?: return null

        val data = JSONArray()

        data.put(SEARCH_INDEX_FORMAT_VERSION)
        data.put(ZoneId.systemDefault().id)

        data.put(
            Locale.getDefault(Locale.Category.FORMAT)
                .toLanguageTag()
        )

        for (volume in volumes) {
            data.put(
                JSONArray()
                    .put(volume.volume)
                    .put(volume.version)
                    .put(volume.generation)
            )
        }

        val favourites = JSONArray()

        favouriteIds.sorted().forEach {
            favourites.put(it)
        }

        data.put(favourites)

        val hash = MessageDigest
            .getInstance("SHA-256")
            .digest(
                data.toString().toByteArray(Charsets.UTF_8)
            )

        return Base64.encodeToString(
            hash,
            Base64.NO_WRAP
        )
    }

    private fun hasFullMediaAccess(): Boolean {
        return hasFullMediaAccess(applicationContext)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val WORK_NAME = "chronicle_search_indexing"
        const val KEY_FORCE_SCAN = "force_scan"

        private const val TAG = "SearchIndexingWorker"

        //        private const val PREFS_NAME = "search_index_checkpoint"
        private const val KEY_CHECKPOINT = "checkpoint"
        const val PREFS_NAME = "search_index_checkpoint"
        const val KEY_ERROR = "last_indexing_error"
        const val KEY_INDEXING = "is_indexing"

        private val syncMutex = Mutex()
    }
}