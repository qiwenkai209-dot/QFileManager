package com.gemini.filemanager.core.smb

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.OpenableColumns
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.SmbConstants
import org.codelibs.jcifs.smb.SmbRandomAccess
import org.codelibs.jcifs.smb.config.PropertyConfiguration
import org.codelibs.jcifs.smb.context.BaseContext
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator
import org.codelibs.jcifs.smb.impl.SmbAuthException
import org.codelibs.jcifs.smb.impl.SmbException
import org.codelibs.jcifs.smb.impl.SmbFile
import org.codelibs.jcifs.smb.impl.SmbFileInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.Security
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

sealed class LocalUploadSource {
    data class FileSource(val file: File) : LocalUploadSource()
    data class UriSource(val uri: Uri, val name: String, val size: Long) : LocalUploadSource()
}

data class SmbDownloadFileSpec(
    val remoteFile: SmbRemoteFile,
    val targetLocalDir: File
)

object SmbClientManager {

    private const val TRANSFER_BUFFER_SIZE = 256 * 1024
    private const val PREVIEW_BUFFER_SIZE = 128 * 1024
    private const val PROGRESS_NOTIFY_INTERVAL_MS = 180L

    @Volatile
    private var securityInitialized = false

    private data class CachedContext(
        val signature: String,
        val context: CIFSContext
    )

    private val contextCache = ConcurrentHashMap<String, CachedContext>()

    private fun ensureSecurityProvider() {
        if (securityInitialized) return
        synchronized(this) {
            if (securityInitialized) return
            try {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
            } catch (_: Exception) {
            }
            securityInitialized = true
        }
    }

    private fun configSignature(config: SmbConnectionConfig): String {
        return "${config.host}|${config.port}|${config.domain}|${config.username}|${config.password}|${config.isGuest}|${config.protocolMode.name}"
    }

    fun invalidateContext(configId: String) {
        val removed = contextCache.remove(configId)
        if (removed != null) {
            try {
                removed.context.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun createBaseContext(config: SmbConnectionConfig): BaseContext {
        ensureSecurityProvider()
        val props = Properties().apply {
            setProperty("jcifs.client.minVersion", config.protocolMode.minVersion)
            setProperty("jcifs.client.maxVersion", config.protocolMode.maxVersion)
            setProperty("jcifs.client.connTimeout", "8000")
            setProperty("jcifs.client.soTimeout", "20000")
            setProperty("jcifs.client.responseTimeout", "20000")
            setProperty("jcifs.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS,BCAST")
            if (config.isGuest) {
                setProperty("jcifs.client.allowGuestFallback", "true")
            }
        }
        return BaseContext(PropertyConfiguration(props))
    }

    fun getContext(config: SmbConnectionConfig, forceAnonymous: Boolean = false): CIFSContext {
        val sig = configSignature(config) + "|anon=$forceAnonymous"
        val cached = contextCache[config.id]
        if (cached != null && cached.signature == sig) {
            return cached.context
        }
        if (cached != null) {
            try {
                cached.context.close()
            } catch (_: Exception) {
            }
        }

        val baseContext = createBaseContext(config)
        val ctx = when {
            forceAnonymous -> baseContext.withAnonymousCredentials()
            config.isGuest -> baseContext.withGuestCredentials()
            config.username.isBlank() && config.password.isBlank() -> baseContext.withAnonymousCredentials()
            else -> baseContext.withCredentials(
                NtlmPasswordAuthenticator(
                    config.domain.trim().ifBlank { null },
                    config.username.trim(),
                    config.password
                )
            )
        }
        contextCache[config.id] = CachedContext(sig, ctx)
        return ctx
    }

    fun buildServerBaseUrl(config: SmbConnectionConfig): String {
        val cleanHost = config.host.trim()
            .removePrefix("smb://")
            .removePrefix("\\\\")
            .trim('/', '\\')
        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        val portPart = if (config.port in 1..65535 && config.port != 445) ":${config.port}" else ""
        return "smb://$formattedHost$portPart/"
    }

    fun buildEntryUrl(config: SmbConnectionConfig): String {
        val base = buildServerBaseUrl(config)
        val share = config.shareName.trim('/', '\\')
        return if (share.isBlank()) base else "$base$share/"
    }

    fun isServerRootUrl(config: SmbConnectionConfig, url: String): Boolean {
        val base = buildServerBaseUrl(config).trimEnd('/')
        val current = url.trimEnd('/')
        return base.equals(current, ignoreCase = true)
    }

    suspend fun testConnection(config: SmbConnectionConfig): Result<String> = withContext(Dispatchers.IO) {
        invalidateContext(config.id)
        val entryUrl = buildEntryUrl(config)
        listDirectory(config, entryUrl).map { list ->
            if (config.shareName.isNotBlank()) {
                "连接成功（共享目录「${config.shareName}」内共 ${list.size} 项）"
            } else {
                "连接成功（发现 ${list.size} 个共享目录）"
            }
        }
    }

    suspend fun listDirectory(
        config: SmbConnectionConfig,
        targetUrl: String
    ): Result<List<SmbRemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = if (targetUrl.endsWith("/")) targetUrl else "$targetUrl/"
            val list = try {
                listDirectoryInternal(config, normalizedUrl, forceAnonymous = false)
            } catch (authEx: SmbAuthException) {
                if (config.isGuest) {
                    listDirectoryInternal(config, normalizedUrl, forceAnonymous = true)
                } else {
                    throw authEx
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            if (isTransportFailure(e)) {
                invalidateContext(config.id)
            }
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    private fun listDirectoryInternal(
        config: SmbConnectionConfig,
        normalizedUrl: String,
        forceAnonymous: Boolean
    ): List<SmbRemoteFile> {
        val ctx = getContext(config, forceAnonymous = forceAnonymous)
        val isRoot = isServerRootUrl(config, normalizedUrl)
        val dir = SmbFile(normalizedUrl, ctx)
        try {
            val children = dir.listFiles() ?: emptyArray()
            val results = mutableListOf<SmbRemoteFile>()
            for (child in children) {
                try {
                    val rawName = child.name.trimEnd('/')
                    if (rawName.isBlank() || rawName == "." || rawName == "..") continue

                    val type = try {
                        child.type
                    } catch (_: Exception) {
                        SmbConstants.TYPE_FILESYSTEM
                    }

                    if (type == SmbConstants.TYPE_NAMED_PIPE ||
                        type == SmbConstants.TYPE_PRINTER ||
                        type == SmbConstants.TYPE_COMM
                    ) {
                        continue
                    }

                    val isShareRoot = isRoot || type == SmbConstants.TYPE_SHARE
                    if (isShareRoot && !config.showHiddenShares && rawName.endsWith("$")) {
                        continue
                    }

                    val isDir = isShareRoot || try {
                        child.isDirectory
                    } catch (_: Exception) {
                        child.canonicalPath.endsWith("/")
                    }

                    val size = if (isDir) 0L else try {
                        child.length()
                    } catch (_: Exception) {
                        0L
                    }

                    val lastMod = if (isShareRoot) 0L else try {
                        child.lastModified()
                    } catch (_: Exception) {
                        0L
                    }

                    val ext = if (isDir) "" else rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val category = if (isDir) FileCategory.DOCUMENT else FileUtils.getCategory(ext)
                    val shareName = try {
                        child.share ?: ""
                    } catch (_: Exception) {
                        if (isShareRoot) rawName else ""
                    }
                    val canonical = child.canonicalPath.let {
                        if (isDir && !it.endsWith("/")) "$it/" else it
                    }

                    results.add(
                        SmbRemoteFile(
                            name = rawName,
                            smbUrl = canonical,
                            shareName = shareName,
                            isDirectory = isDir,
                            isShareRoot = isShareRoot,
                            size = size,
                            lastModified = lastMod,
                            extension = ext,
                            category = category
                        )
                    )
                } finally {
                    try {
                        child.close()
                    } catch (_: Exception) {
                    }
                }
            }
            return results
        } finally {
            try {
                dir.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun createDirectory(
        config: SmbConnectionConfig,
        parentDirUrl: String,
        folderName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var target: SmbFile? = null
        try {
            val cleanName = folderName.trim().trim('/', '\\')
            if (cleanName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("文件夹名称不能为空"))
            }
            val ctx = getContext(config)
            val parent = SmbFile(if (parentDirUrl.endsWith("/")) parentDirUrl else "$parentDirUrl/", ctx)
            try {
                target = SmbFile(parent, "$cleanName/")
                if (target.exists()) {
                    return@withContext Result.failure(IllegalStateException("同名文件夹已存在"))
                }
                target.mkdir()
                Result.success(Unit)
            } finally {
                try {
                    parent.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(formatSmbError(config, e), e))
        } finally {
            try {
                target?.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun renameRemoteFile(
        config: SmbConnectionConfig,
        item: SmbRemoteFile,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (item.isShareRoot) {
            return@withContext Result.failure(IllegalArgumentException("不支持重命名共享根目录"))
        }
        var source: SmbFile? = null
        var target: SmbFile? = null
        try {
            val cleanName = newName.trim().trim('/', '\\')
            if (cleanName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("新名称不能为空"))
            }
            val ctx = getContext(config)
            source = SmbFile(item.smbUrl, ctx)
            val parentUrl = source.parent
            val parent = SmbFile(parentUrl, ctx)
            try {
                val targetName = if (item.isDirectory) "$cleanName/" else cleanName
                target = SmbFile(parent, targetName)
                if (target.exists()) {
                    return@withContext Result.failure(IllegalStateException("目标名称已存在"))
                }
                source.renameTo(target)
                Result.success(Unit)
            } finally {
                try {
                    parent.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception(formatSmbError(config, e), e))
        } finally {
            try { source?.close() } catch (_: Exception) {}
            try { target?.close() } catch (_: Exception) {}
        }
    }

    suspend fun deleteRemoteItems(
        config: SmbConnectionConfig,
        items: List<SmbRemoteFile>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val ctx = getContext(config)
            var deleted = 0
            for (item in items) {
                if (item.isShareRoot) continue
                val file = SmbFile(item.smbUrl, ctx)
                try {
                    deleteRecursively(file)
                    deleted++
                } finally {
                    try {
                        file.close()
                    } catch (_: Exception) {
                    }
                }
            }
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    fun deleteRemotePathSilent(config: SmbConnectionConfig, remoteUrl: String) {
        if (remoteUrl.isBlank()) return
        try {
            val ctx = getContext(config)
            val file = SmbFile(remoteUrl, ctx)
            try {
                if (file.exists()) {
                    file.delete()
                }
            } finally {
                try { file.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursively(target: SmbFile) {
        if (target.isDirectory) {
            val children = target.listFiles() ?: emptyArray()
            for (child in children) {
                try {
                    deleteRecursively(child)
                } finally {
                    try {
                        child.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        target.delete()
    }

    suspend fun copyOrMoveRemoteItems(
        config: SmbConnectionConfig,
        items: List<SmbRemoteFile>,
        destDirUrl: String,
        isMove: Boolean
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val ctx = getContext(config)
            val normalizedDest = if (destDirUrl.endsWith("/")) destDirUrl else "$destDirUrl/"
            val destDir = SmbFile(normalizedDest, ctx)
            var count = 0
            try {
                for (item in items) {
                    if (item.isShareRoot) continue
                    val src = SmbFile(item.smbUrl, ctx)
                    var dest: SmbFile? = null
                    try {
                        val suffix = if (item.isDirectory) "${item.name}/" else item.name
                        dest = SmbFile(destDir, suffix)
                        if (dest.canonicalPath.equals(src.canonicalPath, ignoreCase = true)) {
                            continue
                        }
                        if (item.isDirectory && dest.canonicalPath.startsWith(src.canonicalPath, ignoreCase = true)) {
                            continue
                        }
                        if (dest.exists()) {
                            val altName = if (item.isDirectory) {
                                "${item.name}_${System.currentTimeMillis()}/"
                            } else {
                                val base = item.name.substringBeforeLast('.', item.name)
                                val ext = item.name.substringAfterLast('.', "")
                                val extStr = if (ext.isNotEmpty() && item.name.contains('.')) ".$ext" else ""
                                "${base}_${System.currentTimeMillis()}$extStr"
                            }
                            dest.close()
                            dest = SmbFile(destDir, altName)
                        }

                        val sameShare = src.share.equals(dest.share, ignoreCase = true)
                        if (isMove && sameShare) {
                            src.renameTo(dest)
                        } else {
                            src.copyTo(dest)
                            if (isMove) {
                                deleteRecursively(src)
                            }
                        }
                        count++
                    } finally {
                        try { src.close() } catch (_: Exception) {}
                        try { dest?.close() } catch (_: Exception) {}
                    }
                }
            } finally {
                try { destDir.close() } catch (_: Exception) {}
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    fun getStreamUrl(config: SmbConnectionConfig, remoteFile: SmbRemoteFile): String {
        val ctx = getContext(config)
        val mime = resolveMimeType(remoteFile.name)
        val server = SmbHttpStreamServer.getInstance()
        return server.registerTarget(
            SmbStreamTarget(
                smbUrl = remoteFile.smbUrl,
                context = ctx,
                fileName = remoteFile.name,
                fileSize = remoteFile.size,
                mimeType = mime
            )
        )
    }

    suspend fun downloadToCacheForPreview(
        config: SmbConnectionConfig,
        remoteFile: SmbRemoteFile,
        cacheRootDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val previewDir = File(cacheRootDir, "smb_preview")
            if (!previewDir.exists()) previewDir.mkdirs()
            val target = File(previewDir, "${remoteFile.smbUrl.hashCode()}_${remoteFile.name}")
            if (target.exists() && target.length() == remoteFile.size && remoteFile.size > 0L) {
                return@withContext Result.success(target)
            }
            val ctx = getContext(config)
            val smbFile = SmbFile(remoteFile.smbUrl, ctx)
            try {
                val total = if (remoteFile.size > 0L) remoteFile.size else smbFile.length()
                SmbFileInputStream(smbFile).use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(PREVIEW_BUFFER_SIZE)
                        var read: Int
                        var copied = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            copied += read
                            if (total > 0L) {
                                onProgress?.invoke((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }
                Result.success(target)
            } finally {
                try { smbFile.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            if (isTransportFailure(e)) invalidateContext(config.id)
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    /**
     * 展开包含文件夹的选择列表，为每个远端文件生成独立的可断点续传下载规格
     */
    suspend fun collectDownloadSpecs(
        config: SmbConnectionConfig,
        items: List<SmbRemoteFile>,
        baseLocalDir: File
    ): Result<List<SmbDownloadFileSpec>> = withContext(Dispatchers.IO) {
        try {
            val ctx = getContext(config)
            val specs = mutableListOf<SmbDownloadFileSpec>()
            for (item in items) {
                if (item.isShareRoot) continue
                if (!item.isDirectory) {
                    specs.add(SmbDownloadFileSpec(item, baseLocalDir))
                } else {
                    val smbDir = SmbFile(item.smbUrl, ctx)
                    try {
                        collectDirSpecsRecursively(smbDir, File(baseLocalDir, item.name), specs)
                    } finally {
                        try { smbDir.close() } catch (_: Exception) {}
                    }
                }
            }
            Result.success(specs)
        } catch (e: Exception) {
            if (isTransportFailure(e)) invalidateContext(config.id)
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    private fun collectDirSpecsRecursively(
        smbDir: SmbFile,
        localDir: File,
        out: MutableList<SmbDownloadFileSpec>
    ) {
        val children = try {
            smbDir.listFiles() ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        for (child in children) {
            try {
                val childName = child.name.trimEnd('/')
                if (childName.isBlank() || childName == "." || childName == "..") continue
                if (child.isDirectory) {
                    collectDirSpecsRecursively(child, File(localDir, childName), out)
                } else {
                    val size = try { child.length() } catch (_: Exception) { 0L }
                    val lastMod = try { child.lastModified() } catch (_: Exception) { 0L }
                    val ext = childName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    out.add(
                        SmbDownloadFileSpec(
                            remoteFile = SmbRemoteFile(
                                name = childName,
                                smbUrl = child.canonicalPath,
                                shareName = try { child.share ?: "" } catch (_: Exception) { "" },
                                isDirectory = false,
                                isShareRoot = false,
                                size = size,
                                lastModified = lastMod,
                                extension = ext,
                                category = FileUtils.getCategory(ext)
                            ),
                            targetLocalDir = localDir
                        )
                    )
                }
            } finally {
                try { child.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 支持断点续传的 SMB 下载：基于 .smbpart 临时文件与 SmbRandomAccess.seek(offset)
     */
    fun executeResumableDownload(
        context: Context,
        config: SmbConnectionConfig,
        task: SmbTransferTask,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onUpdate: (SmbTransferTask, persistCheckpoint: Boolean) -> Unit
    ): SmbTransferTask {
        val targetDir = File(task.targetLocalDir.ifBlank { File(task.localPath).parent ?: context.filesDir.absolutePath })
        if (!targetDir.exists()) targetDir.mkdirs()
        val tempFile = File(
            task.localTempPath.ifBlank {
                File(targetDir, "${task.fileName}.${task.id.take(8)}.smbpart").absolutePath
            }
        )

        var workingTask = task.copy(
            localTempPath = tempFile.absolutePath,
            targetLocalDir = targetDir.absolutePath,
            status = SmbTransferStatus.TRANSFERRING,
            errorMessage = null
        )
        onUpdate(workingTask, true)

        var smbFile: SmbFile? = null
        var smbRaf: SmbRandomAccess? = null
        var localRaf: RandomAccessFile? = null

        return try {
            val ctx = getContext(config)
            smbFile = SmbFile(task.remoteUrl, ctx)
            if (!smbFile.exists()) {
                throw IllegalStateException("远程文件不存在或已被移除")
            }

            val remoteSize = smbFile.length()
            val remoteLastMod = try { smbFile.lastModified() } catch (_: Exception) { 0L }
            val actualSize = if (remoteSize >= 0L) remoteSize else task.size

            // 校验远端文件是否发生变化；若匹配且存在有效 .smbpart，则从断点偏移继续
            var resumeOffset = 0L
            if (tempFile.exists()) {
                val partLen = tempFile.length()
                val sameLength = task.size <= 0L || task.size == actualSize
                val sameModTime = task.remoteLastModified <= 0L || remoteLastMod <= 0L || task.remoteLastModified == remoteLastMod
                if (partLen in 1L..actualSize && sameLength && sameModTime) {
                    resumeOffset = partLen
                } else {
                    tempFile.delete()
                }
            }

            workingTask = workingTask.copy(
                size = actualSize,
                remoteLastModified = remoteLastMod,
                bytesTransferred = resumeOffset,
                progress = if (actualSize > 0L) (resumeOffset.toFloat() / actualSize).coerceIn(0f, 1f) else 0f
            )
            onUpdate(workingTask, true)

            if (actualSize == 0L) {
                val finalFile = uniqueLocalFile(targetDir, task.fileName)
                finalFile.createNewFile()
                if (tempFile.exists()) tempFile.delete()
                MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
                val done = workingTask.copy(
                    localPath = finalFile.absolutePath,
                    bytesTransferred = 0L,
                    progress = 1f,
                    speed = "",
                    status = SmbTransferStatus.COMPLETED
                )
                onUpdate(done, true)
                return done
            }

            var totalCopied = resumeOffset
            if (resumeOffset < actualSize) {
                val activeSmbRaf = smbFile.openRandomAccess("r")
                smbRaf = activeSmbRaf
                activeSmbRaf.seek(resumeOffset)

                val activeLocalRaf = RandomAccessFile(tempFile, "rw")
                localRaf = activeLocalRaf
                activeLocalRaf.seek(resumeOffset)
                activeLocalRaf.setLength(resumeOffset)

                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                val sessionStart = System.currentTimeMillis()
                var lastNotify = sessionStart
                var lastPersistBytes = resumeOffset

                while (totalCopied < actualSize) {
                    if (isCancelled()) {
                        try { activeLocalRaf.close() } catch (_: Exception) {}
                        try { activeSmbRaf.close() } catch (_: Exception) {}
                        localRaf = null
                        smbRaf = null
                        tempFile.delete()
                        val cancelled = workingTask.copy(
                            bytesTransferred = 0L,
                            progress = 0f,
                            speed = "",
                            status = SmbTransferStatus.CANCELLED
                        )
                        onUpdate(cancelled, true)
                        return cancelled
                    }

                    if (isPaused()) {
                        try { activeLocalRaf.fd.sync() } catch (_: Exception) {}
                        val paused = workingTask.copy(
                            bytesTransferred = totalCopied,
                            progress = (totalCopied.toFloat() / actualSize).coerceIn(0f, 1f),
                            speed = "",
                            status = SmbTransferStatus.PAUSED
                        )
                        onUpdate(paused, true)
                        return paused
                    }

                    val toRead = minOf(buffer.size.toLong(), actualSize - totalCopied).toInt()
                    val n = activeSmbRaf.read(buffer, 0, toRead)
                    if (n <= 0) break

                    activeLocalRaf.write(buffer, 0, n)
                    totalCopied += n

                    val now = System.currentTimeMillis()
                    if (now - lastNotify >= PROGRESS_NOTIFY_INTERVAL_MS) {
                        val elapsedSec = ((now - sessionStart).coerceAtLeast(1L)) / 1000.0
                        val sessionBytes = (totalCopied - resumeOffset).coerceAtLeast(0L)
                        val speed = formatSpeed(sessionBytes / elapsedSec)
                        val prog = (totalCopied.toFloat() / actualSize).coerceIn(0f, 1f)
                        val shouldPersist = (totalCopied - lastPersistBytes) >= 1024 * 1024L
                        if (shouldPersist) lastPersistBytes = totalCopied
                        workingTask = workingTask.copy(
                            bytesTransferred = totalCopied,
                            progress = prog,
                            speed = speed
                        )
                        onUpdate(workingTask, shouldPersist)
                        lastNotify = now
                    }
                }

                try { activeLocalRaf.fd.sync() } catch (_: Exception) {}
                activeLocalRaf.close()
                localRaf = null
                activeSmbRaf.close()
                smbRaf = null
            }

            if (totalCopied < actualSize) {
                throw IllegalStateException("远程读取提前结束 ($totalCopied / $actualSize)")
            }

            val finalFile = finalizeDownloadedTempFile(tempFile, targetDir, task.fileName)
            MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
            val completed = workingTask.copy(
                localPath = finalFile.absolutePath,
                bytesTransferred = totalCopied,
                progress = 1f,
                speed = "",
                status = SmbTransferStatus.COMPLETED,
                errorMessage = null
            )
            onUpdate(completed, true)
            completed
        } catch (e: Exception) {
            if (isTransportFailure(e)) invalidateContext(config.id)
            val failed = workingTask.copy(
                bytesTransferred = if (tempFile.exists()) tempFile.length() else workingTask.bytesTransferred,
                progress = if (workingTask.size > 0L && tempFile.exists()) {
                    (tempFile.length().toFloat() / workingTask.size).coerceIn(0f, 1f)
                } else {
                    workingTask.progress
                },
                speed = "",
                status = SmbTransferStatus.FAILED,
                errorMessage = formatSmbError(config, e)
            )
            onUpdate(failed, true)
            failed
        } finally {
            try { localRaf?.close() } catch (_: Exception) {}
            try { smbRaf?.close() } catch (_: Exception) {}
            try { smbFile?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 支持断点续传的 SMB 上传：远端写入 .smbpart 临时文件，完成后原子 renameTo 正式文件名
     */
    fun executeResumableUpload(
        context: Context,
        config: SmbConnectionConfig,
        task: SmbTransferTask,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onUpdate: (SmbTransferTask, persistCheckpoint: Boolean) -> Unit
    ): SmbTransferTask {
        val normalizedDir = if (task.remoteDirUrl.endsWith("/")) task.remoteDirUrl else "${task.remoteDirUrl}/"
        val remoteTempUrl = task.remoteTempUrl.ifBlank {
            "${normalizedDir}${task.fileName}.${task.id.take(8)}.smbpart"
        }

        var workingTask = task.copy(
            remoteDirUrl = normalizedDir,
            remoteTempUrl = remoteTempUrl,
            status = SmbTransferStatus.TRANSFERRING,
            errorMessage = null
        )
        onUpdate(workingTask, true)

        var remoteDir: SmbFile? = null
        var remoteTemp: SmbFile? = null
        var smbRaf: SmbRandomAccess? = null
        var localRandomFile: RandomAccessFile? = null
        var uriStream: InputStream? = null

        return try {
            val ctx = getContext(config)
            remoteDir = SmbFile(normalizedDir, ctx)
            remoteTemp = SmbFile(remoteTempUrl, ctx)

            val totalSize = resolveLocalSourceSize(context, task.localPath, task.size)
            workingTask = workingTask.copy(size = totalSize)

            if (totalSize == 0L) {
                val finalName = uniqueRemoteFileName(remoteDir, task.fileName)
                val finalRemote = SmbFile(remoteDir, finalName)
                try {
                    if (!finalRemote.exists()) finalRemote.createNewFile()
                    val done = workingTask.copy(
                        fileName = finalName,
                        remoteUrl = finalRemote.canonicalPath,
                        bytesTransferred = 0L,
                        progress = 1f,
                        speed = "",
                        status = SmbTransferStatus.COMPLETED
                    )
                    onUpdate(done, true)
                    return done
                } finally {
                    try { finalRemote.close() } catch (_: Exception) {}
                }
            }

            // 以 SMB 服务端实际已落盘的 .smbpart 长度为权威断点位置
            val existingRemoteLen = try {
                if (remoteTemp.exists()) remoteTemp.length() else 0L
            } catch (_: Exception) {
                0L
            }

            val resumeOffset = when {
                existingRemoteLen in 1L..totalSize -> existingRemoteLen
                existingRemoteLen > totalSize -> {
                    try { remoteTemp.delete() } catch (_: Exception) {}
                    0L
                }
                else -> 0L
            }

            workingTask = workingTask.copy(
                bytesTransferred = resumeOffset,
                progress = (resumeOffset.toFloat() / totalSize).coerceIn(0f, 1f)
            )
            onUpdate(workingTask, true)

            var totalWritten = resumeOffset
            if (resumeOffset < totalSize) {
                // 打开本地源文件并精确跳转到 resumeOffset
                val localPath = task.localPath
                val isContentUri = localPath.startsWith("content://")
                if (!isContentUri) {
                    val f = File(localPath.removePrefix("file://"))
                    if (!f.exists() || !f.isFile) {
                        throw IllegalStateException("本地源文件已不存在")
                    }
                    localRandomFile = RandomAccessFile(f, "r")
                    localRandomFile.seek(resumeOffset)
                } else {
                    val uri = Uri.parse(localPath)
                    val pfd = try {
                        context.contentResolver.openFileDescriptor(uri, "r")
                    } catch (_: Exception) {
                        null
                    }
                    if (pfd != null) {
                        val fis = FileInputStream(pfd.fileDescriptor)
                        try {
                            fis.channel.position(resumeOffset)
                            uriStream = object : InputStream() {
                                override fun read(): Int = fis.read()
                                override fun read(b: ByteArray, off: Int, len: Int): Int = fis.read(b, off, len)
                                override fun close() {
                                    try { fis.close() } catch (_: Exception) {}
                                    try { pfd.close() } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            try { fis.close() } catch (_: Exception) {}
                            try { pfd.close() } catch (_: Exception) {}
                            throw e
                        }
                    } else {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("无法打开本地文件流（可能已失去权限，请重新选文件上传）")
                        skipExactly(stream, resumeOffset)
                        uriStream = stream
                    }
                }

                val activeSmbRaf = remoteTemp.openRandomAccess("rw")
                smbRaf = activeSmbRaf
                activeSmbRaf.seek(resumeOffset)

                val activeLocalFile = localRandomFile
                val activeUriStream = uriStream
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                val sessionStart = System.currentTimeMillis()
                var lastNotify = sessionStart
                var lastPersistBytes = resumeOffset

                while (totalWritten < totalSize) {
                    if (isCancelled()) {
                        try { activeSmbRaf.close() } catch (_: Exception) {}
                        smbRaf = null
                        try { remoteTemp.delete() } catch (_: Exception) {}
                        val cancelled = workingTask.copy(
                            bytesTransferred = 0L,
                            progress = 0f,
                            speed = "",
                            status = SmbTransferStatus.CANCELLED
                        )
                        onUpdate(cancelled, true)
                        return cancelled
                    }

                    if (isPaused()) {
                        val paused = workingTask.copy(
                            bytesTransferred = totalWritten,
                            progress = (totalWritten.toFloat() / totalSize).coerceIn(0f, 1f),
                            speed = "",
                            status = SmbTransferStatus.PAUSED
                        )
                        onUpdate(paused, true)
                        return paused
                    }

                    val n = if (activeLocalFile != null) {
                        activeLocalFile.read(buffer)
                    } else {
                        activeUriStream!!.read(buffer)
                    }
                    if (n <= 0) break

                    activeSmbRaf.write(buffer, 0, n)
                    totalWritten += n

                    val now = System.currentTimeMillis()
                    if (now - lastNotify >= PROGRESS_NOTIFY_INTERVAL_MS) {
                        val elapsedSec = ((now - sessionStart).coerceAtLeast(1L)) / 1000.0
                        val sessionBytes = (totalWritten - resumeOffset).coerceAtLeast(0L)
                        val speed = formatSpeed(sessionBytes / elapsedSec)
                        val prog = (totalWritten.toFloat() / totalSize).coerceIn(0f, 1f)
                        val shouldPersist = (totalWritten - lastPersistBytes) >= 1024 * 1024L
                        if (shouldPersist) lastPersistBytes = totalWritten
                        workingTask = workingTask.copy(
                            bytesTransferred = totalWritten,
                            progress = prog,
                            speed = speed
                        )
                        onUpdate(workingTask, shouldPersist)
                        lastNotify = now
                    }
                }

                activeSmbRaf.close()
                smbRaf = null
                localRandomFile?.close()
                localRandomFile = null
                uriStream?.close()
                uriStream = null
            }

            if (totalWritten < totalSize) {
                throw IllegalStateException("本地文件读取提前结束 ($totalWritten / $totalSize)")
            }

            val finalName = uniqueRemoteFileName(remoteDir, task.fileName)
            val finalRemote = SmbFile(remoteDir, finalName)
            try {
                remoteTemp.renameTo(finalRemote)
                val completed = workingTask.copy(
                    fileName = finalName,
                    remoteUrl = finalRemote.canonicalPath,
                    bytesTransferred = totalWritten,
                    progress = 1f,
                    speed = "",
                    status = SmbTransferStatus.COMPLETED,
                    errorMessage = null
                )
                onUpdate(completed, true)
                completed
            } finally {
                try { finalRemote.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            if (isTransportFailure(e)) invalidateContext(config.id)
            val serverSavedLen = try {
                if (remoteTemp != null && remoteTemp.exists()) remoteTemp.length() else workingTask.bytesTransferred
            } catch (_: Exception) {
                workingTask.bytesTransferred
            }
            val failed = workingTask.copy(
                bytesTransferred = serverSavedLen,
                progress = if (workingTask.size > 0L) (serverSavedLen.toFloat() / workingTask.size).coerceIn(0f, 1f) else 0f,
                speed = "",
                status = SmbTransferStatus.FAILED,
                errorMessage = formatSmbError(config, e)
            )
            onUpdate(failed, true)
            failed
        } finally {
            try { localRandomFile?.close() } catch (_: Exception) {}
            try { uriStream?.close() } catch (_: Exception) {}
            try { smbRaf?.close() } catch (_: Exception) {}
            try { remoteTemp?.close() } catch (_: Exception) {}
            try { remoteDir?.close() } catch (_: Exception) {}
        }
    }

    private fun resolveLocalSourceSize(context: Context, localPath: String, fallbackSize: Long): Long {
        if (!localPath.startsWith("content://")) {
            val f = File(localPath.removePrefix("file://"))
            if (f.exists()) return f.length()
            return fallbackSize
        }
        val uri = Uri.parse(localPath)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize >= 0L) return pfd.statSize
            }
        } catch (_: Exception) {
        }
        return fallbackSize
    }

    private fun skipExactly(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
        }
    }

    private fun finalizeDownloadedTempFile(tempFile: File, targetDir: File, desiredName: String): File {
        val finalFile = uniqueLocalFile(targetDir, desiredName)
        if (tempFile.renameTo(finalFile)) {
            return finalFile
        }
        FileInputStream(tempFile).use { input ->
            FileOutputStream(finalFile).use { output ->
                val buf = ByteArray(TRANSFER_BUFFER_SIZE)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
        tempFile.delete()
        return finalFile
    }

    fun resolveUriMetadata(context: Context, uri: Uri): LocalUploadSource.UriSource? {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) {
                return LocalUploadSource.UriSource(uri, f.name, f.length())
            }
        }
        var displayName = "upload_${System.currentTimeMillis()}"
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (size < 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize >= 0L) size = pfd.statSize
                }
            } catch (_: Exception) {
            }
        }
        return LocalUploadSource.UriSource(uri, displayName, size.coerceAtLeast(0L))
    }

    private fun uniqueLocalFile(targetDir: File, fileName: String): File {
        var candidate = File(targetDir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    fun uniqueRemoteFileName(remoteDir: SmbFile, fileName: String): String {
        var candidateName = fileName
        var index = 1
        while (true) {
            val probe = SmbFile(remoteDir, candidateName)
            val exists = try {
                probe.exists()
            } catch (_: Exception) {
                false
            } finally {
                try { probe.close() } catch (_: Exception) {}
            }
            if (!exists) return candidateName
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            candidateName = "$base ($index)$ext"
            index++
        }
    }

    private fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            else -> FileUtils.getMimeType(File(fileName))
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    private fun isTransportFailure(error: Throwable): Boolean {
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is SocketException ||
            error is NoRouteToHostException ||
            error.cause is SocketTimeoutException ||
            error.cause is ConnectException ||
            error.cause is SocketException
    }

    fun formatSmbError(config: SmbConnectionConfig, error: Throwable): String {
        val msg = (error.message ?: "") + " " + (error.cause?.message ?: "")
        return when {
            error is SmbAuthException || msg.contains("Logon failure", ignoreCase = true) ||
                msg.contains("authentication", ignoreCase = true) ->
                "认证失败：用户名或密码错误（Windows 请使用本机账户密码，或开启匿名/访客模式）"
            msg.contains("Access is denied", ignoreCase = true) ->
                "拒绝访问：当前账户无权访问此目录或执行操作"
            msg.contains("network name cannot be found", ignoreCase = true) ||
                msg.contains("BAD_NETWORK_NAME", ignoreCase = true) ->
                "找不到共享目录，请检查共享名称是否正确"
            msg.contains("encryption", ignoreCase = true) ->
                "服务器强制要求 SMB3 加密，请在连接设置中选择「现代模式 (SMB2~SMB3)」"
            error is UnknownHostException || error.cause is UnknownHostException ->
                "无法解析主机 ${config.host}，请确认局域网 IP 是否正确"
            error is ConnectException || error.cause is ConnectException ||
                error is NoRouteToHostException || error.cause is NoRouteToHostException ->
                "无法连接 ${config.host}:${config.port}，请确认对方已开启 SMB 文件共享且防火墙已放行"
            error is SocketTimeoutException || error.cause is SocketTimeoutException ||
                msg.contains("timed out", ignoreCase = true) ->
                "连接超时，请检查手机与目标设备是否处于同一局域网"
            error is SmbException ->
                error.message ?: "SMB 操作失败"
            else ->
                error.message ?: "未知 SMB 错误"
        }
    }
}
