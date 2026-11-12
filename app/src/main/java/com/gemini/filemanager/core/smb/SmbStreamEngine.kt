package com.gemini.filemanager.core.smb

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.SmbRandomAccess
import org.codelibs.jcifs.smb.impl.SmbAuthException
import org.codelibs.jcifs.smb.impl.SmbFile
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 滑动窗口实时速率计量器（用于平滑计算 SMB 传输及流媒体的瞬时吞吐速率）。
 */
internal class SlidingSpeedMeter(
    private val windowMs: Long = 1500L,
    private val staleTimeoutMs: Long = 2500L
) {
    private data class Sample(val timestampMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var totalWindowBytes = 0L

    @Synchronized
    fun record(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0L) return
        samples.addLast(Sample(nowMs, bytes))
        totalWindowBytes += bytes
        prune(nowMs)
    }

    @Synchronized
    fun getBytesPerSecond(nowMs: Long = System.currentTimeMillis()): Long {
        prune(nowMs)
        if (samples.isEmpty()) return 0L
        val newest = samples.last().timestampMs
        if (nowMs - newest > staleTimeoutMs) {
            samples.clear()
            totalWindowBytes = 0L
            return 0L
        }
        val oldest = samples.first().timestampMs
        val elapsedMs = (nowMs - oldest).coerceAtLeast(250L)
        return (totalWindowBytes * 1000L) / elapsedMs
    }

    @Synchronized
    fun reset() {
        samples.clear()
        totalWindowBytes = 0L
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            val removed = samples.removeFirst()
            totalWindowBytes -= removed.bytes
        }
    }
}

/**
 * 底层 SMB 随机块读取源抽象，便于真实网络与单元测试注入。
 */
internal interface SmbChunkSource {
    val totalSize: Long
    fun readChunk(offset: Long, length: Int): ByteArray
    fun closeIdleIfNeeded(maxIdleMs: Long, nowMs: Long = System.currentTimeMillis()) {}
    fun close()
}

/**
 * 基于 JCIFS SmbRandomAccess 的可复用连接块读取器，支持游标跟踪与传输断线自动重连。
 */
internal class JcifsSmbChunkSource(
    private val smbUrl: String,
    private val contextProvider: () -> CIFSContext,
    initialSize: Long,
    private val onTransportFailure: () -> Unit
) : SmbChunkSource {

    private val ioLock = Any()
    private var smbFile: SmbFile? = null
    private var raf: SmbRandomAccess? = null
    private var cursorOffset: Long = -1L
    @Volatile
    private var cachedTotalSize: Long = initialSize
    @Volatile
    private var lastIoAtMs: Long = System.currentTimeMillis()
    @Volatile
    private var closed = false

    override val totalSize: Long
        get() {
            val known = cachedTotalSize
            if (known > 0L) return known
            synchronized(ioLock) {
                if (cachedTotalSize > 0L) return cachedTotalSize
                val file = ensureOpenLocked()
                val len = try {
                    file.length()
                } catch (e: Exception) {
                    closeHandlesLocked()
                    throw IOException("获取远程文件大小失败: ${e.message}", e)
                }
                cachedTotalSize = len
                return len
            }
        }

    override fun readChunk(offset: Long, length: Int): ByteArray {
        if (closed) throw IOException("SMB stream source closed")
        if (length <= 0) return ByteArray(0)

        synchronized(ioLock) {
            if (closed) throw IOException("SMB stream source closed")
            var lastError: Exception? = null
            for (attempt in 0..1) {
                try {
                    ensureOpenLocked()
                    val activeRaf = raf ?: throw IOException("无法打开 SMB 随机读句柄")
                    if (cursorOffset != offset) {
                        activeRaf.seek(offset)
                        cursorOffset = offset
                    }

                    val buffer = ByteArray(length)
                    var totalRead = 0
                    while (totalRead < length) {
                        if (closed) throw IOException("SMB stream source closed")
                        val n = activeRaf.read(buffer, totalRead, length - totalRead)
                        if (n <= 0) break
                        totalRead += n
                        cursorOffset += n
                    }
                    lastIoAtMs = System.currentTimeMillis()
                    return if (totalRead == length) buffer else buffer.copyOf(totalRead)
                } catch (e: Exception) {
                    lastError = e
                    closeHandlesLocked()
                    val canRetry = attempt == 0 && !closed && isRecoverableStreamError(e)
                    if (canRetry) {
                        onTransportFailure()
                    } else {
                        break
                    }
                }
            }
            throw IOException("SMB 读取失败 (offset=$offset): ${lastError?.message}", lastError)
        }
    }

    override fun closeIdleIfNeeded(maxIdleMs: Long, nowMs: Long) {
        synchronized(ioLock) {
            if (raf != null && nowMs - lastIoAtMs >= maxIdleMs) {
                closeHandlesLocked()
            }
        }
    }

    override fun close() {
        closed = true
        synchronized(ioLock) {
            closeHandlesLocked()
        }
    }

    private fun ensureOpenLocked(): SmbFile {
        val existingFile = smbFile
        if (existingFile != null && raf != null) {
            return existingFile
        }
        closeHandlesLocked()
        val ctx = contextProvider()
        val file = SmbFile(smbUrl, ctx)
        try {
            if (cachedTotalSize <= 0L) {
                cachedTotalSize = file.length()
            }
            val openedRaf = file.openRandomAccess("r")
            smbFile = file
            raf = openedRaf
            cursorOffset = 0L
            lastIoAtMs = System.currentTimeMillis()
            return file
        } catch (e: Exception) {
            try {
                file.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun closeHandlesLocked() {
        cursorOffset = -1L
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
        try {
            smbFile?.close()
        } catch (_: Exception) {
        }
        smbFile = null
    }

    private fun isRecoverableStreamError(error: Exception): Boolean {
        if (error is SmbAuthException) return false
        val msg = error.message.orEmpty()
        if (msg.contains("Access is denied", ignoreCase = true) ||
            msg.contains("cannot find the file", ignoreCase = true) ||
            msg.contains("OBJECT_NAME_NOT_FOUND", ignoreCase = true)
        ) {
            return false
        }
        return true
    }
}

/**
 * 带有 LRU 块缓存、并发请求去重与后台超前预读的 SMB 流媒体会话。
 * 同时为 Media3 直连 DataSource 与 NanoHTTPD Range Proxy 提供高速零重复读取支撑。
 */
internal class SmbBufferedStreamSession(
    private val source: SmbChunkSource,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxCachedChunks: Int = DEFAULT_MAX_CACHED_CHUNKS,
    private val prefetchAheadChunks: Int = DEFAULT_PREFETCH_AHEAD_CHUNKS,
    private val prefetchExecutor: ExecutorService
) {
    private val cacheLock = Any()
    private val chunkCache = object : LinkedHashMap<Long, ByteArray>(maxCachedChunks + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > maxCachedChunks
        }
    }

    private val inFlight = ConcurrentHashMap<Long, CompletableFuture<ByteArray>>()
    private val speedMeter = SlidingSpeedMeter()
    private val closed = AtomicBoolean(false)
    private val latestRequestedChunk = AtomicLong(0L)
    @Volatile
    var lastAccessAtMs: Long = System.currentTimeMillis()
        private set

    val totalSize: Long
        get() = source.totalSize

    fun currentSpeedBytesPerSec(): Long = speedMeter.getBytesPerSecond()

    fun readAt(offset: Long, outBuffer: ByteArray, outOffset: Int, length: Int): Int {
        if (closed.get()) throw IOException("SMB stream session closed")
        if (length <= 0) return 0
        val fileSize = totalSize
        if (fileSize <= 0L || offset < 0L || offset >= fileSize) return -1

        lastAccessAtMs = System.currentTimeMillis()
        val chunkIndex = offset / chunkSize
        val inChunkOffset = (offset % chunkSize).toInt()
        latestRequestedChunk.set(chunkIndex)

        val chunkData = getChunk(chunkIndex, isPrefetch = false)
        if (inChunkOffset >= chunkData.size) return -1

        val available = chunkData.size - inChunkOffset
        val toCopy = minOf(length, available)
        System.arraycopy(chunkData, inChunkOffset, outBuffer, outOffset, toCopy)

        schedulePrefetch(chunkIndex + 1, fileSize)
        return toCopy
    }

    fun openInputStream(startOffset: Long, contentLength: Long): InputStream {
        return SessionInputStream(this, startOffset, contentLength)
    }

    fun closeIdleHandles(maxIdleMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (!closed.get()) {
            source.closeIdleIfNeeded(maxIdleMs, nowMs)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
        synchronized(cacheLock) {
            chunkCache.clear()
        }
        speedMeter.reset()
        source.close()
    }

    private fun getChunk(chunkIndex: Long, isPrefetch: Boolean): ByteArray {
        if (closed.get()) throw IOException("SMB stream session closed")

        synchronized(cacheLock) {
            chunkCache[chunkIndex]?.let { return it }
        }

        if (isPrefetch && abs(chunkIndex - latestRequestedChunk.get()) > prefetchAheadChunks + 2) {
            throw CancellationException("Stale prefetch skipped for chunk $chunkIndex")
        }

        inFlight[chunkIndex]?.let { existing ->
            return awaitFuture(existing)
        }

        val created = CompletableFuture<ByteArray>()
        val raced = inFlight.putIfAbsent(chunkIndex, created)
        if (raced != null) {
            return awaitFuture(raced)
        }

        try {
            synchronized(cacheLock) {
                chunkCache[chunkIndex]?.let { cached ->
                    created.complete(cached)
                    return cached
                }
            }
            if (isPrefetch && abs(chunkIndex - latestRequestedChunk.get()) > prefetchAheadChunks + 2) {
                val cancelEx = CancellationException("Stale prefetch skipped for chunk $chunkIndex")
                created.completeExceptionally(cancelEx)
                throw cancelEx
            }

            val fileSize = totalSize
            val chunkStart = chunkIndex * chunkSize.toLong()
            if (chunkStart >= fileSize) {
                val empty = ByteArray(0)
                created.complete(empty)
                return empty
            }
            val expectedLen = minOf(chunkSize.toLong(), fileSize - chunkStart).toInt()
            val loaded = source.readChunk(chunkStart, expectedLen)
            if (!closed.get() && loaded.isNotEmpty()) {
                synchronized(cacheLock) {
                    chunkCache[chunkIndex] = loaded
                }
                speedMeter.record(loaded.size.toLong())
            }
            created.complete(loaded)
            return loaded
        } catch (t: Throwable) {
            created.completeExceptionally(t)
            if (t is IOException) throw t
            if (t is CancellationException) throw t
            throw IOException(t.message ?: "SMB stream chunk load failed", t)
        } finally {
            inFlight.remove(chunkIndex, created)
        }
    }

    private fun awaitFuture(future: CompletableFuture<ByteArray>): ByteArray {
        return try {
            future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause
            when (cause) {
                is IOException -> throw cause
                is CancellationException -> throw cause
                else -> throw IOException(cause?.message ?: "SMB chunk read failed", cause)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted waiting for SMB chunk", e)
        }
    }

    private fun schedulePrefetch(firstAheadChunk: Long, fileSize: Long) {
        if (closed.get() || prefetchAheadChunks <= 0) return
        val maxChunkIndex = ((fileSize - 1L).coerceAtLeast(0L)) / chunkSize
        for (delta in 0 until prefetchAheadChunks) {
            val targetChunk = firstAheadChunk + delta
            if (targetChunk > maxChunkIndex) break
            val alreadyCached = synchronized(cacheLock) { chunkCache.containsKey(targetChunk) }
            if (alreadyCached || inFlight.containsKey(targetChunk)) continue
            try {
                prefetchExecutor.execute {
                    if (closed.get()) return@execute
                    try {
                        getChunk(targetChunk, isPrefetch = true)
                    } catch (_: CancellationException) {
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private class SessionInputStream(
        private val session: SmbBufferedStreamSession,
        startOffset: Long,
        private var remaining: Long
    ) : InputStream() {
        private var currentOffset = startOffset
        private val singleByte = ByteArray(1)
        @Volatile
        private var streamClosed = false

        override fun read(): Int {
            if (streamClosed || remaining <= 0L) return -1
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (streamClosed) return -1
            if (len == 0) return 0
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = session.readAt(currentOffset, b, off, toRead)
            if (n <= 0) {
                remaining = 0L
                return -1
            }
            currentOffset += n
            remaining -= n
            return n
        }

        override fun close() {
            // 仅标记当前 HTTP 响应流结束，保留共享会话和预读缓存以便 Seek 复用
            streamClosed = true
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 512 * 1024
        const val DEFAULT_MAX_CACHED_CHUNKS = 16
        const val DEFAULT_PREFETCH_AHEAD_CHUNKS = 3
    }
}

data class SmbStreamTarget(
    val smbUrl: String,
    val contextProvider: () -> CIFSContext,
    val onTransportFailure: () -> Unit,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class SmbPreparedStream(
    val token: String,
    val directUri: String,
    val httpUrl: String,
    val mimeType: String
)

/**
 * 全局 SMB 流媒体会话注册中心，统一服务 Media3 直连流与本地 HTTP Range 代理。
 */
object SmbStreamRegistry {

    private const val MAX_ACTIVE_TARGETS = 24
    private const val IDLE_SMB_HANDLE_MS = 45_000L
    private const val EXPIRED_SESSION_MS = 30 * 60_000L

    private class RegisteredEntry(
        val token: String,
        val target: SmbStreamTarget,
        private val prefetchExecutor: ExecutorService
    ) {
        @Volatile
        private var sessionRef: SmbBufferedStreamSession? = null

        @Volatile
        var lastTouchedAt: Long = System.currentTimeMillis()

        fun getOrCreateSession(): SmbBufferedStreamSession {
            lastTouchedAt = System.currentTimeMillis()
            sessionRef?.let { return it }
            synchronized(this) {
                sessionRef?.let { return it }
                val source = JcifsSmbChunkSource(
                    smbUrl = target.smbUrl,
                    contextProvider = target.contextProvider,
                    initialSize = target.fileSize,
                    onTransportFailure = target.onTransportFailure
                )
                return SmbBufferedStreamSession(
                    source = source,
                    prefetchExecutor = prefetchExecutor
                ).also { sessionRef = it }
            }
        }

        fun currentSpeedBytesPerSec(): Long {
            return sessionRef?.currentSpeedBytesPerSec() ?: 0L
        }

        fun closeIdleIfNeeded(nowMs: Long) {
            sessionRef?.closeIdleHandles(IDLE_SMB_HANDLE_MS, nowMs)
        }

        fun release() {
            sessionRef?.close()
            sessionRef = null
        }
    }

    private val entries = ConcurrentHashMap<String, RegisteredEntry>()
    private val prefetchCounter = AtomicInteger(1)
    private val prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "smb-prefetch-${prefetchCounter.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    private val maintenanceScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "smb-stream-janitor").apply { isDaemon = true }
    }

    init {
        maintenanceScheduler.scheduleWithFixedDelay({
            try {
                cleanupIdleAndExpired()
            } catch (_: Exception) {
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    fun register(target: SmbStreamTarget): String {
        val now = System.currentTimeMillis()
        // 清理过旧或超限的会话
        if (entries.size >= MAX_ACTIVE_TARGETS) {
            val oldestKey = entries.entries.minByOrNull { it.value.lastTouchedAt }?.key
            if (oldestKey != null) {
                entries.remove(oldestKey)?.release()
            }
        }
        val token = UUID.randomUUID().toString()
        entries[token] = RegisteredEntry(token, target, prefetchExecutor)
        cleanupIdleAndExpired(now)
        return token
    }

    fun buildDirectUri(token: String, fileName: String): String {
        return "smbstream://smb/stream/$token/${Uri.encode(fileName)}"
    }

    internal fun getTarget(token: String): SmbStreamTarget? = entries[token]?.target

    internal fun getSession(token: String): SmbBufferedStreamSession? {
        return entries[token]?.getOrCreateSession()
    }

    fun getStreamSpeedBytesPerSec(token: String): Long {
        return entries[token]?.currentSpeedBytesPerSec() ?: 0L
    }

    fun release(token: String) {
        entries.remove(token)?.release()
    }

    private fun cleanupIdleAndExpired(nowMs: Long = System.currentTimeMillis()) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowMs - entry.lastTouchedAt >= EXPIRED_SESSION_MS) {
                iterator.remove()
                entry.release()
            } else {
                entry.closeIdleIfNeeded(nowMs)
            }
        }
    }
}

/**
 * Media3 ExoPlayer 直连 SMB 数据源：绕过本地 HTTP 代理与小块拷贝限制，直接对接分块缓存预读引擎。
 */
@OptIn(UnstableApi::class)
class SmbExoDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var activeSession: SmbBufferedStreamSession? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        transferInitializing(dataSpec)

        val token = extractToken(uri)
            ?: throw DataSourceException("Invalid smbstream URI: $uri", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val session = SmbStreamRegistry.getSession(token)
            ?: throw DataSourceException("SMB stream session expired", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val totalSize = try {
            session.totalSize
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }

        if (totalSize < 0L || dataSpec.position > totalSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        activeSession = session
        readPosition = dataSpec.position
        val available = totalSize - dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available)
        } else {
            available
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val session = activeSession ?: return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        val readBytes = session.readAt(readPosition, buffer, offset, toRead)
        if (readBytes <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        readPosition += readBytes
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= readBytes
        }
        bytesTransferred(readBytes)
        return readBytes
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        activeSession = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        const val SCHEME = "smbstream"

        internal fun extractToken(uri: Uri): String? {
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            val segments = uri.pathSegments
            if (segments.size < 2 || segments[0] != "stream") return null
            return segments[1].takeIf { it.isNotBlank() }
        }
    }
}

/**
 * 统一媒体数据源工厂：smbstream:// 走直连高速引擎，其余 URI 委托 Media3 DefaultDataSource。
 */
@OptIn(UnstableApi::class)
class QMediaDataSourceFactory(context: Context) : DataSource.Factory {
    private val defaultFactory = DefaultDataSource.Factory(context.applicationContext)

    override fun createDataSource(): DataSource {
        val delegate = defaultFactory.createDataSource()
        val smbSource = SmbExoDataSource()
        return object : DataSource {
            private var active: DataSource? = null

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                delegate.addTransferListener(transferListener)
                smbSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val chosen = if (SmbExoDataSource.SCHEME.equals(dataSpec.uri.scheme, ignoreCase = true)) {
                    smbSource
                } else {
                    delegate
                }
                active = chosen
                return chosen.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            }

            override fun getUri(): Uri? = active?.uri

            override fun getResponseHeaders(): MutableMap<String, MutableList<String>> {
                return active?.responseHeaders ?: mutableMapOf()
            }

            override fun close() {
                try {
                    active?.close()
                } finally {
                    active = null
                }
            }
        }
    }
}
