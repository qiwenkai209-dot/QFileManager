package com.gemini.filemanager.core.file

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

data class ApkDetails(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: Drawable? = null
)

object ApkParser {

    fun parseApk(context: Context, apkFile: File): ApkDetails? {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info: PackageInfo? = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            info?.let {
                val appInfo = it.applicationInfo
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                val appName = appInfo.loadLabel(pm).toString()
                val icon = appInfo.loadIcon(pm)
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toLong()
                }
                ApkDetails(
                    appName = appName,
                    packageName = it.packageName,
                    versionName = it.versionName ?: "1.0",
                    versionCode = versionCode,
                    icon = icon
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
