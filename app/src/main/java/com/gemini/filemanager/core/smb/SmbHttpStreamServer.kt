package com.gemini.filemanager.core.smb

import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class SmbHttpStreamServer private constructor(port: Int) : NanoHTTPD("127.0.0.1", port) {

    init {
        setAsyncRunner(PooledAsyncRunner())
    }

    fun registerTarget(target: SmbStreamTarget): String {
        val token = SmbStreamRegistry.register(target)
        return buildHttpUrl(token, target.fileName)
    }

    fun buildHttpUrl(token: String, fileName: String): String {
        val encodedName = Uri.encode(fileName)
        return "http://127.0.0.1:$listeningPort/stream/$token/$encodedName"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found"
        )
        val segments = uri.trim('/').split('/')
        if (segments.size < 2 || segments[0] != "stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val token = segments[1]
        val target = SmbStreamRegistry.getTarget(token) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Stream Expired"
        )
        val streamSession = SmbStreamRegistry.getSession(token) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Stream Expired"
        )

        return try {
            val totalSize = streamSession.totalSize
            if (totalSize <= 0L) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Empty File")
            }

            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    target.mimeType,
                    ByteArrayInputStream(ByteArray(0)),
                    0L
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Length", totalSize.toString())
                }
            }

            val rangeHeader = session.headers.entries
                .firstOrNull { it.key.equals("range", ignoreCase = true) }
                ?.value
                ?.trim()

            if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                val rangeSpec = rangeHeader.substring(6).substringBefore(",").trim()
                val (start, end) = parseByteRange(rangeSpec, totalSize) ?: run {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Range Not Satisfiable"
                    ).apply {
                        addHeader("Content-Range", "bytes */$totalSize")
                    }
                }

                val contentLength = end - start + 1L
                val stream = streamSession.openInputStream(start, contentLength)
                newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    target.mimeType,
                    stream,
                    contentLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$totalSize")
                }
            } else {
                val stream = streamSession.openInputStream(0L, totalSize)
                newFixedLengthResponse(
                    Response.Status.OK,
                    target.mimeType,
                    stream,
                    totalSize
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "SMB Stream Error: ${e.message}"
            )
        }
    }

    private class PooledAsyncRunner : AsyncRunner {
        private val threadId = AtomicInteger(1)
        private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "smb-http-proxy-${threadId.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        private val running = java.util.Collections.synchronizedList(mutableListOf<ClientHandler>())

        override fun closeAll() {
            val snapshot = synchronized(running) { running.toList() }
            snapshot.forEach { it.close() }
        }

        override fun closed(clientHandler: ClientHandler) {
            running.remove(clientHandler)
        }

        override fun exec(code: ClientHandler) {
            running.add(code)
            executor.execute(code)
        }
    }

    companion object {
        private const val STREAM_SOCKET_TIMEOUT_MS = 30_000

        @Volatile
        private var instance: SmbHttpStreamServer? = null

        internal fun parseByteRange(spec: String, totalSize: Long): Pair<Long, Long>? {
            if (totalSize <= 0L) return null
            val dashIdx = spec.indexOf('-')
            if (dashIdx < 0) return null
            val startPart = spec.substring(0, dashIdx).trim()
            val endPart = spec.substring(dashIdx + 1).trim()

            return try {
                val start: Long
                val end: Long
                if (startPart.isEmpty()) {
                    val suffixLength = endPart.toLong()
                    if (suffixLength <= 0L) return null
                    start = (totalSize - suffixLength).coerceAtLeast(0L)
                    end = totalSize - 1L
                } else {
                    start = startPart.toLong()
                    end = if (endPart.isEmpty()) totalSize - 1L else min(endPart.toLong(), totalSize - 1L)
                }
                if (start < 0L || start >= totalSize || start > end) null else start to end
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun getInstance(): SmbHttpStreamServer {
            instance?.let { if (it.isAlive) return it }
            synchronized(this) {
                instance?.let { if (it.isAlive) return it }
                var lastErr: Exception? = null
                repeat(5) {
                    try {
                        val freePort = ServerSocket(0).use { it.localPort }
                        val server = SmbHttpStreamServer(freePort)
                        server.start(STREAM_SOCKET_TIMEOUT_MS, false)
                        instance = server
                        return server
                    } catch (e: IOException) {
                        lastErr = e
                    }
                }
                throw IllegalStateException("无法启动本地 SMB 流媒体代理: ${lastErr?.message}", lastErr)
            }
        }
    }
}
