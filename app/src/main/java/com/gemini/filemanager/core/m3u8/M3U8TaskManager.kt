package com.gemini.filemanager.core.m3u8

import android.content.Context
import android.os.PowerManager
import com.gemini.filemanager.data.model.M3U8Item
import com.gemini.filemanager.data.model.M3U8Status
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 转码单项更新事件
 */
sealed class M3U8ConvertEvent {
    data class Progress(val id: String, val progress: Float, val speed: String) : M3U8ConvertEvent()
    data class Success(val id: String, val outputFile: File) : M3U8ConvertEvent()
    data class Error(val id: String, val error: String) : M3U8ConvertEvent()
}

/**
 * 全局后台批量转码管理器
 * 独立于 UI 生命周期，保证用户退出界面或锁屏后转码任务在后台持续执行
 */
object M3U8TaskManager {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var convertJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val _currentConvertingItem = MutableStateFlow<M3U8Item?>(null)
    val currentConvertingItem: StateFlow<M3U8Item?> = _currentConvertingItem.asStateFlow()

    private val _eventFlow = MutableSharedFlow<M3U8ConvertEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<M3U8ConvertEvent> = _eventFlow.asSharedFlow()

    /**
     * 启动批量后台转码
     */
    fun startBatchConvert(
        context: Context,
        items: List<M3U8Item>,
        deleteSourceAfter: Boolean
    ) {
        if (items.isEmpty()) return

        // 启动常驻前台服务
        M3U8BackgroundService.startService(context)

        // 获取 WakeLock，防止手机锁屏后 CPU 休眠导致转码暂停
        acquireWakeLock(context)

        convertJob?.cancel()
        convertJob = applicationScope.launch {
            _isConverting.value = true
            val totalCount = items.size

            for ((index, item) in items.withIndex()) {
                if (!isActive) break

                _currentConvertingItem.value = item
                _eventFlow.tryEmit(M3U8ConvertEvent.Progress(item.id, 0.05f, "准备中"))

                M3U8BackgroundService.updateNotification(
                    context = context,
                    title = "正在后台转码 (${index + 1}/$totalCount)",
                    content = "${item.title} (5%)",
                    progress = 5
                )

                M3U8Converter.convertM3U8ToMp4(
                    context = context,
                    item = item,
                    customKeyHex = item.customKeyHex,
                    listener = object : M3U8Converter.ConvertListener {
                        override fun onProgress(item: M3U8Item, progress: Float, speed: String, outputSize: Long) {
                            _eventFlow.tryEmit(M3U8ConvertEvent.Progress(item.id, progress, speed))
                            val pct = (progress * 100).toInt().coerceIn(0, 100)
                            val speedText = if (speed.isNotBlank()) " · 速度 $speed" else ""
                            M3U8BackgroundService.updateNotification(
                                context = context,
                                title = "正在后台转码 (${index + 1}/$totalCount)",
                                content = "${item.title} ($pct%)$speedText",
                                progress = pct
                            )
                        }

                        override fun onSuccess(item: M3U8Item, outputFile: File) {
                            if (deleteSourceAfter) {
                                deleteSourceFiles(item)
                            }
                            _eventFlow.tryEmit(M3U8ConvertEvent.Success(item.id, outputFile))
                        }

                        override fun onError(item: M3U8Item, error: String) {
                            _eventFlow.tryEmit(M3U8ConvertEvent.Error(item.id, error))
                        }
                    }
                )
            }

            // 全部处理完成
            _isConverting.value = false
            _currentConvertingItem.value = null

            M3U8BackgroundService.showCompletedNotification(
                context = context,
                message = "批量转码已全部完成！共处理 $totalCount 个视频"
            )

            releaseWakeLock()
            M3U8BackgroundService.stopService(context)
        }
    }

    /**
     * 取消当前转码
     */
    fun cancelAll(context: Context) {
        convertJob?.cancel()
        _isConverting.value = false
        _currentConvertingItem.value = null
        releaseWakeLock()
        M3U8BackgroundService.stopService(context)
    }

    /**
     * 删除源切片文件
     */
    fun deleteSourceFiles(item: M3U8Item) {
        try {
            if (item.m3u8Path.isNotBlank()) {
                val m3u8 = File(item.m3u8Path)
                if (m3u8.exists()) m3u8.delete()
            }
            val parent = File(item.parentDir)
            if (parent.exists() && parent.isDirectory) {
                val files = parent.listFiles() ?: emptyArray()
                for (f in files) {
                    val name = f.name.lowercase()
                    if (name.endsWith(".ts") || name.endsWith(".qsv") || f.name.all { it.isDigit() }) {
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QFileManager:M3U8TranscodeWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 最长 60 分钟兜底
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (ignored: Exception) {}
    }
}
