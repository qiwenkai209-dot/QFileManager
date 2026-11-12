package com.gemini.filemanager.core.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gemini.filemanager.MainActivity
import com.gemini.filemanager.core.localsend.LocalSendManager
import com.gemini.filemanager.core.smb.SmbStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 统一后台常驻防休眠前台服务：
 * - 维持应用进程优先级，防止息屏或切后台被 Doze/系统回收；
 * - 托管 LocalSend 局域网服务端与多任务唤醒锁续期心跳；
 * - 支持通知栏一键暂停常驻（若无活跃传输/转码任务则自动停止）。
 */
class BackgroundKeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppBackgroundKeepAliveManager.init(this, startServiceIfAllowed = false)
        AppBackgroundKeepAliveManager.markServiceRunning(true)
        startForeground(NOTIFICATION_ID, buildNotification())

        SmbStreamRegistry.attachAppContext(this)
        LocalSendManager.init(this)

        startHeartbeatLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE_RESIDENT -> {
                AppBackgroundKeepAliveManager.setKeepAliveEnabled(this, false)
                if (!AppBackgroundKeepAliveManager.shouldRunServiceSticky()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_REFRESH_NOTIFICATION -> {
                updateNotificationSafe()
            }
            else -> {
                updateNotificationSafe()
            }
        }
        return if (AppBackgroundKeepAliveManager.shouldRunServiceSticky()) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 若仍处于保活状态或有活跃任务，刷新守护通知维持服务状态
        if (AppBackgroundKeepAliveManager.shouldRunServiceSticky()) {
            AppBackgroundKeepAliveManager.renewPowerLocksIfHeld(this)
            updateNotificationSafe()
        }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        AppBackgroundKeepAliveManager.markServiceRunning(false)
        AppBackgroundKeepAliveManager.releaseAllLocksInternal()
        super.onDestroy()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                AppBackgroundKeepAliveManager.renewPowerLocksIfHeld(this@BackgroundKeepAliveService)
                updateNotificationSafe()
            }
        }
    }

    private fun updateNotificationSafe() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台常驻防休眠服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 SMB 传输/播片、M3U8 转码与 LocalSend 快传在后台不休眠"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val state = AppBackgroundKeepAliveManager.uiState.value
        val summary = AppBackgroundKeepAliveManager.formatActiveReasonsSummary(state.activeTaskReasons)
        val title = if (summary.isNotEmpty()) {
            "Q管理 · 后台任务运行中（防休眠）"
        } else if (state.strictWakeLockEnabled) {
            "Q管理 · 锁屏强保活守护中"
        } else {
            "Q管理 · 后台防休眠守护中"
        }

        val content = when {
            summary.isNotEmpty() -> "$summary · 已锁定 CPU/Wi-Fi 防休眠"
            state.strictWakeLockEnabled -> "已开启锁屏唤醒锁，LocalSend 与局域网保持在线"
            else -> "LocalSend 快传在线中，待命传输与后台任务"
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.keepAliveEnabled && state.activeTaskReasons.isEmpty()) {
            val pauseIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, BackgroundKeepAliveService::class.java).apply {
                    action = ACTION_DISABLE_RESIDENT
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "关闭常驻", pauseIntent)
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "background_keep_alive_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L

        private const val ACTION_DISABLE_RESIDENT = "com.gemini.filemanager.action.DISABLE_RESIDENT"
        private const val ACTION_REFRESH_NOTIFICATION = "com.gemini.filemanager.action.REFRESH_KEEPALIVE"

        fun startService(context: Context) {
            val intent = Intent(context, BackgroundKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun refreshNotification(context: Context) {
            val intent = Intent(context, BackgroundKeepAliveService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, BackgroundKeepAliveService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
