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
import com.gemini.filemanager.core.file.WeChatDuplicateGroup
import com.gemini.filemanager.core.file.WeChatFileItem
import com.gemini.filemanager.core.file.WeChatScanResult
import com.gemini.filemanager.core.file.WeChatStorageCleaner
import com.gemini.filemanager.data.model.InstalledAppInfo
import com.gemini.filemanager.data.model.JunkCategory
import com.gemini.filemanager.data.model.JunkGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CleanerUiState(
    val isScanning: Boolean = false,
    val isRefreshing: Boolean = false,
    val scanStage: String = "",
    val scanCurrentDir: String = "",
    val groups: List<JunkGroup> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val appFilterType: Int = 1, // 0: 全部应用, 1: 用户安装软件(第三方), 2: 系统应用
    val appSortType: Int = 0,   // 0: 占用大小降序, 1: 更新时间降序, 2: 名称升序
    val searchQuery: String = "",
    val hasUsageStatsPermission: Boolean = false,
    val totalJunkBytes: Long = 0L,
    val selectedJunkBytes: Long = 0L,
    val selectedCount: Int = 0,
    val currentTab: Int = 0, // 0: 软件应用管理, 1: 全盘垃圾深度清理, 2: 微信精准专清

    // 微信精准专清状态（最新算法：根目录隐藏弃用文件 + 文件名大小校对 + 最晚版默认保留）
    val isScanningWeChat: Boolean = false,
    val weChatDuplicateGroups: List<WeChatDuplicateGroup> = emptyList(),
    val weChatDeprecatedFiles: List<WeChatFileItem> = emptyList(),
    val weChatSelectedPaths: Set<String> = emptySet(),
    val weChatTotalBytes: Long = 0L,
    val weChatReclaimableBytes: Long = 0L,

    val statusMessage: String? = null
) {
    val selectedApps: List<InstalledAppInfo>
        get() = installedApps.filter { it.isSelected }

    val selectedAppCount: Int
        get() = selectedApps.size

    val selectedAppsTotalExternalBytes: Long
        get() = selectedApps.sumOf { it.totalExternalSize }

    val weChatSelectedBytes: Long
        get() {
            var sum = 0L
            weChatDeprecatedFiles.forEach {
                if (weChatSelectedPaths.contains(it.fileItem.path)) sum += it.fileItem.size
            }
            weChatDuplicateGroups.forEach { group ->
                group.redundantItems.forEach {
                    if (weChatSelectedPaths.contains(it.fileItem.path)) sum += it.fileItem.size
                }
                if (weChatSelectedPaths.contains(group.latestItem.fileItem.path)) {
                    sum += group.latestItem.fileItem.size
                }
            }
            return sum
        }
}

class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // 全局静态缓存：打开文件夹起始就秒显刚加载的内容
        private var cachedApps: List<InstalledAppInfo>? = null
        private var cachedGroups: List<JunkGroup>? = null
        private var cachedWeChat: WeChatScanResult? = null
    }

    private val _uiState = MutableStateFlow(CleanerUiState())
    val uiState: StateFlow<CleanerUiState> = _uiState.asStateFlow()

    init {
        // 1. 若有缓存先秒显刚加载的内容，体验零等待
        val cApps = cachedApps
        val cGroups = cachedGroups
        val cWeChat = cachedWeChat
        if (cApps != null || cGroups != null || cWeChat != null) {
            val totalBytes = cGroups?.sumOf { it.totalSize } ?: 0L
            val selectedBytes = cGroups?.sumOf { it.selectedSize } ?: 0L
            val selCount = cGroups?.sumOf { it.selectedCount } ?: 0

            _uiState.update {
                it.copy(
                    installedApps = cApps ?: emptyList(),
                    groups = cGroups ?: emptyList(),
                    totalJunkBytes = totalBytes,
                    selectedJunkBytes = selectedBytes,
                    selectedCount = selCount,
                    weChatDuplicateGroups = cWeChat?.duplicateGroups ?: emptyList(),
                    weChatDeprecatedFiles = cWeChat?.deprecatedFiles ?: emptyList(),
                    weChatTotalBytes = cWeChat?.totalScannedBytes ?: 0L,
                    weChatReclaimableBytes = cWeChat?.reclaimableBytes ?: 0L,
                    isRefreshing = true
                )
            }
            initWeChatDefaultSelections(cWeChat?.duplicateGroups ?: emptyList(), cWeChat?.deprecatedFiles ?: emptyList())
        }

        startScan()
        scanWeChat()
    }

    fun setTab(tabIndex: Int) {
        _uiState.update { it.copy(currentTab = tabIndex) }
        if (tabIndex == 2 && _uiState.value.weChatDuplicateGroups.isEmpty() && !_uiState.value.isScanningWeChat) {
            scanWeChat()
        }
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

    fun checkPermissionAndRefresh() {
        val hasPermission = StorageCleanerEngine.hasUsageStatsPermission(getApplication())
        _uiState.update { it.copy(hasUsageStatsPermission = hasPermission) }
        if (hasPermission) {
            viewModelScope.launch {
                val apps = StorageCleanerEngine.getInstalledApps(getApplication())
                cachedApps = apps
                _uiState.update { it.copy(installedApps = apps) }
            }
        }
    }

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
     * 启动全面软件与全盘垃圾扫描：异步分批加载，扫描完重新统一刷新
     */
    fun startScan() {
        val hasPermission = StorageCleanerEngine.hasUsageStatsPermission(getApplication())
        val hasData = _uiState.value.groups.isNotEmpty() || _uiState.value.installedApps.isNotEmpty()

        _uiState.update {
            it.copy(
                isScanning = !hasData,
                isRefreshing = hasData,
                scanStage = "正在深度嗅探手机垃圾与软件缓存...",
                scanCurrentDir = "",
                hasUsageStatsPermission = hasPermission,
                statusMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // 1. 扫描手机上安装的所有 App
                val apps = StorageCleanerEngine.getInstalledApps(getApplication())
                cachedApps = apps
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

                cachedGroups = groups
                _uiState.update { state ->
                    val totalBytes = groups.sumOf { it.totalSize }
                    val selectedBytes = groups.sumOf { it.selectedSize }
                    val selCount = groups.sumOf { it.selectedCount }

                    state.copy(
                        isScanning = false,
                        isRefreshing = false,
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
                        isRefreshing = false,
                        scanStage = "扫描完成 (部分受阻: ${e.message})"
                    )
                }
            }
        }
    }

    /**
     * 微信精准专清：最新算法扫描根目录隐藏弃用文件，文件名与大小精确校对，默认最晚版保留
     */
    fun scanWeChat() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningWeChat = true) }
            try {
                val result = WeChatStorageCleaner.scanWeChatStorage(getApplication())
                cachedWeChat = result

                initWeChatDefaultSelections(result.duplicateGroups, result.deprecatedFiles)

                _uiState.update { current ->
                    current.copy(
                        isScanningWeChat = false,
                        weChatDuplicateGroups = result.duplicateGroups,
                        weChatDeprecatedFiles = result.deprecatedFiles,
                        weChatTotalBytes = result.totalScannedBytes,
                        weChatReclaimableBytes = result.reclaimableBytes
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanningWeChat = false) }
            }
        }
    }

    private fun initWeChatDefaultSelections(
        groups: List<WeChatDuplicateGroup>,
        deprecated: List<WeChatFileItem>
    ) {
        val selected = mutableSetOf<String>()
        // 1. 弃用/损坏/日志文件默认全部选中
        deprecated.forEach { selected.add(it.fileItem.path) }
        // 2. 重复版本中：最晚修改版保留不选，其余历史副本全部默认勾选待删
        groups.forEach { g ->
            g.redundantItems.forEach { selected.add(it.fileItem.path) }
        }
        _uiState.update { it.copy(weChatSelectedPaths = selected) }
    }

    fun toggleWeChatFileSelect(path: String) {
        val set = _uiState.value.weChatSelectedPaths.toMutableSet()
        if (set.contains(path)) set.remove(path) else set.add(path)
        _uiState.update { it.copy(weChatSelectedPaths = set) }
    }

    fun selectAllWeChatRedundant() {
        initWeChatDefaultSelections(_uiState.value.weChatDuplicateGroups, _uiState.value.weChatDeprecatedFiles)
    }

    fun clearAllWeChatSelections() {
        _uiState.update { it.copy(weChatSelectedPaths = emptySet()) }
    }

    /**
     * 执行选中的微信冗余及弃用文件一键删除
     */
    fun cleanSelectedWeChatFiles(onComplete: (Long) -> Unit) {
        val paths = _uiState.value.weChatSelectedPaths.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val freed = WeChatStorageCleaner.deleteWeChatFiles(getApplication(), paths)
            val deletedSet = paths.toSet()

            _uiState.update { current ->
                val newDeprecated = current.weChatDeprecatedFiles.filterNot { deletedSet.contains(it.fileItem.path) }
                val newDuplicateGroups = current.weChatDuplicateGroups.mapNotNull { g ->
                    val remainingRedundants = g.redundantItems.filterNot { deletedSet.contains(it.fileItem.path) }
                    val latestDeleted = deletedSet.contains(g.latestItem.fileItem.path)
                    if (latestDeleted && remainingRedundants.isEmpty()) {
                        null
                    } else if (latestDeleted && remainingRedundants.isNotEmpty()) {
                        // 若用户强行把最晚版也删了，将剩余中最晚版提升为保留
                        val newLatest = remainingRedundants.first()
                        g.copy(
                            latestItem = newLatest.copy(isSelectedForDelete = false),
                            redundantItems = remainingRedundants.drop(1)
                        )
                    } else if (remainingRedundants.isEmpty()) {
                        null // 冗余副本已全部清理完毕，移出重复组
                    } else {
                        g.copy(redundantItems = remainingRedundants)
                    }
                }

                current.copy(
                    weChatDeprecatedFiles = newDeprecated,
                    weChatDuplicateGroups = newDuplicateGroups,
                    weChatSelectedPaths = emptySet(),
                    weChatReclaimableBytes = (current.weChatReclaimableBytes - freed).coerceAtLeast(0L),
                    statusMessage = "成功清理微信占用 ${FileUtils.formatFileSize(freed)}"
                )
            }

            withContext(Dispatchers.Main) {
                onComplete(freed)
            }
        }
    }

    fun toggleAppSelect(packageName: String) {
        _uiState.update { state ->
            val updated = state.installedApps.map { app ->
                if (app.packageName == packageName) app.copy(isSelected = !app.isSelected) else app
            }
            state.copy(installedApps = updated)
        }
    }

    fun selectAllApps(select: Boolean) {
        _uiState.update { state ->
            val updated = state.installedApps.map { it.copy(isSelected = select) }
            state.copy(installedApps = updated)
        }
    }

    fun toggleJunkItem(groupCategory: JunkCategory, itemPath: String) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.category == groupCategory) {
                    val updatedItems = group.items.map { item ->
                        if (item.path == itemPath) item.copy(isSelected = !item.isSelected) else item
                    }
                    group.copy(items = updatedItems)
                } else group
            }
            val selBytes = updatedGroups.sumOf { it.selectedSize }
            val selCount = updatedGroups.sumOf { it.selectedCount }
            state.copy(
                groups = updatedGroups,
                selectedJunkBytes = selBytes,
                selectedCount = selCount
            )
        }
    }

    fun toggleGroupSelectAll(groupCategory: JunkCategory, selectAll: Boolean) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.category == groupCategory) {
                    val updatedItems = group.items.map { it.copy(isSelected = selectAll) }
                    group.copy(items = updatedItems)
                } else group
            }
            val selBytes = updatedGroups.sumOf { it.selectedSize }
            val selCount = updatedGroups.sumOf { it.selectedCount }
            state.copy(
                groups = updatedGroups,
                selectedJunkBytes = selBytes,
                selectedCount = selCount
            )
        }
    }

    fun cleanSelected(onComplete: (Int, Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedItems = _uiState.value.groups.flatMap { it.items }.filter { it.isSelected }
            val (deletedCount, freedBytes) = StorageCleanerEngine.cleanJunkItems(
                context = getApplication(),
                items = selectedItems
            )
            withContext(Dispatchers.Main) {
                startScan()
                onComplete(deletedCount, freedBytes)
            }
        }
    }

    fun cleanSelectedJunk(onComplete: (Long, Int) -> Unit) {
        cleanSelected { count, freed ->
            onComplete(freed, count)
        }
    }

    fun cleanSelectedAppsExternal(onComplete: (Long, Int) -> Unit) {
        batchDeleteSelectedAppsExternalData { count, freed ->
            onComplete(freed, count)
        }
    }

    fun toggleGroupExpand(category: JunkCategory) {
        _uiState.update { state ->
            val updated = state.groups.map { group ->
                if (group.category == category) group.copy(isExpanded = !group.isExpanded) else group
            }
            state.copy(groups = updated)
        }
    }

    fun toggleGroupSelect(category: JunkCategory) {
        _uiState.update { state ->
            val updated = state.groups.map { group ->
                if (group.category == category) {
                    val nextSelect = !group.isAllSelected
                    val items = group.items.map { it.copy(isSelected = nextSelect) }
                    group.copy(items = items)
                } else group
            }
            val selBytes = updated.sumOf { it.selectedSize }
            val selCount = updated.sumOf { it.selectedCount }
            state.copy(groups = updated, selectedJunkBytes = selBytes, selectedCount = selCount)
        }
    }

    fun toggleItemSelect(category: JunkCategory, path: String) {
        toggleJunkItem(category, path)
    }

    fun selectAll(select: Boolean) {
        _uiState.update { state ->
            val updated = state.groups.map { group ->
                val items = group.items.map { it.copy(isSelected = select) }
                group.copy(items = items)
            }
            val selBytes = updated.sumOf { it.selectedSize }
            val selCount = updated.sumOf { it.selectedCount }
            state.copy(groups = updated, selectedJunkBytes = selBytes, selectedCount = selCount)
        }
    }

    fun openAppDetails(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

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

    fun deleteAppExternalData(packageName: String, onFinished: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = StorageCleanerEngine.deleteAppExternalData(packageName)
            _uiState.update { state ->
                val updatedApps = state.installedApps.map { app ->
                    if (app.packageName == packageName) {
                        app.copy(externalDataSize = 0L, externalObbSize = 0L, externalMediaSize = 0L)
                    } else app
                }
                state.copy(
                    installedApps = updatedApps,
                    statusMessage = if (freed > 0) "成功清空该软件的外部缓存与数据，释放了 ${FileUtils.formatFileSize(freed)}！" else "该软件在外部存储卡下暂无额外数据残留"
                )
            }
            withContext(Dispatchers.Main) {
                onFinished(freed)
            }
        }
    }

    fun batchDeleteSelectedAppsExternalData(onFinished: (Int, Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedApps = _uiState.value.selectedApps
            var totalFreed = 0L
            var count = 0
            for (app in selectedApps) {
                val freed = StorageCleanerEngine.deleteAppExternalData(app.packageName)
                totalFreed += freed
                count++
            }
            withContext(Dispatchers.Main) {
                startScan()
                onFinished(count, totalFreed)
            }
        }
    }
}
