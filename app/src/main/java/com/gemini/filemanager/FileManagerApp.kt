package com.gemini.filemanager

import android.app.Application
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.core.smb.SmbStreamRegistry

class FileManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SmbStreamRegistry.attachAppContext(this)
        // 初始化后台防休眠管理；若系统允许则立即拉起常驻守护服务，否则在 Activity 进入前台时自动激活
        AppBackgroundKeepAliveManager.init(this, startServiceIfAllowed = true)
    }
}
