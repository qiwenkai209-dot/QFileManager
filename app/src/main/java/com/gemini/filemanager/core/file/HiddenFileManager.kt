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

data class HiddenFileItem(
    val fileItem: FileItem,
    val isHiddenByDot: Boolean,       // 文件名以 . 开头
    val isInHiddenFolder: Boolean,    // 位于以 . 开头的隐藏目录中
    val isInNoMediaFolder: Boolean,   // 位于包含 .nomedia 的隐藏目录中
    val isMiuiSecretAlbum: Boolean,   // 小米私密相册/私密文件
    val realExtension: String         // 魔数探测出的真实扩展名
)

object HiddenFileManager {

    /**
     * 针对小米等手机常见隐藏目录及全盘深度嗅探隐藏文件
     */
    suspend fun scanHiddenFiles(
        context: Context,
        onBatch: ((List<HiddenFileItem>) -> Unit)? = null
    ): List<HiddenFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<HiddenFileItem>()
        val visitedPaths = HashSet<String>()
        val root = Environment.getExternalStorageDirectory()

        // 1. 重点高频私密/隐藏路径优先扫描（小米 MIUI/HyperOS 私密相册、私密文件夹、微信隐藏媒体等）
        val priorityDirs = listOf(
            File(root, "MIUI/.secretAlbum"),
            File(root, "MIUI/secretAlbum"),
            File(root, "MIUI/.privacy"),
            File(root, "DCIM/.globalTrash"),
            File(root, "DCIM/.trash"),
            File(root, "Pictures/.gallery"),
            File(root, "tencent/MicroMsg/.nomedia_media"),
            File(root, ".hidden"),
            File(root, ".secret")
        ).filter { it.exists() }

        val priorityDirCanonicalPaths = priorityDirs.mapNotNull {
            try { it.canonicalPath.lowercase(Locale.ROOT) } catch (_: Throwable) { it.absolutePath.lowercase(Locale.ROOT) }
        }.toSet()

        for (pDir in priorityDirs) {
            scanDirectoryInternal(
                dir = pDir,
                isInHiddenTree = true,
                hasNoMediaParent = true,
                isMiuiSpecial = true,
                priorityDirCanonicalPaths = emptySet(),
                visitedPaths = visitedPaths,
                results = results,
                onBatch = onBatch
            )
        }

        // 2. 全盘递归扫描隐藏文件（以 . 开头的文件、文件夹以及包含 .nomedia 的隐藏媒体），跳过前面已扫的高优先级目录避免重复
        scanDirectoryInternal(
            dir = root,
            isInHiddenTree = false,
            hasNoMediaParent = false,
            isMiuiSpecial = false,
            priorityDirCanonicalPaths = priorityDirCanonicalPaths,
            visitedPaths = visitedPaths,
            results = results,
            onBatch = onBatch,
            depth = 0
        )

        val finalSorted = results.distinctBy { it.fileItem.path }.sortedByDescending { it.fileItem.lastModified }
        onBatch?.invoke(finalSorted)
        finalSorted
    }

    private fun scanDirectoryInternal(
        dir: File,
        isInHiddenTree: Boolean,
        hasNoMediaParent: Boolean,
        isMiuiSpecial: Boolean,
        priorityDirCanonicalPaths: Set<String>,
        visitedPaths: MutableSet<String>,
        results: MutableList<HiddenFileItem>,
        onBatch: ((List<HiddenFileItem>) -> Unit)?,
        depth: Int = 0
    ) {
        if (depth > 7) return

        val dirCanonical = try { dir.canonicalPath.lowercase(Locale.ROOT) } catch (_: Throwable) { dir.absolutePath.lowercase(Locale.ROOT) }
        // 若当前目录在第 1 阶段已作为高优先级目录全量扫描过，则跳过防止重复
        if (priorityDirCanonicalPaths.isNotEmpty() && priorityDirCanonicalPaths.contains(dirCanonical)) {
            return
        }

        val files = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return

        // 检查当前目录下是否有 .nomedia 文件
        val hasLocalNoMedia = hasNoMediaParent || files.any { it.name.equals(".nomedia", ignoreCase = true) }

        for (file in files) {
            val name = file.name
            // 忽略系统受限目录或无用系统日志
            if (name == ".thumbnails" || name == ".cache" || name.equals(".nomedia", true)) {
                continue
            }

            if (file.isDirectory) {
                val isHiddenDir = isInHiddenTree || name.startsWith(".") || isMiuiSpecial
                val isMiuiSub = isMiuiSpecial || name.contains("secretAlbum", ignoreCase = true)
                scanDirectoryInternal(
                    dir = file,
                    isInHiddenTree = isHiddenDir,
                    hasNoMediaParent = hasLocalNoMedia,
                    isMiuiSpecial = isMiuiSub,
                    priorityDirCanonicalPaths = priorityDirCanonicalPaths,
                    visitedPaths = visitedPaths,
                    results = results,
                    onBatch = onBatch,
                    depth = depth + 1
                )
            } else {
                val isDotFile = name.startsWith(".")
                val shouldInclude = isDotFile || isInHiddenTree || hasLocalNoMedia || isMiuiSpecial

                if (shouldInclude && file.length() > 0L) {
                    val filePathKey = try { file.canonicalPath.lowercase(Locale.ROOT) } catch (_: Throwable) { file.absolutePath.lowercase(Locale.ROOT) }
                    if (!visitedPaths.add(filePathKey)) {
                        // 已经访问过该文件（硬链接或重复遍历），跳过
                        continue
                    }

                    val realExt = detectRealExtension(file)
                    val cat = guessCategoryByExtension(realExt)

                    val item = HiddenFileItem(
                        fileItem = FileItem(
                            path = file.absolutePath,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            extension = realExt,
                            category = cat
                        ),
                        isHiddenByDot = isDotFile,
                        isInHiddenFolder = isInHiddenTree,
                        isInNoMediaFolder = hasLocalNoMedia,
                        isMiuiSecretAlbum = isMiuiSpecial || dir.path.contains("secretAlbum", ignoreCase = true),
                        realExtension = realExt
                    )

                    results.add(item)
                    if (onBatch != null && results.size % 25 == 0) {
                        onBatch(results.toList())
                    }
                }
            }
        }
    }

    /**
     * 读取文件头部魔数（Magic Bytes），精准识别真实文件类型
     */
    fun detectRealExtension(file: File): String {
        val originalExt = file.extension.lowercase(Locale.ROOT)
        try {
            if (!file.exists() || !file.isFile || file.length() < 3L) {
                return if (originalExt.isNotBlank()) originalExt else "bin"
            }
            val header = ByteArray(16)
            val bytesRead = FileInputStream(file).use { it.read(header) }
            if (bytesRead >= 3) {
                // JPEG: FF D8 FF
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                    return "jpg"
                }
                // PNG: 89 50 4E 47
                if (bytesRead >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                    return "png"
                }
                // GIF: 47 49 46 38 (GIF8)
                if (bytesRead >= 4 && header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x38.toByte()) {
                    return "gif"
                }
                // MP4 / MOV: ....ftyp (4~8 字节为 66 74 79 70)
                if (bytesRead >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                    header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
                    return "mp4"
                }
                // WEBP: RIFF....WEBP
                if (bytesRead >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() && header[11] == 0x50.toByte()) {
                    return "webp"
                }
                // MP3: ID3
                if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                    return "mp3"
                }
                // PDF: %PDF (25 50 44 46)
                if (bytesRead >= 4 && header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
                    return "pdf"
                }
                // ZIP / APK: PK..
                if (bytesRead >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
                    return if (originalExt == "apk") "apk" else "zip"
                }
            }
        } catch (_: Exception) {}

        return if (originalExt.isNotBlank()) originalExt else "bin"
    }

    private fun guessCategoryByExtension(ext: String): FileCategory {
        return when (ext.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> FileCategory.IMAGE
            "mp4", "mkv", "avi", "mov", "flv", "3gp", "webm" -> FileCategory.VIDEO
            "mp3", "m4a", "flac", "wav", "aac", "ogg" -> FileCategory.AUDIO
            "apk" -> FileCategory.APK
            "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVE
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt" -> FileCategory.DOCUMENT
            else -> FileCategory.DOCUMENT
        }
    }

    /**
     * 将隐藏文件恢复为常规公开文件，并自动通知系统相册和媒体库
     */
    suspend fun restoreFileToNormal(
        context: Context,
        item: HiddenFileItem
    ): File? = withContext(Dispatchers.IO) {
        val srcFile = File(item.fileItem.path)
        if (!srcFile.exists()) return@withContext null

        val root = Environment.getExternalStorageDirectory()

        // 1. 确定恢复后的目标目录（图片恢复到 DCIM/Restored，视频恢复到 Movies/Restored，其它恢复到 Download/Restored）
        val targetDir = when (item.fileItem.category) {
            FileCategory.IMAGE -> File(root, "DCIM/Restored")
            FileCategory.VIDEO -> File(root, "Movies/Restored")
            FileCategory.AUDIO -> File(root, "Music/Restored")
            FileCategory.DOCUMENT -> File(root, "Documents/Restored")
            else -> File(root, "Download/Restored")
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        // 2. 清理文件名中的点前缀（.）
        var cleanName = srcFile.name.trimStart('.')
        if (cleanName.isBlank()) {
            cleanName = "restored_${System.currentTimeMillis()}"
        }

        // 3. 补全真实扩展名
        val realExt = item.realExtension
        if (!cleanName.contains(".") && realExt.isNotBlank() && realExt != "bin") {
            cleanName = "$cleanName.$realExt"
        } else if (realExt.isNotBlank() && realExt != "bin" && !cleanName.endsWith(".$realExt", ignoreCase = true)) {
            // 若原后缀是 .d.file 等隐藏混淆后缀，修正为真实后缀
            val baseWithoutExt = cleanName.substringBeforeLast(".")
            cleanName = "$baseWithoutExt.$realExt"
        }

        var destFile = File(targetDir, cleanName)
        var counter = 1
        val baseName = destFile.nameWithoutExtension
        val ext = destFile.extension
        while (destFile.exists()) {
            val suffix = if (ext.isNotBlank()) ".$ext" else ""
            destFile = File(targetDir, "${baseName}_$counter$suffix")
            counter++
        }

        // 4. 执行文件移动转移
        val moved = srcFile.renameTo(destFile)
        val finalFile = if (moved) {
            destFile
        } else {
            // 回退到字节复制并删除源文件
            try {
                srcFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                srcFile.delete()
                destFile
            } catch (_: Exception) {
                null
            }
        }

        // 5. 调用系统 MediaScannerConnection 刷新系统相册与媒体库
        finalFile?.let { f ->
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(f.absolutePath),
                null,
                null
            )
        }

        finalFile
    }
}
