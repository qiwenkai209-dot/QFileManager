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
    val appFilterType: Int = 1, // 0: 全部应用, 1: 用户安装软件(第三方), 2: 系统应用 (默认展示用户软件更聚焦)
    val appSortType: Int = 0,   // 0: 占用大小降序, 1: 更新时间降序, 2: 名称升序
    val searchQuery: String = "",
    val hasUsageStatsPermission: Boolean = false,
    val totalJunkBytes: Long = 0L,
    val selectedJunkBytes: Long = 0L,
    val selectedCount: Int = 0,
    val currentTab: Int = 0, // 0: 软件应用管理, 1: 全盘垃圾深度清理
    val statusMessage: String? = null
) {
    val selectedApps: List<InstalledAppInfo>
        get() = installedApps.filter { it.isSelected }

    val selectedAppCount: Int
        get() = selectedApps.size

    val selectedAppsTotalExternalBytes: Long
        get() = selectedApps.sumOf { it.totalExternalSize }
}

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

    fun setAppSortType(sortType: Int) {
        _uiState.update { it.copy(appSortType = sortType) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * 检查并刷新使用情况权限状态
     */
    fun checkPermissionAndRefresh() {
        val hasPermission = StorageCleanerEngine.hasUsageStatsPermission(getApplication())
        _uiState.update { it.copy(hasUsageStatsPermission = hasPermission) }
        if (hasPermission) {
            // 重新刷新已安装软件获取精确空间
            viewModelScope.launch {
                val apps = StorageCleanerEngine.getInstalledApps(getApplication())
                _uiState.update { it.copy(installedApps = apps) }
            }
        }
    }

    /**
     * 跳转至手机权限管理页，指导用户开启「读取应用列表」权限（针对小米 MIUI/HyperOS 等定制系统）
     */
    fun openAppListPermissionSettings(context: Context) {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (err: Exception) {
                _uiState.update { it.copy(statusMessage = "无法打开权限设置页") }
            }
        }
    }

    /**
     * 跳转至系统的使用情况授权页面
     */
    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (err: Exception) {
                _uiState.update { it.copy(statusMessage = "无法打开设置页面") }
            }
        }
    }

    /**
     * 启动全面软件与全盘垃圾扫描
     */
    fun startScan() {
        val hasPermission = StorageCleanerEngine.hasUsageStatsPermission(getApplication())
        _uiState.update {
            it.copy(
                isScanning = true,
                scanStage = "正在扫描手机上安装的所有软件...",
                scanCurrentDir = "",
                groups = emptyList(),
                hasUsageStatsPermission = hasPermission,
                statusMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // 1. 扫描手机上安装的所有 App (包含第三方与系统应用，深度统计占用)
                val apps = StorageCleanerEngine.getInstalledApps(getApplication())
                _uiState.update {
                    it.copy(
                        installedApps = apps,
                        scanStage = "已识别 ${apps.size} 款软件，正在扫描全盘垃圾..."
                    )
                }

                // 2. 全盘扫描垃圾文件
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
     * 切换单个软件的多选选中状态
     */
    fun toggleAppSelect(packageName: String) {
        _uiState.update { state ->
            val updated = state.installedApps.map { app ->
                if (app.packageName == packageName) app.copy(isSelected = !app.isSelected) else app
            }
            state.copy(installedApps = updated)
        }
    }

    /**
     * 全选或全不选应用
     */
    fun selectAllApps(select: Boolean) {
        _uiState.update { state ->
            val updated = state.installedApps.map { it.copy(isSelected = select) }
            state.copy(installedApps = updated)
        }
    }

    /**
     * 批量清空勾选软件的外部残留数据与缓存
     */
    fun batchDeleteSelectedAppsExternalData(onFinished: (Int, Long) -> Unit) {
        val selected = _uiState.value.selectedApps
        if (selected.isEmpty()) return

        viewModelScope.launch {
            var totalFreed = 0L
            var successCount = 0
            for (app in selected) {
                val freed = StorageCleanerEngine.deleteAppExternalData(app.packageName)
                if (freed > 0) {
                    totalFreed += freed
                    successCount++
                }
            }

            // 更新状态
            _uiState.update { state ->
                val updatedApps = state.installedApps.map { app ->
                    if (app.isSelected) {
                        app.copy(externalDataSize = 0L, externalObbSize = 0L, externalMediaSize = 0L, isSelected = false)
                    } else {
                        app
                    }
                }
                state.copy(
                    installedApps = updatedApps,
                    statusMessage = "批量清理完成！共释放了 ${FileUtils.formatFileSize(totalFreed)} 存储空间！"
                )
            }

            onFinished(successCount, totalFreed)
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
     * 全选所有垃圾项或全不选
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
     * 执行物理安全清理垃圾文件
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
     * 删除指定软件在外部存储卡下的所有离线缓存与数据 (Android/data/<pkg>, Android/obb/<pkg> 和关联目录)
     */
    fun deleteAppExternalData(packageName: String, onFinished: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = StorageCleanerEngine.deleteAppExternalData(packageName)
            _uiState.update { state ->
                val updatedApps = state.installedApps.map { app ->
                    if (app.packageName == packageName) {
                        app.copy(externalDataSize = 0L, externalObbSize = 0L, externalMediaSize = 0L)
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
