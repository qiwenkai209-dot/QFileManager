package com.gemini.filemanager.core.file

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object FileScanner {

    data class StorageStats(
        val totalBytes: Long,
        val availableBytes: Long,
        val categorySizes: Map<FileCategory, Long> = emptyMap(),
        val categoryCounts: Map<FileCategory, Int> = emptyMap()
    )

    fun getStorageOverview(): StorageStats {
        val root = Environment.getExternalStorageDirectory()
        val totalBytes = root.totalSpace
        val freeBytes = root.freeSpace
        return StorageStats(
            totalBytes = totalBytes,
            availableBytes = freeBytes
        )
    }

    suspend fun scanCategory(context: Context, category: FileCategory): List<FileItem> {
        return scanCategoryProgressive(context, category, null)
    }

    suspend fun scanCategoryProgressive(
        context: Context,
        category: FileCategory,
        onBatch: ((List<FileItem>) -> Unit)? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()
        when (category) {
            FileCategory.IMAGE -> results.addAll(scanMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FileCategory.IMAGE, onBatch))
            FileCategory.AUDIO -> results.addAll(scanMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, FileCategory.AUDIO, onBatch))
            FileCategory.VIDEO -> results.addAll(scanMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FileCategory.VIDEO, onBatch))
            FileCategory.DOCUMENT, FileCategory.APK, FileCategory.ARCHIVE, FileCategory.M3U8 -> {
                results.addAll(scanFileSystemForCategory(context, category, onBatch))
            }
        }
        val sorted = results.sortedByDescending { it.lastModified }
        onBatch?.invoke(sorted)
        sorted
    }

    private fun scanMediaStore(
        context: Context,
        uri: Uri,
        category: FileCategory,
        onBatch: ((List<FileItem>) -> Unit)? = null
    ): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                var count = 0
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val path = it.getString(dataCol) ?: continue
                    val physicalFile = File(path)
                    val name = it.getString(nameCol) ?: physicalFile.name
                    val fileSize = if (physicalFile.exists() && physicalFile.length() > 0L) physicalFile.length() else it.getLong(sizeCol)
                    val fileModified = if (physicalFile.exists()) physicalFile.lastModified() else 0L
                    val actualModified = if (fileModified > 0L) fileModified else (it.getLong(dateCol) * 1000L)
                    val mime = it.getString(mimeCol) ?: "*/*"
                    val contentUri = ContentUris.withAppendedId(uri, id)

                    list.add(
                        FileItem(
                            path = path,
                            name = name,
                            size = fileSize,
                            lastModified = actualModified,
                            mimeType = mime,
                            extension = physicalFile.extension.lowercase(Locale.ROOT),
                            category = category,
                            uri = contentUri
                        )
                    )
                    count++
                    if (onBatch != null && count % 30 == 0) {
                        onBatch(list.toList())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun scanFileSystemForCategory(
        context: Context,
        category: FileCategory,
        onBatch: ((List<FileItem>) -> Unit)? = null
    ): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val root = Environment.getExternalStorageDirectory()
        val targetExtensions = when (category) {
            FileCategory.APK -> setOf("apk", "apks", "xapk")
            FileCategory.ARCHIVE -> setOf("zip", "rar", "7z", "tar", "gz")
            FileCategory.DOCUMENT -> setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "md", "csv")
            FileCategory.M3U8 -> setOf("m3u8")
            else -> emptySet()
        }

        scanDirRecursively(root, targetExtensions, list, context, category, onBatch = onBatch)
        return list
    }

    private fun scanDirRecursively(
        dir: File,
        targetExtensions: Set<String>,
        results: MutableList<FileItem>,
        context: Context,
        category: FileCategory,
        depth: Int = 0,
        onBatch: ((List<FileItem>) -> Unit)? = null
    ) {
        if (depth > 6) return // 深度保护
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                // 跳过隐藏目录以及 Android 11+ 受限的系统应用私有目录
                val name = file.name
                val isRestrictedAndroidSubdir = Build.VERSION.SDK_INT >= 30 &&
                    dir.name.equals("Android", ignoreCase = true) &&
                    (name.equals("data", ignoreCase = true) || name.equals("obb", ignoreCase = true))
                if (name.startsWith(".") || isRestrictedAndroidSubdir) {
                    continue
                }
                scanDirRecursively(file, targetExtensions, results, context, category, depth + 1, onBatch)
            } else {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (targetExtensions.contains(ext)) {
                    var appName: String? = null
                    var pkg: String? = null
                    var ver: String? = null

                    if (category == FileCategory.APK) {
                        val apkInfo = ApkParser.parseApk(context, file)
                        appName = apkInfo?.appName
                        pkg = apkInfo?.packageName
                        ver = apkInfo?.versionName
                    }

                    results.add(
                        FileItem(
                            path = file.absolutePath,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            mimeType = FileUtils.getMimeType(file),
                            extension = ext,
                            category = category,
                            appName = appName,
                            packageName = pkg,
                            versionName = ver
                        )
                    )
                    if (onBatch != null && results.size % 25 == 0) {
                        onBatch(results.toList())
                    }
                }
            }
        }
    }
}
