package com.myurrrjj.chronicle.domain.usecase

import com.myurrrjj.chronicle.data.local.dao.GeotaggedPhotoRow
import com.myurrrjj.chronicle.domain.model.PhotoMapCluster
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.tan

class ClusterPhotoPinsUseCase @Inject constructor() {


    operator fun invoke(
        photos: List<GeotaggedPhotoRow>,
        zoom: Double,
        cellPx: Float,
        centerLng: Double
    ): List<PhotoMapCluster> {
        if (photos.isEmpty()) return emptyList()

        val worldPx = TILE_PX * 2.0.pow(zoom.coerceIn(0.0, 22.0))
        val cell = cellPx.coerceAtLeast(1f).toDouble()
        val buckets = LinkedHashMap<Long, MutableList<GeotaggedPhotoRow>>()

        for (photo in photos) {
            val lat = photo.latitude.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
            val lng = photo.longitude + 360.0 * round((centerLng - photo.longitude) / 360.0)

            val xPx = ((lng + 180.0) / 360.0) * worldPx
            val latRad = lat * PI / 180.0
            val yPx = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * worldPx

            val cellX = floor(xPx / cell).toLong()
            val cellY = floor(yPx / cell).toLong()
            val key = (cellX shl 32) or (cellY and 0xFFFFFFFFL)

            buckets.getOrPut(key) { ArrayList() }.add(photo)
        }

        return buckets.map { (key, group) ->
            var latSum = 0.0
            var lngSum = 0.0
            var newest = group[0]
            val ids = ArrayList<Long>(group.size)

            for (item in group) {
                latSum += item.latitude
                lngSum += item.longitude
                ids += item.mediaId
                if (item.timestampSec > newest.timestampSec) newest = item
            }

            PhotoMapCluster(
                id = key.toString(),
                latitude = latSum / group.size,
                longitude = lngSum / group.size,
                coverMediaId = newest.mediaId,
                coverUriString = newest.uriString,
                count = group.size,
                placeName = group.firstNotNullOfOrNull { it.placeName },
                newestTimestampSec = newest.timestampSec,
                mediaIds = ids
            )
        }
    }

    companion object {
        private const val TILE_PX = 512.0
        private const val MAX_MERCATOR_LAT = 85.05112878
    }
}