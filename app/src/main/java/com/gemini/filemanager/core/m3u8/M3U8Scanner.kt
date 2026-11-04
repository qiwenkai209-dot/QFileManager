package com.gemini.filemanager.core.m3u8

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.gemini.filemanager.data.model.M3U8Item
import com.gemini.filemanager.data.model.M3U8Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.UUID

object M3U8Scanner {

    private const val TAG = "M3U8Scanner"

    /**
     * 高速轻量扫描 M3U8 任务（内置严格去重机制，杜绝重复项）
     * 每扫描到一批就通过 onBatchFound 回调流式通知 UI，同时返回全部结果
     */
    suspend fun scanLocalM3U8Tasks(
        context: Context? = null,
        onBatchFound: ((List<M3U8Item>) -> Unit)? = null
    ): List<M3U8Item> = withContext(Dispatchers.IO) {
        // 使用物理目录与输出路径双重规范化去重
        val candidateItems = mutableListOf<M3U8Item>()

        fun addItem(item: M3U8Item) {
            candidateItems.add(item)
            if (candidateItems.size % 6 == 0) {
                val dedupedBatch = deduplicateItems(candidateItems)
                onBatchFound?.invoke(dedupedBatch)
            }
        }

        val sdcard = Environment.getExternalStorageDirectory()

        // 1. 核心重点靶向：QQ 浏览器目录 (/sdcard/Download/QQBrowser/ 或 /sdcard/QQBrowser/)
        scanQQBrowserDirs(sdcard, ::addItem)
        yield()

        // 2. 核心重点靶向：夸克、UC浏览器、系统 Movies、Download
        scanOtherKnownDirs(sdcard, ::addItem)
        yield()

        // 3. MediaStore 补充查询
        if (context != null) {
            scanMediaStoreFast(context, ::addItem)
        }

        // 最终严格深度去重与排序
        val finalDeduped = deduplicateItems(candidateItems)

        val sorted = finalDeduped.sortedWith(
            compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                .thenByDescending { it.lastModified }
        )
        onBatchFound?.invoke(sorted)
        sorted
    }

    /**
     * 多维度深度去重：
     * 1. 统一解析并使用 canonicalPath（消除 /sdcard 与 /storage/emulated/0 的符号链接重复）
     * 2. 同一个切片父目录下如果扫描到了多个 .m3u8，仅保留最新修改、分片数量最多的代表项
     * 3. 同一个已输出的 MP4 文件绝对只保留一个条目
     */
    private fun deduplicateItems(items: List<M3U8Item>): List<M3U8Item> {
        val uniqueMap = LinkedHashMap<String, M3U8Item>()

        for (item in items) {
            // 计算唯一键标识
            val canonicalDir = try {
                File(item.parentDir).canonicalPath.lowercase()
            } catch (e: Exception) {
                item.parentDir.lowercase()
            }

            val canonicalMp4 = if (item.outputPath != null && File(item.outputPath).exists()) {
                try {
                    File(item.outputPath).canonicalPath.lowercase()
                } catch (e: Exception) {
                    item.outputPath.lowercase()
                }
            } else {
                null
            }

            // 去重主键：已完成时以 MP4 物理文件为准，未完成时以切片所在父目录为准
            val dedupKey = canonicalMp4 ?: "dir_$canonicalDir"

            val existing = uniqueMap[dedupKey]
            if (existing == null) {
                uniqueMap[dedupKey] = item
            } else {
                // 如果已有相同视频，选出更佳的一个（优先保留已完成的、切片数更多的、或更新的）
                if (existing.status != M3U8Status.COMPLETED && item.status == M3U8Status.COMPLETED) {
                    uniqueMap[dedupKey] = item
                } else if (item.segmentCount > existing.segmentCount) {
                    uniqueMap[dedupKey] = item
                } else if (item.lastModified > existing.lastModified && item.segmentCount == existing.segmentCount) {
                    uniqueMap[dedupKey] = item
                }
            }
        }

        return uniqueMap.values.toList()
    }

    /**
     * 极速扫描 QQ 浏览器的各种存放结构：
     * /sdcard/Download/QQBrowser/<userId>/视频/
     * /sdcard/Download/QQBrowser/<userId>/
     * /sdcard/QQBrowser/视频/
     */
    private suspend fun scanQQBrowserDirs(sdcard: File, onFound: (M3U8Item) -> Unit) {
        val qqBases = listOf(
            File(sdcard, "Download/QQBrowser"),
            File(sdcard, "QQBrowser"),
            File(sdcard, "tencent/QQBrowser")
        )

        for (base in qqBases) {
            if (!base.exists() || !base.isDirectory) continue

            // 检查 base 下的一级或二级目录
            val children = base.listFiles() ?: continue
            for (child in children) {
                yield()
                if (child.isDirectory) {
                    // 如 1712623647998/
                    val videoDir = File(child, "视频")
                    if (videoDir.exists() && videoDir.isDirectory) {
                        scanM3U8InVideoDir(videoDir, onFound)
                    }
                    val videoDirEn = File(child, "video")
                    if (videoDirEn.exists() && videoDirEn.isDirectory) {
                        scanM3U8InVideoDir(videoDirEn, onFound)
                    }
                    // child 自身也是个目录
                    scanM3U8InVideoDir(child, onFound)
                } else if (child.name.endsWith(".m3u8", ignoreCase = true)) {
                    val item = parseM3U8Fast(child, base)
                    if (item != null) onFound(item)
                }
            }
        }
    }

    /**
     * 扫描包含视频的文件夹（如 QQBrowser/<userId>/视频/）
     * 该目录下直接平铺着很多 .m3u8 文件和已转码好的 .mp4 文件
     */
    private suspend fun scanM3U8InVideoDir(videoDir: File, onFound: (M3U8Item) -> Unit) {
        val files = videoDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.endsWith(".m3u8", ignoreCase = true)) {
                val item = parseM3U8Fast(file, videoDir)
                if (item != null) {
                    onFound(item)
                }
            }
        }
    }

    /**
     * 扫描 Quark、UC 等其他常见目录
     */
    private suspend fun scanOtherKnownDirs(sdcard: File, onFound: (M3U8Item) -> Unit) {
        val targetDirs = listOf(
            File(sdcard, "Quark/Download"),
            File(sdcard, "QuarkDownloads"),
            File(sdcard, "UCDownloads"),
            File(sdcard, "Movies")
        )

        for (dir in targetDirs) {
            yield()
            if (!dir.exists() || !dir.isDirectory) continue
            val list = dir.listFiles() ?: continue
            for (sub in list) {
                if (sub.isFile && sub.name.endsWith(".m3u8", ignoreCase = true)) {
                    val item = parseM3U8Fast(sub, dir)
                    if (item != null) onFound(item)
                } else if (sub.isDirectory && !sub.name.startsWith(".")) {
                    val subFiles = sub.listFiles() ?: continue
                    for (f in subFiles) {
                        if (f.isFile && f.name.endsWith(".m3u8", ignoreCase = true)) {
                            val item = parseM3U8Fast(f, sub)
                            if (item != null) onFound(item)
                        }
                    }
                }
            }
        }
    }

    /**
     * 极速解析一个 .m3u8 描述文件：
     * 1. 快速读取前 20 行，提取切片目录与是否加密
     * 2. 检查是否有同名已转码的 .mp4
     * 3. 统计对应切片目录的切片数量与真实大小
     */
    private fun parseM3U8Fast(m3u8File: File, parentDir: File): M3U8Item? {
        try {
            val title = m3u8File.nameWithoutExtension
            var isEncrypted = false
            var segmentDir: File? = null

            // 检查是否已经生成过同名 MP4（检查本目录或公共合成目录 Movies/M3U8Converted/）
            val possibleMp4 = File(parentDir, "$title.mp4")
            val standardConvertedMp4 = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "M3U8Converted/$title.mp4")
            val finalMp4 = when {
                possibleMp4.exists() && possibleMp4.length() > 1024 * 1024 -> possibleMp4
                standardConvertedMp4.exists() && standardConvertedMp4.length() > 1024 * 1024 -> standardConvertedMp4
                else -> null
            }
            val isAlreadyMp4 = finalMp4 != null

            // 快速读取前 20 行提取切片信息
            m3u8File.bufferedReader().useLines { lines ->
                var count = 0
                for (line in lines) {
                    if (count++ > 25) break
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-KEY")) {
                        isEncrypted = true
                    } else if (trimmed.startsWith("file://") || trimmed.startsWith("/")) {
                        val path = if (trimmed.startsWith("file://")) trimmed.removePrefix("file://") else trimmed
                        val sliceFile = File(path)
                        if (sliceFile.parentFile != null && sliceFile.parentFile!!.exists()) {
                            segmentDir = sliceFile.parentFile
                            break
                        }
                    } else if (trimmed.endsWith(".ts", ignoreCase = true) || trimmed.endsWith(".qsv", ignoreCase = true)) {
                        // 相对路径
                        segmentDir = parentDir
                        break
                    }
                }
            }

            // 计算切片数量与总大小
            var segCount = 0
            var totalSize = 0L

            val targetDir = segmentDir ?: parentDir
            if (targetDir.exists() && targetDir.isDirectory) {
                // 使用 fast String list，不为每个文件创建 File 对象，避免 GC 爆炸
                val fileNames = targetDir.list() ?: emptyArray()
                for (name in fileNames) {
                    if (name.endsWith(".ts", ignoreCase = true) ||
                        name.endsWith(".qsv", ignoreCase = true) ||
                        name.all { it.isDigit() }
                    ) {
                        segCount++
                    }
                }
                // 估算或快速统计切片大小：抽样或快速累加
                if (segCount > 0) {
                    if (targetDir != parentDir) {
                        // 针对隐藏分片目录（如 .hash/），里面全是切片，直接累加切片文件大小
                        val slices = targetDir.listFiles() ?: emptyArray()
                        totalSize = slices.sumOf { it.length() }
                    } else {
                        // 自身目录，只累加匹配分片
                        val slices = targetDir.listFiles { _, name ->
                            name.endsWith(".ts", ignoreCase = true) || name.all { it.isDigit() }
                        } ?: emptyArray()
                        totalSize = slices.sumOf { it.length() }
                    }
                }
            }

            if (totalSize == 0L) {
                totalSize = if (isAlreadyMp4 && finalMp4 != null) finalMp4.length() else m3u8File.length()
            }

            val fileModified = if (m3u8File.exists() && m3u8File.lastModified() > 0L) {
                m3u8File.lastModified()
            } else {
                segmentDir?.lastModified() ?: parentDir.lastModified()
            }

            return M3U8Item(
                id = m3u8File.absolutePath.hashCode().toString(),
                title = title,
                m3u8Path = m3u8File.absolutePath,
                parentDir = (segmentDir ?: parentDir).absolutePath,
                segmentCount = segCount,
                totalSize = totalSize,
                isEncrypted = isEncrypted,
                status = if (isAlreadyMp4) M3U8Status.COMPLETED else M3U8Status.PENDING,
                outputPath = finalMp4?.absolutePath,
                progress = if (isAlreadyMp4) 1.0f else 0.0f,
                lastModified = fileModified
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing ${m3u8File.name}: ${e.message}")
            return null
        }
    }

    private fun scanMediaStoreFast(context: Context, onFound: (M3U8Item) -> Unit) {
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
            val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.m3u8'"
            val cursor = context.contentResolver.query(uri, projection, selection, null, null)
            cursor?.use {
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        val parent = file.parentFile ?: continue
                        val item = parseM3U8Fast(file, parent)
                        if (item != null) onFound(item)
                    }
                }
            }
        } catch (ignored: Exception) {}
    }
}
