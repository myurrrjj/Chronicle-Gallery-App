package com.myurrrjj.chronicle.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class GalleryViewMode(
    val minCellSizeDp: Int, val zoomThreshold: Float
) {

    DAY(minCellSizeDp = 120, zoomThreshold = 1.5f),

    MONTH(minCellSizeDp = 70, zoomThreshold = 1.0f),

    YEAR(minCellSizeDp = 40, zoomThreshold = 0.5f);


    val isDense: Boolean
        get() = this != DAY

    val isMicroCell: Boolean
        get() = this == YEAR


    fun formatHeader(timestampSeconds: Long,zoneId: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochSecond(timestampSeconds).atZone(zoneId)
        return date.format(
            when (this) {
                DAY -> HEADER_DAY
                MONTH -> HEADER_MONTH
                YEAR -> HEADER_YEAR
            }
        )

    }

    fun getGroupingKey(timestampSeconds: Long,zoneId: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochSecond(timestampSeconds).atZone(ZoneId.systemDefault())
        return date.format(
            when (this) {
                DAY -> KEY_DAY
                MONTH -> KEY_MONTH
                YEAR -> KEY_YEAR
            }
        )
    }

    private companion object {
        val HEADER_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        val HEADER_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        val HEADER_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")
        val KEY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val KEY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        val KEY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy")
    }
}
