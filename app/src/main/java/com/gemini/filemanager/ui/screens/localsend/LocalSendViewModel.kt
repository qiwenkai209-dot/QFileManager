package com.gemini.filemanager.ui.screens.localsend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.filemanager.core.localsend.LocalSendManager
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.TransferItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class LocalSendViewModel(application: Application) : AndroidViewModel(application) {

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = LocalSendManager.discoveredDevices
    val transfers: StateFlow<List<TransferItem>> = LocalSendManager.transfers

    private val _pendingFilesToSend = MutableStateFlow<List<File>>(emptyList())
    val pendingFilesToSend: StateFlow<List<File>> = _pendingFilesToSend.asStateFlow()

    private val _isSubnetScanning = MutableStateFlow(false)
    val isSubnetScanning: StateFlow<Boolean> = _isSubnetScanning.asStateFlow()

    private val _subnetScanProgress = MutableStateFlow(0f)
    val subnetScanProgress: StateFlow<Float> = _subnetScanProgress.asStateFlow()

    val localIp: String
        get() = LocalSendManager.getLocalIp()

    fun setPendingFiles(files: List<File>) {
        _pendingFilesToSend.value = files
    }

    fun rescan() {
        _isSubnetScanning.value = true
        _subnetScanProgress.value = 0f
        LocalSendManager.rescan()
        LocalSendManager.scanSubnet { current, total ->
            _subnetScanProgress.value = current.toFloat() / total
            if (current >= total) {
                _isSubnetScanning.value = false
            }
        }
    }

    fun manualConnectIp(ip: String, onResult: (Boolean) -> Unit) {
        LocalSendManager.addDeviceByIp(ip) { success ->
            onResult(success)
        }
    }

    fun sendToDevice(target: DiscoveredDevice) {
        val files = _pendingFilesToSend.value
        if (files.isNotEmpty()) {
            LocalSendManager.sendFilesTo(target, files)
            _pendingFilesToSend.value = emptyList() // 清空待发送
        }
    }
}
