package com.gemini.filemanager.core.m3u8

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.gemini.filemanager.data.model.M3U8Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

object M3U8Converter {

    interface ConvertListener {
        fun onProgress(item: M3U8Item, progress: Float, speed: String = "", outputSize: Long = 0L)
        fun onSuccess(item: M3U8Item, outputFile: File)
        fun onError(item: M3U8Item, error: String)
    }

    suspend fun convertM3U8ToMp4(
        context: Context,
        item: M3U8Item,
        customKeyHex: String? = null,
        listener: ConvertListener? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val parentDir = File(item.parentDir)
        val origFile = if (item.m3u8Path.isNotBlank()) File(item.m3u8Path) else null

        // 输出目录设定在系统 Movies/M3U8Converted
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "M3U8Converted"
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "video_${System.currentTimeMillis()}" }
        val outputFile = File(moviesDir, "$cleanTitle.mp4")

        listener?.onProgress(item, 0.05f, "准备中...")

        // 计算总时长基准
        var totalDurationMs = M3U8Parser.parseTotalDurationMs(origFile)
        if (totalDurationMs <= 0L) {
            totalDurationMs = if (item.segmentCount > 0) {
                item.segmentCount * 5000L
            } else {
                (item.totalSize / (1024 * 1024)).coerceAtLeast(10L) * 3500L
            }
        }

        // 注册实时进度回调监听
        FFmpegKitConfig.enableStatisticsCallback { stats ->
            if (stats == null) return@enableStatisticsCallback
            val time = stats.time.toDouble()
            val speed = stats.speed
            val size = stats.size

            if (totalDurationMs > 0 && time > 0) {
                val ratio = (time / totalDurationMs.toDouble()).toFloat().coerceIn(0.08f, 0.98f)
                val spdStr = if (speed > 0) String.format(Locale.ROOT, "%.1fx", speed) else ""
                listener?.onProgress(item, ratio, spdStr, size)
            }
        }

        // 超时保护：最多允许 3 分钟，超时强制取消
        val result = withTimeoutOrNull(180_000L) {
            var success = false

            // 模式 1：规范化本地 M3U8 + 极速 Direct Stream Copy
            try {
                val readyM3U8 = M3U8Parser.prepareLocalPlayableM3U8(parentDir, origFile, customKeyHex)
                listener?.onProgress(item, 0.15f, "启动合并引擎...")

                // 核心防死锁参数：-loglevel error 避免输出管道溢出卡死；-fflags 解决时间戳断裂
                val cmdCopy = "-y -loglevel error -allowed_extensions ALL -protocol_whitelist file,crypto,data,http,https -fflags +genpts+discardcorrupt -avoid_negative_ts make_zero -i \"${readyM3U8.absolutePath}\" -c copy -bsf:a aac_adtstoasc -movflags +faststart \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(cmdCopy)
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 1024) {
                    success = true
                } else {
                    // 若直接 copy 遇到非 AAC 音频，自动尝试安全转码 fallback
                    val cmdSafe = "-y -loglevel error -allowed_extensions ALL -protocol_whitelist file,crypto,data,http,https -fflags +genpts+discardcorrupt -avoid_negative_ts make_zero -i \"${readyM3U8.absolutePath}\" -c:v copy -c:a aac -b:a 128k -movflags +faststart \"${outputFile.absolutePath}\""
                    val sessionSafe = FFmpegKit.execute(cmdSafe)
                    if (ReturnCode.isSuccess(sessionSafe.returnCode) && outputFile.exists() && outputFile.length() > 1024) {
                        success = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 模式 2（终极兜底方案）：物理 TS 二进制流合并
            if (!success) {
                val concatSuccess = tryBinaryConcatAndRemux(parentDir, outputFile, item, listener)
                if (concatSuccess) {
                    success = true
                }
            }

            success
        }

        // 清理回调
        FFmpegKitConfig.enableStatisticsCallback(null)

        val isFinished = (result == true) && outputFile.exists() && outputFile.length() > 1024

        if (isFinished) {
            // 刷新系统媒体库
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (ignored: Exception) {}

            listener?.onProgress(item, 1.0f, "100%", outputFile.length())
            listener?.onSuccess(item, outputFile)
            true
        } else {
            listener?.onError(item, "合并失败：无法合成有效 MP4 视频")
            false
        }
    }

    /**
     * 终极兜底：直接流式拼合所有 TS 切片，带分步进度回调
     */
    private fun tryBinaryConcatAndRemux(
        parentDir: File,
        targetMp4: File,
        item: M3U8Item,
        listener: ConvertListener?
    ): Boolean {
        try {
            val tsFiles = parentDir.listFiles()?.filter {
                it.isFile && (it.extension.lowercase(Locale.ROOT) == "ts" || it.name.matches(Regex("^\\d+$")))
            }?.sortedWith { f1, f2 -> naturalCompare(f1.name, f2.name) } ?: return false

            if (tsFiles.isEmpty()) return false

            val mergedTs = File(parentDir, "temp_combined.ts")
            val totalCount = tsFiles.size
            FileOutputStream(mergedTs).use { outStream ->
                for ((index, ts) in tsFiles.withIndex()) {
                    FileInputStream(ts).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    if (index % 5 == 0 || index == totalCount - 1) {
                        val p = 0.2f + 0.65f * ((index + 1).toFloat() / totalCount)
                        listener?.onProgress(item, p, "分片 ${index + 1}/$totalCount")
                    }
                }
            }

            listener?.onProgress(item, 0.88f, "正在封装 MP4...")
            val cmd = "-y -loglevel error -fflags +genpts+discardcorrupt -i \"${mergedTs.absolutePath}\" -c copy -bsf:a aac_adtstoasc -movflags +faststart \"${targetMp4.absolutePath}\""
            val session = FFmpegKit.execute(cmd)

            try { mergedTs.delete() } catch (ignored: Exception) {}

            return ReturnCode.isSuccess(session.returnCode) && targetMp4.exists() && targetMp4.length() > 1024
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val p = java.util.regex.Pattern.compile("(\\d+)|(\\D+)")
        val m1 = p.matcher(s1)
        val m2 = p.matcher(s2)

        while (m1.find() && m2.find()) {
            val g1 = m1.group()
            val g2 = m2.group()

            val isDigit1 = Character.isDigit(g1[0])
            val isDigit2 = Character.isDigit(g2[0])

            if (isDigit1 && isDigit2) {
                val num1 = g1.toLongOrNull() ?: 0L
                val num2 = g2.toLongOrNull() ?: 0L
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return cmp
            } else {
                val cmp = g1.compareTo(g2)
                if (cmp != 0) return cmp
            }
        }
        return s1.length - s2.length
    }
}
