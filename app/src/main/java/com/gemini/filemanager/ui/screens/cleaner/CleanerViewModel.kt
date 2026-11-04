package com.gemini.filemanager.ui.screens.cleaner

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.core.file.StorageCleanerEngine
import com.gemini.filemanager.data.model.InstalledAppInfo
import com.gemini.filemanager.data.model.JunkCategory
import com.gemini.filemanager.data.model.JunkGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CleanerUiState(
    val isScanning: Boolean = false,
    val scanStage: String = "",
    val scanCurrentDir: String = "",
    val groups: List<JunkGroup> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val appFilterType: Int = 0, // 0: 全部应用, 1: 第三方应用, 2: 系统应用
    val searchQuery: String = "",
    val totalJunkBytes: Long = 0L,
    val selectedJunkBytes: Long = 0L,
    val selectedCount: Int = 0,
    val currentTab: Int = 0, // 0: 垃圾清理, 1: 应用管理
    val statusMessage: String? = null
)

class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CleanerUiState())
    val uiState: StateFlow<CleanerUiState> = _uiState.asStateFlow()

    init {
        startScan()
    }

    fun setTab(tabIndex: Int) {
        _uiState.update { it.copy(currentTab = tabIndex) }
    }

    fun setAppFilterType(filterType: Int) {
        _uiState.update { it.copy(appFilterType = filterType) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * 启动全面垃圾与应用扫描
     */
    fun startScan() {
        _uiState.update {
            it.copy(
                isScanning = true,
                scanStage = "正在初始化扫描引擎...",
                scanCurrentDir = "",
                groups = emptyList(),
                statusMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // 扫描已安装 App
                val apps = StorageCleanerEngine.getInstalledApps(getApplication())
                _uiState.update { it.copy(installedApps = apps) }

                // 全盘扫描垃圾文件
                val groups = StorageCleanerEngine.scanJunkFiles(
                    context = getApplication(),
                    callback = object : StorageCleanerEngine.CleanerScanCallback {
                        override fun onProgress(stage: String, currentDir: String, totalBytesFound: Long) {
                            _uiState.update {
                                it.copy(
                                    scanStage = stage,
                                    scanCurrentDir = currentDir,
                                    totalJunkBytes = totalBytesFound
                                )
                            }
                        }
                    }
                )

                _uiState.update { state ->
                    val totalBytes = groups.sumOf { it.totalSize }
                    val selectedBytes = groups.sumOf { it.selectedSize }
                    val selCount = groups.sumOf { it.selectedCount }

                    state.copy(
                        isScanning = false,
                        scanStage = "扫描完成",
                        groups = groups,
                        totalJunkBytes = totalBytes,
                        selectedJunkBytes = selectedBytes,
                        selectedCount = selCount
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanStage = "扫描完成 (部分受阻: ${e.message})"
                    )
                }
            }
        }
    }

    /**
     * 展开/折叠某类垃圾详情
     */
    fun toggleGroupExpand(category: JunkCategory) {
        _uiState.update { state ->
            val updated = state.groups.map { g ->
                if (g.category == category) g.copy(isExpanded = !g.isExpanded) else g
            }
            state.copy(groups = updated)
        }
    }

    /**
     * 整组全选/全不选
     */
    fun toggleGroupSelect(category: JunkCategory) {
        _uiState.update { state ->
            val updated = state.groups.map { g ->
                if (g.category == category) {
                    val targetSelect = !g.isAllSelected
                    val newItems = g.items.map { it.copy(isSelected = targetSelect) }
                    g.copy(items = newItems)
                } else {
                    g
                }
            }
            recalculateSelection(state, updated)
        }
    }

    /**
     * 单个垃圾项切换勾选
     */
    fun toggleItemSelect(category: JunkCategory, path: String) {
        _uiState.update { state ->
            val updated = state.groups.map { g ->
                if (g.category == category) {
                    val newItems = g.items.map {
                        if (it.path == path) it.copy(isSelected = !it.isSelected) else it
                    }
                    g.copy(items = newItems)
                } else {
                    g
                }
            }
            recalculateSelection(state, updated)
        }
    }

    /**
     * 全选所有或全不选
     */
    fun selectAll(select: Boolean) {
        _uiState.update { state ->
            val updated = state.groups.map { g ->
                val newItems = g.items.map { it.copy(isSelected = select) }
                g.copy(items = newItems)
            }
            recalculateSelection(state, updated)
        }
    }

    /**
     * 执行物理安全清理
     */
    fun cleanSelected(onFinished: (Int, Long) -> Unit) {
        val currentGroups = _uiState.value.groups
        val itemsToClean = currentGroups.flatMap { g -> g.items.filter { it.isSelected } }
        if (itemsToClean.isEmpty()) return

        viewModelScope.launch {
            val (deletedCount, freedBytes) = StorageCleanerEngine.cleanJunkItems(
                context = getApplication(),
                items = itemsToClean
            )

            // 更新列表
            _uiState.update { state ->
                val cleanedPaths = itemsToClean.map { it.path }.toSet()
                val updatedGroups = state.groups.mapNotNull { g ->
                    val remainingItems = g.items.filter { !cleanedPaths.contains(it.path) }
                    if (remainingItems.isNotEmpty()) {
                        g.copy(items = remainingItems)
                    } else {
                        null
                    }
                }

                val totalBytes = updatedGroups.sumOf { it.totalSize }
                val selectedBytes = updatedGroups.sumOf { it.selectedSize }
                val selCount = updatedGroups.sumOf { it.selectedCount }

                state.copy(
                    groups = updatedGroups,
                    totalJunkBytes = totalBytes,
                    selectedJunkBytes = selectedBytes,
                    selectedCount = selCount,
                    statusMessage = "成功清理 $deletedCount 个垃圾项，释放了 ${FileUtils.formatFileSize(freedBytes)} 存储空间！"
                )
            }

            onFinished(deletedCount, freedBytes)
        }
    }

    /**
     * 跳转至系统应用详情设置页，供用户一键清除应用缓存和全部数据
     */
    fun openAppDetails(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 卸载指定的软件应用
     */
    fun uninstallApp(context: Context, packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.fromParts("package", packageName, null)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (err: Exception) {
                _uiState.update { it.copy(statusMessage = "无法唤起系统卸载: ${err.message}") }
            }
        }
    }

    /**
     * 删除指定软件在外部存储卡下的所有离线缓存与数据 (Android/data/<pkg> 和 Android/obb/<pkg>)
     */
    fun deleteAppExternalData(packageName: String, onFinished: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = StorageCleanerEngine.deleteAppExternalData(packageName)
            _uiState.update { state ->
                val updatedApps = state.installedApps.map { app ->
                    if (app.packageName == packageName) {
                        app.copy(externalDataSize = 0L, externalObbSize = 0L)
                    } else {
                        app
                    }
                }
                state.copy(
                    installedApps = updatedApps,
                    statusMessage = if (freed > 0) "成功清空该软件的外部缓存与数据，释放了 ${FileUtils.formatFileSize(freed)}！" else "该软件在外部存储卡下暂无额外数据残留"
                )
            }
            onFinished(freed)
        }
    }

    private fun recalculateSelection(state: CleanerUiState, updatedGroups: List<JunkGroup>): CleanerUiState {
        val totalBytes = updatedGroups.sumOf { it.totalSize }
        val selectedBytes = updatedGroups.sumOf { it.selectedSize }
        val selCount = updatedGroups.sumOf { it.selectedCount }
        return state.copy(
            groups = updatedGroups,
            totalJunkBytes = totalBytes,
            selectedJunkBytes = selectedBytes,
            selectedCount = selCount
        )
    }
}
