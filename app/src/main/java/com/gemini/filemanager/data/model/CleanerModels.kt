package com.gemini.filemanager.data.model

import android.graphics.drawable.Drawable
import java.io.File

/**
 * 垃圾文件分类
 */
enum class JunkCategory(
    val title: String,
    val description: String,
    val iconName: String,
    val isRecommended: Boolean = true
) {
    SYSTEM_CACHE(
        title = "应用与系统临时缓存",
        description = "各类 App 运行产生的 .tmp, .temp 临时交互数据",
        iconName = "storage"
    ),
    THUMBNAILS(
        title = "相册缩略图与图库缓存",
        description = "手机图片缩略图索引 (.thumbnails)，清理后不影响相册原图",
        iconName = "image"
    ),
    LOG_FILES(
        title = "日志与崩溃转储文件",
        description = "各类软件生成的运行日志 (.log, .dmp, crashlog)",
        iconName = "description"
    ),
    OBSOLETE_APK(
        title = "废弃安装包残留",
        description = "已下载或历史残留的 .apk 安装包",
        iconName = "android"
    ),
    EMPTY_DIRS(
        title = "无用空文件夹",
        description = "卸载应用或临时目录残留的空文件夹树",
        iconName = "folder"
    )
}

/**
 * 垃圾文件单项
 */
data class JunkItem(
    val file: File,
    val size: Long,
    val category: JunkCategory,
    val isSelected: Boolean = true,
    val detailInfo: String = ""
) {
    val path: String get() = file.absolutePath
    val name: String get() = file.name
}

/**
 * 垃圾分类组
 */
data class JunkGroup(
    val category: JunkCategory,
    val items: List<JunkItem>,
    val isExpanded: Boolean = false
) {
    val totalSize: Long get() = items.sumOf { it.size }
    val selectedSize: Long get() = items.filter { it.isSelected }.sumOf { it.size }
    val itemCount: Int get() = items.size
    val selectedCount: Int get() = items.count { it.isSelected }
    val isAllSelected: Boolean get() = items.isNotEmpty() && items.all { it.isSelected }
}

/**
 * 已安装应用存储信息
 */
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkSize: Long,
    val externalDataSize: Long = 0L, // Android/data/<pkg> 占用大小
    val externalObbSize: Long = 0L,  // Android/obb/<pkg> 占用大小
    val isSystemApp: Boolean = false,
    val lastUpdateTime: Long = 0L,
    val icon: Drawable? = null
) {
    val totalExternalSize: Long get() = externalDataSize + externalObbSize
    val totalEstimatedSize: Long get() = apkSize + totalExternalSize
}
