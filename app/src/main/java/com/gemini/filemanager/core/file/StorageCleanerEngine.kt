package com.gemini.filemanager.core.file

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import com.gemini.filemanager.data.model.InstalledAppInfo
import com.gemini.filemanager.data.model.JunkCategory
import com.gemini.filemanager.data.model.JunkGroup
import com.gemini.filemanager.data.model.JunkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

object StorageCleanerEngine {

    interface CleanerScanCallback {
        fun onProgress(stage: String, currentDir: String, totalBytesFound: Long)
    }

    /**
     * 执行全盘垃圾深度扫描
     */
    suspend fun scanJunkFiles(
        context: Context,
        callback: CleanerScanCallback? = null
    ): List<JunkGroup> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val tempItems = mutableListOf<JunkItem>()
        val thumbItems = mutableListOf<JunkItem>()
        val logItems = mutableListOf<JunkItem>()
        val apkItems = mutableListOf<JunkItem>()
        val emptyDirItems = mutableListOf<JunkItem>()

        var totalBytesFound = 0L

        // 获取已安装包名集合，用于智能判断 APK 是否废弃
        val installedPackages = try {
            val pm = context.packageManager
            pm.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        callback?.onProgress("正在扫描相册缩略图与图库缓存...", root.name, totalBytesFound)

        // 1. 扫描相册缩略图缓存 (.thumbnails)
        val dcimDir = File(root, "DCIM")
        val picDir = File(root, "Pictures")
        scanThumbnailsDir(dcimDir, thumbItems)
        scanThumbnailsDir(picDir, thumbItems)
        totalBytesFound += thumbItems.sumOf { it.size }
        callback?.onProgress("已发现缩略图缓存: ${FileUtils.formatFileSize(totalBytesFound)}", "DCIM/.thumbnails", totalBytesFound)

        // 2. 深度扫描外部存储常用目录
        val targetDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(root, "Download"),
            File(root, "Tencent"),
            File(root, "Android/data"),
            File(root, "Android/obb"),
            File(root, "Temp"),
            File(root, "cache")
        ).filter { it.exists() }

        var scannedCount = 0
        for (dir in targetDirs) {
            if (!coroutineContext.isActive) break
            callback?.onProgress("正在扫描 ${dir.name} 目录垃圾...", dir.absolutePath, totalBytesFound)

            scanDirectoryRecursive(
                dir = dir,
                context = context,
                installedPackages = installedPackages,
                tempList = tempItems,
                thumbList = thumbItems,
                logList = logItems,
                apkList = apkItems,
                emptyDirList = emptyDirItems,
                onFileScanned = { bytes ->
                    scannedCount++
                    totalBytesFound += bytes
                    if (scannedCount % 50 == 0) {
                        callback?.onProgress("已发现垃圾文件: ${FileUtils.formatFileSize(totalBytesFound)}", dir.name, totalBytesFound)
                    }
                }
            )
        }

        // 组装结果
        val groups = mutableListOf<JunkGroup>()

        if (tempItems.isNotEmpty()) {
            groups.add(
                JunkGroup(
                    category = JunkCategory.SYSTEM_CACHE,
                    items = tempItems.sortedByDescending { it.size }
                )
            )
        }

        if (thumbItems.isNotEmpty()) {
            groups.add(
                JunkGroup(
                    category = JunkCategory.THUMBNAILS,
                    items = thumbItems.sortedByDescending { it.size }
                )
            )
        }

        if (logItems.isNotEmpty()) {
            groups.add(
                JunkGroup(
                    category = JunkCategory.LOG_FILES,
                    items = logItems.sortedByDescending { it.size }
                )
            )
        }

        if (apkItems.isNotEmpty()) {
            groups.add(
                JunkGroup(
                    category = JunkCategory.OBSOLETE_APK,
                    items = apkItems.sortedByDescending { it.size }
                )
            )
        }

        if (emptyDirItems.isNotEmpty()) {
            groups.add(
                JunkGroup(
                    category = JunkCategory.EMPTY_DIRS,
                    items = emptyDirItems
                )
            )
        }

        callback?.onProgress("扫描完成，共发现 ${FileUtils.formatFileSize(totalBytesFound)} 垃圾文件", "完成", totalBytesFound)
        groups
    }

    /**
     * 扫描相册缩略图专用目录
     */
    private fun scanThumbnailsDir(parentDir: File, outList: MutableList<JunkItem>) {
        if (!parentDir.exists() || !parentDir.isDirectory) return
        val files = parentDir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory && f.name.equals(".thumbnails", ignoreCase = true)) {
                val thumbs = f.listFiles() ?: continue
                for (thumb in thumbs) {
                    if (thumb.isFile && thumb.length() > 0L) {
                        outList.add(
                            JunkItem(
                                file = thumb,
                                size = thumb.length(),
                                category = JunkCategory.THUMBNAILS,
                                isSelected = true,
                                detailInfo = "相册自动生成的快照缓存"
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 递归扫描目录
     */
    private fun scanDirectoryRecursive(
        dir: File,
        context: Context,
        installedPackages: Set<String>,
        tempList: MutableList<JunkItem>,
        thumbList: MutableList<JunkItem>,
        logList: MutableList<JunkItem>,
        apkList: MutableList<JunkItem>,
        emptyDirList: MutableList<JunkItem>,
        depth: Int = 0,
        onFileScanned: (Long) -> Unit
    ) {
        if (depth > 10) return
        val files = dir.listFiles() ?: return

        // 检查空目录
        if (files.isEmpty() && !dir.name.startsWith(".")) {
            emptyDirList.add(
                JunkItem(
                    file = dir,
                    size = 0L,
                    category = JunkCategory.EMPTY_DIRS,
                    isSelected = true,
                    detailInfo = "无用空文件夹"
                )
            )
            return
        }

        for (f in files) {
            if (f.isDirectory) {
                val lowerName = f.name.lowercase(Locale.ROOT)
                if (lowerName == ".thumbnails" || lowerName == "thumbcache") {
                    val thumbs = f.listFiles() ?: continue
                    for (t in thumbs) {
                        if (t.isFile && t.length() > 0L) {
                            thumbList.add(
                                JunkItem(
                                    file = t,
                                    size = t.length(),
                                    category = JunkCategory.THUMBNAILS,
                                    isSelected = true,
                                    detailInfo = "图库缩略图缓存"
                                )
                            )
                            onFileScanned(t.length())
                        }
                    }
                } else {
                    scanDirectoryRecursive(
                        f, context, installedPackages,
                        tempList, thumbList, logList, apkList, emptyDirList,
                        depth + 1, onFileScanned
                    )
                }
            } else if (f.isFile && f.length() > 0L) {
                val ext = f.extension.lowercase(Locale.ROOT)
                val nameLower = f.name.lowercase(Locale.ROOT)
                val pathLower = f.absolutePath.lowercase(Locale.ROOT)

                when {
                    // 临时文件与缓存
                    ext in setOf("tmp", "temp", "chk", "bak", "old") || nameLower.startsWith("temp_") || nameLower.endsWith(".tmp") -> {
                        tempList.add(
                            JunkItem(
                                file = f,
                                size = f.length(),
                                category = JunkCategory.SYSTEM_CACHE,
                                isSelected = true,
                                detailInfo = "临时交互缓存文件"
                            )
                        )
                        onFileScanned(f.length())
                    }
                    // 日志与崩溃转储
                    ext in setOf("log", "dmp", "crash", "trace") || pathLower.contains("/crashlog/") || pathLower.contains("/logs/") -> {
                        logList.add(
                            JunkItem(
                                file = f,
                                size = f.length(),
                                category = JunkCategory.LOG_FILES,
                                isSelected = true,
                                detailInfo = "软件运行日志与崩溃记录"
                            )
                        )
                        onFileScanned(f.length())
                    }
                    // 安装包残留
                    ext == "apk" -> {
                        // 尝试读取包名，判断是否已经安装
                        val isInstalled = try {
                            val pkgInfo = context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
                            pkgInfo?.packageName != null && installedPackages.contains(pkgInfo.packageName)
                        } catch (e: Exception) {
                            false
                        }

                        apkList.add(
                            JunkItem(
                                file = f,
                                size = f.length(),
                                category = JunkCategory.OBSOLETE_APK,
                                isSelected = isInstalled, // 如果已安装则默认勾选清理，未安装则不勾选防止误删
                                detailInfo = if (isInstalled) "该应用在手机中已安装，安装包可安全清理" else "未安装的 APK 安装包"
                            )
                        )
                        onFileScanned(f.length())
                    }
                }
            }
        }
    }

    /**
     * 获取手机已安装的所有软件信息列表（包含第三方与系统应用，并统计外部数据占用）
     */
    suspend fun getInstalledApps(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<InstalledAppInfo>()
        val externalRoot = Environment.getExternalStorageDirectory()
        val dataBaseDir = File(externalRoot, "Android/data")
        val obbBaseDir = File(externalRoot, "Android/obb")

        try {
            val pm = context.packageManager
            // 扫描手机上安装的所有包
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkg.packageName
                }

                val apkFile = File(appInfo.sourceDir)
                val apkSize = if (apkFile.exists()) apkFile.length() else 0L

                // 统计该 App 在 Android/data/<pkg> 和 Android/obb/<pkg> 的外部存储占用
                val pkgDataDir = File(dataBaseDir, pkg.packageName)
                val pkgObbDir = File(obbBaseDir, pkg.packageName)
                val extDataSize = if (pkgDataDir.exists()) calculateDirectorySize(pkgDataDir) else 0L
                val extObbSize = if (pkgObbDir.exists()) calculateDirectorySize(pkgObbDir) else 0L

                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    null
                }

                list.add(
                    InstalledAppInfo(
                        packageName = pkg.packageName,
                        appName = appName,
                        versionName = pkg.versionName ?: "1.0",
                        apkSize = apkSize,
                        externalDataSize = extDataSize,
                        externalObbSize = extObbSize,
                        isSystemApp = isSystem,
                        lastUpdateTime = pkg.lastUpdateTime,
                        icon = icon
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 非系统应用排前面，按综合占用大小降序
        list.sortedWith(
            compareBy<InstalledAppInfo> { it.isSystemApp }
                .thenByDescending { it.totalEstimatedSize }
        )
    }

    /**
     * 删除指定软件在手机存储卡下的全部外部缓存与数据 (Android/data/<pkg> 和 Android/obb/<pkg>)
     */
    suspend fun deleteAppExternalData(packageName: String): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        val externalRoot = Environment.getExternalStorageDirectory()
        val targetDirs = listOf(
            File(externalRoot, "Android/data/$packageName"),
            File(externalRoot, "Android/obb/$packageName")
        )

        for (dir in targetDirs) {
            if (dir.exists()) {
                val size = calculateDirectorySize(dir)
                if (dir.deleteRecursively()) {
                    freedBytes += size
                }
            }
        }
        freedBytes
    }

    private fun calculateDirectorySize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) calculateDirectorySize(f) else f.length()
        }
        return size
    }

    /**
     * 执行物理删除已选中的垃圾项
     */
    suspend fun cleanJunkItems(
        context: Context,
        items: List<JunkItem>
    ): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var deletedCount = 0
        var freedBytes = 0L
        val deletedPaths = mutableListOf<String>()

        for (item in items) {
            try {
                if (item.category == JunkCategory.EMPTY_DIRS) {
                    if (item.file.exists() && item.file.isDirectory) {
                        item.file.deleteRecursively()
                        deletedCount++
                    }
                } else {
                    if (item.file.exists()) {
                        val len = item.file.length()
                        if (item.file.delete()) {
                            deletedCount++
                            freedBytes += len
                            deletedPaths.add(item.file.absolutePath)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 刷新媒体库
        if (deletedPaths.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context, deletedPaths.toTypedArray(), null, null)
            } catch (ignored: Exception) {}
        }

        Pair(deletedCount, freedBytes)
    }
}
