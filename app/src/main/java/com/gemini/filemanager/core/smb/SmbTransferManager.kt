package com.gemini.filemanager.core.smb

import android.content.Context
import android.content.Intent
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SmbTransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencySemaphore = Semaphore(3)

    private val _tasks = MutableStateFlow<List<SmbTransferTask>>(emptyList())
    val tasks: StateFlow<List<SmbTransferTask>> = _tasks.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile
    private var store: SmbTransferStore? = null

    @Volatile
    private var connectionStore: SmbConnectionStore? = null

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var lastNotificationUpdateAt = 0L

    fun init(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            val appCtx = context.applicationContext
            val tStore = SmbTransferStore(appCtx)
            store = tStore
            connectionStore = SmbConnectionStore(appCtx)
            _tasks.value = tStore.loadTasks()
        }
    }

    fun enqueueDownloads(
        context: Context,
        config: SmbConnectionConfig,
        items: List<SmbRemoteFile>,
        targetLocalDir: File,
        onQueued: (Int, String?) -> Unit = { _, _ -> }
    ) {
        init(context)
        val appCtx = context.applicationContext
        scope.launch {
            val result = SmbClientManager.collectDownloadSpecs(config, items, targetLocalDir)
            result.onSuccess { specs ->
                if (specs.isEmpty()) {
                    launch(Dispatchers.Main) { onQueued(0, "所选目录中没有可下载的文件") }
                    return@onSuccess
                }
                val newTasks = specs.map { spec ->
                    val id = UUID.randomUUID().toString()
                    val tempFile = File(spec.targetLocalDir, "${spec.remoteFile.name}.${id.take(8)}.smbpart")
                    val finalFile = File(spec.targetLocalDir, spec.remoteFile.name)
                    SmbTransferTask(
                        id = id,
                        configId = config.id,
                        fileName = spec.remoteFile.name,
                        remoteUrl = spec.remoteFile.smbUrl,
                        localPath = finalFile.absolutePath,
                        localTempPath = tempFile.absolutePath,
                        targetLocalDir = spec.targetLocalDir.absolutePath,
                        size = spec.remoteFile.size,
                        remoteLastModified = spec.remoteFile.lastModified,
                        direction = SmbTransferDirection.DOWNLOAD,
                        status = SmbTransferStatus.WAITING
                    )
                }
                appendTasks(newTasks)
                newTasks.forEach { startWorker(appCtx, config, it) }
                launch(Dispatchers.Main) { onQueued(newTasks.size, null) }
            }.onFailure { err ->
                launch(Dispatchers.Main) { onQueued(0, err.message ?: "读取下载列表失败") }
            }
        }
    }

    fun enqueueUploads(
        context: Context,
        config: SmbConnectionConfig,
        sources: List<LocalUploadSource>,
        targetRemoteDirUrl: String,
        onQueued: (Int) -> Unit = {}
    ) {
        init(context)
        if (sources.isEmpty()) {
            onQueued(0)
            return
        }
        val appCtx = context.applicationContext
        val normalizedDir = if (targetRemoteDirUrl.endsWith("/")) targetRemoteDirUrl else "$targetRemoteDirUrl/"

        val newTasks = sources.mapNotNull { source ->
            val id = UUID.randomUUID().toString()
            when (source) {
                is LocalUploadSource.FileSource -> {
                    if (!source.file.exists() || !source.file.isFile) return@mapNotNull null
                    SmbTransferTask(
                        id = id,
                        configId = config.id,
                        fileName = source.file.name,
                        remoteUrl = "$normalizedDir${source.file.name}",
                        remoteTempUrl = "$normalizedDir${source.file.name}.${id.take(8)}.smbpart",
                        remoteDirUrl = normalizedDir,
                        localPath = source.file.absolutePath,
                        size = source.file.length(),
                        direction = SmbTransferDirection.UPLOAD,
                        status = SmbTransferStatus.WAITING
                    )
                }
                is LocalUploadSource.UriSource -> {
                    try {
                        appCtx.contentResolver.takePersistableUriPermission(
                            source.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    SmbTransferTask(
                        id = id,
                        configId = config.id,
                        fileName = source.name,
                        remoteUrl = "$normalizedDir${source.name}",
                        remoteTempUrl = "$normalizedDir${source.name}.${id.take(8)}.smbpart",
                        remoteDirUrl = normalizedDir,
                        localPath = source.uri.toString(),
                        size = source.size,
                        direction = SmbTransferDirection.UPLOAD,
                        status = SmbTransferStatus.WAITING
                    )
                }
            }
        }

        if (newTasks.isNotEmpty()) {
            appendTasks(newTasks)
            newTasks.forEach { startWorker(appCtx, config, it) }
        }
        onQueued(newTasks.size)
    }

    fun pauseTask(context: Context, taskId: String) {
        init(context)
        pauseFlags.getOrPut(taskId) { AtomicBoolean(false) }.set(true)
        val current = _tasks.value.find { it.id == taskId } ?: return
        if (current.status == SmbTransferStatus.WAITING) {
            activeJobs.remove(taskId)?.cancel()
            updateTask(
                current.copy(status = SmbTransferStatus.PAUSED, speed = ""),
                persist = true
            )
            syncBackgroundServiceState(context.applicationContext)
        }
    }

    fun resumeTask(context: Context, taskId: String, fallbackConfig: SmbConnectionConfig? = null): Boolean {
        init(context)
        val task = _tasks.value.find { it.id == taskId } ?: return false
        if (!task.canResume) return false

        val config = resolveConfig(task.configId, fallbackConfig) ?: run {
            updateTask(
                task.copy(errorMessage = "未找到对应的 SMB 服务器配置，请重新连接后重试"),
                persist = true
            )
            return false
        }

        val waiting = task.copy(status = SmbTransferStatus.WAITING, errorMessage = null, speed = "")
        updateTask(waiting, persist = true)
        startWorker(context.applicationContext, config, waiting)
        return true
    }

    fun cancelTask(context: Context, taskId: String, fallbackConfig: SmbConnectionConfig? = null) {
        init(context)
        val appCtx = context.applicationContext
        cancelFlags.getOrPut(taskId) { AtomicBoolean(false) }.set(true)
        val task = _tasks.value.find { it.id == taskId } ?: return

        val runningJob = activeJobs[taskId]
        if (task.status == SmbTransferStatus.TRANSFERRING && runningJob?.isActive == true) {
            // 循环会在下一块读写时自动清理临时文件并更新状态
            return
        }

        runningJob?.cancel()
        activeJobs.remove(taskId)
        scope.launch {
            cleanupTempArtifacts(task, fallbackConfig)
            removeTaskFromList(taskId)
            syncBackgroundServiceState(appCtx)
        }
    }

    fun pauseAll(context: Context) {
        init(context)
        _tasks.value.filter { it.isActive }.forEach { pauseTask(context, it.id) }
    }

    fun resumeAll(context: Context, fallbackConfig: SmbConnectionConfig? = null) {
        init(context)
        _tasks.value.filter { it.canResume }.forEach { resumeTask(context, it.id, fallbackConfig) }
    }

    fun removeTask(context: Context, taskId: String, fallbackConfig: SmbConnectionConfig? = null) {
        init(context)
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.isActive) {
            cancelTask(context, taskId, fallbackConfig)
            return
        }
        scope.launch {
            if (task.status == SmbTransferStatus.PAUSED || task.status == SmbTransferStatus.FAILED) {
                cleanupTempArtifacts(task, fallbackConfig)
            }
            removeTaskFromList(taskId)
        }
    }

    fun clearFinished() {
        _tasks.update { list ->
            val kept = list.filter {
                it.status != SmbTransferStatus.COMPLETED && it.status != SmbTransferStatus.CANCELLED
            }
            store?.saveTasks(kept)
            kept
        }
    }

    private fun startWorker(appCtx: Context, config: SmbConnectionConfig, initialTask: SmbTransferTask) {
        activeJobs.remove(initialTask.id)?.cancel()
        val pauseFlag = pauseFlags.getOrPut(initialTask.id) { AtomicBoolean(false) }.apply { set(false) }
        val cancelFlag = cancelFlags.getOrPut(initialTask.id) { AtomicBoolean(false) }.apply { set(false) }

        ensureServiceStarted(appCtx)

        val job = scope.launch {
            try {
                concurrencySemaphore.withPermit {
                    if (cancelFlag.get()) {
                        cleanupTempArtifacts(initialTask, config)
                        removeTaskFromList(initialTask.id)
                        return@withPermit
                    }
                    if (pauseFlag.get()) {
                        updateTask(initialTask.copy(status = SmbTransferStatus.PAUSED, speed = ""), persist = true)
                        return@withPermit
                    }

                    val latestTask = _tasks.value.find { it.id == initialTask.id } ?: initialTask
                    val resultTask = when (latestTask.direction) {
                        SmbTransferDirection.DOWNLOAD -> SmbClientManager.executeResumableDownload(
                            context = appCtx,
                            config = config,
                            task = latestTask,
                            isPaused = { pauseFlag.get() },
                            isCancelled = { cancelFlag.get() },
                            onUpdate = { updated, persist ->
                                updateTask(updated, persist)
                                maybeUpdateServiceNotification(appCtx)
                            }
                        )
                        SmbTransferDirection.UPLOAD -> SmbClientManager.executeResumableUpload(
                            context = appCtx,
                            config = config,
                            task = latestTask,
                            isPaused = { pauseFlag.get() },
                            isCancelled = { cancelFlag.get() },
                            onUpdate = { updated, persist ->
                                updateTask(updated, persist)
                                maybeUpdateServiceNotification(appCtx)
                            }
                        )
                    }

                    if (resultTask.status == SmbTransferStatus.CANCELLED) {
                        removeTaskFromList(resultTask.id)
                    }
                }
            } finally {
                activeJobs.remove(initialTask.id)
                syncBackgroundServiceState(appCtx)
            }
        }
        activeJobs[initialTask.id] = job
    }

    private fun cleanupTempArtifacts(task: SmbTransferTask, fallbackConfig: SmbConnectionConfig?) {
        if (task.direction == SmbTransferDirection.DOWNLOAD) {
            if (task.localTempPath.isNotBlank()) {
                try {
                    File(task.localTempPath).delete()
                } catch (_: Exception) {
                }
            }
        } else {
            val cfg = resolveConfig(task.configId, fallbackConfig)
            if (cfg != null && task.remoteTempUrl.isNotBlank()) {
                SmbClientManager.deleteRemotePathSilent(cfg, task.remoteTempUrl)
            }
        }
    }

    private fun resolveConfig(configId: String, fallback: SmbConnectionConfig?): SmbConnectionConfig? {
        if (fallback != null && fallback.id == configId) return fallback
        return connectionStore?.getConnections()?.find { it.id == configId } ?: fallback
    }

    private fun appendTasks(newTasks: List<SmbTransferTask>) {
        _tasks.update { list ->
            val updated = list + newTasks
            store?.saveTasks(updated)
            updated
        }
    }

    private fun updateTask(task: SmbTransferTask, persist: Boolean) {
        _tasks.update { list ->
            val updated = if (list.any { it.id == task.id }) {
                list.map { if (it.id == task.id) task else it }
            } else {
                list + task
            }
            if (persist) {
                store?.saveTasks(updated)
            }
            updated
        }
    }

    private fun removeTaskFromList(taskId: String) {
        _tasks.update { list ->
            val updated = list.filterNot { it.id == taskId }
            store?.saveTasks(updated)
            updated
        }
    }

    private fun ensureServiceStarted(appCtx: Context) {
        AppBackgroundKeepAliveManager.acquireLease(appCtx, "smb_transfer", "SMB 文件传输")
        if (!serviceRunning) {
            serviceRunning = true
            SmbBackgroundService.startService(appCtx)
        }
    }

    private fun maybeUpdateServiceNotification(appCtx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateAt < 800L) return
        lastNotificationUpdateAt = now

        val active = _tasks.value.filter { it.isActive }
        if (active.isEmpty()) return

        val avgProgress = ((active.map { it.progress }.average()) * 100).toInt().coerceIn(0, 100)
        val first = active.first()
        val title = if (active.size == 1) {
            val dirText = if (first.direction == SmbTransferDirection.UPLOAD) "正在上传" else "正在下载"
            "$dirText ${first.fileName}"
        } else {
            "SMB 正在传输 ${active.size} 个文件"
        }
        val content = if (first.speed.isNotBlank()) {
            "$avgProgress% · ${first.speed}"
        } else {
            "总进度 $avgProgress%"
        }
        SmbBackgroundService.updateNotification(appCtx, title, content, avgProgress)
    }

    private fun syncBackgroundServiceState(appCtx: Context) {
        val current = _tasks.value
        val hasActive = current.any { it.isActive }
        if (!hasActive && serviceRunning) {
            serviceRunning = false
            AppBackgroundKeepAliveManager.releaseLease(appCtx, "smb_transfer")
            SmbBackgroundService.stopService(appCtx)
            val completedCount = current.count { it.status == SmbTransferStatus.COMPLETED }
            if (completedCount > 0) {
                SmbBackgroundService.showCompletedNotification(appCtx, "SMB 传输队列已结束（已完成 $completedCount 项）")
            }
        } else if (!hasActive) {
            AppBackgroundKeepAliveManager.releaseLease(appCtx, "smb_transfer")
        }
    }
}

