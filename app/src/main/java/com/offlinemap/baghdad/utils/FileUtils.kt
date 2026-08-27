package com.offlinemap.baghdad.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object FileUtils {

    fun getMapsDirectory(context: Context): File {
        val internalDir = File(context.filesDir, "maps")
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        val externalDir = File(context.getExternalFilesDir(null), "maps")
        if (externalDir.exists() && externalDir.listFiles()?.any { it.name.endsWith(".map") } == true) {
            return externalDir
        }
        return internalDir
    }

    fun getRoutesDirectory(context: Context): File {
        val internalDir = File(context.filesDir, "routes")
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        val externalDir = File(context.getExternalFilesDir(null), "routes")
        if (externalDir.exists() && externalDir.listFiles()?.isNotEmpty() == true) {
            return externalDir
        }
        return internalDir
    }

    fun unzip(zipFile: File, targetDirectory: File) {
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun copyAssetToFile(context: Context, assetName: String, destinationFile: File): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
