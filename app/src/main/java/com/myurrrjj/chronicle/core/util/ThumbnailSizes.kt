package com.myurrrjj.chronicle.core.util

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import coil.ImageLoader
import coil.memory.MemoryCache
import kotlin.math.max

object ThumbnailSizes {

    const val MAX_EDGE = 512
    const val FACE_CROP_PX = 384

    private val BUCKETS_DESC = intArrayOf(512, 384, 320, 256, 192, 128)

    fun decodePxForGrid(
        density: Density,
        availableWidthDp: Dp,
        minCellDp: Dp,
        spacingDp: Dp
    ): Int = with(density) {
        val availablePx = max(1, availableWidthDp.roundToPx())
        val minCellPx = max(1, minCellDp.roundToPx())
        val spacingPx = spacingDp.roundToPx()
        val columns = max(1, (availablePx + spacingPx) / (minCellPx + spacingPx))
        val cellPx = max(1, (availablePx - spacingPx * (columns - 1)) / columns)
        bucketPx(cellPx)
    }

    fun bucketPx(px: Int): Int = when {
        px <= 128 -> 128
        px <= 192 -> 192
        px <= 256 -> 256
        px <= 320 -> 320
        px <= 384 -> 384
        else -> MAX_EDGE
    }

    fun cacheKey(uriString: String, px: Int): String = "thumb:$px:$uriString"

    fun faceCacheKey(path: String, px: Int): String = "face:$px:$path"

    fun findCachedThumbnailKey(
        imageLoader: ImageLoader,
        uriString: String
    ): MemoryCache.Key? {
        val memoryCache = imageLoader.memoryCache ?: return null
        for (bucket in BUCKETS_DESC) {
            val candidate = MemoryCache.Key(cacheKey(uriString, bucket))
            if (memoryCache[candidate] != null) {
                return candidate
            }
        }
        return null
    }
}