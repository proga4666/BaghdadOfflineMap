package com.offlinemap.baghdad.data.model

import java.io.File

data class MapPackage(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val targetFile: File,
    val sizeBytesApprox: Long,
    val isInstalled: Boolean
)
