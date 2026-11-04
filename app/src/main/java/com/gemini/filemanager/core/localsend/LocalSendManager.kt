package com.gemini.filemanager.core.localsend

import android.content.Context
import android.os.Build
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.TransferDirection
import com.gemini.filemanager.data.model.TransferItem
import com.gemini.filemanager.data.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

object LocalSendManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers = _transfers.asStateFlow()

    private var discovery: LocalSendDiscovery? = null
    private var server: LocalSendServer? = null
    private var client: LocalSendClient? = null

    val myAlias = "${Build.BRAND} ${Build.MODEL}".trim()
    val myFingerprint = UUID.randomUUID().toString()

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        discovery = LocalSendDiscovery(context, scope) { device ->
            _discoveredDevices.update { list ->
                val filtered = list.filter { it.device.fingerprint != device.device.fingerprint }
                filtered + device
            }
        }

        server = LocalSendServer(
            context = context,
            myAlias = myAlias,
            myFingerprint = myFingerprint,
            onReceiveProgress = { fileName, progress, totalBytes ->
                updateReceiveProgress(fileName, progress, totalBytes)
            },
            onFileReceived = { file ->
                markReceiveCompleted(file)
            }
        )

        client = LocalSendClient(myAlias, myFingerprint)

        try {
            server?.start()
            discovery?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun rescan() {
        _discoveredDevices.value = emptyList()
        discovery?.sendAnnouncement()
        discovery?.scanLocalSubnet()
    }

    fun scanSubnet(onProgress: (Int, Int) -> Unit) {
        discovery?.scanLocalSubnet(onProgress)
    }

    fun addDeviceByIp(ip: String, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val device = discovery?.pingDevice(ip)
            withContext(Dispatchers.Main) {
                onResult(device != null)
            }
        }
    }

    fun getLocalIp(): String {
        return discovery?.getLocalIpAddress() ?: "未知局域网IP"
    }

    fun sendFilesTo(target: DiscoveredDevice, files: List<File>) {
        val transferItems = files.map { file ->
            TransferItem(
                id = UUID.randomUUID().toString(),
                fileName = file.name,
                filePath = file.absolutePath,
                size = file.length(),
                direction = TransferDirection.SEND,
                remoteDevice = target.device.alias,
                status = TransferStatus.CONNECTING
            )
        }

        _transfers.update { it + transferItems }

        scope.launch(Dispatchers.IO) {
            val c = client ?: return@launch
            val success = c.sendFiles(target, files) { fileName, prog, speed ->
                _transfers.update { list ->
                    list.map { item ->
                        if (item.fileName == fileName && item.direction == TransferDirection.SEND) {
                            item.copy(
                                progress = prog,
                                speed = speed,
                                status = if (prog >= 1.0f) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                            )
                        } else item
                    }
                }
            }

            if (!success) {
                _transfers.update { list ->
                    list.map { item ->
                        if (files.any { it.name == item.fileName } && item.direction == TransferDirection.SEND && item.status != TransferStatus.COMPLETED) {
                            item.copy(status = TransferStatus.FAILED, errorMessage = "发送中断或对端拒绝")
                        } else item
                    }
                }
            }
        }
    }

    private fun updateReceiveProgress(fileName: String, progress: Float, totalBytes: Long) {
        _transfers.update { list ->
            val existing = list.find { it.fileName == fileName && it.direction == TransferDirection.RECEIVE }
            if (existing != null) {
                list.map {
                    if (it.id == existing.id) {
                        it.copy(
                            progress = progress,
                            status = if (progress >= 1f) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                        )
                    } else it
                }
            } else {
                val newItem = TransferItem(
                    id = UUID.randomUUID().toString(),
                    fileName = fileName,
                    filePath = "",
                    size = totalBytes,
                    progress = progress,
                    direction = TransferDirection.RECEIVE,
                    remoteDevice = "附近设备",
                    status = TransferStatus.TRANSFERRING
                )
                list + newItem
            }
        }
    }

    private fun markReceiveCompleted(file: File) {
        _transfers.update { list ->
            list.map {
                if (it.fileName == file.name && it.direction == TransferDirection.RECEIVE) {
                    it.copy(
                        filePath = file.absolutePath,
                        progress = 1.0f,
                        status = TransferStatus.COMPLETED
                    )
                } else it
            }
        }
    }

    fun onDestroy() {
        discovery?.stop()
        server?.stop()
    }
}
