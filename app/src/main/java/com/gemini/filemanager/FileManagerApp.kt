package com.gemini.filemanager

import android.app.Application
import com.gemini.filemanager.core.localsend.LocalSendBackgroundService

class FileManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启动后台快传发现服务
        try {
            LocalSendBackgroundService.startService(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
