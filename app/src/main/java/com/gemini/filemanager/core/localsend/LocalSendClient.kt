package com.gemini.filemanager.core.localsend

import android.os.Build
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
import okhttp3.RequestBody.Companion.asRequestBody
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

class LocalSendClient(
    private val myAlias: String,
    private val myFingerprint: String
) {
    // 兼容官方客户端自签名证书的 OkHttpClient
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
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendFiles(
        target: DiscoveredDevice,
        filesToSend: List<File>,
        onProgress: (fileName: String, progress: Float, speed: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val proto = if (target.device.protocol.equals("https", ignoreCase = true)) "https" else "http"
        val baseUrl = "$proto://${target.ip}:${target.port}"

        // 1. 组装 PrepareUpload 请求
        val fileMap = mutableMapOf<String, FileInfoDto>()
        val fileMapById = mutableMapOf<String, File>()

        for (file in filesToSend) {
            val fileId = UUID.randomUUID().toString()
            fileMap[fileId] = FileInfoDto(
                id = fileId,
                fileName = file.name,
                size = file.length()
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
            .post(RequestBody.create("application/json".toMediaType(), jsonBody))
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }

        if (!resp.isSuccessful) return@withContext false
        val respBody = resp.body?.string() ?: return@withContext false
        val prepareResp = try {
            json.decodeFromString<PrepareUploadResponseDto>(respBody)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }

        val sessionId = prepareResp.sessionId

        // 2. 依次传输文件
        for ((fileId, token) in prepareResp.files) {
            val file = fileMapById[fileId] ?: continue
            val totalBytes = file.length()
            var startTime = System.currentTimeMillis()

            val progressBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    val source = file.source()
                    var bytesWritten = 0L
                    val buffer = okio.Buffer()

                    var read: Long
                    while (source.read(buffer, 16384L).also { read = it } != -1L) {
                        sink.write(buffer, read)
                        bytesWritten += read

                        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                        val speed = if (elapsedSec > 0) {
                            val speedBps = (bytesWritten / elapsedSec).toLong()
                            "${speedBps / 1024 / 1024} MB/s"
                        } else ""

                        val prog = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
                        onProgress(file.name, prog, speed)
                    }
                }
            }

            val uploadReq = Request.Builder()
                .url("$baseUrl${LocalSendConstants.PATH_UPLOAD}?sessionId=$sessionId&fileId=$fileId&token=$token")
                .post(progressBody)
                .build()

            val uploadResp = try {
                client.newCall(uploadReq).execute()
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }

            if (!uploadResp.isSuccessful) {
                return@withContext false
            }
        }

        true
    }
}
