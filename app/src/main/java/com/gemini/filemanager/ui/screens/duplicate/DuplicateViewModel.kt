package com.gemini.filemanager.ui.screens.duplicate

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.DuplicateFileScanner
import com.gemini.filemanager.data.model.DuplicateFileItem
import com.gemini.filemanager.data.model.DuplicateFilterType
import com.gemini.filemanager.data.model.DuplicateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DuplicateUiState(
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scanStage: String = "",
    val scanCurrentFile: String = "",
    val currentFilter: DuplicateFilterType = DuplicateFilterType.ALL,
    val groups: List<DuplicateGroup> = emptyList(),
    val totalDuplicateCount: Int = 0,
    val totalReclaimableBytes: Long = 0L,
    val selectedReclaimableBytes: Long = 0L,
    val selectedFileCount: Int = 0,
    val statusMessage: String? = null
)

class DuplicateViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DuplicateUiState())
    val uiState: StateFlow<DuplicateUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        startScan(DuplicateFilterType.ALL)
    }

    /**
     * 启动扫描
     */
    fun startScan(filterType: DuplicateFilterType = _uiState.value.currentFilter) {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                isScanning = true,
                scanProgress = 0f,
                scanStage = "准备启动扫描...",
                scanCurrentFile = "",
                currentFilter = filterType,
                groups = emptyList(),
                statusMessage = null
            )
        }

        scanJob = viewModelScope.launch {
            try {
                val groups = DuplicateFileScanner.scanDuplicates(
                    context = getApplication(),
                    filterType = filterType,
                    callback = object : DuplicateFileScanner.ScanCallback {
                        override fun onProgress(stage: String, progress: Float, currentFile: String) {
                            _uiState.update {
                                it.copy(
                                    scanStage = stage,
                                    scanProgress = progress,
                                    scanCurrentFile = currentFile
                                )
                            }
                        }
                    }
                )

                _uiState.update { state ->
                    val totalDups = groups.sumOf { it.duplicateCount }
                    val totalReclaim = groups.sumOf { it.potentialReclaimBytes }
                    val selectedFiles = groups.sumOf { g -> g.files.count { it.isSelected } }
                    val selectedBytes = groups.sumOf { it.selectedReclaimBytes }

                    state.copy(
                        isScanning = false,
                        scanProgress = 1f,
                        scanStage = "扫描完成",
                        groups = groups,
                        totalDuplicateCount = totalDups,
                        totalReclaimableBytes = totalReclaim,
                        selectedFileCount = selectedFiles,
                        selectedReclaimableBytes = selectedBytes
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanStage = "扫描异常: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 切换过滤类型
     */
    fun setFilter(filterType: DuplicateFilterType) {
        if (_uiState.value.currentFilter != filterType || !_uiState.value.isScanning) {
            startScan(filterType)
        }
    }

    /**
     * 单项切换勾选状态
     */
    fun toggleItemSelection(groupHash: String, targetPath: String) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.hash == groupHash) {
                    val updatedFiles = group.files.map { fileItem ->
                        if (fileItem.path == targetPath) {
                            fileItem.copy(isSelected = !fileItem.isSelected)
                        } else {
                            fileItem
                        }
                    }
                    group.copy(files = updatedFiles)
                } else {
                    group
                }
            }
            recalculateSelection(state, updatedGroups)
        }
    }

    /**
     * 智能预设：保留最早（将修改时间最早的保留，其余副本全选）
     */
    fun smartSelectKeepOldest() {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                val sorted = group.files.sortedBy { it.lastModified }
                val updatedFiles = sorted.mapIndexed { index, item ->
                    item.copy(
                        isOriginal = (index == 0),
                        isSelected = (index != 0)
                    )
                }
                group.copy(files = updatedFiles)
            }
            recalculateSelection(state, updatedGroups)
        }
    }

    /**
     * 智能预设：保留最新（将修改时间最新的保留，其余副本全选）
     */
    fun smartSelectKeepNewest() {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                val sorted = group.files.sortedByDescending { it.lastModified }
                val updatedFiles = sorted.mapIndexed { index, item ->
                    item.copy(
                        isOriginal = (index == 0),
                        isSelected = (index != 0)
                    )
                }
                group.copy(files = updatedFiles)
            }
            recalculateSelection(state, updatedGroups)
        }
    }

    /**
     * 全选所有组的副本（保留原文件）
     */
    fun selectAllDuplicates() {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                val updatedFiles = group.files.map { item ->
                    item.copy(isSelected = !item.isOriginal)
                }
                group.copy(files = updatedFiles)
            }
            recalculateSelection(state, updatedGroups)
        }
    }

    /**
     * 取消所有勾选
     */
    fun clearSelection() {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                val updatedFiles = group.files.map { item ->
                    item.copy(isSelected = false)
                }
                group.copy(files = updatedFiles)
            }
            recalculateSelection(state, updatedGroups)
        }
    }

    /**
     * 执行安全删除已勾选的重复文件
     */
    fun deleteSelectedFiles(onFinished: (deletedCount: Int, reclaimedBytes: Long) -> Unit) {
        val currentGroups = _uiState.value.groups
        val filesToDelete = currentGroups.flatMap { it.files.filter { f -> f.isSelected } }
        if (filesToDelete.isEmpty()) return

        viewModelScope.launch {
            var deletedCount = 0
            var reclaimedBytes = 0L
            val deletedPaths = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                for (item in filesToDelete) {
                    try {
                        val file = File(item.path)
                        val length = file.length()
                        if (file.exists() && file.delete()) {
                            deletedCount++
                            reclaimedBytes += length
                            deletedPaths.add(item.path)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 通知系统媒体库同步刷新被删除的文件
                if (deletedPaths.isNotEmpty()) {
                    try {
                        MediaScannerConnection.scanFile(
                            getApplication(),
                            deletedPaths.toTypedArray(),
                            null,
                            null
                        )
                    } catch (ignored: Exception) {}
                }
            }

            // 更新组状态：移除已删除的文件；如果某组只剩一个文件，则不再是重复文件，解除分组
            _uiState.update { state ->
                val updatedGroups = state.groups.mapNotNull { group ->
                    val remainingFiles = group.files.filter { !deletedPaths.contains(it.path) }
                    if (remainingFiles.size >= 2) {
                        // 重新判定首项原件
                        val hasOriginal = remainingFiles.any { it.isOriginal }
                        val fixedFiles = if (!hasOriginal) {
                            val sorted = remainingFiles.sortedBy { it.lastModified }
                            sorted.mapIndexed { idx, itm -> itm.copy(isOriginal = idx == 0) }
                        } else {
                            remainingFiles
                        }
                        group.copy(files = fixedFiles)
                    } else {
                        null // 剩 1 个或 0 个，解除重复组
                    }
                }

                val totalDups = updatedGroups.sumOf { it.duplicateCount }
                val totalReclaim = updatedGroups.sumOf { it.potentialReclaimBytes }
                val selectedFiles = updatedGroups.sumOf { g -> g.files.count { it.isSelected } }
                val selectedBytes = updatedGroups.sumOf { it.selectedReclaimBytes }

                state.copy(
                    groups = updatedGroups,
                    totalDuplicateCount = totalDups,
                    totalReclaimableBytes = totalReclaim,
                    selectedFileCount = selectedFiles,
                    selectedReclaimableBytes = selectedBytes,
                    statusMessage = "成功清理 $deletedCount 个重复文件，释放了 ${com.gemini.filemanager.core.file.FileUtils.formatFileSize(reclaimedBytes)} 空间！"
                )
            }

            onFinished(deletedCount, reclaimedBytes)
        }
    }

    private fun recalculateSelection(
        state: DuplicateUiState,
        updatedGroups: List<DuplicateGroup>
    ): DuplicateUiState {
        val selectedFiles = updatedGroups.sumOf { g -> g.files.count { it.isSelected } }
        val selectedBytes = updatedGroups.sumOf { it.selectedReclaimBytes }
        return state.copy(
            groups = updatedGroups,
            selectedFileCount = selectedFiles,
            selectedReclaimableBytes = selectedBytes
        )
    }
}
