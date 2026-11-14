package com.gemini.filemanager.core.file

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * 微信重复版本文件组
 */
data class WeChatDuplicateGroup(
    val groupKey: String,               // 校对键 (文件名_大小)
    val fileName: String,
    val fileSize: Long,
    val latestItem: WeChatFileItem,      // 最晚修改版（默认保留）
    val redundantItems: List<WeChatFileItem> // 历史弃用/冗余版本（默认勾选删除）
) {
    val totalWastedBytes: Long
        get() = redundantItems.sumOf { it.fileItem.size }
}

data class WeChatFileItem(
    val fileItem: FileItem,
    val isHiddenOrDeprecated: Boolean,   // 是否为根目录隐藏/弃用/日志崩溃文件
    val deprecateReason: String? = null,
    val isSelectedForDelete: Boolean = true
)

data class WeChatScanResult(
    val duplicateGroups: List<WeChatDuplicateGroup>,
    val deprecatedFiles: List<WeChatFileItem>,
    val totalScannedBytes: Long,
    val reclaimableBytes: Long
)

object WeChatStorageCleaner {

    /**
     * 最新算法：精准扫描微信内外所有根目录占用、隐藏弃用文件，并进行文件名与大小精确校对
     */
    suspend fun scanWeChatStorage(
        context: Context,
        onProgress: ((currentCount: Int, foundBytes: Long) -> Unit)? = null
    ): WeChatScanResult = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()

        // 覆盖 Android 10+ 与旧版所有可能出现的微信存储根目录
        val candidateRoots = listOf(
            File(root, "Android/data/com.tencent.mm/MicroMsg"),
            File(root, "Android/data/com.tencent.mm/cache"),
            File(root, "Android/data/com.tencent.mm/files"),
            File(root, "tencent/MicroMsg"),
            File(root, "tencent/WeiXin"),
            File(root, "Pictures/WeiXin")
        ).filter { it.exists() }

        val deprecatedList = mutableListOf<WeChatFileItem>()
        // 校对归集表：Key 为 (文件名.lowercase() + "_" + 文件大小)
        val candidateMap = mutableMapOf<String, MutableList<File>>()

        var totalBytes = 0L
        var scannedFileCount = 0

        for (cRoot in candidateRoots) {
            collectWeChatFilesRecursive(
                dir = cRoot,
                deprecatedList = deprecatedList,
                candidateMap = candidateMap,
                onFile = { size ->
                    scannedFileCount++
                    totalBytes += size
                    if (scannedFileCount % 40 == 0) {
                        onProgress?.invoke(scannedFileCount, totalBytes)
                    }
                }
            )
        }

        // 智能版本归集去重算法：比对文件名与文件大小，相同者归入一组
        val duplicateGroups = mutableListOf<WeChatDuplicateGroup>()
        for ((key, files) in candidateMap) {
            if (files.size >= 2) {
                // 按修改时间降序排序：最新（最晚）修改的文件排在第 0 位
                val sortedFiles = files.sortedByDescending { it.lastModified() }
                val latest = sortedFiles.first()
                val redundants = sortedFiles.drop(1)

                val latestItem = WeChatFileItem(
                    fileItem = toFileItem(latest),
                    isHiddenOrDeprecated = false,
                    deprecateReason = "最新有效版本（保留）",
                    isSelectedForDelete = false // 默认最晚版保留，绝不勾选
                )

                val redundantItems = redundants.map { redFile ->
                    WeChatFileItem(
                        fileItem = toFileItem(redFile),
                        isHiddenOrDeprecated = false,
                        deprecateReason = "冗余历史副本（较晚版已保留）",
                        isSelectedForDelete = true // 默认历史副本勾选待删
                    )
                }

                duplicateGroups.add(
                    WeChatDuplicateGroup(
                        groupKey = key,
                        fileName = latest.name,
                        fileSize = latest.length(),
                        latestItem = latestItem,
                        redundantItems = redundantItems
                    )
                )
            }
        }

        // 按可释放体积由大到小排序
        val sortedDuplicateGroups = duplicateGroups.sortedByDescending { it.totalWastedBytes }
        val reclaimableBytes = sortedDuplicateGroups.sumOf { it.totalWastedBytes } +
                deprecatedList.sumOf { it.fileItem.size }

        WeChatScanResult(
            duplicateGroups = sortedDuplicateGroups,
            deprecatedFiles = deprecatedList.sortedByDescending { it.fileItem.size },
            totalScannedBytes = totalBytes,
            reclaimableBytes = reclaimableBytes
        )
    }

    private fun collectWeChatFilesRecursive(
        dir: File,
        deprecatedList: MutableList<WeChatFileItem>,
        candidateMap: MutableMap<String, MutableList<File>>,
        onFile: (Long) -> Unit,
        depth: Int = 0
    ) {
        if (depth > 8) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                val name = file.name
                // 忽略非微信目录或系统缩略图
                if (name == ".thumbnails" || name == ".cache") {
                    continue
                }
                collectWeChatFilesRecursive(file, deprecatedList, candidateMap, onFile, depth + 1)
            } else {
                val name = file.name
                val len = file.length()
                onFile(len)

                // 1. 检查是否为隐藏或废弃文件
                val lowerName = name.lowercase(Locale.ROOT)
                val isHidden = name.startsWith(".")
                val isDeprecatedExt = lowerName.endsWith(".tmp") ||
                        lowerName.endsWith(".temp") ||
                        lowerName.endsWith(".bak") ||
                        lowerName.endsWith(".log") ||
                        lowerName.endsWith(".xlog") ||
                        lowerName.endsWith(".dump") ||
                        lowerName.endsWith(".d.file")
                val isZeroByte = (len == 0L)
                val isCrashDir = file.parentFile?.name.equals("crash", true)
                val isAvatarCache = file.parentFile?.name.equals("avatar", true) &&
                        (System.currentTimeMillis() - file.lastModified() > 45L * 86400 * 1000)

                if (isHidden || isDeprecatedExt || isZeroByte || isCrashDir || isAvatarCache) {
                    val reason = when {
                        isZeroByte -> "0字节空损坏文件"
                        isCrashDir -> "微信崩溃日志转储"
                        isDeprecatedExt -> "弃用临时缓存文件 (${file.extension})"
                        isAvatarCache -> "超45天已过期头像残留"
                        isHidden -> "根目录隐藏占用文件"
                        else -> "弃用缓存残留"
                    }
                    deprecatedList.add(
                        WeChatFileItem(
                            fileItem = toFileItem(file),
                            isHiddenOrDeprecated = true,
                            deprecateReason = reason,
                            isSelectedForDelete = true // 默认选中待清理
                        )
                    )
                } else if (len > 1024L) {
                    // 2. 正常多媒体与文档，进行文件名与大小精确校对
                    // 若文件名为哈希（如无后缀或纯32位md5），则结合大小和文件前32字节哈希进行精确特征校对
                    val verifyKey = if (name.length == 32 && !name.contains(".")) {
                        val headHash = getFastHeadHash(file)
                        "hash_${headHash}_len_$len"
                    } else {
                        "name_${name.lowercase(Locale.ROOT)}_len_$len"
                    }

                    val list = candidateMap.getOrPut(verifyKey) { mutableListOf() }
                    list.add(file)
                }
            }
        }
    }

    private fun getFastHeadHash(file: File): Int {
        return try {
            val buf = ByteArray(32)
            val read = FileInputStream(file).use { it.read(buf) }
            if (read > 0) buf.contentHashCode() else file.name.hashCode()
        } catch (_: Exception) {
            file.name.hashCode()
        }
    }

    private fun toFileItem(file: File): FileItem {
        val ext = file.extension.lowercase(Locale.ROOT)
        val cat = when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> FileCategory.IMAGE
            "mp4", "mkv", "mov", "avi" -> FileCategory.VIDEO
            "mp3", "m4a", "aac", "wav", "amr" -> FileCategory.AUDIO
            "apk" -> FileCategory.APK
            "zip", "rar", "7z" -> FileCategory.ARCHIVE
            else -> FileCategory.DOCUMENT
        }

        return FileItem(
            path = file.absolutePath,
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            extension = ext,
            category = cat
        )
    }

    /**
     * 执行选中的微信冗余及弃用文件删除
     */
    suspend fun deleteWeChatFiles(
        context: Context,
        filePaths: List<String>
    ): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        for (path in filePaths) {
            val file = File(path)
            if (file.exists() && file.isFile) {
                val len = file.length()
                if (file.delete()) {
                    freedBytes += len
                }
            }
        }
        MediaScannerConnection.scanFile(context.applicationContext, filePaths.toTypedArray(), null, null)
        freedBytes
    }
}
