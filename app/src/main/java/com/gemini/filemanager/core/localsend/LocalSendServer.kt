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

        // 统一处理 CORS 预检
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            return addCorsHeaders(response)
        }

        // 设备信息查询接口 (LocalSend v2)
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
                val postData = map["postData"] ?: ""
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

        // 上传会话准备
        if (uri == LocalSendConstants.PATH_PREPARE_UPLOAD && method == Method.POST) {
            val map = HashMap<String, String>()
            try {
                session.parseBody(map)
                val postData = map["postData"] ?: ""
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

        // 文件实际流式上传接收
        if (uri == LocalSendConstants.PATH_UPLOAD && method == Method.POST) {
            val params = session.parameters
            val sessionId = params["sessionId"]?.firstOrNull() ?: ""
            val fileId = params["fileId"]?.firstOrNull() ?: ""

            val fileMap = pendingSessions[sessionId]
            val fileInfo = fileMap?.get(fileId)

            val fileName = fileInfo?.fileName ?: "received_${System.currentTimeMillis()}"
            val downloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LocalSend"
            )
            if (!downloadDir.exists()) downloadDir.mkdirs()

            var targetFile = File(downloadDir, fileName)
            // 避免重名覆盖
            if (targetFile.exists()) {
                val base = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "")
                val extStr = if (ext.isNotEmpty()) ".$ext" else ""
                targetFile = File(downloadDir, "${base}_${System.currentTimeMillis()}$extStr")
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
                    while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0L) {
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateAt >= 150L || totalRead >= contentLength) {
                                lastUiUpdateAt = now
                                val prog = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                onReceiveProgress(targetFile.name, prog, contentLength)
                            }
                        }
                    }
                    outputStream.flush()
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

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }
}
