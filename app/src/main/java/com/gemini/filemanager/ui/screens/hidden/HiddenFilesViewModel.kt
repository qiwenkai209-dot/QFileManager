package com.gemini.filemanager.ui.screens.hidden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.HiddenFileItem
import com.gemini.filemanager.core.file.HiddenFileManager
import com.gemini.filemanager.data.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HiddenFilesUiState(
    val items: List<HiddenFileItem> = emptyList(),
    val filteredItems: List<HiddenFileItem> = emptyList(),
    val filterCategory: FileCategory? = null,
    val isScanning: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val restoredCount: Int = 0,
    val message: String? = null
)

class HiddenFilesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // 静态缓存：进入时立即显示历史加载内容
        private var memoryCache: List<HiddenFileItem>? = null
    }

    private val _uiState = MutableStateFlow(HiddenFilesUiState())
    val uiState: StateFlow<HiddenFilesUiState> = _uiState.asStateFlow()

    init {
        // 1. 若有缓存先秒显刚加载的内容
        val cached = memoryCache
        if (!cached.isNullOrEmpty()) {
            _uiState.update {
                it.copy(
                    items = cached,
                    filteredItems = filterItems(cached, it.filterCategory),
                    isRefreshing = true
                )
            }
        }
        startScan()
    }

    fun startScan() {
        viewModelScope.launch {
            val hasData = _uiState.value.items.isNotEmpty()
            if (!hasData) {
                _uiState.update { it.copy(isScanning = true) }
            } else {
                _uiState.update { it.copy(isRefreshing = true) }
            }

            try {
                val fullList = HiddenFileManager.scanHiddenFiles(
                    context = getApplication(),
                    onBatch = { batch ->
                        val distinctBatch = batch.distinctBy { it.fileItem.path }
                        _uiState.update { current ->
                            current.copy(
                                items = distinctBatch,
                                filteredItems = filterItems(distinctBatch, current.filterCategory),
                                isScanning = false
                            )
                        }
                    }
                )

                val distinctFull = fullList.distinctBy { it.fileItem.path }
                memoryCache = distinctFull
                _uiState.update { current ->
                    current.copy(
                        items = distinctFull,
                        filteredItems = filterItems(distinctFull, current.filterCategory),
                        isScanning = false,
                        isRefreshing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanning = false, isRefreshing = false) }
            }
        }
    }

    fun setFilterCategory(category: FileCategory?) {
        _uiState.update {
            it.copy(
                filterCategory = category,
                filteredItems = filterItems(it.items, category)
            )
        }
    }

    private fun filterItems(items: List<HiddenFileItem>, category: FileCategory?): List<HiddenFileItem> {
        val distinct = items.distinctBy { it.fileItem.path }
        if (category == null) return distinct
        return distinct.filter { it.fileItem.category == category }
    }

    fun toggleSelection(path: String) {
        val currentSet = _uiState.value.selectedPaths.toMutableSet()
        if (currentSet.contains(path)) currentSet.remove(path) else currentSet.add(path)
        _uiState.update {
            it.copy(
                selectedPaths = currentSet,
                isSelectionMode = currentSet.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        val all = _uiState.value.filteredItems.map { it.fileItem.path }.toSet()
        _uiState.update {
            it.copy(
                selectedPaths = all,
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedPaths = emptySet(),
                isSelectionMode = false
            )
        }
    }

    /**
     * 批量恢复选中的隐藏文件为常规文件
     */
    fun restoreSelected(onComplete: (Int) -> Unit) {
        val targets = _uiState.value.items.filter { _uiState.value.selectedPaths.contains(it.fileItem.path) }
        if (targets.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            val successPaths = mutableSetOf<String>()
            for (item in targets) {
                val restored = HiddenFileManager.restoreFileToNormal(getApplication(), item)
                if (restored != null) {
                    count++
                    successPaths.add(item.fileItem.path)
                }
            }

            // 原地从隐藏列表移除已恢复项，保持视图稳定
            _uiState.update { current ->
                val newItems = current.items.filterNot { successPaths.contains(it.fileItem.path) }
                memoryCache = newItems
                current.copy(
                    items = newItems,
                    filteredItems = filterItems(newItems, current.filterCategory),
                    selectedPaths = emptySet(),
                    isSelectionMode = false,
                    restoredCount = current.restoredCount + count,
                    message = "成功恢复 $count 个文件至常规媒体目录"
                )
            }

            withContext(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    /**
     * 单项恢复
     */
    fun restoreSingle(item: HiddenFileItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = HiddenFileManager.restoreFileToNormal(getApplication(), item)
            val success = result != null
            if (success) {
                _uiState.update { current ->
                    val newItems = current.items.filterNot { it.fileItem.path == item.fileItem.path }
                    memoryCache = newItems
                    current.copy(
                        items = newItems,
                        filteredItems = filterItems(newItems, current.filterCategory),
                        restoredCount = current.restoredCount + 1
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
            }
        }
    }
}
