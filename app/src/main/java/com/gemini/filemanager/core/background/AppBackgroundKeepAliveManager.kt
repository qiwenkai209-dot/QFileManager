package com.gemini.filemanager.core.background

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class KeepAliveUiState(
    val keepAliveEnabled: Boolean = true,
    val strictWakeLockEnabled: Boolean = false,
    val isServiceRunning: Boolean = false,
    val isWakeLockHeld: Boolean = false,
    val isWifiLockHeld: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val activeTaskReasons: List<String> = emptyList()
)

internal data class KeepAlivePolicy(
    val shouldRunForegroundService: Boolean,
    val shouldHoldPowerLocks: Boolean
)

/**
 * 全局后台保活与防系统休眠管理器：
 * 1. 统一维护前台保活服务状态与用户常驻开关；
 * 2. 基于租约计数自动持有/续期 CPU PARTIAL_WAKE_LOCK 与 Wi-Fi 高性能锁，任务结束自动释放；
 * 3. 提供系统电池优化白名单与通知权限状态检测及一键设置 Intent。
 */
object AppBackgroundKeepAliveManager {

    private const val PREFS_NAME = "keep_alive_prefs_v1"
    private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
    private const val KEY_STRICT_WAKELOCK_ENABLED = "strict_wakelock_enabled"

    private const val WAKELOCK_TAG = "QFileManager:KeepAliveCpuLock"
    private const val WIFILOCK_TAG = "QFileManager:KeepAliveWifiLock"
    private const val WAKELOCK_TIMEOUT_MS = 90 * 60 * 1000L

    private val lock = Any()
    private val activeLeases = ConcurrentHashMap<String, String>()

    @Volatile
    private var prefsLoaded = false
    @Volatile
    private var keepAliveEnabledPref = true
    @Volatile
    private var strictWakeLockPref = false
    @Volatile
    private var serviceRunning = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _uiState = MutableStateFlow(KeepAliveUiState())
    val uiState: StateFlow<KeepAliveUiState> = _uiState.asStateFlow()

    fun init(context: Context, startServiceIfAllowed: Boolean = false) {
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        refreshSystemStates(appCtx)
        syncPowerAndService(appCtx, allowStartService = startServiceIfAllowed)
    }

    fun ensureStartedFromForeground(context: Context) {
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        refreshSystemStates(appCtx)
        syncPowerAndService(appCtx, allowStartService = true)
    }

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        synchronized(lock) {
            keepAliveEnabledPref = enabled
            if (!enabled) {
                strictWakeLockPref = false
            }
            savePrefs(appCtx)
        }
        syncPowerAndService(appCtx, allowStartService = true)
    }

    fun setStrictWakeLockEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        synchronized(lock) {
            strictWakeLockPref = enabled
            if (enabled) {
                keepAliveEnabledPref = true
            }
            savePrefs(appCtx)
        }
        syncPowerAndService(appCtx, allowStartService = true)
    }

    fun acquireLease(context: Context, tag: String, reason: String) {
        if (tag.isBlank()) return
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        activeLeases[tag] = reason
        syncPowerAndService(appCtx, allowStartService = true)
    }

    fun releaseLease(context: Context, tag: String) {
        if (tag.isBlank()) return
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        activeLeases.remove(tag)
        syncPowerAndService(appCtx, allowStartService = false)
    }

    fun renewPowerLocksIfHeld(context: Context) {
        val appCtx = context.applicationContext
        ensurePrefsLoaded(appCtx)
        refreshSystemStates(appCtx)
        val policy = evaluatePolicy(keepAliveEnabledPref, strictWakeLockPref, activeLeases.size)
        if (policy.shouldHoldPowerLocks) {
            synchronized(lock) {
                acquirePowerLocksLocked(appCtx, forceRenew = true)
            }
            publishUiState()
        }
    }

    fun shouldRunServiceSticky(): Boolean {
        return evaluatePolicy(keepAliveEnabledPref, strictWakeLockPref, activeLeases.size).shouldRunForegroundService
    }

    internal fun markServiceRunning(running: Boolean) {
        serviceRunning = running
        publishUiState()
    }

    internal fun releaseAllLocksInternal() {
        synchronized(lock) {
            releasePowerLocksLocked()
        }
        publishUiState()
    }

    fun refreshSystemStates(context: Context) {
        val appCtx = context.applicationContext
        val ignoringBattery = isIgnoringBatteryOptimizations(appCtx)
        val hasNotifPerm = hasNotificationPermission(appCtx)
        _uiState.update { state ->
            state.copy(
                keepAliveEnabled = keepAliveEnabledPref,
                strictWakeLockEnabled = strictWakeLockPref,
                isServiceRunning = serviceRunning,
                isIgnoringBatteryOptimizations = ignoringBattery,
                hasNotificationPermission = hasNotifPerm,
                activeTaskReasons = activeLeases.values.toList()
            )
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("BatteryLife")
    fun buildIgnoreBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBatteryOptimizationSettingsFallbackIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildAppNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun ensurePrefsLoaded(appCtx: Context) {
        if (prefsLoaded) return
        synchronized(lock) {
            if (prefsLoaded) return
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            keepAliveEnabledPref = prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
            strictWakeLockPref = prefs.getBoolean(KEY_STRICT_WAKELOCK_ENABLED, false)
            prefsLoaded = true
        }
    }

    private fun savePrefs(appCtx: Context) {
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_KEEP_ALIVE_ENABLED, keepAliveEnabledPref)
            .putBoolean(KEY_STRICT_WAKELOCK_ENABLED, strictWakeLockPref)
            .apply()
    }

    private fun syncPowerAndService(appCtx: Context, allowStartService: Boolean) {
        val policy = evaluatePolicy(keepAliveEnabledPref, strictWakeLockPref, activeLeases.size)
        synchronized(lock) {
            if (policy.shouldHoldPowerLocks) {
                acquirePowerLocksLocked(appCtx, forceRenew = false)
            } else {
                releasePowerLocksLocked()
            }
        }

        if (policy.shouldRunForegroundService) {
            if (serviceRunning) {
                BackgroundKeepAliveService.refreshNotification(appCtx)
            } else if (allowStartService) {
                BackgroundKeepAliveService.startService(appCtx)
            }
        } else if (serviceRunning) {
            BackgroundKeepAliveService.stopService(appCtx)
        }

        publishUiState()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquirePowerLocksLocked(appCtx: Context, forceRenew: Boolean) {
        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                        setReferenceCounted(false)
                    }
                }
                val wl = wakeLock
                if (wl != null && (forceRenew || !wl.isHeld)) {
                    wl.acquire(WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (_: Exception) {
        }

        try {
            val wm = appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                if (wifiLock == null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(mode, WIFILOCK_TAG).apply {
                        setReferenceCounted(false)
                    }
                }
                val wfl = wifiLock
                if (wfl != null && !wfl.isHeld) {
                    wfl.acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releasePowerLocksLocked() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun publishUiState() {
        val wlHeld = try {
            wakeLock?.isHeld == true
        } catch (_: Exception) {
            false
        }
        val wflHeld = try {
            wifiLock?.isHeld == true
        } catch (_: Exception) {
            false
        }
        _uiState.update { state ->
            state.copy(
                keepAliveEnabled = keepAliveEnabledPref,
                strictWakeLockEnabled = strictWakeLockPref,
                isServiceRunning = serviceRunning,
                isWakeLockHeld = wlHeld,
                isWifiLockHeld = wflHeld,
                activeTaskReasons = activeLeases.values.toList()
            )
        }
    }

    internal fun evaluatePolicy(
        keepAliveEnabled: Boolean,
        strictWakeLockEnabled: Boolean,
        activeLeaseCount: Int
    ): KeepAlivePolicy {
        val hasActiveLeases = activeLeaseCount > 0
        return KeepAlivePolicy(
            shouldRunForegroundService = keepAliveEnabled || hasActiveLeases,
            shouldHoldPowerLocks = hasActiveLeases || (keepAliveEnabled && strictWakeLockEnabled)
        )
    }

    internal fun formatActiveReasonsSummary(reasons: Collection<String>): String {
        val distinct = reasons.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return ""
        return if (distinct.size <= 2) {
            distinct.joinToString(" · ")
        } else {
            "${distinct.take(2).joinToString(" · ")} 等 ${distinct.size} 项"
        }
    }
}
