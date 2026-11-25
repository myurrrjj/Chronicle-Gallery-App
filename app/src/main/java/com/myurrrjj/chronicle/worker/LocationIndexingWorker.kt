package com.myurrrjj.chronicle.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myurrrjj.chronicle.R
import com.myurrrjj.chronicle.core.indexing.LocationIndexingProgressTracker
import com.myurrrjj.chronicle.core.util.GeocodeResult
import com.myurrrjj.chronicle.core.util.MediaLocationExtractor
import com.myurrrjj.chronicle.core.util.ReverseGeocodeHelper
import com.myurrrjj.chronicle.data.local.dao.LocationDao
import com.myurrrjj.chronicle.data.local.entity.MediaLocationEntity
import com.myurrrjj.chronicle.domain.repository.MediaRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@HiltWorker
class LocationIndexingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val locationDao: LocationDao,
    private val extractor: MediaLocationExtractor,
    private val geocoder: ReverseGeocodeHelper,
    private val tracker: LocationIndexingProgressTracker
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground("Scanning photo locations…", 0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!canReadLibrary()) {
                tracker.finish()
                return@withContext done(OUTCOME_NO_PERMISSION)
            }

            val geoGranted = canReadGeo()

            val refs = mediaRepository.getMediaRefs()
            if (refs.isEmpty()) {
                tracker.finish()
                return@withContext done(OUTCOME_COMPLETED)
            }

            val eligible = refs.filter { geoGranted || it.isVideo }
            Log.i(
                TAG,
                "eligible=${eligible.size} photos=${refs.count { !it.isVideo }} " +
                        "videos=${refs.count { it.isVideo }} geoGranted=$geoGranted"
            )
            if (eligible.isEmpty()) {
                tracker.finish()
                return@withContext done(OUTCOME_NO_GEO_PERMISSION)
            }

            val indexed = locationDao.getIndexedMedia().associateBy { it.mediaId }

            val pending = eligible.filter { ref ->
                val row = indexed[ref.id]
                row == null || row.extractorVersion < EXTRACTOR_VERSION
            }

            val staleUris = eligible.filter { ref ->
                val row = indexed[ref.id]
                row != null &&
                        row.extractorVersion >= EXTRACTOR_VERSION &&
                        row.uriString != ref.uriString
            }

            val total = eligible.size
            var processed = total - pending.size
            tracker.begin(total = total, processed = processed)

            if (pending.size > FOREGROUND_THRESHOLD) {
                setForeground(foreground("Scanning photo locations…", processed, total))
            }

            for (batch in pending.chunked(BATCH_SIZE)) {
                ensureActive()
                val entities = ArrayList<MediaLocationEntity>(batch.size)
                for (ref in batch) {
                    val coords = extractor.extract(ref.uriString, ref.isVideo)
                    entities += MediaLocationEntity(
                        mediaId = ref.id,
                        uriString = ref.uriString,
                        isVideo = ref.isVideo,
                        timestampSec = ref.dateAddedSec,
                        hasLocation = coords != null,
                        latitude = coords?.latitude,
                        longitude = coords?.longitude,
                        bucketKey = coords?.bucketKey,
                        extractorVersion = EXTRACTOR_VERSION
                    )
                }
                locationDao.upsertLocation(entities)
                processed += batch.size
                tracker.report(processed, total)
                setProgress(workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to total))
            }

            for (ref in staleUris) {
                ensureActive()
                locationDao.updateUri(ref.id, ref.uriString)
            }

            if (canReadWholeLibrary()) {
                val live = refs.mapTo(HashSet(refs.size)) { it.id }
                val deleted = indexed.keys.filterNot { it in live }
                deleted.chunked(PRUNE_CHUNK).forEach { locationDao.deleteByIds(it) }
            }

            if (isOnline()) {
                val buckets = locationDao
                    .getUncachedBucketKeys(System.currentTimeMillis() - PLACE_TTL_MS)
                    .take(MAX_GEOCODES_PER_RUN)
                for (bucketKey in buckets) {
                    ensureActive()
                    val sample = locationDao.getSamplePointForBucket(bucketKey) ?: continue
                    when (val result = geocoder.resolveBucket(bucketKey, sample.latitude, sample.longitude)) {
                        is GeocodeResult.Success -> locationDao.upsertPlace(result.place)
                        GeocodeResult.OfflineOrUnavailable -> break
                        GeocodeResult.NotFound -> Unit
                    }
                    delay(GEOCODE_SPACING_MS)
                }
            }

            tracker.finish()
            done(OUTCOME_COMPLETED)

        } catch (e: CancellationException) {
            tracker.finish()
            throw e
        } catch (e: SecurityException) {
            tracker.fail("Location access was revoked.")
            Result.failure(workDataOf(KEY_OUTCOME to OUTCOME_NO_PERMISSION))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                tracker.fail(e.message ?: "Location indexing stopped unexpectedly")
                Result.failure(workDataOf(KEY_OUTCOME to OUTCOME_FAILED))
            }
        }
    }

    private fun foreground(text: String, processed: Int, total: Int): ForegroundInfo {
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo Places", NotificationManager.IMPORTANCE_LOW)
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Gallery: Mapping your photos")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (total > 0) builder.setProgress(total, processed, false)
        else builder.setProgress(0, 0, true)

        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun canReadLibrary(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun canReadGeo(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun canReadWholeLibrary(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return canReadLibrary()
        val partial = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
        return canReadLibrary() && !partial
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun done(outcome: String): Result =
        Result.success(workDataOf(KEY_OUTCOME to outcome))

    companion object {
        const val WORK_NAME = "chronicle_location_indexing"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_OUTCOME = "outcome"

        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_NO_PERMISSION = "no_permission"
        const val OUTCOME_NO_GEO_PERMISSION = "no_geo_permission"

        const val EXTRACTOR_VERSION = 3

        private const val TAG = "LocationIndexingWorker"
        private const val CHANNEL_ID = "location_indexing_channel"
        private const val NOTIFICATION_ID = 1002
        private const val FOREGROUND_THRESHOLD = 200
        private const val BATCH_SIZE = 100
        private const val PRUNE_CHUNK = 900
        private const val MAX_GEOCODES_PER_RUN = 25
        private const val GEOCODE_SPACING_MS = 250L
        private const val MAX_ATTEMPTS = 3
        private const val PLACE_TTL_MS = 90L * 24L * 60L * 60L * 1000L

    }
}
