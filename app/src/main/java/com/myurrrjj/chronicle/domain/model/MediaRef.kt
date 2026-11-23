package com.myurrrjj.chronicle.domain.model

data class MediaRef(
    val id: Long,
    val uriString:String,
    val isVideo:Boolean,
    val dateAddedSec:Long
)
