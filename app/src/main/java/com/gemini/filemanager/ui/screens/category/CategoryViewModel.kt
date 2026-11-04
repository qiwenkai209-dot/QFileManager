package com.gemini.filemanager.ui.screens.category

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.FileScanner
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
    val isSelectionMode: Boolean = false,
    val selectedFilePaths: Set<String> = emptySet(),
    val clipboardAction: ClipboardAction? = null,
    val clipboardFiles: List<File> = emptyList()
)

class CategoryViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private var currentCategory: FileCategory = FileCategory.DOCUMENT

    fun initCategory(category: FileCategory) {
        currentCategory = category
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val list = FileScanner.scanCategory(getApplication(), currentCategory)
            val sortedAndFiltered = applyFilterAndSort(list, _uiState.value.searchQuery, _uiState.value.sortOption)
            _uiState.value = _uiState.value.copy(
                files = list,
                filteredFiles = sortedAndFiltered,
                isLoading = false
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredFiles = applyFilterAndSort(_uiState.value.files, query, _uiState.value.sortOption)
        )
    }

    fun setSortOption(option: SortOption) {
        _uiState.value = _uiState.value.copy(
            sortOption = option,
            filteredFiles = applyFilterAndSort(_uiState.value.files, _uiState.value.searchQuery, option)
        )
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
        _uiState.value = _uiState.value.copy(
            selectedFilePaths = currentSet,
            isSelectionMode = currentSet.isNotEmpty()
        )
    }

    fun selectAll() {
        val allPaths = _uiState.value.filteredFiles.map { it.path }.toSet()
        _uiState.value = _uiState.value.copy(
            selectedFilePaths = allPaths,
            isSelectionMode = true
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedFilePaths = emptySet(),
            isSelectionMode = false
        )
    }

    // 复制选中的文件至剪贴板
    fun copySelected() {
        val selected = getSelectedFiles()
        _uiState.value = _uiState.value.copy(
            clipboardAction = ClipboardAction.COPY,
            clipboardFiles = selected,
            isSelectionMode = false,
            selectedFilePaths = emptySet()
        )
    }

    // 剪切选中的文件至剪贴板
    fun cutSelected() {
        val selected = getSelectedFiles()
        _uiState.value = _uiState.value.copy(
            clipboardAction = ClipboardAction.CUT,
            clipboardFiles = selected,
            isSelectionMode = false,
            selectedFilePaths = emptySet()
        )
    }

    // 取消剪贴板
    fun clearClipboard() {
        _uiState.value = _uiState.value.copy(
            clipboardAction = null,
            clipboardFiles = emptyList()
        )
    }

    // 粘贴操作（默认粘贴到 Download 目录）
    fun pasteClipboard(customTargetDir: File? = null, onComplete: (Int) -> Unit) {
        val action = _uiState.value.clipboardAction ?: return
        val files = _uiState.value.clipboardFiles
        if (files.isEmpty()) return

        val targetDir = customTargetDir ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!targetDir.exists()) targetDir.mkdirs()

        viewModelScope.launch {
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
            onComplete(successCount)
        }
    }

    fun deleteSelectedFiles() {
        viewModelScope.launch {
            val paths = _uiState.value.selectedFilePaths
            paths.forEach { path ->
                FileUtils.deleteFileOrDirectory(File(path))
            }
            clearSelection()
            loadFiles()
        }
    }

    fun renameFile(path: String, newName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val file = File(path)
            val result = FileUtils.renameFile(getApplication(), file, newName)
            if (result != null) {
                loadFiles()
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun getSelectedFiles(): List<File> {
        return _uiState.value.selectedFilePaths.map { File(it) }
    }
}
