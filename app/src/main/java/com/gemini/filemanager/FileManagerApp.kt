package com.gemini.filemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.core.smb.SmbStreamRegistry

class FileManagerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        SmbStreamRegistry.attachAppContext(this)
        // 初始化后台防休眠管理；若系统允许则立即拉起常驻守护服务，否则在 Activity 进入前台时自动激活
        AppBackgroundKeepAliveManager.init(this, startServiceIfAllowed = true)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
