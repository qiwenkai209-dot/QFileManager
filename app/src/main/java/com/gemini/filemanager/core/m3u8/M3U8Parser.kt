package com.gemini.filemanager.core.m3u8

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object M3U8Parser {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 规范化并准备一个可被 FFmpeg 或 ExoPlayer 本地直接读取、解密与合并的 m3u8 文件
     * 毫秒级生成，零重复 IO！
     */
    suspend fun prepareLocalPlayableM3U8(
        parentDir: File,
        originalM3U8: File?,
        customKeyHex: String? = null
    ): File = withContext(Dispatchers.IO) {
        val targetM3u8 = File(parentDir, "app_generated_merged.m3u8")

        // 1. 处理/寻找或自动静默拉取 AES-128 密钥 Key 文件
        var keyFile = resolveKeyFile(parentDir, customKeyHex)

        if (originalM3U8 != null && originalM3U8.exists()) {
            val lines = originalM3U8.readLines()
            val newLines = mutableListOf<String>()
            var currentMediaSequence = 0L
            var hasGlobalKey = false
            var currentKeyTag: String? = null

            // 预处理：如果本地未找到 key，但清单中有 Key 链接或 Base64 内嵌，自动提取
            if (keyFile == null) {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-KEY")) {
                        keyFile = autoExtractKeyFromTag(parentDir, trimmed)
                        if (keyFile != null) break
                    }
                }
            }

            for (line in lines) {
                var trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    currentMediaSequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
                    newLines.add(trimmed)
                } else if (trimmed.startsWith("#EXT-X-KEY")) {
                    hasGlobalKey = true
                    if (keyFile != null) {
                        trimmed = trimmed.replace(Regex("URI=\"[^\"]+\""), "URI=\"${keyFile.name}\"")
                    }
                    currentKeyTag = trimmed
                    newLines.add(trimmed)
                } else if (trimmed.startsWith("#")) {
                    newLines.add(trimmed)
                } else {
                    // 分片引用行：直接映射本地文件名，零磁盘复制损耗！
                    val uriName = trimmed.substringAfterLast("/").substringBefore("?")
                    val matchedFile = findLocalSegmentFile(parentDir, uriName)

                    if (matchedFile != null) {
                        // 补全 IV（若缺失）
                        if (hasGlobalKey && currentKeyTag != null && !currentKeyTag.contains("IV=")) {
                            val ivHex = generateSequenceIvHex(currentMediaSequence)
                            val keyWithIv = currentKeyTag.replaceFirst(
                                "METHOD=AES-128",
                                "METHOD=AES-128,IV=0x$ivHex"
                            )
                            newLines.add(keyWithIv)
                        }
                        newLines.add(matchedFile.name)
                        currentMediaSequence++
                    } else {
                        newLines.add(trimmed)
                        currentMediaSequence++
                    }
                }
            }
            targetM3u8.writeText(newLines.joinToString("\n"))
            return@withContext targetM3u8
        }

        // 没有原始 m3u8，扫描本地所有切片并按自然数字排序快速生成
        val tsFiles = parentDir.listFiles()?.filter {
            it.isFile && (it.extension.lowercase(Locale.ROOT) in listOf("ts", "qsv", "m4s") || it.name.matches(Regex("^\\d+$")))
        }?.sortedWith { f1, f2 -> naturalCompare(f1.name, f2.name) } ?: emptyList()

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:15\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")

        if (keyFile != null) {
            sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"${keyFile.name}\"\n")
        }

        for ((index, ts) in tsFiles.withIndex()) {
            if (keyFile != null) {
                val ivHex = generateSequenceIvHex(index.toLong())
                sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"${keyFile.name}\",IV=0x$ivHex\n")
            }
            sb.append("#EXTINF:10.0,\n")
            sb.append(ts.name).append("\n")
        }
        sb.append("#EXT-X-ENDLIST\n")

        targetM3u8.writeText(sb.toString())
        targetM3u8
    }

    private fun autoExtractKeyFromTag(parentDir: File, keyTag: String): File? {
        val uriRegex = Regex("URI=\"([^\"]+)\"")
        val match = uriRegex.find(keyTag) ?: return null
        val rawUri = match.groupValues[1]

        if (rawUri.startsWith("data:")) {
            try {
                val base64Data = rawUri.substringAfter("base64,").trim()
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (decodedBytes.isNotEmpty()) {
                    val targetKey = File(parentDir, "embedded_auto.key")
                    targetKey.writeBytes(decodedBytes)
                    return targetKey
                }
            } catch (ignored: Exception) {}
        }

        if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            try {
                val req = Request.Builder()
                    .url(rawUri)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; U; Android 14; zh-cn) Mobile QQBrowser/14.8")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val downloadedKey = File(parentDir, "auto_fetched.key")
                        if (bytes.size in 32..34) {
                            val str = String(bytes).trim()
                            if (str.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                                val binBytes = ByteArray(16)
                                for (i in 0 until 16) {
                                    binBytes[i] = str.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                                }
                                downloadedKey.writeBytes(binBytes)
                                return downloadedKey
                            }
                        }
                        downloadedKey.writeBytes(bytes)
                        return downloadedKey
                    }
                }
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun resolveKeyFile(parentDir: File, customKeyHex: String?): File? {
        if (!customKeyHex.isNullOrBlank()) {
            try {
                val cleanHex = customKeyHex.trim().removePrefix("0x").removePrefix("0X")
                if (cleanHex.length == 32) {
                    val keyBytes = ByteArray(16)
                    for (i in 0 until 16) {
                        keyBytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                    val userKey = File(parentDir, "custom_aes128.key")
                    userKey.writeBytes(keyBytes)
                    return userKey
                }
            } catch (ignored: Exception) {}
        }

        val candidates = parentDir.listFiles() ?: return null
        for (f in candidates) {
            val name = f.name.lowercase(Locale.ROOT)
            val ext = f.extension.lowercase(Locale.ROOT)
            if (ext == "key" || name.contains("key") || name.startsWith("encrypt") || name.startsWith(".")) {
                if (f.length() in 32L..34L) {
                    val content = f.readText().trim()
                    if (content.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                        val binaryKey = File(parentDir, "converted_${f.nameWithoutExtension}.key")
                        val bytes = ByteArray(16)
                        for (i in 0 until 16) {
                            bytes[i] = content.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                        }
                        binaryKey.writeBytes(bytes)
                        return binaryKey
                    }
                }
                if (f.length() == 16L || ext == "key") {
                    return f
                }
            }
        }

        for (f in candidates) {
            if (f.isFile && f.length() == 16L) {
                return f
            }
        }
        return null
    }

    private fun generateSequenceIvHex(sequence: Long): String {
        val bytes = ByteArray(16)
        var temp = sequence
        for (i in 15 downTo 8) {
            bytes[i] = (temp and 0xFF).toByte()
            temp = temp ushr 8
        }
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun findLocalSegmentFile(parentDir: File, uriName: String): File? {
        val direct = File(parentDir, uriName)
        if (direct.exists()) return direct

        val files = parentDir.listFiles() ?: return null
        val match = files.firstOrNull { it.name.equals(uriName, ignoreCase = true) }
        if (match != null) return match

        val cleanName = uriName.substringBefore("?")
        val matchClean = files.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        if (matchClean != null) return matchClean

        return null
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val p = Pattern.compile("(\\d+)|(\\D+)")
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

    fun parseTotalDurationMs(m3u8File: File?): Long {
        if (m3u8File == null || !m3u8File.exists()) return 0L
        var totalSec = 0.0
        try {
            m3u8File.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF:")) {
                        val durStr = trimmed.removePrefix("#EXTINF:").substringBefore(",").trim()
                        val dur = durStr.toDoubleOrNull() ?: 0.0
                        totalSec += dur
                    }
                }
            }
        } catch (ignored: Exception) {}
        return (totalSec * 1000.0).toLong()
    }
}
