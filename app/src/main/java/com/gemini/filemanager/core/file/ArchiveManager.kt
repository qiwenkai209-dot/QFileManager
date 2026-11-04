package com.gemini.filemanager.core.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.File

data class ArchiveEntry(
    val name: String,
    val uncompressedSize: Long,
    val isDirectory: Boolean
)

object ArchiveManager {

    suspend fun listZipEntries(zipFile: File): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        try {
            val zFile = ZipFile(zipFile)
            if (!zFile.isValidZipFile) return@withContext emptyList()
            val headers: List<FileHeader> = zFile.fileHeaders
            headers.map {
                ArchiveEntry(
                    name = it.fileName,
                    uncompressedSize = it.uncompressedSize,
                    isDirectory = it.isDirectory
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun extractZip(
        zipFile: File,
        destDir: File,
        password: String? = null,
        onProgress: (progress: Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            val zFile = ZipFile(zipFile)
            if (!zFile.isValidZipFile) return@withContext false

            if (password != null && zFile.isEncrypted) {
                zFile.setPassword(password.toCharArray())
            }

            zFile.isRunInThread = true
            zFile.extractAll(destDir.absolutePath)
            
            while (zFile.progressMonitor.state == net.lingala.zip4j.progress.ProgressMonitor.State.BUSY) {
                onProgress(zFile.progressMonitor.percentDone)
                kotlinx.coroutines.delay(100)
            }
            zFile.progressMonitor.result == net.lingala.zip4j.progress.ProgressMonitor.Result.SUCCESS
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
