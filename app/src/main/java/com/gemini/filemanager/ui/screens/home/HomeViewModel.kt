package com.gemini.filemanager.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.core.background.KeepAliveUiState
import com.gemini.filemanager.core.file.FileScanner
import com.gemini.filemanager.core.localsend.LocalSendManager
import com.gemini.filemanager.core.m3u8.M3U8Scanner
import com.gemini.filemanager.core.smb.SmbConnectionStore
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HomeUiState(
    val totalStorage: Long = 0L,
    val availableStorage: Long = 0L,
    val categoryCounts: Map<FileCategory, Int> = emptyMap(),
    val cachedM3U8Count: Int = 0,
    val discoveredDevicesCount: Int = 0,
    val savedSmbCount: Int = 0,
    val isLoading: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val smbStore = SmbConnectionStore(application)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = LocalSendManager.discoveredDevices
    val keepAliveState: StateFlow<KeepAliveUiState> = AppBackgroundKeepAliveManager.uiState

    init {
        loadOverview()
    }

    fun refreshKeepAliveStatus() {
        AppBackgroundKeepAliveManager.refreshSystemStates(getApplication())
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        AppBackgroundKeepAliveManager.setKeepAliveEnabled(getApplication(), enabled)
    }

    fun setStrictWakeLockEnabled(enabled: Boolean) {
        AppBackgroundKeepAliveManager.setStrictWakeLockEnabled(getApplication(), enabled)
    }

    fun refreshSmbCount() {
        _uiState.value = _uiState.value.copy(
            savedSmbCount = smbStore.getConnections().size
        )
    }

    fun loadOverview() {
        refreshSmbCount()
        viewModelScope.launch {
            // 1. 立即获取存储容量（毫秒级直出，绝不受任何扫描影响）
            val overview = withContext(Dispatchers.IO) {
                FileScanner.getStorageOverview()
            }
            _uiState.value = _uiState.value.copy(
                totalStorage = overview.totalBytes,
                availableStorage = overview.availableBytes,
                isLoading = false
            )

            // 2. 异步拉取 M3U8 任务统计（超时保护 3 秒）
            launch(Dispatchers.IO) {
                val m3u8List = withTimeoutOrNull(3000L) {
                    M3U8Scanner.scanLocalM3U8Tasks(getApplication())
                } ?: emptyList()

                _uiState.value = _uiState.value.copy(
                    cachedM3U8Count = m3u8List.size
                )
            }
        }
    }
}
