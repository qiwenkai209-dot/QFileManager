package com.gemini.filemanager.data.model

enum class M3U8Status {
    PENDING,
    SCANNING,
    CONVERTING,
    COMPLETED,
    FAILED
}

data class M3U8Item(
    val id: String,
    val title: String,
    val m3u8Path: String,
    val parentDir: String,
    val segmentCount: Int = 0,
    val totalSize: Long = 0L,
    val isEncrypted: Boolean = false,
    val keyUri: String? = null,
    val customKeyHex: String? = null,
    val outputPath: String? = null,
    val status: M3U8Status = M3U8Status.PENDING,
    val progress: Float = 0f,
    val speed: String = "",
    val errorMessage: String? = null,
    val lastModified: Long = 0L,
    val isSelected: Boolean = false
)
