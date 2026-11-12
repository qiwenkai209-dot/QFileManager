package com.gemini.filemanager.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.core.background.KeepAliveUiState
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateCategory: (FileCategory) -> Unit,
    onNavigateM3U8: () -> Unit,
    onNavigateLocalSend: () -> Unit,
    onNavigateSmb: () -> Unit,
    onNavigateDuplicate: () -> Unit,
    onNavigateCleaner: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val keepAliveState by viewModel.keepAliveState.collectAsState()
    val context = LocalContext.current
    var showKeepAliveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshSmbCount()
        viewModel.refreshKeepAliveStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Q管理",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "智能分类 · 存储清理 · M3U8转码 · SMB/LocalSend",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showKeepAliveDialog = true },
                        color = if (keepAliveState.isServiceRunning || keepAliveState.keepAliveEnabled) {
                            Color(0xFF00897B).copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (keepAliveState.isWakeLockHeld) Icons.Default.Bolt else Icons.Default.Shield,
                                contentDescription = "后台防休眠",
                                tint = if (keepAliveState.isServiceRunning || keepAliveState.keepAliveEnabled) {
                                    Color(0xFF00897B)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    keepAliveState.activeTaskReasons.isNotEmpty() -> "锁屏防休眠"
                                    keepAliveState.keepAliveEnabled -> "后台保活中"
                                    else -> "保活已关"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (keepAliveState.isServiceRunning || keepAliveState.keepAliveEnabled) {
                                    Color(0xFF00897B)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.loadOverview() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 存储状态卡片（带一键去清理入口）
            item {
                StorageCard(
                    totalBytes = state.totalStorage,
                    availableBytes = state.availableStorage,
                    onCleanClick = onNavigateCleaner
                )
            }

            // 2. 特色核心工具专区（2x2 黄金功能矩阵）
            item {
                Text(
                    text = "特色核心工具",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureToolCard(
                        modifier = Modifier.weight(1f),
                        title = "软件与存储清理",
                        subtitle = "缓存与临时残留深度清理",
                        icon = Icons.Default.CleaningServices,
                        accentColor = Color(0xFF00897B),
                        onClick = onNavigateCleaner
                    )
                    FeatureToolCard(
                        modifier = Modifier.weight(1f),
                        title = "重复文件筛查",
                        subtitle = "秒级识别重复照片与视频",
                        icon = Icons.Default.ContentCopy,
                        accentColor = Color(0xFF673AB7),
                        onClick = onNavigateDuplicate
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureToolCard(
                        modifier = Modifier.weight(1f),
                        title = "M3U8转MP4",
                        subtitle = if (state.cachedM3U8Count > 0) "发现 ${state.cachedM3U8Count} 个离线缓存" else "批量嗅探与秒级合并",
                        icon = Icons.Default.VideoLibrary,
                        accentColor = AccentOrange,
                        onClick = onNavigateM3U8
                    )
                    FeatureToolCard(
                        modifier = Modifier.weight(1f),
                        title = "LocalSend互传",
                        subtitle = if (discoveredDevices.isNotEmpty()) "发现 ${discoveredDevices.size} 台设备在线" else "开源局域网高速传输",
                        icon = Icons.Default.NearMe,
                        accentColor = AccentCyan,
                        onClick = onNavigateLocalSend
                    )
                }
            }

            item {
                FeatureWideCard(
                    title = "SMB 局域网网络共享",
                    subtitle = if (state.savedSmbCount > 0) {
                        "已保存 ${state.savedSmbCount} 台共享主机 · 断点续传与在线播片"
                    } else {
                        "直连 Windows / macOS / NAS 共享，支持断点续传与在线看片"
                    },
                    icon = Icons.Default.FolderShared,
                    accentColor = Color(0xFF3F51B5),
                    onClick = onNavigateSmb
                )
            }

            item {
                BackgroundKeepAliveCard(
                    keepAliveState = keepAliveState,
                    onToggleKeepAlive = { enabled -> viewModel.setKeepAliveEnabled(enabled) },
                    onOpenDetails = { showKeepAliveDialog = true }
                )
            }

            // 3. 基础分类管理
            item {
                Text(
                    text = "媒体与文件分类",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.IMAGE,
                            icon = Icons.Default.Image,
                            color = Color(0xFF4CAF50),
                            onClick = { onNavigateCategory(FileCategory.IMAGE) }
                        )
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.AUDIO,
                            icon = Icons.Default.Audiotrack,
                            color = Color(0xFFE91E63),
                            onClick = { onNavigateCategory(FileCategory.AUDIO) }
                        )
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.VIDEO,
                            icon = Icons.Default.Movie,
                            color = Color(0xFF9C27B0),
                            onClick = { onNavigateCategory(FileCategory.VIDEO) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.DOCUMENT,
                            icon = Icons.Default.Description,
                            color = Color(0xFF2196F3),
                            onClick = { onNavigateCategory(FileCategory.DOCUMENT) }
                        )
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.APK,
                            icon = Icons.Default.Android,
                            color = Color(0xFF009688),
                            onClick = { onNavigateCategory(FileCategory.APK) }
                        )
                        CategoryGridItem(
                            modifier = Modifier.weight(1f),
                            category = FileCategory.ARCHIVE,
                            icon = Icons.Default.Archive,
                            color = Color(0xFFFF9800),
                            onClick = { onNavigateCategory(FileCategory.ARCHIVE) }
                        )
                    }
                }
            }
        }
    }

    if (showKeepAliveDialog) {
        BackgroundKeepAliveDialog(
            state = keepAliveState,
            onToggleKeepAlive = { enabled -> viewModel.setKeepAliveEnabled(enabled) },
            onToggleStrictWakeLock = { enabled -> viewModel.setStrictWakeLockEnabled(enabled) },
            onRequestBatteryWhitelist = {
                try {
                    context.startActivity(AppBackgroundKeepAliveManager.buildIgnoreBatteryOptimizationIntent(context))
                } catch (_: Exception) {
                    try {
                        context.startActivity(AppBackgroundKeepAliveManager.buildBatteryOptimizationSettingsFallbackIntent())
                    } catch (_: Exception) {
                        Toast.makeText(context, "请在系统设置中手动允许 Q管理 后台无限制运行", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRequestNotificationSettings = {
                try {
                    context.startActivity(AppBackgroundKeepAliveManager.buildAppNotificationSettingsIntent(context))
                } catch (_: Exception) {
                    Toast.makeText(context, "请在系统设置中开启通知权限", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showKeepAliveDialog = false }
        )
    }
}

@Composable
fun StorageCard(
    totalBytes: Long,
    availableBytes: Long,
    onCleanClick: (() -> Unit)? = null
) {
    val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedPercent = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "手机内部存储",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已使用 ${FileUtils.formatFileSize(usedBytes)} / 共 ${FileUtils.formatFileSize(totalBytes)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${(usedPercent * 100).toInt()}%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (onCleanClick != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = onCleanClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("去清理", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { usedPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun FeatureToolCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
fun CategoryGridItem(
    modifier: Modifier = Modifier,
    category: FileCategory,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = category.displayName,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun FeatureWideCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = accentColor
            )
        }
    }
}

@Composable
fun BackgroundKeepAliveCard(
    keepAliveState: KeepAliveUiState,
    onToggleKeepAlive: (Boolean) -> Unit,
    onOpenDetails: () -> Unit
) {
    val activeSummary = AppBackgroundKeepAliveManager.formatActiveReasonsSummary(keepAliveState.activeTaskReasons)
    val accent = if (keepAliveState.keepAliveEnabled || keepAliveState.activeTaskReasons.isNotEmpty()) {
        Color(0xFF00897B)
    } else {
        MaterialTheme.colorScheme.outline
    }
    val statusText = buildString {
        when {
            activeSummary.isNotEmpty() -> append("活跃保护中: $activeSummary")
            keepAliveState.strictWakeLockEnabled -> append("锁屏强保活已开启 · CPU/Wi-Fi 唤醒锁保持中")
            keepAliveState.keepAliveEnabled -> append("前台常驻守护中 · 传输/转码自动锁定防休眠")
            else -> append("常驻保活已关闭 · 仅在传输或转码期间临时防休眠")
        }
        if (!keepAliveState.isIgnoringBatteryOptimizations) {
            append("\n提示：尚未加入系统电池优化白名单，点击可一键设置")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpenDetails() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.10f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (keepAliveState.isWakeLockHeld) Icons.Default.Bolt else Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "后台运行防休眠",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (keepAliveState.isWakeLockHeld || keepAliveState.isWifiLockHeld) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = accent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (keepAliveState.isWifiLockHeld) "CPU+Wi-Fi锁" else "CPU锁",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = keepAliveState.keepAliveEnabled,
                onCheckedChange = onToggleKeepAlive
            )
        }
    }
}

@Composable
private fun BackgroundKeepAliveDialog(
    state: KeepAliveUiState,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleStrictWakeLock: (Boolean) -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onRequestNotificationSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台运行与防休眠设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("后台常驻守护服务", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "保持前台低功耗守护，防止切后台后 LocalSend 与传输服务被系统回收",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.keepAliveEnabled,
                        onCheckedChange = onToggleKeepAlive
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("锁屏始终防休眠（强保活）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "关闭屏幕待机时也持续保持 CPU 与 Wi-Fi 唤醒锁；关闭此项则在任务结束后自动释放锁以省电",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.strictWakeLockEnabled,
                        onCheckedChange = onToggleStrictWakeLock
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("忽略系统电池优化", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (state.isIgnoringBatteryOptimizations) "已加入白名单（系统不会主动休眠本应用）" else "建议设为「无限制/不优化」防止厂商杀后台",
                            fontSize = 11.sp,
                            color = if (state.isIgnoringBatteryOptimizations) Color(0xFF00897B) else Color(0xFFE65100)
                        )
                    }
                    if (!state.isIgnoringBatteryOptimizations) {
                        TextButton(onClick = onRequestBatteryWhitelist) {
                            Text("去开启")
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00897B))
                    }
                }

                if (!state.hasNotificationPermission) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("通知栏显示权限", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("开启通知权限可实时查看后台保活与传输进度", fontSize = 11.sp, color = Color(0xFFE65100))
                        }
                        TextButton(onClick = onRequestNotificationSettings) {
                            Text("去授权")
                        }
                    }
                }

                val activeSummary = AppBackgroundKeepAliveManager.formatActiveReasonsSummary(state.activeTaskReasons)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = buildString {
                            append("守护服务：")
                            append(if (state.isServiceRunning) "运行中" else "未运行")
                            append(" · CPU锁：")
                            append(if (state.isWakeLockHeld) "锁定" else "空闲")
                            append(" · Wi-Fi锁：")
                            append(if (state.isWifiLockHeld) "锁定" else "空闲")
                            if (activeSummary.isNotEmpty()) {
                                append("\n当前保活任务：$activeSummary")
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}
