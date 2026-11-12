package com.gemini.filemanager.core.smb

import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbProtocolMode
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SmbUnitTests {

    @Test
    fun `buildServerBaseUrl and buildEntryUrl format standard and custom ports cleanly`() {
        val defaultPortConfig = SmbConnectionConfig(
            id = "1",
            host = "smb://192.168.1.50/",
            port = 445,
            shareName = ""
        )
        assertEquals("smb://192.168.1.50/", SmbClientManager.buildServerBaseUrl(defaultPortConfig))
        assertEquals("smb://192.168.1.50/", SmbClientManager.buildEntryUrl(defaultPortConfig))
        assertTrue(SmbClientManager.isServerRootUrl(defaultPortConfig, "smb://192.168.1.50/"))
        assertFalse(SmbClientManager.isServerRootUrl(defaultPortConfig, "smb://192.168.1.50/Movies/"))

        val customPortAndShare = SmbConnectionConfig(
            id = "2",
            host = "\\\\192.168.1.88\\",
            port = 1445,
            shareName = "/Media/"
        )
        assertEquals("smb://192.168.1.88:1445/", SmbClientManager.buildServerBaseUrl(customPortAndShare))
        assertEquals("smb://192.168.1.88:1445/Media/", SmbClientManager.buildEntryUrl(customPortAndShare))
    }

    @Test
    fun `parseByteRange handles standard, open-ended, suffix, and invalid ranges`() {
        assertEquals(0L to 999L, SmbHttpStreamServer.parseByteRange("0-999", 5000L))
        assertEquals(500L to 4999L, SmbHttpStreamServer.parseByteRange("500-", 5000L))
        assertEquals(0L to 4999L, SmbHttpStreamServer.parseByteRange("0-99999", 5000L))
        assertEquals(4800L to 4999L, SmbHttpStreamServer.parseByteRange("-200", 5000L))
        assertEquals(0L to 99L, SmbHttpStreamServer.parseByteRange("-500", 100L))

        assertNull(SmbHttpStreamServer.parseByteRange("5000-", 5000L))
        assertNull(SmbHttpStreamServer.parseByteRange("300-200", 5000L))
        assertNull(SmbHttpStreamServer.parseByteRange("-0", 5000L))
        assertNull(SmbHttpStreamServer.parseByteRange("invalid", 5000L))
        assertNull(SmbHttpStreamServer.parseByteRange("0-10", 0L))
    }

    @Test
    fun `formatSmbError returns actionable Chinese messages`() {
        val cfg = SmbConnectionConfig(id = "1", host = "192.168.1.10", port = 445)
        assertTrue(SmbClientManager.formatSmbError(cfg, ConnectException("refused")).contains("无法连接"))
        assertTrue(SmbClientManager.formatSmbError(cfg, SocketTimeoutException("timeout")).contains("连接超时"))
        assertTrue(SmbClientManager.formatSmbError(cfg, UnknownHostException("nas")).contains("无法解析主机"))
        assertTrue(
            SmbClientManager.formatSmbError(cfg, RuntimeException("Logon failure: unknown user"))
                .contains("认证失败")
        )
    }

    @Test
    fun `SmbConnectionConfig serialization round trip preserves protocol mode and fields`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = SmbConnectionConfig(
            id = "id-123",
            displayName = "家庭 NAS",
            host = "192.168.50.2",
            port = 445,
            shareName = "Movies",
            domain = "WORKGROUP",
            username = "admin",
            password = "secret",
            isGuest = false,
            protocolMode = SmbProtocolMode.SMB2_3,
            showHiddenShares = true,
            lastConnectedAt = 1700000000L
        )
        val encoded = json.encodeToString(listOf(original))
        val decoded = json.decodeFromString<List<SmbConnectionConfig>>(encoded)
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `SmbTransferTask checkpoint serialization preserves resume metadata and flags`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val pausedDownload = SmbTransferTask(
            id = "task-1",
            configId = "cfg-1",
            fileName = "movie.mkv",
            remoteUrl = "smb://192.168.1.10/Media/movie.mkv",
            localPath = "/storage/emulated/0/Download/SMB/movie.mkv",
            localTempPath = "/storage/emulated/0/Download/SMB/movie.mkv.task-1.smbpart",
            targetLocalDir = "/storage/emulated/0/Download/SMB",
            size = 1024L * 1024L * 500L,
            remoteLastModified = 1700001234L,
            bytesTransferred = 1024L * 1024L * 200L,
            progress = 0.4f,
            direction = SmbTransferDirection.DOWNLOAD,
            status = SmbTransferStatus.PAUSED
        )
        assertTrue(pausedDownload.canResume)
        assertFalse(pausedDownload.isActive)

        val uploading = pausedDownload.copy(
            id = "task-2",
            direction = SmbTransferDirection.UPLOAD,
            status = SmbTransferStatus.TRANSFERRING,
            remoteDirUrl = "smb://192.168.1.10/Media/",
            remoteTempUrl = "smb://192.168.1.10/Media/movie.mkv.task-2.smbpart"
        )
        assertFalse(uploading.canResume)
        assertTrue(uploading.isActive)

        val encoded = json.encodeToString(listOf(pausedDownload, uploading))
        val decoded = json.decodeFromString<List<SmbTransferTask>>(encoded)
        assertEquals(listOf(pausedDownload, uploading), decoded)
    }

    @Test
    fun `SmbBufferedStreamSession reads sequentially across chunks and caches LRU entries`() {
        val data = ByteArray(100) { idx -> (idx % 127).toByte() }
        val chunkReads = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val source = object : SmbChunkSource {
                override val totalSize: Long = data.size.toLong()
                override fun readChunk(offset: Long, length: Int): ByteArray {
                    chunkReads.incrementAndGet()
                    val end = minOf(data.size, offset.toInt() + length)
                    return data.copyOfRange(offset.toInt(), end)
                }
                override fun close() {}
            }
            // chunkSize=32, maxCachedChunks=2, 无预读以精确验证缓存命中与淘汰
            val session = SmbBufferedStreamSession(
                source = source,
                chunkSize = 32,
                maxCachedChunks = 2,
                prefetchAheadChunks = 0,
                prefetchExecutor = executor
            )

            val out = ByteArray(50)
            var totalRead = 0
            while (totalRead < out.size) {
                val n = session.readAt(totalRead.toLong(), out, totalRead, out.size - totalRead)
                assertTrue(n > 0)
                totalRead += n
            }
            assertArrayEquals(data.copyOfRange(0, 50), out)
            assertEquals(2, chunkReads.get())

            // 再次读取 chunk 0 和 chunk 1，应全部命中缓存不增加底层读次数
            val hitBuf = ByteArray(16)
            assertEquals(16, session.readAt(10L, hitBuf, 0, 16))
            assertArrayEquals(data.copyOfRange(10, 26), hitBuf)
            assertEquals(2, chunkReads.get())

            // 访问越界偏移与 EOF 边界
            assertEquals(-1, session.readAt(100L, hitBuf, 0, 16))
            assertEquals(-1, session.readAt(-1L, hitBuf, 0, 16))
            assertEquals(0, session.readAt(0L, hitBuf, 0, 0))

            // 连续读取 chunk 2 和 chunk 3，LRU 容量=2 应淘汰更早的 chunk，之后重读 chunk 0 触发重新拉取
            val tailBuf = ByteArray(4)
            assertEquals(4, session.readAt(64L, tailBuf, 0, 4)) // chunk 2
            assertEquals(4, session.readAt(96L, tailBuf, 0, 4)) // chunk 3
            assertEquals(4, chunkReads.get())

            assertEquals(4, session.readAt(0L, tailBuf, 0, 4)) // chunk 0 已被淘汰
            assertEquals(5, chunkReads.get())
            session.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `SmbBufferedStreamSession coalesces concurrent requests for the same chunk`() {
        val data = ByteArray(64) { idx -> (idx + 7).toByte() }
        val readCalls = AtomicInteger(0)
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val prefetchExecutor = Executors.newSingleThreadExecutor()
        val callerPool = Executors.newFixedThreadPool(4)
        try {
            val source = object : SmbChunkSource {
                override val totalSize: Long = data.size.toLong()
                override fun readChunk(offset: Long, length: Int): ByteArray {
                    readCalls.incrementAndGet()
                    enteredRead.countDown()
                    releaseRead.await(3, TimeUnit.SECONDS)
                    val end = minOf(data.size, offset.toInt() + length)
                    return data.copyOfRange(offset.toInt(), end)
                }
                override fun close() {}
            }
            val session = SmbBufferedStreamSession(
                source = source,
                chunkSize = 32,
                maxCachedChunks = 4,
                prefetchAheadChunks = 0,
                prefetchExecutor = prefetchExecutor
            )

            val futures = (0 until 4).map {
                callerPool.submit(Callable {
                    val buf = ByteArray(32)
                    val n = session.readAt(0L, buf, 0, 32)
                    n to buf
                })
            }
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
            Thread.sleep(60)
            releaseRead.countDown()

            futures.forEach { future ->
                val (n, buf) = future.get(3, TimeUnit.SECONDS)
                assertEquals(32, n)
                assertArrayEquals(data.copyOfRange(0, 32), buf)
            }
            assertEquals(1, readCalls.get())
            session.close()
        } finally {
            callerPool.shutdownNow()
            prefetchExecutor.shutdownNow()
        }
    }

    @Test
    fun `SmbBufferedStreamSession prefetches upcoming chunks asynchronously and serves Range streams`() {
        val data = ByteArray(160) { idx -> ((idx * 3) % 101).toByte() }
        val requestedOffsets = ConcurrentHashMap.newKeySet<Long>()
        val prefetchDone = CountDownLatch(3) // chunk 0 + prefetch chunk 1, chunk 2
        val prefetchExecutor = Executors.newFixedThreadPool(2)
        try {
            val source = object : SmbChunkSource {
                override val totalSize: Long = data.size.toLong()
                override fun readChunk(offset: Long, length: Int): ByteArray {
                    requestedOffsets.add(offset)
                    val end = minOf(data.size, offset.toInt() + length)
                    prefetchDone.countDown()
                    return data.copyOfRange(offset.toInt(), end)
                }
                override fun close() {}
            }
            val session = SmbBufferedStreamSession(
                source = source,
                chunkSize = 32,
                maxCachedChunks = 8,
                prefetchAheadChunks = 2,
                prefetchExecutor = prefetchExecutor
            )

            val first = ByteArray(10)
            assertEquals(10, session.readAt(0L, first, 0, 10))
            assertTrue(prefetchDone.await(2, TimeUnit.SECONDS))
            assertTrue(requestedOffsets.contains(0L))
            assertTrue(requestedOffsets.contains(32L))
            assertTrue(requestedOffsets.contains(64L))

            // 通过 SessionInputStream 读取指定 Range (40..119, 共80字节)
            session.openInputStream(startOffset = 40L, contentLength = 80L).use { input ->
                val single = input.read()
                assertEquals(data[40].toInt() and 0xFF, single)
                val rest = ByteArray(128)
                var pos = 0
                while (true) {
                    val r = input.read(rest, pos, rest.size - pos)
                    if (r < 0) break
                    pos += r
                }
                assertEquals(79, pos)
                assertArrayEquals(data.copyOfRange(41, 120), rest.copyOfRange(0, 79))
                assertEquals(-1, input.read())
            }
            session.close()
        } finally {
            prefetchExecutor.shutdownNow()
        }
    }

    @Test
    fun `SlidingSpeedMeter measures active throughput and clears stale samples`() {
        val meter = SlidingSpeedMeter(windowMs = 1500L, staleTimeoutMs = 2000L)
        assertEquals(0L, meter.getBytesPerSecond(1000L))

        meter.record(1024L * 1024L, nowMs = 1000L)
        meter.record(1024L * 1024L, nowMs = 2000L)
        // 2MB over 1000ms = 2 MB/s
        assertEquals(2L * 1024L * 1024L, meter.getBytesPerSecond(2000L))

        // 超出 staleTimeoutMs 应归零
        assertEquals(0L, meter.getBytesPerSecond(5000L))

        meter.record(500_000L, nowMs = 6000L)
        meter.reset()
        assertEquals(0L, meter.getBytesPerSecond(6100L))
    }

    @Test
    fun `SmbPlaybackStore validates resume positions by duration and progress thresholds`() {
        // 太短的媒体 (<15s) 或刚开头 (<5s) 不保存续播
        assertFalse(SmbPlaybackStore.isValidResumeRecord(positionMs = 6_000L, durationMs = 10_000L))
        assertFalse(SmbPlaybackStore.isValidResumeRecord(positionMs = 3_000L, durationMs = 120_000L))
        assertFalse(SmbPlaybackStore.isValidResumeRecord(positionMs = 10_000L, durationMs = 0L))

        // 正常观看中段允许续播
        assertTrue(SmbPlaybackStore.isValidResumeRecord(positionMs = 45_000L, durationMs = 120_000L))

        // 接近片尾 (>=95% 或剩余不足 10s) 清除断点，视为已看完
        assertFalse(SmbPlaybackStore.isValidResumeRecord(positionMs = 115_000L, durationMs = 120_000L))
        assertFalse(SmbPlaybackStore.isValidResumeRecord(positionMs = 591_000L, durationMs = 600_000L))
        assertTrue(SmbPlaybackStore.isValidResumeRecord(positionMs = 560_000L, durationMs = 600_000L))
    }

    @Test
    fun `findMatchingSubtitles prioritizes exact stem over language tags and ignores unrelated files`() {
        val video = SmbRemoteFile(
            name = "Inception.2010.1080p.mkv",
            smbUrl = "smb://nas/Movies/Inception.2010.1080p.mkv",
            isDirectory = false,
            extension = "mkv",
            category = FileCategory.VIDEO
        )
        val folderFiles = listOf(
            video,
            SmbRemoteFile(
                name = "Inception.2010.1080p.zh-cn.ass",
                smbUrl = "smb://nas/Movies/Inception.2010.1080p.zh-cn.ass",
                isDirectory = false,
                extension = ""
            ),
            SmbRemoteFile(
                name = "Inception.2010.1080p.srt",
                smbUrl = "smb://nas/Movies/Inception.2010.1080p.srt",
                isDirectory = false,
                extension = "srt"
            ),
            SmbRemoteFile(
                name = "Inception.2010.1080p_Commentary.vtt",
                smbUrl = "smb://nas/Movies/Inception.2010.1080p_Commentary.vtt",
                isDirectory = false,
                extension = "vtt"
            ),
            SmbRemoteFile(
                name = "Inception.2010.1080p.srt",
                smbUrl = "smb://nas/Movies/subs/",
                isDirectory = true,
                extension = "srt"
            ),
            SmbRemoteFile(
                name = "Interstellar.2014.srt",
                smbUrl = "smb://nas/Movies/Interstellar.2014.srt",
                isDirectory = false,
                extension = "srt"
            ),
            SmbRemoteFile(
                name = "Inception.2010.1080p.nfo",
                smbUrl = "smb://nas/Movies/Inception.2010.1080p.nfo",
                isDirectory = false,
                extension = "nfo"
            )
        )

        val matched = SmbClientManager.findMatchingSubtitles(video, folderFiles)
        assertEquals(3, matched.size)
        assertEquals("Inception.2010.1080p.srt", matched[0].name)
        assertTrue(matched.any { it.name == "Inception.2010.1080p.zh-cn.ass" })
        assertTrue(matched.any { it.name == "Inception.2010.1080p_Commentary.vtt" })
        assertFalse(matched.any { it.name == "Interstellar.2014.srt" })
        assertFalse(matched.any { it.isDirectory })
    }

    @Test
    fun `resolveMimeType maps video audio and subtitle formats accurately`() {
        assertEquals("video/x-matroska", SmbClientManager.resolveMimeType("demo.MKV"))
        assertEquals("video/mp2t", SmbClientManager.resolveMimeType("clip.m2ts"))
        assertEquals("application/vnd.rn-realmedia-vbr", SmbClientManager.resolveMimeType("old.rmvb"))
        assertEquals("audio/ogg", SmbClientManager.resolveMimeType("track.opus"))
        assertEquals("audio/alac", SmbClientManager.resolveMimeType("lossless.alac"))
        assertEquals("application/x-subrip", SmbClientManager.resolveMimeType("sub.srt"))
        assertEquals("text/x-ssa", SmbClientManager.resolveMimeType("sub.ass"))
        assertEquals("text/vtt", SmbClientManager.resolveMimeType("sub.vtt"))
    }
}

