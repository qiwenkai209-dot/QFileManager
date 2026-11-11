package com.gemini.filemanager.core.smb

import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.SmbRandomAccess
import org.codelibs.jcifs.smb.impl.SmbFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class SmbStreamTarget(
    val smbUrl: String,
    val context: CIFSContext,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis()
)

class SmbHttpStreamServer private constructor(port: Int) : NanoHTTPD("127.0.0.1", port) {

    private val targets = ConcurrentHashMap<String, SmbStreamTarget>()

    fun registerTarget(target: SmbStreamTarget): String {
        if (targets.size > 20) {
            val oldest = targets.entries.minByOrNull { it.value.createdAt }?.key
            if (oldest != null) targets.remove(oldest)
        }
        val token = UUID.randomUUID().toString()
        targets[token] = target
        val encodedName = Uri.encode(target.fileName)
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
        val target = targets[token] ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Stream Expired"
        )

        var smbFile: SmbFile? = null
        var raf: SmbRandomAccess? = null
        return try {
            smbFile = SmbFile(target.smbUrl, target.context)
            val totalSize = if (target.fileSize > 0L) target.fileSize else smbFile.length()
            if (totalSize <= 0L) {
                smbFile.close()
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Empty File")
            }

            val rangeHeader = session.headers["range"]
            if (session.method == Method.HEAD) {
                smbFile.close()
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

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").substringBefore(",").trim()
                val (start, end) = parseByteRange(rangeSpec, totalSize) ?: run {
                    smbFile.close()
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Range Not Satisfiable"
                    ).apply {
                        addHeader("Content-Range", "bytes */$totalSize")
                    }
                }

                val contentLength = end - start + 1L
                raf = smbFile.openRandomAccess("r")
                raf.seek(start)
                val stream = SmbRandomAccessInputStream(smbFile, raf, contentLength)
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
                raf = smbFile.openRandomAccess("r")
                val stream = SmbRandomAccessInputStream(smbFile, raf, totalSize)
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
            try { raf?.close() } catch (_: Exception) {}
            try { smbFile?.close() } catch (_: Exception) {}
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "SMB Stream Error: ${e.message}"
            )
        }
    }

    private class SmbRandomAccessInputStream(
        private val smbFile: SmbFile,
        private val raf: SmbRandomAccess,
        private var remaining: Long
    ) : InputStream() {

        private val buffer = ByteArray(64 * 1024)
        private var bufferPos = 0
        private var bufferLimit = 0
        private var closed = false

        override fun read(): Int {
            if (remaining <= 0L) return -1
            if (bufferPos >= bufferLimit) {
                if (!refillBuffer()) return -1
            }
            remaining--
            return buffer[bufferPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0L) return -1
            if (bufferPos < bufferLimit) {
                val available = bufferLimit - bufferPos
                val toCopy = minOf(len, available, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                System.arraycopy(buffer, bufferPos, b, off, toCopy)
                bufferPos += toCopy
                remaining -= toCopy
                return toCopy
            }
            val toRead = minOf(len.toLong(), remaining).toInt()
            return if (toRead >= buffer.size) {
                val n = raf.read(b, off, toRead)
                if (n <= 0) -1 else {
                    remaining -= n
                    n
                }
            } else {
                if (!refillBuffer()) return -1
                val available = bufferLimit - bufferPos
                val toCopy = minOf(len, available, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                System.arraycopy(buffer, bufferPos, b, off, toCopy)
                bufferPos += toCopy
                remaining -= toCopy
                toCopy
            }
        }

        private fun refillBuffer(): Boolean {
            if (remaining <= 0L) return false
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val n = raf.read(buffer, 0, toRead)
            if (n <= 0) {
                remaining = 0L
                return false
            }
            bufferPos = 0
            bufferLimit = n
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                raf.close()
            } catch (_: Exception) {
            }
            try {
                smbFile.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
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
                        server.start(SOCKET_READ_TIMEOUT, false)
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
