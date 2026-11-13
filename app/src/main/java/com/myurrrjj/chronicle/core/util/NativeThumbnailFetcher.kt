package com.myurrrjj.chronicle.core.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

class NativeThumbnailFetcher(
    private val uri: Uri,
    private val context: Context,
    private val isVideo: Boolean,
    private val target: Size
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return withContext(Dispatchers.IO.limitedParallelism(8)) {
            coroutineContext.ensureActive()
            val signal = CancellationSignal()
            val registration = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause != null) {
                    signal.cancel()
                }
            }
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        uri, target, signal
                    )
                } else {
                    val id = uri.lastPathSegment?.toLongOrNull() ?: return@withContext null
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

                coroutineContext.ensureActive()
                bitmap?.let {
                    DrawableResult(
                        drawable = it.toDrawable(context.resources),
                        isSampled = true,
                        dataSource = DataSource.DISK
                    )
                }

            } catch (e: OperationCanceledException) {
                throw CancellationException("Thumbnail load cancelled for $uri", e)

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                registration.dispose()
            }

        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val isThumbnail = options.parameters.value("is_thumbnail") as? Boolean ?: false

            if (!isThumbnail || data.scheme != "content" || data.authority != MediaStore.AUTHORITY) {
                return null
            }
            val w = options.size.width.pxOrElse { ThumbnailSizes.MAX_EDGE }.coerceIn(1, ThumbnailSizes.MAX_EDGE)
            val h = options.size.height.pxOrElse { ThumbnailSizes.MAX_EDGE }.coerceIn(1, ThumbnailSizes.MAX_EDGE)
            return NativeThumbnailFetcher(data, context, data.isVideoUri(), Size(w, h))

        }


    }


}

private fun Uri.isVideoUri() = pathSegments.contains("video")
