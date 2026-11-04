package com.gemini.filemanager.data.model

import java.io.File

/**
 * 重复文件单项数据模型
 */
data class DuplicateFileItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val category: FileCategory,
    val isOriginal: Boolean = false,
    val isSelected: Boolean = !isOriginal
) {
    val file: File get() = File(path)
}

/**
 * 重复文件组（由相同 MD5 哈希的一组文件组成）
 */
data class DuplicateGroup(
    val hash: String,
    val fileSize: Long,
    val category: FileCategory,
    val files: List<DuplicateFileItem>
) {
    val totalCount: Int get() = files.size
    val duplicateCount: Int get() = (files.size - 1).coerceAtLeast(0)
    // 假设保留一个原文件，最多可释放的空间
    val potentialReclaimBytes: Long get() = fileSize * duplicateCount
    // 当前已勾选要删除的文件将释放的空间
    val selectedReclaimBytes: Long get() = fileSize * files.count { it.isSelected }
}

/**
 * 重复文件筛查类型过滤器
 */
enum class DuplicateFilterType(val displayName: String) {
    ALL("全部文件"),
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    DOCUMENT("文档"),
    PACKAGE("安装包与压缩包")
}

/**
 * 扫描过程阶段
 */
sealed class DuplicateScanStatus {
    object Idle : DuplicateScanStatus()
    data class Scanning(val stage: String, val progress: Float, val currentFile: String = "") : DuplicateScanStatus()
    object Finished : DuplicateScanStatus()
    data class Error(val message: String) : DuplicateScanStatus()
}
