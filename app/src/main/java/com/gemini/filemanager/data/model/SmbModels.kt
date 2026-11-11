package com.gemini.filemanager.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SmbProtocolMode(
    val displayName: String,
    val minVersion: String,
    val maxVersion: String
) {
    AUTO("自动协商 (SMB1~SMB3)", "SMB1", "SMB311"),
    SMB2_3("现代模式 (SMB2~SMB3)", "SMB202", "SMB311"),
    SMB1_COMPAT("兼容旧设备 (SMB1)", "SMB1", "SMB1")
}

@Serializable
data class SmbConnectionConfig(
    val id: String,
    val displayName: String = "",
    val host: String,
    val port: Int = 445,
    val shareName: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = false,
    val protocolMode: SmbProtocolMode = SmbProtocolMode.AUTO,
    val showHiddenShares: Boolean = false,
    val lastConnectedAt: Long = 0L
) {
    fun effectiveLabel(): String = displayName.ifBlank { host }
}

data class SmbDiscoveredHost(
    val ip: String,
    val hostName: String = "",
    val port: Int = 445,
    val source: String = "端口探测"
)

data class SmbRemoteFile(
    val name: String,
    val smbUrl: String,
    val shareName: String = "",
    val isDirectory: Boolean,
    val isShareRoot: Boolean = false,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val extension: String = "",
    val category: FileCategory = FileCategory.DOCUMENT
)

enum class SmbSortOption(val displayName: String) {
    NAME_ASC("名称 (A-Z)"),
    NAME_DESC("名称 (Z-A)"),
    TIME_DESC("时间 ↓ (最新)"),
    TIME_ASC("时间 ↑ (最早)"),
    SIZE_DESC("大小 ↓ (最大)"),
    SIZE_ASC("大小 ↑ (最小)")
}

enum class SmbTransferDirection {
    DOWNLOAD,
    UPLOAD
}

enum class SmbTransferStatus {
    WAITING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SmbTransferTask(
    val id: String,
    val fileName: String,
    val remoteUrl: String,
    val localPath: String,
    val size: Long,
    val bytesTransferred: Long = 0L,
    val progress: Float = 0f,
    val speed: String = "",
    val direction: SmbTransferDirection,
    val status: SmbTransferStatus = SmbTransferStatus.WAITING,
    val errorMessage: String? = null
)

enum class SmbClipboardAction {
    COPY,
    CUT
}

data class SmbClipboard(
    val action: SmbClipboardAction,
    val items: List<SmbRemoteFile>,
    val configId: String
)

