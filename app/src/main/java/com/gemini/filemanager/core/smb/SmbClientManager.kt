package com.gemini.filemanager.core.smb

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.OpenableColumns
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.SmbConstants
import org.codelibs.jcifs.smb.config.PropertyConfiguration
import org.codelibs.jcifs.smb.context.BaseContext
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator
import org.codelibs.jcifs.smb.impl.SmbAuthException
import org.codelibs.jcifs.smb.impl.SmbException
import org.codelibs.jcifs.smb.impl.SmbFile
import org.codelibs.jcifs.smb.impl.SmbFileInputStream
import org.codelibs.jcifs.smb.impl.SmbFileOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.Security
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class LocalUploadSource {
    data class FileSource(val file: File) : LocalUploadSource()
    data class UriSource(val uri: Uri, val name: String, val size: Long) : LocalUploadSource()
}

object SmbClientManager {

    private const val BUFFER_SIZE = 64 * 1024

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
                        val buf = ByteArray(BUFFER_SIZE)
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
            Result.failure(Exception(formatSmbError(config, e), e))
        }
    }

    suspend fun downloadItems(
        context: Context,
        config: SmbConnectionConfig,
        items: List<SmbRemoteFile>,
        targetLocalDir: File,
        onTaskUpdate: (SmbTransferTask) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (!targetLocalDir.exists()) targetLocalDir.mkdirs()
        val ctx = getContext(config)
        var successCount = 0

        for (item in items) {
            if (item.isShareRoot) continue
            val rootSmb = SmbFile(item.smbUrl, ctx)
            try {
                if (item.isDirectory) {
                    val subDir = File(targetLocalDir, item.name)
                    successCount += downloadDirectoryRecursively(context, rootSmb, subDir, onTaskUpdate)
                } else {
                    if (downloadSingleSmbFile(context, rootSmb, item.name, item.size, targetLocalDir, onTaskUpdate)) {
                        successCount++
                    }
                }
            } finally {
                try { rootSmb.close() } catch (_: Exception) {}
            }
        }
        successCount
    }

    private fun downloadDirectoryRecursively(
        context: Context,
        smbDir: SmbFile,
        localDir: File,
        onTaskUpdate: (SmbTransferTask) -> Unit
    ): Int {
        if (!localDir.exists()) localDir.mkdirs()
        var count = 0
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
                    count += downloadDirectoryRecursively(context, child, File(localDir, childName), onTaskUpdate)
                } else {
                    val len = try { child.length() } catch (_: Exception) { 0L }
                    if (downloadSingleSmbFile(context, child, childName, len, localDir, onTaskUpdate)) {
                        count++
                    }
                }
            } finally {
                try { child.close() } catch (_: Exception) {}
            }
        }
        return count
    }

    private fun downloadSingleSmbFile(
        context: Context,
        smbFile: SmbFile,
        fileName: String,
        knownSize: Long,
        targetDir: File,
        onTaskUpdate: (SmbTransferTask) -> Unit
    ): Boolean {
        val totalSize = if (knownSize > 0L) knownSize else try { smbFile.length() } catch (_: Exception) { 0L }
        val localFile = uniqueLocalFile(targetDir, fileName)
        val taskId = UUID.randomUUID().toString()
        var currentTask = SmbTransferTask(
            id = taskId,
            fileName = fileName,
            remoteUrl = smbFile.canonicalPath,
            localPath = localFile.absolutePath,
            size = totalSize,
            direction = SmbTransferDirection.DOWNLOAD,
            status = SmbTransferStatus.TRANSFERRING
        )
        onTaskUpdate(currentTask)

        return try {
            val totalRead = SmbFileInputStream(smbFile).use { input ->
                FileOutputStream(localFile).use { output ->
                    copyStreamWithProgress(input, output, totalSize) { copied, prog, speed ->
                        currentTask = currentTask.copy(
                            bytesTransferred = copied,
                            progress = prog,
                            speed = speed
                        )
                        onTaskUpdate(currentTask)
                    }
                }
            }

            MediaScannerConnection.scanFile(context, arrayOf(localFile.absolutePath), null, null)
            onTaskUpdate(
                currentTask.copy(
                    bytesTransferred = totalRead,
                    progress = 1f,
                    speed = "",
                    status = SmbTransferStatus.COMPLETED
                )
            )
            true
        } catch (e: Exception) {
            if (localFile.exists() && localFile.length() == 0L) {
                localFile.delete()
            }
            onTaskUpdate(
                currentTask.copy(
                    status = SmbTransferStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                )
            )
            false
        }
    }

    suspend fun uploadSources(
        context: Context,
        config: SmbConnectionConfig,
        sources: List<LocalUploadSource>,
        targetSmbDirUrl: String,
        onTaskUpdate: (SmbTransferTask) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val ctx = getContext(config)
        val normalizedDir = if (targetSmbDirUrl.endsWith("/")) targetSmbDirUrl else "$targetSmbDirUrl/"
        val dirFile = SmbFile(normalizedDir, ctx)
        var successCount = 0

        try {
            for (source in sources) {
                if (uploadSingleSource(context, config, source, dirFile, onTaskUpdate)) {
                    successCount++
                }
            }
        } finally {
            try { dirFile.close() } catch (_: Exception) {}
        }
        successCount
    }

    private fun uploadSingleSource(
        context: Context,
        config: SmbConnectionConfig,
        source: LocalUploadSource,
        remoteDir: SmbFile,
        onTaskUpdate: (SmbTransferTask) -> Unit
    ): Boolean {
        val fileName: String
        val totalSize: Long
        val localPath: String
        when (source) {
            is LocalUploadSource.FileSource -> {
                if (!source.file.exists() || !source.file.isFile) return false
                fileName = source.file.name
                totalSize = source.file.length()
                localPath = source.file.absolutePath
            }
            is LocalUploadSource.UriSource -> {
                fileName = source.name
                totalSize = source.size
                localPath = source.uri.toString()
            }
        }

        val targetName = uniqueRemoteFileName(remoteDir, fileName)
        val destSmb = SmbFile(remoteDir, targetName)
        val taskId = UUID.randomUUID().toString()
        var currentTask = SmbTransferTask(
            id = taskId,
            fileName = targetName,
            remoteUrl = destSmb.canonicalPath,
            localPath = localPath,
            size = totalSize,
            direction = SmbTransferDirection.UPLOAD,
            status = SmbTransferStatus.TRANSFERRING
        )
        onTaskUpdate(currentTask)

        return try {
            val inputStream: InputStream = when (source) {
                is LocalUploadSource.FileSource -> FileInputStream(source.file)
                is LocalUploadSource.UriSource -> context.contentResolver.openInputStream(source.uri)
                    ?: throw IllegalStateException("无法读取所选文件流")
            }

            val totalWritten = inputStream.use { input ->
                SmbFileOutputStream(destSmb).use { output ->
                    copyStreamWithProgress(input, output, totalSize) { copied, prog, speed ->
                        currentTask = currentTask.copy(
                            bytesTransferred = copied,
                            progress = prog,
                            speed = speed
                        )
                        onTaskUpdate(currentTask)
                    }
                }
            }

            onTaskUpdate(
                currentTask.copy(
                    bytesTransferred = totalWritten,
                    progress = 1f,
                    speed = "",
                    status = SmbTransferStatus.COMPLETED
                )
            )
            true
        } catch (e: Exception) {
            onTaskUpdate(
                currentTask.copy(
                    status = SmbTransferStatus.FAILED,
                    errorMessage = formatSmbError(config, e)
                )
            )
            false
        } finally {
            try { destSmb.close() } catch (_: Exception) {}
        }
    }

    private inline fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalSize: Long,
        onProgress: (copiedBytes: Long, progress: Float, speed: String) -> Unit
    ): Long {
        val startTime = System.currentTimeMillis()
        var lastNotifyTime = startTime
        var totalCopied = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } > 0) {
            output.write(buffer, 0, bytesRead)
            totalCopied += bytesRead
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime >= 150L) {
                val elapsedSec = ((now - startTime).coerceAtLeast(1L)) / 1000.0
                val speed = formatSpeed(totalCopied / elapsedSec)
                val prog = if (totalSize > 0L) (totalCopied.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
                onProgress(totalCopied, prog, speed)
                lastNotifyTime = now
            }
        }
        output.flush()
        return totalCopied
    }

    fun resolveUriMetadata(context: Context, uri: Uri): LocalUploadSource.UriSource? {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) {
                return LocalUploadSource.UriSource(uri, f.name, f.length())
            }
        }
        var displayName = "upload_${System.currentTimeMillis()}"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return LocalUploadSource.UriSource(uri, displayName, size)
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

    private fun uniqueRemoteFileName(remoteDir: SmbFile, fileName: String): String {
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
