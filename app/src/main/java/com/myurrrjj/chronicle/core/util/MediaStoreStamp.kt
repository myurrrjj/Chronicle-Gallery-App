package com.myurrrjj.chronicle.core.util


import android.content.Context
import android.os.Build
import android.provider.MediaStore

data class MediaVolumeStamp(
    val volume: String,
    val version: String,
    val generation: Long
)

fun readMediaStoreStamp(
    context: Context
): List<MediaVolumeStamp>? {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return null
    }

    return try {
        val volumes = MediaStore
            .getExternalVolumeNames(context)
            .sorted()

        if (volumes.isEmpty()) return null

        volumes.map { volume ->
            val version = MediaStore.getVersion(
                context,
                volume
            ) ?: return null

            val generation = MediaStore.getGeneration(
                context,
                volume
            )

            MediaVolumeStamp(
                volume = volume,
                version = version,
                generation = generation
            )
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}