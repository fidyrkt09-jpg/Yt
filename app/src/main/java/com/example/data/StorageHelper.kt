package com.example.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File

object StorageHelper {
    private const val TAG = "StorageHelper"
    private const val SUBFOLDER_NAME = "YT_DLP_Bot"

    /**
     * Get the target download folder. Under Android 10+ without full storage permissions,
     * it will fall back gracefully to the app-scoped external downloads directory.
     */
    fun getDownloadDirectory(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appFolder = File(publicDownloads, SUBFOLDER_NAME)
        
        try {
            if (!appFolder.exists()) {
                val created = appFolder.mkdirs()
                if (created) {
                    Log.i(TAG, "Created public downloads subfolder: ${appFolder.absolutePath}")
                    return appFolder
                }
            } else {
                return appFolder
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to use public downloads folder. Falling back...", e)
        }

        // Graceful fallback to app's own sandboxed external files folder
        val fallbackFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val fallbackAppFolder = File(fallbackFolder, SUBFOLDER_NAME)
        if (!fallbackAppFolder.exists()) {
            fallbackAppFolder.mkdirs()
        }
        Log.i(TAG, "Using fallback directory: ${fallbackAppFolder.absolutePath}")
        return fallbackAppFolder
    }

    /**
     * Structure representing the disk space details
     */
    data class StorageSpace(
        val totalBytes: Long,
        val freeBytes: Long,
        val availableBytes: Long,
        val path: String
    ) {
        val totalMegaBytes: Long get() = totalBytes / (1024 * 1024)
        val freeMegaBytes: Long get() = freeBytes / (1024 * 1024)
        val isWarningNeeded: Boolean get() = freeBytes < 500 * 1024 * 1024 // < 500 MB
        val isFull: Boolean get() = freeBytes <= 0
    }

    /**
     * Retrieve details of available space on target download directory
     */
    fun getStorageDetails(context: Context): StorageSpace {
        val dir = getDownloadDirectory(context)
        return try {
            val stat = StatFs(dir.absolutePath)
            val total = stat.totalBytes
            val free = stat.freeBytes
            val available = stat.availableBytes
            StorageSpace(total, free, available, dir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage size", e)
            StorageSpace(0L, 0L, 0L, dir.absolutePath)
        }
    }

    /**
     * Struct representing a completed downloadable file
     */
    data class FileDetails(
        val index: Int,
        val name: String,
        val format: String,
        val sizeBytes: Long,
        val displaySize: String,
        val lastModified: Long,
        val displayDate: String,
        val absolutePath: String
    )

    /**
     * List all downloaded files present in the app's target download folder
     */
    fun listFiles(context: Context): List<FileDetails> {
        val dir = getDownloadDirectory(context)
        val files = dir.listFiles() ?: return emptyList()
        
        // Sort files by last modified date (newest first)
        val sortedFiles = files.filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            
        return sortedFiles.mapIndexed { idx, file ->
            val size = file.length()
            val name = file.name
            val ext = file.extension.uppercase()
            
            val formattedSize = when {
                size >= 1024 * 1024 * 1024 -> String.format("%.2f GB", size.toDouble() / (1024 * 1024 * 1024))
                size >= 1024 * 1024 -> String.format("%.2f MB", size.toDouble() / (1024 * 1024))
                size >= 1024 -> String.format("%.2f KB", size.toDouble() / 1024)
                else -> "$size B"
            }
            
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            val dateStr = sdf.format(java.util.Date(file.lastModified()))
            
            FileDetails(
                index = idx + 1,
                name = name,
                format = ext,
                sizeBytes = size,
                displaySize = formattedSize,
                lastModified = file.lastModified(),
                displayDate = dateStr,
                absolutePath = file.absolutePath
            )
        }
    }

    /**
     * Delete a downloadable file by name
     */
    fun deleteFileByName(context: Context, name: String): Boolean {
        val dir = getDownloadDirectory(context)
        val file = File(dir, name)
        return if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Delete a file by its list line index (1-based)
     */
    fun deleteFileByIndex(context: Context, lineIndex: Int): Pair<Boolean, String> {
        val currentList = listFiles(context)
        val item = currentList.firstOrNull { it.index == lineIndex }
            ?: return Pair(false, "Fichier d'index $lineIndex non trouvé. Utilisez /list_files pour voir la liste valide.")
            
        val file = File(item.absolutePath)
        return if (file.exists() && file.isFile) {
            val deleted = file.delete()
            if (deleted) {
                Pair(true, item.name)
            } else {
                Pair(false, "Impossible de supprimer le fichier: ${item.name}")
            }
        } else {
            Pair(false, "Le fichier n'existe plus sur le disque: ${item.name}")
        }
    }
}
