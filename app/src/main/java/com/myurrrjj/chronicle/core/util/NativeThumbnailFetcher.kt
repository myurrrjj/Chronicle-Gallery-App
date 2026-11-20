package com.myurrrjj.chronicle.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.os.Trace
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

private class LifoSemaphore(private val maxPermits: Int) {
    private val lock = Any()
    private var availablePermits = maxPermits
    private val waiters = ArrayDeque<CancellableContinuation<Unit>>()

    suspend inline fun <T> withPermit(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    suspend fun acquire() {
        synchronized(lock) {
            if (availablePermits > 0) {
                availablePermits--
                return
            }
        }
        suspendCancellableCoroutine { cont ->
            val acquiredImmediately = synchronized(lock) {
                if (availablePermits > 0) {
                    availablePermits--
                    true
                } else {
                    waiters.addLast(cont)
                    false
                }
            }
            if (acquiredImmediately) {
                @Suppress("DEPRECATION")
                cont.resume(Unit) { release() }
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                synchronized(lock) {
                    waiters.remove(cont)
                }
            }
        }
    }

    fun release() {
        while (true) {
            val next = synchronized(lock) {
                val candidate = waiters.removeLastOrNull()
                if (candidate == null) {
                    if (availablePermits < maxPermits) {
                        availablePermits++
                    }
                    return
                }
                candidate
            }
            if (next.isActive) {
                @Suppress("DEPRECATION")
                next.resume(Unit) { release() }
                return
            }
        }
    }
}

class NativeThumbnailFetcher(
    private val uri: Uri,
    private val context: Context,
    private val isVideo: Boolean,
    private val target: Size,
    private val useRgb565: Boolean
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return THUMBNAIL_SEMAPHORE.withPermit {
            withContext(THUMBNAIL_DISPATCHER) {
                fetchThumbnail()
            }
        }
    }

    private suspend fun fetchThumbnail(): FetchResult? {
        coroutineContext.ensureActive()
        val signal = CancellationSignal()
        val registration = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) {
                signal.cancel()
            }
        }
        try {
            Trace.beginSection("thumb:load")
            val rawBitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        uri, target, signal
                    )
                } else {
                    val id = uri.lastPathSegment?.toLongOrNull() ?: return null
                    @Suppress("DEPRECATION") if (isVideo) {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    } else {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver,
                            id,
                            MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                }
            } finally {
                Trace.endSection()
            } ?: return null

            coroutineContext.ensureActive()

            val finalBitmap = normalizeBitmapSize(rawBitmap, target.width, target.height, useRgb565)

            return DrawableResult(
                drawable = finalBitmap.toDrawable(context.resources),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        } catch (e: OperationCanceledException) {
            throw CancellationException("Thumbnail load cancelled for $uri", e)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            registration.dispose()
        }
    }

    private fun normalizeBitmapSize(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        useRgb565: Boolean
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return source
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            return source
        }

        val targetShortEdge = minOf(targetW, targetH)
        val srcShortEdge = minOf(srcW, srcH)
        val dstW: Int
        val dstH: Int
        if (srcShortEdge > targetShortEdge) {
            val scale = targetShortEdge.toFloat() / srcShortEdge.toFloat()
            dstW = (srcW * scale).toInt().coerceAtLeast(1)
            dstH = (srcH * scale).toInt().coerceAtLeast(1)
        } else {
            dstW = srcW
            dstH = srcH
        }

        val needsResize = dstW != srcW || dstH != srcH
        val needs565 = useRgb565 && source.config != Bitmap.Config.RGB_565
        if (!needsResize && !needs565) return source

        return runCatching {
            if (useRgb565) {
                val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.RGB_565)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                Canvas(out).drawBitmap(source, null, Rect(0, 0, dstW, dstH), paint)
                source.recycle()
                out
            } else {
                val scaled = source.scale(dstW, dstH)
                if (scaled !== source) {
                    source.recycle()
                }
                scaled
            }
        }.getOrDefault(source)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val isThumbnail = options.parameters.value("is_thumbnail") as? Boolean ?: false

            if (!isThumbnail || data.scheme != "content" || data.authority != MediaStore.AUTHORITY) {
                return null
            }
            val w = options.size.width.pxOrElse { ThumbnailSizes.MAX_EDGE }
                .coerceIn(1, ThumbnailSizes.MAX_EDGE)
            val h = options.size.height.pxOrElse { ThumbnailSizes.MAX_EDGE }
                .coerceIn(1, ThumbnailSizes.MAX_EDGE)
            val useRgb565 = options.config == Bitmap.Config.RGB_565
            return NativeThumbnailFetcher(data, context, data.isVideoUri(), Size(w, h), useRgb565)
        }
    }

    companion object {
        private const val PARALLEL_THREADS = 4
        private val THREAD_ID = AtomicInteger(1)
        private val THUMBNAIL_SEMAPHORE = LifoSemaphore(PARALLEL_THREADS)

        private val THUMBNAIL_DISPATCHER =
            Executors.newFixedThreadPool(PARALLEL_THREADS) { runnable ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        runnable.run()
                    }, "ChronicleThumb-${THREAD_ID.getAndIncrement()}"
                ).apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
    }
}

private fun Uri.isVideoUri() = pathSegments.contains("video")