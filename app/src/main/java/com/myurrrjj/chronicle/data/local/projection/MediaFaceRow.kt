package com.myurrrjj.chronicle.data.local.projection

data class MediaFaceRow(
    val embeddingId:Long,
    val faceIndex :Int,
    val clusterId:Long?,
    val personLabel:String?

)