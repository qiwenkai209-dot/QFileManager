package com.gemini.filemanager.data.model

import android.net.Uri

enum class FileCategory(val displayName: String, val iconRes: String) {
    IMAGE("图片", "ic_image"),
    AUDIO("音乐", "ic_audio"),
    DOCUMENT("文档", "ic_document"),
    VIDEO("视频", "ic_video"),
    APK("安装包", "ic_apk"),
    ARCHIVE("压缩包", "ic_archive"),
    M3U8("M3U8缓存", "ic_m3u8")
}

data class FileItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean = false,
    val mimeType: String = "*/*",
    val extension: String = "",
    val category: FileCategory = FileCategory.DOCUMENT,
    val uri: Uri? = null,
    val isSelected: Boolean = false,
    // APK 特有信息
    val appName: String? = null,
    val packageName: String? = null,
    val versionName: String? = null
)
