package com.myurrrjj.chronicle.domain.model

import java.io.File

data class MediaFace (
    val embeddingId:Long,
    val faceIndex:Int,
    val clusterId:Long?,
    val personLabel:String?,
    val cropFile: File?
){
    val displayName:String
        get() = personLabel?.takeIf { it.isNotBlank() }?:"Unknown"
}