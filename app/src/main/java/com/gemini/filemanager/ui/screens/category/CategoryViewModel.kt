package com.gemini.filemanager.ui.screens.category

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.FileScanner
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class ClipboardAction {
    COPY,
    CUT
}

enum class SortOption(val displayName: String) {
    TIME_DESC("时间 ↓ (最新)"),
    TIME_ASC("时间 ↑ (最早)"),
    SIZE_DESC("大小 ↓ (最大)"),
    SIZE_ASC("大小 ↑ (最小)"),
    NAME_ASC("名称 (A-Z)"),
    NAME_DESC("名称 (Z-A)")
}

data class CategoryUiState(
    val files: List<FileItem> = emptyList(),
    val filteredFiles: List<FileItem> = emptyList(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.TIME_DESC,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedFilePaths: Set<String> = emptySet(),
    val clipboardAction: ClipboardAction? = null,
    val clipboardFiles: List<File> = emptyList()
)

class CategoryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // 全局分类内存缓存：实现打开文件夹起始就秒显刚加载的内容
        private val categoryCache = ConcurrentHashMap<FileCategory, List<FileItem>>()
    }

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private var currentCategory: FileCategory = FileCategory.DOCUMENT

    fun initCategory(category: FileCategory) {
        currentCategory = category

        // 1. 若有缓存，立即秒开显示，杜绝白屏等待
        val cached = categoryCache[category]
        if (!cached.isNullOrEmpty()) {
            val sorted = applyFilterAndSort(cached, _uiState.value.searchQuery, _uiState.value.sortOption)
            _uiState.update {
                it.copy(
                    files = cached,
                    filteredFiles = sorted,
                    isLoading = false,
                    isRefreshing = true
                )
            }
        } else {
            _uiState.update { it.copy(isLoading = true, isRefreshing = false) }
        }

        // 2. 异步流式增量加载最新内容，加载完再统一刷新
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            val hasExistingData = _uiState.value.files.isNotEmpty()
            if (!hasExistingData) {
                _uiState.update { it.copy(isLoading = true) }
            } else {
                _uiState.update { it.copy(isRefreshing = true) }
            }

            try {
                val fullList = FileScanner.scanCategoryProgressive(
                    context = getApplication(),
                    category = currentCategory,
                    onBatch = { batch ->
                        // 增量流式更新，打开文件夹时持续呈现刚扫描到的内容
                        _uiState.update { current ->
                            val sorted = applyFilterAndSort(batch, current.searchQuery, current.sortOption)
                            current.copy(
                                files = batch,
                                filteredFiles = sorted,
                                isLoading = false
                            )
                        }
                    }
                )

                // 写入缓存并全局重排刷新
                categoryCache[currentCategory] = fullList
                _uiState.update { current ->
                    val sorted = applyFilterAndSort(fullList, current.searchQuery, current.sortOption)
                    current.copy(
                        files = fullList,
                        filteredFiles = sorted,
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredFiles = applyFilterAndSort(it.files, query, it.sortOption)
            )
        }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update {
            it.copy(
                sortOption = option,
                filteredFiles = applyFilterAndSort(it.files, it.searchQuery, option)
            )
        }
    }

    private fun applyFilterAndSort(list: List<FileItem>, query: String, sort: SortOption): List<FileItem> {
        val filtered = if (query.isBlank()) list else {
            list.filter {
                it.name.contains(query, ignoreCase = true) ||
                (it.appName?.contains(query, ignoreCase = true) == true)
            }
        }
        return when (sort) {
            SortOption.TIME_DESC -> filtered.sortedByDescending { it.lastModified }
            SortOption.TIME_ASC -> filtered.sortedBy { it.lastModified }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.size }
            SortOption.SIZE_ASC -> filtered.sortedBy { it.size }
            SortOption.NAME_ASC -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        }
    }

    fun toggleSelection(path: String) {
        val currentSet = _uiState.value.selectedFilePaths.toMutableSet()
        if (currentSet.contains(path)) {
            currentSet.remove(path)
        } else {
            currentSet.add(path)
        }
        _uiState.update {
            it.copy(
                selectedFilePaths = currentSet,
                isSelectionMode = currentSet.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        val allPaths = _uiState.value.filteredFiles.map { it.path }.toSet()
        _uiState.update {
            it.copy(
                selectedFilePaths = allPaths,
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedFilePaths = emptySet(),
                isSelectionMode = false
            )
        }
    }

    // 复制选中的文件至剪贴板
    fun copySelected() {
        val selected = getSelectedFiles()
        _uiState.update {
            it.copy(
                clipboardAction = ClipboardAction.COPY,
                clipboardFiles = selected,
                isSelectionMode = false,
                selectedFilePaths = emptySet()
            )
        }
    }

    // 剪切选中的文件至剪贴板
    fun cutSelected() {
        val selected = getSelectedFiles()
        _uiState.update {
            it.copy(
                clipboardAction = ClipboardAction.CUT,
                clipboardFiles = selected,
                isSelectionMode = false,
                selectedFilePaths = emptySet()
            )
        }
    }

    // 取消剪贴板
    fun clearClipboard() {
        _uiState.update {
            it.copy(
                clipboardAction = null,
                clipboardFiles = emptyList()
            )
        }
    }

    // 粘贴操作（默认粘贴到 Download 目录）
    fun pasteClipboard(customTargetDir: File? = null, onComplete: (Int) -> Unit) {
        val action = _uiState.value.clipboardAction ?: return
        val files = _uiState.value.clipboardFiles
        if (files.isEmpty()) return

        val targetDir = customTargetDir ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!targetDir.exists()) targetDir.mkdirs()

        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (file in files) {
                if (action == ClipboardAction.COPY) {
                    val result = FileUtils.copyFile(getApplication(), file, targetDir)
                    if (result != null) successCount++
                } else if (action == ClipboardAction.CUT) {
                    val result = FileUtils.moveFile(getApplication(), file, targetDir)
                    if (result != null) successCount++
                }
            }
            clearClipboard()
            loadFiles()
            withContext(Dispatchers.Main) {
                onComplete(successCount)
            }
        }
    }

    fun deleteSelectedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = _uiState.value.selectedFilePaths
            paths.forEach { path ->
                FileUtils.deleteFileOrDirectory(File(path))
            }

            // 原地过滤删除项，保持列表滚动位置不跳到最前面
            _uiState.update { current ->
                val newFiles = current.files.filterNot { paths.contains(it.path) }
                val newFiltered = current.filteredFiles.filterNot { paths.contains(it.path) }
                categoryCache[currentCategory] = newFiles
                current.copy(
                    files = newFiles,
                    filteredFiles = newFiltered,
                    selectedFilePaths = emptySet(),
                    isSelectionMode = false
                )
            }
        }
    }

    /**
     * 重命名文件：原地替换列表项，绝对不会清空列表或跳到最前面
     */
    fun renameFile(path: String, newName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            val result = FileUtils.renameFile(getApplication(), file, newName)
            if (result != null) {
                // 原地精准更新列表项，保持滚动状态与视图位置，不重置到顶部
                val newPath = result.absolutePath
                val newFileName = result.name
                val newLastModified = result.lastModified()
                val newExtension = result.extension.lowercase(Locale.ROOT)

                _uiState.update { current ->
                    val updateItem: (FileItem) -> FileItem = { old ->
                        if (old.path == path) {
                            old.copy(
                                path = newPath,
                                name = newFileName,
                                lastModified = newLastModified,
                                extension = newExtension
                            )
                        } else old
                    }
                    val newFiles = current.files.map(updateItem)
                    val newFiltered = current.filteredFiles.map(updateItem)
                    categoryCache[currentCategory] = newFiles

                    current.copy(
                        files = newFiles,
                        filteredFiles = newFiltered
                    )
                }

                withContext(Dispatchers.Main) {
                    onResult(true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(false)
                }
            }
        }
    }

    fun getSelectedFiles(): List<File> {
        return _uiState.value.selectedFilePaths.map { File(it) }
    }
}
