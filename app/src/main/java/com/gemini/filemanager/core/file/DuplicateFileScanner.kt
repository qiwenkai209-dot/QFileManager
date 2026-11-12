package com.gemini.filemanager.core.file

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.data.model.DuplicateFileItem
import com.gemini.filemanager.data.model.DuplicateFilterType
import com.gemini.filemanager.data.model.DuplicateGroup
import com.gemini.filemanager.data.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

object DuplicateFileScanner {

    interface ScanCallback {
        fun onProgress(stage: String, progress: Float, currentFile: String)
    }

    /**
     * 执行三阶段快速精准重复文件筛查
     */
    suspend fun scanDuplicates(
        context: Context,
        filterType: DuplicateFilterType = DuplicateFilterType.ALL,
        callback: ScanCallback? = null
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        AppBackgroundKeepAliveManager.acquireLease(appCtx, "duplicate_scan", "重复文件深度筛查")
        try {
            // 阶段 1：收集所有候选物理文件
            callback?.onProgress("正在扫描手机存储文件...", 0.05f, "")
            val rawFileList = collectFiles(appCtx, filterType, callback)
            if (!coroutineContext.isActive) return@withContext emptyList()

            if (rawFileList.isEmpty()) {
                callback?.onProgress("扫描完成，未发现候选文件", 1f, "")
                return@withContext emptyList()
            }

            // 阶段 2：按文件大小分组 (过滤掉单文件大小)
            callback?.onProgress("正在比对文件大小...", 0.35f, "")
            val sizeGroupMap = rawFileList
                .filter { it.size > 0L && it.file.exists() && it.file.canRead() }
                .groupBy { it.size }
                .filter { it.value.size >= 2 }

            val sizeCandidateFiles = sizeGroupMap.values.flatten()
            if (sizeCandidateFiles.isEmpty() || !coroutineContext.isActive) {
                callback?.onProgress("扫描完成，未发现重复文件", 1f, "")
                return@withContext emptyList()
            }

            // 阶段 3：前缀快速指纹过滤 (读取前 8KB 计算快照 Hash)
            callback?.onProgress("正在进行文件头部快照校验...", 0.55f, "")
            val partialHashMap = mutableMapOf<String, MutableList<DuplicateFileItem>>()
            val totalCandidateCount = sizeCandidateFiles.size
            var processedCount = 0

            for (item in sizeCandidateFiles) {
                if (!coroutineContext.isActive) return@withContext emptyList()
                processedCount++
                if (processedCount % 10 == 0 || processedCount == totalCandidateCount) {
                    val p = 0.55f + 0.20f * (processedCount.toFloat() / totalCandidateCount)
                    callback?.onProgress("正在快速校验文件头部指纹 (${processedCount}/$totalCandidateCount)...", p, item.name)
                }

                val partialKey = "${item.size}_${computePartialHash(item.file)}"
                val list = partialHashMap.getOrPut(partialKey) { mutableListOf() }
                list.add(item)
            }

            // 仅保留头部哈希相同且数量 >= 2 的分组
            val confirmedCandidates = partialHashMap.values.filter { it.size >= 2 }.flatten()
            if (confirmedCandidates.isEmpty() || !coroutineContext.isActive) {
                callback?.onProgress("扫描完成，未发现重复文件", 1f, "")
                return@withContext emptyList()
            }

            // 阶段 4：全量 MD5 确认与分组
        callback?.onProgress("正在进行全量 MD5 深度比对...", 0.78f, "")
        val fullHashMap = mutableMapOf<String, MutableList<DuplicateFileItem>>()
        val fullCheckCount = confirmedCandidates.size
        var fullProcessed = 0

        for (item in confirmedCandidates) {
            if (!coroutineContext.isActive) return@withContext emptyList()
            fullProcessed++
            if (fullProcessed % 5 == 0 || fullProcessed == fullCheckCount) {
                val p = 0.78f + 0.20f * (fullProcessed.toFloat() / fullCheckCount)
                callback?.onProgress("正在比对完整数据指纹 (${fullProcessed}/$fullCheckCount)...", p, item.name)
            }

            val fullHash = computeFullMD5(item.file)
            if (fullHash.isNotEmpty()) {
                val list = fullHashMap.getOrPut(fullHash) { mutableListOf() }
                list.add(item)
            }
        }

        // 阶段 5：生成重复组结果并标记原文件与副本
        val resultGroups = mutableListOf<DuplicateGroup>()
        for ((hash, items) in fullHashMap) {
            if (items.size >= 2) {
                // 按修改时间升序排列：最早生成/修改的文件作为“原文件”，其余作为“副本”
                val sortedItems = items.sortedBy { it.lastModified }
                val finalizedItems = sortedItems.mapIndexed { index, fileItem ->
                    val isFirst = (index == 0)
                    fileItem.copy(
                        isOriginal = isFirst,
                        isSelected = !isFirst // 默认自动勾选副本，原件不选
                    )
                }

                val repItem = finalizedItems.first()
                resultGroups.add(
                    DuplicateGroup(
                        hash = hash,
                        fileSize = repItem.size,
                        category = repItem.category,
                        files = finalizedItems
                    )
                )
            }
        }

        // 按该组可释放的空间从大到小排序（大文件优先清理）
        resultGroups.sortByDescending { it.potentialReclaimBytes }

        callback?.onProgress("扫描完成，发现 ${resultGroups.size} 组重复文件", 1f, "")
        resultGroups
        } finally {
            AppBackgroundKeepAliveManager.releaseLease(appCtx, "duplicate_scan")
        }
    }

    /**
     * 收集手机中的文件
     */
    private fun collectFiles(
        context: Context,
        filterType: DuplicateFilterType,
        callback: ScanCallback?
    ): List<DuplicateFileItem> {
        val list = mutableListOf<DuplicateFileItem>()
        val seenPaths = HashSet<String>()

        // 1. 利用 MediaStore 高速收集媒体文件
        if (filterType == DuplicateFilterType.ALL ||
            filterType == DuplicateFilterType.IMAGE ||
            filterType == DuplicateFilterType.VIDEO ||
            filterType == DuplicateFilterType.AUDIO
        ) {
            val mediaUris = when (filterType) {
                DuplicateFilterType.IMAGE -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to FileCategory.IMAGE)
                DuplicateFilterType.VIDEO -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to FileCategory.VIDEO)
                DuplicateFilterType.AUDIO -> listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to FileCategory.AUDIO)
                else -> listOf(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI to FileCategory.IMAGE,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to FileCategory.VIDEO,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to FileCategory.AUDIO
                )
            }

            for ((uri, cat) in mediaUris) {
                queryMediaStore(context, uri, cat, list, seenPaths)
            }
        }

        // 2. 遍历外部存储重点与通用文件夹 (Download, Documents, DCIM, Pictures, Movies, Music 等)
        val root = Environment.getExternalStorageDirectory()
        val targetDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            File(root, "Tencent"),
            File(root, "Download"),
            File(root, "Documents")
        ).filter { it.exists() && it.isDirectory }

        for (dir in targetDirs) {
            scanDirectory(dir, filterType, list, seenPaths)
        }

        return list
    }

    private fun queryMediaStore(
        context: Context,
        uri: android.net.Uri,
        category: FileCategory,
        outList: MutableList<DuplicateFileItem>,
        seenPaths: HashSet<String>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: continue
                    if (seenPaths.contains(path)) continue
                    val physicalFile = File(path)
                    if (!physicalFile.exists() || physicalFile.length() <= 0L) continue

                    val name = it.getString(nameCol) ?: physicalFile.name
                    val size = physicalFile.length()
                    val actualModified = if (physicalFile.lastModified() > 0L) physicalFile.lastModified() else (it.getLong(dateCol) * 1000L)

                    seenPaths.add(path)
                    outList.add(
                        DuplicateFileItem(
                            path = path,
                            name = name,
                            size = size,
                            lastModified = actualModified,
                            category = category
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scanDirectory(
        dir: File,
        filterType: DuplicateFilterType,
        outList: MutableList<DuplicateFileItem>,
        seenPaths: HashSet<String>,
        currentDepth: Int = 0
    ) {
        // 防止递归过深，限制在 12 层以内
        if (currentDepth > 12) return

        val files = dir.listFiles() ?: return
        for (f in files) {
            // 跳过隐藏文件、系统私有文件
            if (f.name.startsWith(".")) continue
            if (f.name.equals("Android", ignoreCase = true) && currentDepth == 0) continue

            if (f.isDirectory) {
                scanDirectory(f, filterType, outList, seenPaths, currentDepth + 1)
            } else if (f.isFile && f.length() > 0L) {
                val path = f.absolutePath
                if (seenPaths.contains(path)) continue

                val ext = f.extension.lowercase(Locale.ROOT)
                val category = determineCategory(ext)

                // 根据过滤条件判断是否收集
                if (matchesFilter(category, filterType)) {
                    seenPaths.add(path)
                    outList.add(
                        DuplicateFileItem(
                            path = path,
                            name = f.name,
                            size = f.length(),
                            lastModified = f.lastModified(),
                            category = category
                        )
                    )
                }
            }
        }
    }

    private fun determineCategory(ext: String): FileCategory {
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> FileCategory.IMAGE
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "3gp", "webm", "ts" -> FileCategory.VIDEO
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "ape" -> FileCategory.AUDIO
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "epub", "md", "csv" -> FileCategory.DOCUMENT
            "apk", "apks", "xapk" -> FileCategory.APK
            "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVE
            "m3u8" -> FileCategory.M3U8
            else -> FileCategory.DOCUMENT
        }
    }

    private fun matchesFilter(category: FileCategory, filterType: DuplicateFilterType): Boolean {
        return when (filterType) {
            DuplicateFilterType.ALL -> true
            DuplicateFilterType.IMAGE -> category == FileCategory.IMAGE
            DuplicateFilterType.VIDEO -> category == FileCategory.VIDEO
            DuplicateFilterType.AUDIO -> category == FileCategory.AUDIO
            DuplicateFilterType.DOCUMENT -> category == FileCategory.DOCUMENT
            DuplicateFilterType.PACKAGE -> category == FileCategory.APK || category == FileCategory.ARCHIVE
        }
    }

    /**
     * 计算文件头部前 8KB 的快速 Hash
     */
    private fun computePartialHash(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                val read = fis.read(buffer)
                if (read > 0) {
                    md.update(buffer, 0, read)
                }
            }
            bytesToHex(md.digest())
        } catch (e: Exception) {
            file.name + "_" + file.length()
        }
    }

    /**
     * 流式计算文件完整 MD5，安全高效不发生 OOM
     */
    private fun computeFullMD5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(65536) // 64KB 缓冲区
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            bytesToHex(md.digest())
        } catch (e: Exception) {
            ""
        }
    }

    private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    private fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}
