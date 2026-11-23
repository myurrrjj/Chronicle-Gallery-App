package com.myurrrjj.chronicle.domain.usecase

import com.myurrrjj.chronicle.data.local.dao.GeotaggedPhotoRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterPhotoPinsUseCaseTest {

    private val useCase = ClusterPhotoPinsUseCase()

    private fun photo(id: Long, lat: Double, lng: Double, ts: Long = id) = GeotaggedPhotoRow(
        mediaId = id, uriString = "content://media/$id", isVideo = false,
        timestampSec = ts, latitude = lat, longitude = lng, bucketKey = null, placeName = null
    )

    @Test
    fun `photos a few metres apart become one pin`() {
        val result = useCase(
            listOf(photo(1, 28.6139, 77.2090), photo(2, 28.6140, 77.2091)),
            zoom = 12.0, cellPx = 48f, centerLng = 77.2090
        )
        assertEquals(1, result.size)
        assertEquals(2, result.single().count)
    }

    @Test
    fun `photos in different cities stay separate`() {
        val result = useCase(
            listOf(photo(1, 28.6139, 77.2090), photo(2, 19.0760, 72.8777)),
            zoom = 10.0, cellPx = 48f, centerLng = 77.0
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `the cover is the newest photo`() {
        val result = useCase(
            listOf(photo(1, 28.6139, 77.2090, ts = 100), photo(2, 28.6140, 77.2091, ts = 900)),
            zoom = 12.0, cellPx = 48f, centerLng = 77.2
        )
        assertEquals(2L, result.single().coverMediaId)
    }

    @Test
    fun `photos either side of the 180th meridian share a pin`() {
        val result = useCase(
            listOf(photo(1, 0.0, 179.9999), photo(2, 0.0, -179.9999)),
            zoom = 4.0, cellPx = 48f, centerLng = 180.0
        )
        assertEquals(1, result.size)
    }
}