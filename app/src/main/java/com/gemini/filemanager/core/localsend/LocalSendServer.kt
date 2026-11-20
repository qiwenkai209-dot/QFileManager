package com.gemini.filemanager.core.localsend

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.FileInfoDto
import com.gemini.filemanager.data.model.LocalSendDeviceDto
import com.gemini.filemanager.data.model.PrepareUploadRequestDto
import com.gemini.filemanager.data.model.PrepareUploadResponseDto
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LocalSendServer(
    private val context: Context,
    private val port: Int = LocalSendConstants.PORT,
    private val myAlias: String,
    private val myFingerprint: String,
    private val onDeviceDiscovered: (DiscoveredDevice) -> Unit = {},
    private val onReceiveProgress: (fileName: String, progress: Float, totalBytes: Long) -> Unit,
    private val onFileReceived: (File) -> Unit
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSessions = ConcurrentHashMap<String, Map<String, FileInfoDto>>()
    private val activeUploads = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // 统一处理 CORS 跨域预检
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            return addCorsHeaders(response)
        }

        // 设备信息查询接口 (LocalSend v2 /api/localsend/v2/info)
        if (uri == LocalSendConstants.PATH_INFO && (method == Method.GET || method == Method.POST)) {
            val myInfo = LocalSendDeviceDto(
                alias = myAlias,
                version = LocalSendConstants.PROTOCOL_VERSION,
                deviceModel = Build.MODEL,
                deviceType = "mobile",
                fingerprint = myFingerprint,
                port = port,
                protocol = "http",
                download = true
            )
            val jsonStr = json.encodeToString(LocalSendDeviceDto.serializer(), myInfo)
            val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", jsonStr)
            return addCorsHeaders(response)
        }

        // 官方对等主动注册接口 (LocalSend v2 /api/localsend/v2/register)
        if (uri == LocalSendConstants.PATH_REGISTER && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
                val postData = getBodyString(map)
                if (postData.isNotEmpty()) {
                    val dto = json.decodeFromString<LocalSendDeviceDto>(postData)
                    if (dto.fingerprint != myFingerprint) {
                        val senderIp = session.remoteIpAddress
                            ?: session.headers["http-client-ip"]
                            ?: ""
                        if (senderIp.isNotEmpty()) {
                            onDeviceDiscovered(
                                DiscoveredDevice(
                                    ip = senderIp,
                                    port = dto.port,
                                    device = dto
                                )
                            )
                        }
                    }
                }
                val myInfo = LocalSendDeviceDto(
                    alias = myAlias,
                    version = LocalSendConstants.PROTOCOL_VERSION,
                    deviceModel = Build.MODEL,
                    deviceType = "mobile",
                    fingerprint = myFingerprint,
                    port = port,
                    protocol = "http",
                    download = true
                )
                val jsonStr = json.encodeToString(LocalSendDeviceDto.serializer(), myInfo)
                val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", jsonStr)
                return addCorsHeaders(response)
            } catch (e: Exception) {
                e.printStackTrace()
                val response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad Request")
                return addCorsHeaders(response)
            }
        }

        // 上传会话准备 (/api/localsend/v2/prepare-upload)
        if (uri == LocalSendConstants.PATH_PREPARE_UPLOAD && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
                val postData = getBodyString(map)
                val req = json.decodeFromString<PrepareUploadRequestDto>(postData)
                val sessionId = UUID.randomUUID().toString()
                pendingSessions[sessionId] = req.files

                val fileTokenMap = req.files.keys.associateWith { UUID.randomUUID().toString() }
                val respDto = PrepareUploadResponseDto(sessionId = sessionId, files = fileTokenMap)
                val respJson = json.encodeToString(PrepareUploadResponseDto.serializer(), respDto)
                val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", respJson)
                return addCorsHeaders(response)
            } catch (e: Exception) {
                e.printStackTrace()
                val response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad Request: ${e.message}")
                return addCorsHeaders(response)
            }
        }

        // 传输取消接口 (/api/localsend/v2/cancel)
        if (uri == LocalSendConstants.PATH_CANCEL && method == Method.POST) {
            val sessionId = session.parameters["sessionId"]?.firstOrNull() ?: ""
            if (sessionId.isNotEmpty()) {
                pendingSessions.remove(sessionId)
            }
            val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{}")
            return addCorsHeaders(response)
        }

        // 文件实际流式上传接收 (/api/localsend/v2/upload)
        if (uri == LocalSendConstants.PATH_UPLOAD && method == Method.POST) {
            val params = session.parameters
            val sessionId = params["sessionId"]?.firstOrNull() ?: ""
            val fileId = params["fileId"]?.firstOrNull() ?: ""

            val fileMap = pendingSessions[sessionId]
            val fileInfo = fileMap?.get(fileId)

            val rawFileName = fileInfo?.fileName ?: "received_${System.currentTimeMillis()}"
            val fileName = sanitizeFileName(rawFileName)
            val downloadDir = getDownloadDirectory()

            var targetFile = File(downloadDir, fileName)
            // 避免重名覆盖，自动重命名如 (1), (2)
            if (targetFile.exists()) {
                val base = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "")
                val extStr = if (ext.isNotEmpty()) ".$ext" else ""
                var counter = 1
                while (targetFile.exists()) {
                    targetFile = File(downloadDir, "${base} ($counter)$extStr")
                    counter++
                }
            }

            val tempFile = File(downloadDir, ".${fileName}.${UUID.randomUUID().toString().take(8)}.part")
            val appCtx = context.applicationContext
            if (activeUploads.incrementAndGet() == 1) {
                AppBackgroundKeepAliveManager.acquireLease(appCtx, "localsend_recv", "LocalSend 接收文件")
            }

            return try {
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: (fileInfo?.size ?: 0L)
                val inputStream = session.inputStream
                val buffer = ByteArray(256 * 1024)
                var bytesRead: Int
                var totalRead = 0L
                var lastUiUpdateAt = 0L

                BufferedOutputStream(FileOutputStream(tempFile), 256 * 1024).use { outputStream ->
                    if (contentLength > 0L) {
                        // 严格按 Content-Length 截断读取，避免 HTTP Keep-Alive 下 Socket 无限阻塞超时
                        while (totalRead < contentLength) {
                            val toRead = minOf(buffer.size.toLong(), contentLength - totalRead).toInt()
                            bytesRead = inputStream.read(buffer, 0, toRead)
                            if (bytesRead <= 0) break
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateAt >= 150L || totalRead >= contentLength) {
                                lastUiUpdateAt = now
                                val prog = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                onReceiveProgress(targetFile.name, prog, contentLength)
                            }
                        }
                    } else {
                        while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                    }
                    outputStream.flush()
                }

                if (contentLength > 0L && totalRead < contentLength) {
                    throw IOException("传输中断：接收文件不完整 ($totalRead / $contentLength 字节)")
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                // 通知系统媒体库刷新
                MediaScannerConnection.scanFile(
                    appCtx,
                    arrayOf(targetFile.absolutePath),
                    null,
                    null
                )
                onFileReceived(targetFile)

                val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{}")
                addCorsHeaders(response)
            } catch (e: Exception) {
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
                e.printStackTrace()
                val response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
                addCorsHeaders(response)
            } finally {
                if (activeUploads.decrementAndGet() <= 0) {
                    AppBackgroundKeepAliveManager.releaseLease(appCtx, "localsend_recv")
                }
            }
        }

        val notFoundResponse = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        return addCorsHeaders(notFoundResponse)
    }

    private fun getBodyString(map: Map<String, String>): String {
        val postData = map["postData"] ?: ""
        if (postData.isBlank()) return ""
        val trimmed = postData.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return postData
        }
        val file = File(postData)
        if (file.exists() && file.isFile) {
            try {
                return file.readText(Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        return postData
    }

    private fun sanitizeFileName(rawName: String): String {
        val nameWithoutPath = rawName.substringAfterLast("/").substringAfterLast("\\")
        val clean = nameWithoutPath.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (clean.isBlank()) "received_${System.currentTimeMillis()}" else clean
    }

    private fun getDownloadDirectory(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(publicDownloads, "LocalSend")
        if (target.exists() || target.mkdirs()) {
            return target
        }
        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalSend")
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }
}
