package com.myurrrjj.chronicle.core.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class FaceCropLocator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun cropDirectory(): File = File(context.filesDir, CROP_DIR)

    fun cropFile(mediaId: Long, faceIndex: Int): File =
        File(cropDirectory(), "${mediaId}_$faceIndex.jpg")

    fun legacyCropFile(embeddingId: Long): File =
        File(cropDirectory(), "$embeddingId.jpg")


    fun existingCropFile(mediaId: Long, faceIndex: Int, embeddingId: Long? = null): File? {
        val preferred = cropFile(mediaId, faceIndex)
        if (preferred.exists()) return preferred
        if (embeddingId != null) {
            val legacy = legacyCropFile(embeddingId)
            if (legacy.exists()) return legacy
        }
        return null
    }

    companion object {
        const val CROP_DIR = "face_crops"
    }
}
