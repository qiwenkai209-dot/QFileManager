package com.gemini.filemanager.ui.screens.m3u8

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.m3u8.M3U8ConvertEvent
import com.gemini.filemanager.core.m3u8.M3U8Converter
import com.gemini.filemanager.core.m3u8.M3U8Parser
import com.gemini.filemanager.core.m3u8.M3U8Scanner
import com.gemini.filemanager.core.m3u8.M3U8TaskManager
import com.gemini.filemanager.data.model.M3U8Item
import com.gemini.filemanager.data.model.M3U8Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class M3U8SortOption(val displayName: String) {
    TIME_DESC("时间 ↓ (最新)"),
    TIME_ASC("时间 ↑ (最早)"),
    SIZE_DESC("体积 ↓ (最大)"),
    SIZE_ASC("体积 ↑ (最小)"),
    NAME_ASC("名称 (A-Z)"),
    NAME_DESC("名称 (Z-A)")
}

data class M3U8UiState(
    val items: List<M3U8Item> = emptyList(),
    val isScanning: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isBatchConverting: Boolean = false,
    val sortOption: M3U8SortOption = M3U8SortOption.TIME_DESC,
    val deleteSourceAfterConvert: Boolean = false
)

class M3U8ViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(M3U8UiState())
    val uiState: StateFlow<M3U8UiState> = _uiState.asStateFlow()

    init {
        scanTasks()
        observeBackgroundTaskEvents()
    }

    private fun observeBackgroundTaskEvents() {
        viewModelScope.launch {
            M3U8TaskManager.isConverting.collect { converting ->
                _uiState.update { it.copy(isBatchConverting = converting) }
            }
        }

        viewModelScope.launch {
            M3U8TaskManager.eventFlow.collect { event ->
                when (event) {
                    is M3U8ConvertEvent.Progress -> {
                        updateItemStatus(event.id, M3U8Status.CONVERTING, event.progress, event.speed)
                    }
                    is M3U8ConvertEvent.Success -> {
                        _uiState.update { state ->
                            state.copy(items = state.items.map {
                                if (it.id == event.id) {
                                    it.copy(
                                        status = M3U8Status.COMPLETED,
                                        progress = 1.0f,
                                        outputPath = event.outputFile.absolutePath,
                                        segmentCount = if (_uiState.value.deleteSourceAfterConvert) 0 else it.segmentCount
                                    )
                                } else it
                            })
                        }
                    }
                    is M3U8ConvertEvent.Error -> {
                        _uiState.update { state ->
                            state.copy(items = state.items.map {
                                if (it.id == event.id) {
                                    it.copy(
                                        status = M3U8Status.FAILED,
                                        errorMessage = event.error
                                    )
                                } else it
                            })
                        }
                    }
                }
            }
        }
    }

    fun scanTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            try {
                val list = M3U8Scanner.scanLocalM3U8Tasks(getApplication()) { batch ->
                    // 增量流式更新 UI，毫秒级立即可见
                    _uiState.update { current ->
                        val sortedBatch = sortM3U8List(batch, current.sortOption)
                        val uncompletedIds = sortedBatch.filter { it.status != M3U8Status.COMPLETED }.map { it.id }.toSet()
                        current.copy(
                            items = sortedBatch,
                            selectedIds = if (current.selectedIds.isEmpty()) uncompletedIds else current.selectedIds
                        )
                    }
                }
                _uiState.update { current ->
                    val sortedList = sortM3U8List(list, current.sortOption)
                    val uncompletedIds = sortedList.filter { it.status != M3U8Status.COMPLETED }.map { it.id }.toSet()
                    current.copy(
                        items = sortedList,
                        isScanning = false,
                        selectedIds = if (current.selectedIds.isEmpty()) uncompletedIds else current.selectedIds
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false) }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun setSortOption(option: M3U8SortOption) {
        _uiState.update { current ->
            current.copy(
                sortOption = option,
                items = sortM3U8List(current.items, option)
            )
        }
    }

    private fun sortM3U8List(list: List<M3U8Item>, option: M3U8SortOption): List<M3U8Item> {
        return when (option) {
            M3U8SortOption.TIME_DESC -> list.sortedWith(
                compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                    .thenByDescending { it.lastModified }
            )
            M3U8SortOption.TIME_ASC -> list.sortedWith(
                compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                    .thenBy { it.lastModified }
            )
            M3U8SortOption.SIZE_DESC -> list.sortedWith(
                compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                    .thenByDescending { it.totalSize }
            )
            M3U8SortOption.SIZE_ASC -> list.sortedWith(
                compareByDescending<M3U8Item> { it.status == M3U8Status.COMPLETED }
                    .thenBy { it.totalSize }
            )
            M3U8SortOption.NAME_ASC -> list.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            M3U8SortOption.NAME_DESC -> list.sortedByDescending { it.title.lowercase(java.util.Locale.ROOT) }
        }
    }

    fun addManualM3U8(file: File) {
        if (!file.exists()) return
        viewModelScope.launch {
            val parent = file.parentFile ?: return@launch
            val item = M3U8Item(
                id = file.absolutePath.hashCode().toString(),
                title = file.nameWithoutExtension,
                m3u8Path = file.absolutePath,
                parentDir = parent.absolutePath,
                segmentCount = parent.list()?.count { it.endsWith(".ts", true) || it.all { c -> c.isDigit() } } ?: 0,
                totalSize = file.length(),
                isEncrypted = false,
                status = M3U8Status.PENDING,
                lastModified = file.lastModified()
            )
            _uiState.update { current ->
                val newItems = listOf(item) + current.items.filter { it.id != item.id }
                current.copy(
                    items = newItems,
                    selectedIds = current.selectedIds + item.id
                )
            }
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val set = state.selectedIds.toMutableSet()
            if (set.contains(id)) set.remove(id) else set.add(id)
            state.copy(selectedIds = set)
        }
    }

    fun toggleSelectAll() {
        _uiState.update { state ->
            val uncompleted = state.items.filter { it.status != M3U8Status.COMPLETED }.map { it.id }.toSet()
            val newSelected = if (state.selectedIds.size == uncompleted.size) emptySet() else uncompleted
            state.copy(selectedIds = newSelected)
        }
    }

    fun setCustomKey(id: String, keyHex: String) {
        _uiState.update { state ->
            state.copy(items = state.items.map {
                if (it.id == id) it.copy(customKeyHex = keyHex, isEncrypted = true) else it
            })
        }
    }

    fun renameTaskTitle(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        _uiState.update { state ->
            state.copy(items = state.items.map {
                if (it.id == id) it.copy(title = newTitle.trim()) else it
            })
        }
    }

    fun preparePreviewFile(item: M3U8Item, onReady: (File) -> Unit) {
        viewModelScope.launch {
            if (item.outputPath != null && File(item.outputPath).exists()) {
                onReady(File(item.outputPath))
                return@launch
            }
            // 准备本地临时播放清单或者第一个分片
            val parentDir = File(item.parentDir)
            val origFile = if (item.m3u8Path.isNotBlank()) File(item.m3u8Path) else null
            val readyFile = M3U8Parser.prepareLocalPlayableM3U8(parentDir, origFile, item.customKeyHex)
            if (readyFile.exists()) {
                onReady(readyFile)
            } else {
                // 回退到第一个切片文件
                val firstTs = parentDir.listFiles()?.firstOrNull { it.extension == "ts" || it.name.all { c -> c.isDigit() } }
                if (firstTs != null) onReady(firstTs)
            }
        }
    }

    fun convertSelected() {
        val selectedItems = _uiState.value.items.filter { _uiState.value.selectedIds.contains(it.id) }
        if (selectedItems.isEmpty()) return

        M3U8TaskManager.startBatchConvert(
            context = getApplication(),
            items = selectedItems,
            deleteSourceAfter = _uiState.value.deleteSourceAfterConvert
        )
    }

    fun cancelConvert() {
        M3U8TaskManager.cancelAll(getApplication())
    }

    private fun updateItemStatus(id: String, status: M3U8Status, progress: Float, speed: String = "") {
        _uiState.update { state ->
            state.copy(items = state.items.map {
                if (it.id == id) it.copy(status = status, progress = progress, speed = speed) else it
            })
        }
    }

    fun setDeleteSourceAfterConvert(enable: Boolean) {
        _uiState.update { it.copy(deleteSourceAfterConvert = enable) }
    }

    fun deleteSourceFiles(item: M3U8Item, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1. 删除 M3U8 清单文件
                if (item.m3u8Path.isNotBlank()) {
                    val m3u8 = File(item.m3u8Path)
                    if (m3u8.exists() && m3u8.isFile) {
                        m3u8.delete()
                    }
                }

                // 2. 删除切片目录或专属切片
                val parent = File(item.parentDir)
                if (parent.exists()) {
                    val name = parent.name
                    val isGenericDir = name.equals("Download", true) ||
                            name.equals("Downloads", true) ||
                            name.equals("QQBrowser", true) ||
                            name.equals("视频", true) ||
                            name.equals("video", true) ||
                            name.equals("Movies", true) ||
                            name.equals("Quark", true) ||
                            name.equals("QuarkDownloads", true)

                    if (!isGenericDir) {
                        // 专属隐藏分片文件夹（如 .hash/），递归删除
                        parent.deleteRecursively()
                    } else {
                        // 若切片散落在公共目录下，只安全删除属于该条目的切片，绝不删 .mp4
                        parent.listFiles()?.forEach { file ->
                            if (file.isFile && !file.name.endsWith(".mp4", ignoreCase = true)) {
                                val fname = file.name
                                if (fname.endsWith(".ts", ignoreCase = true) ||
                                    fname.endsWith(".qsv", ignoreCase = true) ||
                                    fname == "0.key" ||
                                    fname.all { it.isDigit() }
                                ) {
                                    file.delete()
                                }
                            }
                        }
                    }
                }

                // 3. 更新 UI 状态
                _uiState.update { current ->
                    current.copy(items = current.items.map {
                        if (it.id == item.id) {
                            it.copy(segmentCount = 0, m3u8Path = "")
                        } else it
                    })
                }
                onComplete?.let {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        it.invoke()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
