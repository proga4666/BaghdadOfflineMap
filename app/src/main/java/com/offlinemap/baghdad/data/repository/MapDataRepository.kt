package com.offlinemap.baghdad.data.repository

import android.content.Context
import com.offlinemap.baghdad.data.model.MapPackage
import com.offlinemap.baghdad.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MapDataRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val mapsDirectory: File
        get() = FileUtils.getMapsDirectory(context)

    val routesDirectory: File
        get() = FileUtils.getRoutesDirectory(context)

    /**
     * Automatically extracts the pre-built GraphHopper offline routing graph from assets if needed
     */
    fun extractBundledRoutingGraphIfNeeded() {
        val routesDir = File(context.filesDir, "routes")
        val targetGhDir = File(routesDir, "baghdad-gh")
        if (!targetGhDir.exists() || targetGhDir.listFiles()?.isEmpty() == true) {
            try {
                routesDir.mkdirs()
                val tempZip = File(context.cacheDir, "bundled_baghdad_gh.zip")
                context.assets.open("routes/baghdad-gh.zip").use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
                FileUtils.unzip(tempZip, routesDir)
                tempZip.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Locate the best available .map file (e.g. iraq.map or baghdad.map or any .map in the directory)
     */
    fun findPrimaryMapFile(): File? {
        val searchDirs = listOf(
            File(context.filesDir, "maps"),
            File(context.getExternalFilesDir(null), "maps"),
            File("/sdcard/Android/data/${context.packageName}/files/maps")
        )
        for (dir in searchDirs) {
            if (dir.exists()) {
                val files = dir.listFiles { file ->
                    file.isFile && file.name.endsWith(".map", ignoreCase = true) && file.length() > 500_000L
                }
                if (!files.isNullOrEmpty()) {
                    return files.first()
                }
            }
        }
        return null
    }

    /**
     * Locate the routing graph directory (-gh folder)
     */
    fun findPrimaryRoutingGraph(): File? {
        val searchDirs = listOf(
            File(context.filesDir, "routes"),
            File(context.getExternalFilesDir(null), "routes"),
            File("/sdcard/Android/data/${context.packageName}/files/routes")
        )
        for (dir in searchDirs) {
            if (dir.exists()) {
                val ghDirs = dir.listFiles { file ->
                    file.isDirectory && (file.name.endsWith("-gh", ignoreCase = true) || file.name.contains("gh", ignoreCase = true))
                }
                if (!ghDirs.isNullOrEmpty()) {
                    return ghDirs[0]
                }
                // Also check if directory itself contains nodes/edges files
                val hasGraphFiles = dir.listFiles { file ->
                    file.name.startsWith("nodes") || file.name.startsWith("edges") || file.name == "properties"
                }?.isNotEmpty() == true

                if (hasGraphFiles) return dir
            }
        }
        return null
    }

    fun getAvailablePackages(): List<MapPackage> {
        val baghdadTestMapFile = File(mapsDirectory, "baghdad_central.map")
        val iraqMapFile = File(mapsDirectory, "iraq.map")

        return listOf(
            MapPackage(
                id = "baghdad_central",
                name = "📍 Al-Mansour & Baghdad Center",
                description = "Quick test package for Baghdad Center, Al-Mansour, Karrada & Tigris landmarks (~2.5 MB).",
                downloadUrl = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/asia/iraq.map",
                targetFile = baghdadTestMapFile,
                sizeBytesApprox = 2_500_000L,
                isInstalled = baghdadTestMapFile.exists() && baghdadTestMapFile.length() > 500_000L
            ),
            MapPackage(
                id = "iraq_mapsforge",
                name = "🇮🇶 Complete Iraq & Baghdad Map",
                description = "High-speed mirror with complete vector streets, POIs and all districts of Baghdad (~98 MB).",
                downloadUrl = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/asia/iraq.map",
                targetFile = iraqMapFile,
                sizeBytesApprox = 103_043_871L,
                isInstalled = iraqMapFile.exists() && iraqMapFile.length() > 10_000_000L
            )
        )
    }

    /**
     * Download a map file with reactive progress updates
     */
    fun downloadMapFile(mapPackage: MapPackage): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Started)
        val targetFile = mapPackage.targetFile
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        try {
            val request = Request.Builder()
                .url(mapPackage.downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; BaghdadOfflineMap/1.0)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body from server")
            val totalBytesFromHeader = body.contentLength()
            val totalBytes = if (totalBytesFromHeader > 0) totalBytesFromHeader else mapPackage.sizeBytesApprox
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    var lastEmittedBytes = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        // Emit update every 128 KB or when complete
                        if (downloadedBytes - lastEmittedBytes >= 131072L || downloadedBytes == totalBytes) {
                            lastEmittedBytes = downloadedBytes
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else 0
                            emit(DownloadProgress.Downloading(progress, downloadedBytes, totalBytes))
                        }
                    }
                }
            }

            // Atomically replace target file
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (tempFile.renameTo(targetFile)) {
                emit(DownloadProgress.Completed(targetFile))
            } else {
                throw IOException("Failed to save downloaded map file")
            }

        } catch (e: Exception) {
            tempFile.delete()
            emit(DownloadProgress.Failed(e.localizedMessage ?: "Download interrupted"))
        }
    }.flowOn(Dispatchers.IO)
}

sealed class DownloadProgress {
    object Started : DownloadProgress()
    data class Downloading(val percent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
    data class Completed(val file: File) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
}
