package com.gemini.filemanager.ui.screens.smb

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.smb.LocalUploadSource
import com.gemini.filemanager.core.smb.SmbClientManager
import com.gemini.filemanager.core.smb.SmbConnectionStore
import com.gemini.filemanager.core.smb.SmbDiscovery
import com.gemini.filemanager.core.smb.SmbTransferManager
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.SmbCategoryFilter
import com.gemini.filemanager.data.model.SmbClipboard
import com.gemini.filemanager.data.model.SmbClipboardAction
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbDiscoveredHost
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbSortOption
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class SmbBreadcrumb(
    val label: String,
    val url: String
)

data class SmbUiState(
    val savedConnections: List<SmbConnectionConfig> = emptyList(),
    val discoveredHosts: List<SmbDiscoveredHost> = emptyList(),
    val isDiscovering: Boolean = false,
    val discoveryProgress: Float = 0f,
    val localIp: String = "",
    val activeConfig: SmbConnectionConfig? = null,
    val currentUrl: String = "",
    val isAtServerRoot: Boolean = false,
    val breadcrumbs: List<SmbBreadcrumb> = emptyList(),
    val files: List<SmbRemoteFile> = emptyList(),
    val filteredFiles: List<SmbRemoteFile> = emptyList(),
    val searchQuery: String = "",
    val sortOption: SmbSortOption = SmbSortOption.NAME_ASC,
    val categoryFilter: SmbCategoryFilter = SmbCategoryFilter.ALL,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSelectionMode: Boolean = false,
    val selectedUrls: Set<String> = emptySet(),
    val clipboard: SmbClipboard? = null,
    val pendingUploadFiles: List<File> = emptyList(),
    val isPreparingPreview: Boolean = false,
    val previewProgress: Float = 0f
)

class SmbViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SmbConnectionStore(application)
    private val _uiState = MutableStateFlow(SmbUiState())
    val uiState: StateFlow<SmbUiState> = _uiState.asStateFlow()

    val transferTasks: StateFlow<List<SmbTransferTask>> = SmbTransferManager.tasks

    private var completedUploadIdsSeen = emptySet<String>()

    private val discovery = SmbDiscovery(application, viewModelScope) { host ->
        _uiState.update { state ->
            val existing = state.discoveredHosts.find { it.ip == host.ip }
            val merged = if (existing == null) {
                state.discoveredHosts + host
            } else {
                state.discoveredHosts.map {
                    if (it.ip == host.ip) {
                        it.copy(
                            hostName = host.hostName.ifBlank { it.hostName },
                            source = if (host.source.contains("mDNS")) host.source else it.source
                        )
                    } else it
                }
            }
            state.copy(discoveredHosts = merged.sortedBy { it.ip })
        }
    }

    val defaultDownloadDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SMB"
        )

    init {
        SmbTransferManager.init(application)
        completedUploadIdsSeen = SmbTransferManager.tasks.value
            .filter { it.direction == SmbTransferDirection.UPLOAD && it.status == SmbTransferStatus.COMPLETED }
            .map { it.id }
            .toSet()

        refreshSavedConnections()
        _uiState.update { it.copy(localIp = discovery.getLocalIpAddress() ?: "未连接 Wi-Fi") }
        scanLanHosts()

        viewModelScope.launch {
            SmbTransferManager.tasks.collect { tasks ->
                val newlyCompletedUploads = tasks.filter {
                    it.direction == SmbTransferDirection.UPLOAD &&
                        it.status == SmbTransferStatus.COMPLETED &&
                        !completedUploadIdsSeen.contains(it.id)
                }
                if (newlyCompletedUploads.isNotEmpty()) {
                    completedUploadIdsSeen = completedUploadIdsSeen + newlyCompletedUploads.map { it.id }
                    val curUrl = _uiState.value.currentUrl
                    if (curUrl.isNotBlank() && newlyCompletedUploads.any { it.remoteDirUrl == curUrl }) {
                        refreshCurrentDirectory()
                    }
                }
            }
        }
    }

    fun refreshSavedConnections() {
        _uiState.update { it.copy(savedConnections = store.getConnections()) }
    }

    fun setPendingUploadFiles(files: List<File>) {
        _uiState.update { it.copy(pendingUploadFiles = files) }
    }

    fun clearPendingUploadFiles() {
        _uiState.update { it.copy(pendingUploadFiles = emptyList()) }
    }

    fun scanLanHosts() {
        _uiState.update {
            it.copy(
                discoveredHosts = emptyList(),
                isDiscovering = true,
                discoveryProgress = 0f,
                localIp = discovery.getLocalIpAddress() ?: "未连接 Wi-Fi"
            )
        }
        discovery.startDiscovery { done, total ->
            val progress = (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
            _uiState.update {
                it.copy(
                    discoveryProgress = progress,
                    isDiscovering = done < total
                )
            }
        }
    }

    fun testConnection(config: SmbConnectionConfig, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = SmbClientManager.testConnection(config)
            res.onSuccess { msg -> onResult(true, msg) }
                .onFailure { err -> onResult(false, err.message ?: "连接失败") }
        }
    }

    fun saveConnection(config: SmbConnectionConfig, connectImmediately: Boolean = false) {
        SmbClientManager.invalidateContext(config.id)
        val updated = store.saveConnection(config)
        _uiState.update { state ->
            val activeUpdated = if (state.activeConfig?.id == config.id) config else state.activeConfig
            state.copy(
                savedConnections = updated,
                activeConfig = activeUpdated
            )
        }
        if (connectImmediately) {
            connect(config)
        }
    }

    fun deleteConnection(id: String) {
        SmbClientManager.invalidateContext(id)
        val updated = store.deleteConnection(id)
        _uiState.update { state ->
            val shouldDisconnect = state.activeConfig?.id == id
            state.copy(
                savedConnections = updated,
                activeConfig = if (shouldDisconnect) null else state.activeConfig
            )
        }
    }

    fun connect(config: SmbConnectionConfig) {
        val entryUrl = SmbClientManager.buildEntryUrl(config)
        val updatedList = store.markConnected(config.id)
        val initialCrumbs = buildList {
            add(SmbBreadcrumb(config.effectiveLabel(), SmbClientManager.buildServerBaseUrl(config)))
            if (config.shareName.isNotBlank()) {
                add(SmbBreadcrumb(config.shareName.trim('/', '\\'), entryUrl))
            }
        }
        _uiState.update {
            it.copy(
                savedConnections = updatedList,
                activeConfig = config,
                currentUrl = entryUrl,
                breadcrumbs = initialCrumbs,
                searchQuery = "",
                categoryFilter = SmbCategoryFilter.ALL,
                isSelectionMode = false,
                selectedUrls = emptySet(),
                errorMessage = null
            )
        }
        loadDirectory(entryUrl)
    }

    fun openCustomShare(shareName: String) {
        val config = _uiState.value.activeConfig ?: return
        val cleanShare = shareName.trim('/', '\\')
        if (cleanShare.isBlank()) return
        val shareUrl = SmbClientManager.buildServerBaseUrl(config) + "$cleanShare/"
        val baseCrumb = SmbBreadcrumb(config.effectiveLabel(), SmbClientManager.buildServerBaseUrl(config))
        _uiState.update {
            it.copy(
                currentUrl = shareUrl,
                breadcrumbs = listOf(baseCrumb, SmbBreadcrumb(cleanShare, shareUrl)),
                searchQuery = "",
                categoryFilter = SmbCategoryFilter.ALL,
                isSelectionMode = false,
                selectedUrls = emptySet(),
                errorMessage = null
            )
        }
        loadDirectory(shareUrl)
    }

    fun openDirectory(item: SmbRemoteFile) {
        if (!item.isDirectory) return
        val newCrumbs = _uiState.value.breadcrumbs + SmbBreadcrumb(item.name, item.smbUrl)
        _uiState.update {
            it.copy(
                currentUrl = item.smbUrl,
                breadcrumbs = newCrumbs,
                searchQuery = "",
                categoryFilter = SmbCategoryFilter.ALL,
                isSelectionMode = false,
                selectedUrls = emptySet(),
                errorMessage = null
            )
        }
        loadDirectory(item.smbUrl)
    }

    fun navigateToBreadcrumb(index: Int) {
        val crumbs = _uiState.value.breadcrumbs
        if (index !in crumbs.indices) return
        val targetCrumbs = crumbs.subList(0, index + 1)
        val targetUrl = targetCrumbs.last().url
        _uiState.update {
            it.copy(
                currentUrl = targetUrl,
                breadcrumbs = targetCrumbs,
                searchQuery = "",
                categoryFilter = SmbCategoryFilter.ALL,
                isSelectionMode = false,
                selectedUrls = emptySet(),
                errorMessage = null
            )
        }
        loadDirectory(targetUrl)
    }

    fun navigateUp(): Boolean {
        val state = _uiState.value
        if (state.isSelectionMode) {
            clearSelection()
            return true
        }
        if (state.activeConfig == null) return false
        if (state.breadcrumbs.size > 1) {
            navigateToBreadcrumb(state.breadcrumbs.size - 2)
            return true
        }
        disconnect()
        return true
    }

    fun disconnect() {
        _uiState.update {
            it.copy(
                activeConfig = null,
                currentUrl = "",
                isAtServerRoot = false,
                breadcrumbs = emptyList(),
                files = emptyList(),
                filteredFiles = emptyList(),
                searchQuery = "",
                categoryFilter = SmbCategoryFilter.ALL,
                isSelectionMode = false,
                selectedUrls = emptySet(),
                errorMessage = null
            )
        }
    }

    fun refreshCurrentDirectory() {
        val url = _uiState.value.currentUrl
        if (url.isNotBlank()) {
            loadDirectory(url)
        }
    }

    private fun loadDirectory(url: String) {
        val config = _uiState.value.activeConfig ?: return
        val isRoot = SmbClientManager.isServerRootUrl(config, url)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isAtServerRoot = isRoot,
                    errorMessage = null
                )
            }
            val result = SmbClientManager.listDirectory(config, url)
            result.onSuccess { list ->
                val state = _uiState.value
                _uiState.update {
                    it.copy(
                        files = list,
                        filteredFiles = applyFilterAndSort(
                            list,
                            state.searchQuery,
                            state.sortOption,
                            state.categoryFilter
                        ),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        files = emptyList(),
                        filteredFiles = emptyList(),
                        isLoading = false,
                        errorMessage = err.message ?: "无法读取目录"
                    )
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredFiles = applyFilterAndSort(state.files, query, state.sortOption, state.categoryFilter)
            )
        }
    }

    fun setSortOption(option: SmbSortOption) {
        _uiState.update { state ->
            state.copy(
                sortOption = option,
                filteredFiles = applyFilterAndSort(state.files, state.searchQuery, option, state.categoryFilter)
            )
        }
    }

    fun setCategoryFilter(filter: SmbCategoryFilter) {
        _uiState.update { state ->
            state.copy(
                categoryFilter = filter,
                filteredFiles = applyFilterAndSort(state.files, state.searchQuery, state.sortOption, filter)
            )
        }
    }

    private fun applyFilterAndSort(
        list: List<SmbRemoteFile>,
        query: String,
        sort: SmbSortOption,
        categoryFilter: SmbCategoryFilter
    ): List<SmbRemoteFile> {
        val byCategory = if (categoryFilter == SmbCategoryFilter.ALL) {
            list
        } else {
            list.filter { !it.isDirectory && it.category == categoryFilter.targetCategory }
        }
        val filtered = if (query.isBlank()) byCategory else {
            byCategory.filter { it.name.contains(query, ignoreCase = true) }
        }
        val comparator = when (sort) {
            SmbSortOption.NAME_ASC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SmbSortOption.NAME_DESC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenByDescending { it.name.lowercase(Locale.ROOT) }
            SmbSortOption.TIME_DESC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenByDescending { it.lastModified }
            SmbSortOption.TIME_ASC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenBy { it.lastModified }
            SmbSortOption.SIZE_DESC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenByDescending { it.size }
            SmbSortOption.SIZE_ASC -> compareBy<SmbRemoteFile> { !it.isDirectory }
                .thenBy { it.size }
        }
        return filtered.sortedWith(comparator)
    }

    fun toggleSelection(smbUrl: String) {
        val item = _uiState.value.files.find { it.smbUrl == smbUrl } ?: return
        if (item.isShareRoot) return
        val current = _uiState.value.selectedUrls.toMutableSet()
        if (current.contains(smbUrl)) {
            current.remove(smbUrl)
        } else {
            current.add(smbUrl)
        }
        _uiState.update {
            it.copy(
                selectedUrls = current,
                isSelectionMode = current.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        val allUrls = _uiState.value.filteredFiles
            .filterNot { it.isShareRoot }
            .map { it.smbUrl }
            .toSet()
        if (allUrls.isEmpty()) return
        _uiState.update {
            it.copy(
                selectedUrls = allUrls,
                isSelectionMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedUrls = emptySet(),
                isSelectionMode = false
            )
        }
    }

    private fun getSelectedItems(): List<SmbRemoteFile> {
        val selected = _uiState.value.selectedUrls
        return _uiState.value.files.filter { selected.contains(it.smbUrl) }
    }

    fun createFolder(name: String, onResult: (Boolean, String?) -> Unit) {
        val state = _uiState.value
        val config = state.activeConfig ?: return
        if (state.isAtServerRoot) {
            onResult(false, "请先进入一个共享目录")
            return
        }
        viewModelScope.launch {
            val res = SmbClientManager.createDirectory(config, state.currentUrl, name)
            res.onSuccess {
                refreshCurrentDirectory()
                onResult(true, null)
            }.onFailure { err ->
                onResult(false, err.message)
            }
        }
    }

    fun renameItem(item: SmbRemoteFile, newName: String, onResult: (Boolean, String?) -> Unit) {
        val config = _uiState.value.activeConfig ?: return
        viewModelScope.launch {
            val res = SmbClientManager.renameRemoteFile(config, item, newName)
            res.onSuccess {
                refreshCurrentDirectory()
                onResult(true, null)
            }.onFailure { err ->
                onResult(false, err.message)
            }
        }
    }

    fun deleteSelected(onResult: (Boolean, String?) -> Unit) {
        val config = _uiState.value.activeConfig ?: return
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val res = SmbClientManager.deleteRemoteItems(config, items)
            res.onSuccess { count ->
                clearSelection()
                refreshCurrentDirectory()
                onResult(true, "已删除 $count 项")
            }.onFailure { err ->
                onResult(false, err.message)
            }
        }
    }

    fun copySelected() {
        val config = _uiState.value.activeConfig ?: return
        val items = getSelectedItems()
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = SmbClipboard(SmbClipboardAction.COPY, items, config.id),
                isSelectionMode = false,
                selectedUrls = emptySet()
            )
        }
    }

    fun cutSelected() {
        val config = _uiState.value.activeConfig ?: return
        val items = getSelectedItems()
        if (items.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = SmbClipboard(SmbClipboardAction.CUT, items, config.id),
                isSelectionMode = false,
                selectedUrls = emptySet()
            )
        }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    fun pasteClipboard(onResult: (Boolean, String?) -> Unit) {
        val state = _uiState.value
        val config = state.activeConfig ?: return
        val clip = state.clipboard ?: return
        if (state.isAtServerRoot) {
            onResult(false, "请先进入具体共享目录再粘贴")
            return
        }
        viewModelScope.launch {
            val isMove = clip.action == SmbClipboardAction.CUT
            val res = SmbClientManager.copyOrMoveRemoteItems(config, clip.items, state.currentUrl, isMove)
            res.onSuccess { count ->
                _uiState.update { it.copy(clipboard = null) }
                refreshCurrentDirectory()
                onResult(true, "${if (isMove) "移动" else "复制"}完成 ($count 项)")
            }.onFailure { err ->
                onResult(false, err.message)
            }
        }
    }

    fun downloadSelected(onQueued: (Int, String?) -> Unit) {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        clearSelection()
        downloadItems(items, onQueued)
    }

    fun downloadItems(items: List<SmbRemoteFile>, onQueued: (Int, String?) -> Unit) {
        val config = _uiState.value.activeConfig ?: return
        SmbTransferManager.enqueueDownloads(
            context = getApplication(),
            config = config,
            items = items,
            targetLocalDir = defaultDownloadDir,
            onQueued = onQueued
        )
    }

    fun uploadPendingFiles(onQueued: (Int) -> Unit) {
        val pending = _uiState.value.pendingUploadFiles
        if (pending.isEmpty()) return
        uploadFiles(pending) { count ->
            clearPendingUploadFiles()
            onQueued(count)
        }
    }

    fun uploadFiles(files: List<File>, onQueued: (Int) -> Unit) {
        val state = _uiState.value
        val config = state.activeConfig ?: return
        if (state.isAtServerRoot || files.isEmpty()) {
            onQueued(0)
            return
        }
        val sources = files.map { LocalUploadSource.FileSource(it) }
        SmbTransferManager.enqueueUploads(
            context = getApplication(),
            config = config,
            sources = sources,
            targetRemoteDirUrl = state.currentUrl,
            onQueued = onQueued
        )
    }

    fun uploadUris(uris: List<Uri>, onQueued: (Int) -> Unit) {
        val state = _uiState.value
        val config = state.activeConfig ?: return
        if (state.isAtServerRoot || uris.isEmpty()) {
            onQueued(0)
            return
        }
        val targetUrl = state.currentUrl
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.mapNotNull { SmbClientManager.resolveUriMetadata(getApplication(), it) }
            }
            SmbTransferManager.enqueueUploads(
                context = getApplication(),
                config = config,
                sources = sources,
                targetRemoteDirUrl = targetUrl,
                onQueued = onQueued
            )
        }
    }

    fun pauseTransfer(taskId: String) {
        SmbTransferManager.pauseTask(getApplication(), taskId)
    }

    fun resumeTransfer(taskId: String): Boolean {
        return SmbTransferManager.resumeTask(getApplication(), taskId, _uiState.value.activeConfig)
    }

    fun cancelTransfer(taskId: String) {
        SmbTransferManager.cancelTask(getApplication(), taskId, _uiState.value.activeConfig)
    }

    fun removeTransfer(taskId: String) {
        SmbTransferManager.removeTask(getApplication(), taskId, _uiState.value.activeConfig)
    }

    fun pauseAllTransfers() {
        SmbTransferManager.pauseAll(getApplication())
    }

    fun resumeAllTransfers() {
        SmbTransferManager.resumeAll(getApplication(), _uiState.value.activeConfig)
    }

    fun clearFinishedTransfers() {
        SmbTransferManager.clearFinished()
    }

    fun prepareStreamUrl(
        item: SmbRemoteFile,
        onReady: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = _uiState.value.activeConfig ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = SmbClientManager.getStreamUrl(config, item)
                withContext(Dispatchers.Main) {
                    onReady(url)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "启动流媒体播放失败")
                }
            }
        }
    }

    fun downloadForPreview(
        item: SmbRemoteFile,
        onReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = _uiState.value.activeConfig ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingPreview = true, previewProgress = 0f) }
            val result = SmbClientManager.downloadToCacheForPreview(
                config = config,
                remoteFile = item,
                cacheRootDir = getApplication<Application>().cacheDir,
                onProgress = { prog ->
                    _uiState.update { it.copy(previewProgress = prog) }
                }
            )
            _uiState.update { it.copy(isPreparingPreview = false, previewProgress = 0f) }
            result.onSuccess { file -> onReady(file) }
                .onFailure { err -> onError(err.message ?: "加载文件失败") }
        }
    }

    fun loadPreviewImageSilent(
        item: SmbRemoteFile,
        onReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val config = _uiState.value.activeConfig ?: return
        viewModelScope.launch {
            val result = SmbClientManager.downloadToCacheForPreview(
                config = config,
                remoteFile = item,
                cacheRootDir = getApplication<Application>().cacheDir
            )
            result.onSuccess { file -> onReady(file) }
                .onFailure { err -> onError(err.message ?: "图片加载失败") }
        }
    }

    fun getCurrentFolderImages(): List<SmbRemoteFile> {
        return _uiState.value.filteredFiles.filter { !it.isDirectory && it.category == FileCategory.IMAGE }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }
}
