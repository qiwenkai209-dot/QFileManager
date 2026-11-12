package com.gemini.filemanager.core.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.gemini.filemanager.data.model.FileCategory
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "raw")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "ape", "opus", "alac", "aiff", "dsf")
    private val DOC_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "md", "csv")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "3gp", "3g2", "mpg", "mpeg", "vob", "m2ts", "mts", "rmvb", "rm", "asf", "ogv")
    private val APK_EXTENSIONS = setOf("apk", "apks", "xapk")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    fun getCategory(extension: String): FileCategory {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            IMAGE_EXTENSIONS.contains(ext) -> FileCategory.IMAGE
            AUDIO_EXTENSIONS.contains(ext) -> FileCategory.AUDIO
            DOC_EXTENSIONS.contains(ext) -> FileCategory.DOCUMENT
            VIDEO_EXTENSIONS.contains(ext) -> FileCategory.VIDEO
            APK_EXTENSIONS.contains(ext) -> FileCategory.APK
            ARCHIVE_EXTENSIONS.contains(ext) -> FileCategory.ARCHIVE
            ext == "m3u8" -> FileCategory.M3U8
            else -> FileCategory.DOCUMENT
        }
    }

    fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
        val format = DecimalFormat("#,##0.#")
        return "${format.format(sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun openFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = getMimeType(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果无法直接打开，提示以普通文件打开
            val chooser = Intent.createChooser(intent, "打开方式").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    fun shareFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = getMimeType(files.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "*/*"
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun deleteFileOrDirectory(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteFileOrDirectory(it) }
        }
        return file.delete()
    }

    fun renameFile(context: Context, file: File, newName: String): File? {
        if (!file.exists() || newName.isBlank()) return null
        val parent = file.parentFile ?: return null
        val target = File(parent, newName)
        if (target.exists()) return null
        val success = file.renameTo(target)
        if (success) {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath, target.absolutePath),
                null,
                null
            )
            return target
        }
        return null
    }

    fun copyFile(context: Context, sourceFile: File, targetDir: File): File? {
        if (!sourceFile.exists() || !targetDir.exists()) return null
        var dest = File(targetDir, sourceFile.name)
        if (dest.exists()) {
            val base = sourceFile.nameWithoutExtension
            val ext = if (sourceFile.extension.isNotBlank()) ".${sourceFile.extension}" else ""
            dest = File(targetDir, "${base}_copy_${System.currentTimeMillis()}$ext")
        }
        return try {
            java.io.FileInputStream(sourceFile).use { input ->
                java.io.FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(dest.absolutePath),
                null,
                null
            )
            dest
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun moveFile(context: Context, sourceFile: File, targetDir: File): File? {
        if (!sourceFile.exists() || !targetDir.exists()) return null
        var dest = File(targetDir, sourceFile.name)
        if (dest.exists()) {
            val base = sourceFile.nameWithoutExtension
            val ext = if (sourceFile.extension.isNotBlank()) ".${sourceFile.extension}" else ""
            dest = File(targetDir, "${base}_${System.currentTimeMillis()}$ext")
        }
        // 尝试原子重命名移动
        if (sourceFile.renameTo(dest)) {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(sourceFile.absolutePath, dest.absolutePath),
                null,
                null
            )
            return dest
        }
        // 跨存储卷 fallback
        val copied = copyFile(context, sourceFile, targetDir)
        if (copied != null) {
            sourceFile.delete()
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(sourceFile.absolutePath),
                null,
                null
            )
            return copied
        }
        return null
    }

    fun getPathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            val proj = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
            try {
                context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (col != -1) {
                            val path = cursor.getString(col)
                            if (!path.isNullOrBlank()) return path
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return uri.path
    }
}
