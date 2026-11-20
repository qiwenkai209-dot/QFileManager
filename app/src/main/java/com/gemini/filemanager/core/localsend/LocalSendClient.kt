package com.gemini.filemanager.core.localsend

import android.os.Build
import android.webkit.MimeTypeMap
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.FileInfoDto
import com.gemini.filemanager.data.model.LocalSendDeviceDto
import com.gemini.filemanager.data.model.PrepareUploadRequestDto
import com.gemini.filemanager.data.model.PrepareUploadResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SendResult(
    val success: Boolean,
    val errorMessage: String? = null
)

class LocalSendClient(
    private val myAlias: String,
    private val myFingerprint: String
) {
    // 兼容官方客户端自签名证书的 OkHttpClient，写超时设为 0 支持超大文件无中断持续流式发送
    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS) // 0 表示无写入超时，适合局域网大文件传输
            .readTimeout(180, TimeUnit.SECONDS) // 3分钟读超时，保障对端弹窗确认或接收大文件不被中断
            .retryOnConnectionFailure(true)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendFiles(
        target: DiscoveredDevice,
        filesToSend: List<File>,
        onProgress: (fileName: String, progress: Float, speed: String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        val proto = if (target.device.protocol.equals("https", ignoreCase = true)) "https" else "http"
        val baseUrl = "$proto://${target.ip}:${target.port}"

        // 1. 组装 PrepareUpload 请求
        val fileMap = mutableMapOf<String, FileInfoDto>()
        val fileMapById = mutableMapOf<String, File>()

        for (file in filesToSend) {
            val fileId = UUID.randomUUID().toString()
            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

            fileMap[fileId] = FileInfoDto(
                id = fileId,
                fileName = file.name,
                size = file.length(),
                fileType = mimeType
            )
            fileMapById[fileId] = file
        }

        val myInfo = LocalSendDeviceDto(
            alias = myAlias,
            version = LocalSendConstants.PROTOCOL_VERSION,
            deviceModel = Build.MODEL,
            deviceType = "mobile",
            fingerprint = myFingerprint,
            port = LocalSendConstants.PORT,
            protocol = "http",
            download = true
        )

        val prepareReq = PrepareUploadRequestDto(info = myInfo, files = fileMap)
        val jsonBody = json.encodeToString(PrepareUploadRequestDto.serializer(), prepareReq)

        val req = Request.Builder()
            .url("$baseUrl${LocalSendConstants.PATH_PREPARE_UPLOAD}")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val respBody = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val errorMsg = when (code) {
                        403 -> "对端拒绝接收文件"
                        400 -> "对端不接受所发文件格式"
                        404 -> "对端服务未就绪"
                        500 -> "对端内部错误"
                        else -> "对端返回错误 (HTTP $code)"
                    }
                    return@withContext SendResult(false, errorMsg)
                }
                resp.body?.string() ?: return@withContext SendResult(false, "对端握手响应为空")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext SendResult(false, "连接对端失败: ${e.localizedMessage ?: e.message}")
        }

        val prepareResp = try {
            json.decodeFromString<PrepareUploadResponseDto>(respBody)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext SendResult(false, "解析对端准备响应失败")
        }

        val sessionId = prepareResp.sessionId
        if (prepareResp.files.isEmpty()) {
            return@withContext SendResult(false, "对端已取消或未接受文件")
        }

        // 2. 依次流式传输文件
        for ((fileId, token) in prepareResp.files) {
            val file = fileMapById[fileId] ?: continue
            val totalBytes = file.length()
            val startTime = System.currentTimeMillis()

            val progressBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    var bytesWritten = 0L
                    var lastProgressAt = 0L
                    val buffer = okio.Buffer()
                    val chunkSize = 256L * 1024L

                    if (totalBytes == 0L) {
                        sink.flush()
                        onProgress(file.name, 1f, "0 B/s")
                        return
                    }

                    file.source().use { source ->
                        var read: Long
                        while (source.read(buffer, chunkSize).also { read = it } != -1L) {
                            sink.write(buffer, read)
                            sink.flush() // 及时推入网络套接字，防止缓冲区积压或连接停顿
                            bytesWritten += read

                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= 150L || bytesWritten >= totalBytes) {
                                lastProgressAt = now
                                val elapsedMs = (now - startTime).coerceAtLeast(1L)
                                val speedBps = (bytesWritten * 1000.0) / elapsedMs
                                val speed = formatTransferSpeed(speedBps)
                                val prog = if (totalBytes > 0L) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 1f
                                onProgress(file.name, prog, speed)
                            }
                        }
                    }
                    sink.flush()
                }
            }

            val uploadReq = Request.Builder()
                .url("$baseUrl${LocalSendConstants.PATH_UPLOAD}?sessionId=$sessionId&fileId=$fileId&token=$token")
                .post(progressBody)
                .build()

            try {
                val uploadOk = client.newCall(uploadReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext SendResult(false, "文件 [${file.name}] 传输失败: HTTP ${resp.code}")
                    }
                    true
                }
                if (!uploadOk) {
                    return@withContext SendResult(false, "文件 [${file.name}] 传输失败")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext SendResult(false, "传输文件 [${file.name}] 中断: ${e.localizedMessage ?: e.message}")
            }
        }

        SendResult(true)
    }

    private fun formatTransferSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024.0 * 1024.0 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024.0 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> "${bytesPerSec.toLong()} B/s"
        }
    }
}
