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
     * 高速轻量扫描 M3U8 任务
     * 每扫描到一批就通过 onBatchFound 回调流式通知 UI，同时返回全部结果
     */
    suspend fun scanLocalM3U8Tasks(
        context: Context? = null,
        onBatchFound: ((List<M3U8Item>) -> Unit)? = null
    ): List<M3U8Item> = withContext(Dispatchers.IO) {
        val results = mutableListOf<M3U8Item>()
        val seenPaths = HashSet<String>()

        fun addItem(item: M3U8Item) {
            val key = if (item.m3u8Path.isNotBlank()) item.m3u8Path else item.parentDir
            if (seenPaths.add(key)) {
                results.add(item)
                if (results.size % 5 == 0) {
                    onBatchFound?.invoke(results.toList())
                }
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

        // 最终排序（将已有 MP4 完成的和按时间/大小排在前面）
        val sorted = results.sortedWith(
            compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                .thenByDescending { it.totalSize }
        )
        onBatchFound?.invoke(sorted)
        sorted
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

            // 检查是否已经生成过同名 MP4
            val possibleMp4 = File(parentDir, "$title.mp4")
            val isAlreadyMp4 = possibleMp4.exists() && possibleMp4.length() > 1024 * 1024

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
                totalSize = if (isAlreadyMp4) possibleMp4.length() else m3u8File.length()
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
                outputPath = if (isAlreadyMp4) possibleMp4.absolutePath else null,
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
