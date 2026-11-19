package com.gemini.filemanager.ui.screens.explorer

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import com.gemini.filemanager.ui.screens.category.ClipboardAction
import com.gemini.filemanager.ui.screens.category.SortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ExplorerBreadcrumb(
    val label: String,
    val path: String
)

data class ExplorerUiState(
    val currentPath: String = "",
    val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val filteredFiles: List<FileItem> = emptyList(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.NAME_ASC,
    val isLoading: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedFilePaths: Set<String> = emptySet(),
    val clipboardAction: ClipboardAction? = null,
    val clipboardFiles: List<File> = emptyList(),
    val errorMessage: String? = null
)

class StorageExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val rootDirectory: File = Environment.getExternalStorageDirectory()
    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    init {
        openDirectory(rootDirectory)
    }

    fun openDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val path = dir.absolutePath
        val crumbs = buildBreadcrumbs(dir)

        _uiState.update {
            it.copy(
                currentPath = path,
                breadcrumbs = crumbs,
                searchQuery = "",
                isLoading = true,
                isSelectionMode = false,
                selectedFilePaths = emptySet(),
                errorMessage = null
            )
        }

        loadCurrentDirectory(dir)
    }

    fun navigateUp(): Boolean {
        val current = File(_uiState.value.currentPath)
        val rootPath = rootDirectory.absolutePath
        if (current.absolutePath == rootPath) {
            return false // 已在根目录
        }
        val parent = current.parentFile
        if (parent != null && parent.exists() && parent.absolutePath.startsWith(rootPath)) {
            openDirectory(parent)
            return true
        } else if (parent != null && parent.absolutePath == rootPath) {
            openDirectory(rootDirectory)
            return true
        }
        return false
    }

    fun navigateToBreadcrumb(index: Int) {
        val crumbs = _uiState.value.breadcrumbs
        if (index in crumbs.indices) {
            val target = File(crumbs[index].path)
            if (target.exists() && target.isDirectory) {
                openDirectory(target)
            }
        }
    }

    fun refreshCurrentDirectory() {
        val current = File(_uiState.value.currentPath)
        if (current.exists() && current.isDirectory) {
            loadCurrentDirectory(current)
        }
    }

    private fun loadCurrentDirectory(dir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subFiles = dir.listFiles()?.toList() ?: emptyList()
                val items = subFiles.map { f ->
                    val isDir = f.isDirectory
                    val ext = if (isDir) "" else f.extension.lowercase(Locale.ROOT)
                    val cat = if (isDir) FileCategory.DOCUMENT else FileUtils.getCategory(ext)
                    val subCount = if (isDir) (f.listFiles()?.size ?: 0) else 0

                    FileItem(
                        path = f.absolutePath,
                        name = f.name,
                        size = if (isDir) subCount.toLong() else f.length(),
                        lastModified = f.lastModified(),
                        isDirectory = isDir,
                        mimeType = if (isDir) "resource/folder" else FileUtils.getMimeType(f),
                        extension = ext,
                        category = cat,
                        uri = Uri.fromFile(f)
                    )
                }

                val sorted = applyFilterAndSort(items, _uiState.value.searchQuery, _uiState.value.sortOption)

                _uiState.update {
                    it.copy(
                        files = items,
                        filteredFiles = sorted,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "读取目录失败: ${e.message}"
                    )
                }
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

    fun setSortOption(sort: SortOption) {
        _uiState.update {
            it.copy(
                sortOption = sort,
                filteredFiles = applyFilterAndSort(it.files, it.searchQuery, sort)
            )
        }
    }

    private fun applyFilterAndSort(list: List<FileItem>, query: String, sort: SortOption): List<FileItem> {
        val filtered = if (query.isBlank()) list else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }

        // 文件夹始终排在最前面
        val dirs = filtered.filter { it.isDirectory }
        val nonDirs = filtered.filter { !it.isDirectory }

        val sortComparator: Comparator<FileItem> = when (sort) {
            SortOption.TIME_DESC -> compareByDescending { it.lastModified }
            SortOption.TIME_ASC -> compareBy { it.lastModified }
            SortOption.SIZE_DESC -> compareByDescending { it.size }
            SortOption.SIZE_ASC -> compareBy { it.size }
            SortOption.NAME_ASC -> compareBy { it.name.lowercase(Locale.ROOT) }
            SortOption.NAME_DESC -> compareByDescending { it.name.lowercase(Locale.ROOT) }
        }

        return dirs.sortedWith(sortComparator) + nonDirs.sortedWith(sortComparator)
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

    fun clearClipboard() {
        _uiState.update {
            it.copy(
                clipboardAction = null,
                clipboardFiles = emptyList()
            )
        }
    }

    fun pasteClipboard(onComplete: (Int) -> Unit) {
        val action = _uiState.value.clipboardAction ?: return
        val files = _uiState.value.clipboardFiles
        if (files.isEmpty()) return

        val targetDir = File(_uiState.value.currentPath)
        if (!targetDir.exists() || !targetDir.isDirectory) return

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
            loadCurrentDirectory(targetDir)
            withContext(Dispatchers.Main) {
                onComplete(successCount)
            }
        }
    }

    fun deleteSelectedFiles(onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = _uiState.value.selectedFilePaths
            var deletedCount = 0
            paths.forEach { path ->
                if (FileUtils.deleteFileOrDirectory(File(path))) {
                    deletedCount++
                }
            }
            val currentDir = File(_uiState.value.currentPath)
            loadCurrentDirectory(currentDir)
            withContext(Dispatchers.Main) {
                onComplete(deletedCount)
            }
        }
    }

    fun deleteSingleFile(file: File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = FileUtils.deleteFileOrDirectory(file)
            val currentDir = File(_uiState.value.currentPath)
            loadCurrentDirectory(currentDir)
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }

    fun renameItem(path: String, newName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            val safeName = FileUtils.resolveSafeNewFileName(file, newName)
            val parent = file.parentFile
            val targetFile = if (parent != null) File(parent, safeName) else null

            val resultFile: File? = if (file.exists()) {
                FileUtils.renameFile(getApplication(), file, safeName)
            } else if (targetFile != null && targetFile.exists()) {
                targetFile
            } else {
                null
            }

            if (resultFile != null) {
                val currentDir = File(_uiState.value.currentPath)
                loadCurrentDirectory(currentDir)
                withContext(Dispatchers.Main) {
                    onResult(true, null)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(false, "重命名失败，同名文件或文件夹已存在")
                }
            }
        }
    }

    fun createNewFolder(folderName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = folderName.trim()
            if (trimmed.isBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "文件夹名称不能为空") }
                return@launch
            }
            val currentDir = File(_uiState.value.currentPath)
            val newDir = File(currentDir, trimmed)
            if (newDir.exists()) {
                withContext(Dispatchers.Main) { onResult(false, "同名文件夹已存在") }
                return@launch
            }
            val created = newDir.mkdirs()
            if (created) {
                loadCurrentDirectory(currentDir)
                withContext(Dispatchers.Main) { onResult(true, null) }
            } else {
                withContext(Dispatchers.Main) { onResult(false, "创建文件夹失败") }
            }
        }
    }

    fun getSelectedFiles(): List<File> {
        return _uiState.value.selectedFilePaths.map { File(it) }
    }

    private fun buildBreadcrumbs(dir: File): List<ExplorerBreadcrumb> {
        val rootPath = rootDirectory.absolutePath
        val dirPath = dir.absolutePath

        if (dirPath == rootPath) {
            return listOf(ExplorerBreadcrumb("手机存储", rootPath))
        }

        val relative = if (dirPath.startsWith(rootPath)) {
            dirPath.removePrefix(rootPath).trim('/', '\\')
        } else {
            dirPath.trim('/', '\\')
        }

        val segments = relative.split('/', '\\').filter { it.isNotBlank() }
        val list = mutableListOf(ExplorerBreadcrumb("手机存储", rootPath))

        var curPath = rootPath
        for (seg in segments) {
            curPath = "$curPath/$seg"
            list.add(ExplorerBreadcrumb(seg, curPath))
        }
        return list
    }
}
