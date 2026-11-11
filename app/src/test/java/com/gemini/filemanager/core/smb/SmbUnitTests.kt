package com.gemini.filemanager.core.smb

import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbProtocolMode
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
}

