package com.gemini.filemanager.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalSendDeviceDto(
    val alias: String,
    val version: String = "2.1",
    val deviceModel: String? = null,
    val deviceType: String = "mobile", // mobile, desktop, web, headless, server
    val fingerprint: String,
    val port: Int = 53317,
    val protocol: String = "http", // http or https
    val download: Boolean = true,
    val announce: Boolean? = null
)

@Serializable
data class FileInfoDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String = "*/*",
    val sha256: String? = null,
    val preview: String? = null
)

@Serializable
data class PrepareUploadRequestDto(
    val info: LocalSendDeviceDto,
    val files: Map<String, FileInfoDto>
)

@Serializable
data class PrepareUploadResponseDto(
    val sessionId: String,
    val files: Map<String, String> // fileId to token
)

enum class TransferDirection {
    SEND,
    RECEIVE
}

enum class TransferStatus {
    WAITING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferItem(
    val id: String,
    val fileName: String,
    val filePath: String,
    val size: Long,
    val bytesTransferred: Long = 0L,
    val progress: Float = 0f,
    val speed: String = "",
    val direction: TransferDirection,
    val remoteDevice: String,
    val status: TransferStatus = TransferStatus.WAITING,
    val errorMessage: String? = null
)

data class DiscoveredDevice(
    val ip: String,
    val port: Int,
    val device: LocalSendDeviceDto,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
