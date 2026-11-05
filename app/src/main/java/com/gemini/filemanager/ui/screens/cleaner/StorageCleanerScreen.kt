package com.gemini.filemanager.ui.screens.cleaner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.InstalledAppInfo
import com.gemini.filemanager.data.model.JunkCategory
import com.gemini.filemanager.data.model.JunkGroup
import com.gemini.filemanager.data.model.JunkItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCleanerScreen(
    onBack: () -> Unit,
    viewModel: CleanerViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var showConfirmCleanDialog by remember { mutableStateOf(false) }
    var appToDeleteDataTarget by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkPermissionAndRefresh()
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // 过滤与排序应用列表
    val filteredApps = remember(state.installedApps, state.appFilterType, state.searchQuery, state.appSortType) {
        var list = when (state.appFilterType) {
            1 -> state.installedApps.filter { !it.isSystemApp } // 用户安装软件
            2 -> state.installedApps.filter { it.isSystemApp }  // 系统应用
            else -> state.installedApps                         // 全部
        }

        if (state.searchQuery.isNotBlank()) {
            val q = state.searchQuery.trim().lowercase(Locale.ROOT)
            list = list.filter {
                it.appName.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
            }
        }

        when (state.appSortType) {
            1 -> list.sortedByDescending { it.lastUpdateTime }
            2 -> list.sortedBy { it.appName.lowercase(Locale.ROOT) }
            else -> list.sortedWith(
                compareBy<InstalledAppInfo> { it.isSystemApp }
                    .thenByDescending { it.totalEstimatedSize }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "本机软件管理与深度清理",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isScanning) "正在高速扫描手机安装的全部软件与数据..." else "扫描全部软件 · 支持卸载 · 清理缓存 · 删除软件数据",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.startScan() },
                        enabled = !state.isScanning
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // 底部悬浮操作栏
            if (state.currentTab == 0 && state.selectedAppCount > 0) {
                // 软件批量操作栏
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "已勾选 ${state.selectedAppCount} 款软件",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.selectedAppsTotalExternalBytes > 0) {
                                Text(
                                    text = "外部数据: ${FileUtils.formatFileSize(state.selectedAppsTotalExternalBytes)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 批量清外部数据
                            if (state.selectedAppsTotalExternalBytes > 0) {
                                OutlinedButton(
                                    onClick = { showBatchDeleteDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("清空数据", fontSize = 13.sp)
                                }
                            }

                            // 批量卸载
                            Button(
                                onClick = {
                                    val uninstalledTarget = state.selectedApps.firstOrNull { !it.isSystemApp }
                                    if (uninstalledTarget != null) {
                                        viewModel.uninstallApp(context, uninstalledTarget.packageName)
                                    } else {
                                        Toast.makeText(context, "所选软件包含系统预置应用，不可卸载", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("卸载所选", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else if (state.currentTab == 1 && state.selectedCount > 0) {
                // 全盘垃圾清理操作栏
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "已勾选 ${state.selectedCount} 项垃圾",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "预计释放 ${FileUtils.formatFileSize(state.selectedJunkBytes)}",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { showConfirmCleanDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("立即清理", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 1. 顶部综合统计看板
            AppCleanerDashboardCard(
                isScanning = state.isScanning,
                scanStage = state.scanStage,
                installedAppCount = state.installedApps.size,
                userAppCount = state.installedApps.count { !it.isSystemApp },
                totalStorageUsed = state.installedApps.sumOf { it.totalEstimatedSize }
            )

            // 2. 权限提示横幅 (若未开启使用情况访问)
            if (!state.hasUsageStatsPermission) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "开启精准存储统计",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "授权后可精确查看每款软件的内部缓存与真实数据占用",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { viewModel.openUsageAccessSettings(context) }) {
                            Text("去开启", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. 核心功能 Tab 切换 (Tab 0 为软件管理，Tab 1 为全盘垃圾清理)
            TabRow(
                selectedTabIndex = state.currentTab,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(
                    selected = state.currentTab == 0,
                    onClick = { viewModel.setTab(0) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("手机软件深度管理 (${state.installedApps.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                )
                Tab(
                    selected = state.currentTab == 1,
                    onClick = { viewModel.setTab(1) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("全盘垃圾深度清理 (${state.groups.size}类)")
                        }
                    }
                )
            }

            // 4. Tab 内容区域
            if (state.currentTab == 0) {
                // ========== Tab 0: 手机软件管理与清理视图 ==========
                Column(modifier = Modifier.fillMaxSize()) {
                    // 搜索与快捷过滤栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("搜索软件名称或包名...", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // 筛选 Chips 与排序选择
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = state.appFilterType == 1,
                                onClick = { viewModel.setAppFilterType(1) },
                                label = { Text("用户软件", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = state.appFilterType == 0,
                                onClick = { viewModel.setAppFilterType(0) },
                                label = { Text("全部 (${state.installedApps.size})", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = state.appFilterType == 2,
                                onClick = { viewModel.setAppFilterType(2) },
                                label = { Text("系统应用", fontSize = 11.sp) }
                            )
                        }

                        // 排序切换按钮
                        TextButton(
                            onClick = {
                                val nextSort = (state.appSortType + 1) % 3
                                viewModel.setAppSortType(nextSort)
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (state.appSortType) {
                                    1 -> "按时间"
                                    2 -> "按名称"
                                    else -> "按空间"
                                },
                                fontSize = 12.sp
                            )
                        }
                    }

                    // 软件列表
                    if (filteredApps.isEmpty() && !state.isScanning) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("未找到匹配的软件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 90.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { appInfo ->
                                InstalledAppCard(
                                    app = appInfo,
                                    onToggleSelect = { viewModel.toggleAppSelect(appInfo.packageName) },
                                    onCleanCache = {
                                        viewModel.openAppDetails(context, appInfo.packageName)
                                    },
                                    onDeleteData = {
                                        appToDeleteDataTarget = appInfo
                                    },
                                    onUninstall = {
                                        viewModel.uninstallApp(context, appInfo.packageName)
                                    }
                                )
                            }
                        }
                    }
                }

            } else {
                // ========== Tab 1: 全盘垃圾深度清理视图 ==========
                if (state.groups.isEmpty() && !state.isScanning) {
                    EmptyCleanView(onRescan = { viewModel.startScan() })
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "推荐深度清理项",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(
                                    onClick = {
                                        val allSelected = state.groups.all { it.isAllSelected }
                                        viewModel.selectAll(!allSelected)
                                    }
                                ) {
                                    Text(if (state.groups.all { it.isAllSelected }) "取消全选" else "全选所有")
                                }
                            }
                        }

                        items(state.groups, key = { it.category.name }) { group ->
                            JunkGroupCard(
                                group = group,
                                onToggleExpand = { viewModel.toggleGroupExpand(group.category) },
                                onToggleGroupSelect = { viewModel.toggleGroupSelect(group.category) },
                                onToggleItemSelect = { path -> viewModel.toggleItemSelect(group.category, path) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 单个软件删除外部数据弹窗
    appToDeleteDataTarget?.let { targetApp ->
        AlertDialog(
            onDismissRequest = { appToDeleteDataTarget = null },
            icon = { Icon(Icons.Default.FolderDelete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("清空软件外部数据与残留？") },
            text = {
                Text(
                    "将彻底删除「${targetApp.appName}」在存储卡 Android/data、Android/obb、Android/media 及关联目录下的所有离线缓存与存储数据（共占用 ${FileUtils.formatFileSize(targetApp.totalExternalSize)}）。\n\n删除后该应用需重新联网下载必要资源，确认立即清空吗？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pkg = targetApp.packageName
                        appToDeleteDataTarget = null
                        viewModel.deleteAppExternalData(pkg) { freed ->
                            Toast.makeText(context, "已成功清空并释放 ${FileUtils.formatFileSize(freed)} 空间", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToDeleteDataTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 批量清空勾选软件的外部数据弹窗
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("批量清空 ${state.selectedAppCount} 款软件的外部数据？") },
            text = {
                Text("即将清理所选软件在存储卡上的离线缓存与下载资源，预计释放 ${FileUtils.formatFileSize(state.selectedAppsTotalExternalBytes)} 空间。\n\n应用本身保留，确认执行吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteDialog = false
                        viewModel.batchDeleteSelectedAppsExternalData { count, freed ->
                            Toast.makeText(context, "批量清理完成，释放了 ${FileUtils.formatFileSize(freed)} 空间", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("立即清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 全盘垃圾清理确认弹窗
    if (showConfirmCleanDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmCleanDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认执行全盘垃圾清理？") },
            text = {
                Text(
                    text = "即将深度清理选中的 ${state.selectedCount} 项缓存与冗余垃圾，预计释放 ${FileUtils.formatFileSize(state.selectedJunkBytes)} 存储空间。\n\n已智能避开您的个人重要文档与相册原图，清理后可有效提升系统流畅度。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmCleanDialog = false
                        viewModel.cleanSelected { count, freed ->
                            Toast.makeText(context, "清理成功！释放了 ${FileUtils.formatFileSize(freed)} 空间", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("立即清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCleanDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 顶部软件管理看板
 */
@Composable
fun AppCleanerDashboardCard(
    isScanning: Boolean,
    scanStage: String,
    installedAppCount: Int,
    userAppCount: Int,
    totalStorageUsed: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Sync else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isScanning) "正在高速扫描手机软件..." else "已扫描本机安装软件",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$installedAppCount 款",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${userAppCount}款用户安装)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (totalStorageUsed > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "总计占用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FileUtils.formatFileSize(totalStorageUsed),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            if (isScanning) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = scanStage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 已安装应用卡片（支持全套操作：卸载、清理缓存/数据、删除软件外部数据与残留）
 */
@Composable
fun InstalledAppCard(
    app: InstalledAppInfo,
    onToggleSelect: () -> Unit,
    onCleanCache: () -> Unit,
    onDeleteData: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 上半部分：Checkbox、图标、应用名称、版本、存储分布
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = app.isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                val bitmap = remember(app.icon) { app.icon?.let { drawableToBitmap(it) } }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = app.appName,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        if (app.isSystemApp) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "系统预置",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${app.packageName} (v${app.versionName})",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 空间占用明细标签行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "代码: ${FileUtils.formatFileSize(app.apkSize)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (app.cacheSize > 0) {
                            Text(
                                text = "缓存: ${FileUtils.formatFileSize(app.cacheSize)}",
                                fontSize = 11.sp,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (app.totalExternalSize > 0) {
                            Text(
                                text = "外部数据: ${FileUtils.formatFileSize(app.totalExternalSize)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(8.dp))

            // 下半部分：三大核心操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 操作 1：删除软件外部数据与离线缓存
                if (app.totalExternalSize > 0) {
                    OutlinedButton(
                        onClick = onDeleteData,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空数据", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 操作 2：直达系统应用详情页一键清除内部缓存与数据
                FilledTonalButton(
                    onClick = onCleanCache,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清除缓存/数据", fontSize = 11.sp)
                }

                // 操作 3：卸载软件（非系统应用支持）
                if (!app.isSystemApp) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onUninstall,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("卸载", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 垃圾分类组卡片
 */
@Composable
fun JunkGroupCard(
    group: JunkGroup,
    onToggleExpand: () -> Unit,
    onToggleGroupSelect: () -> Unit,
    onToggleItemSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = group.isAllSelected,
                    onCheckedChange = { onToggleGroupSelect() },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(getCategoryColor(group.category).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getCategoryIcon(group.category),
                        contentDescription = null,
                        tint = getCategoryColor(group.category),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.category.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${group.itemCount} 项 · ${group.category.description}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = FileUtils.formatFileSize(group.totalSize),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (group.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "展开详情"
                    )
                }
            }

            AnimatedVisibility(visible = group.isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(6.dp))

                    group.items.forEach { item ->
                        JunkItemRow(
                            item = item,
                            onToggle = { onToggleItemSelect(item.path) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 组内单项垃圾文件
 */
@Composable
fun JunkItemRow(
    item: JunkItem,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.path,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.detailInfo.isNotEmpty()) {
                Text(
                    text = item.detailInfo,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }
        Text(
            text = FileUtils.formatFileSize(item.size),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 空状态界面
 */
@Composable
fun EmptyCleanView(onRescan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "手机非常干净！",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "未发现多余的应用临时缓存或残留垃圾，存储空间充裕。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRescan) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新深度体检")
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun getCategoryIcon(category: JunkCategory): ImageVector {
    return when (category) {
        JunkCategory.SYSTEM_CACHE -> Icons.Default.Storage
        JunkCategory.THUMBNAILS -> Icons.Default.Image
        JunkCategory.LOG_FILES -> Icons.Default.Description
        JunkCategory.OBSOLETE_APK -> Icons.Default.Android
        JunkCategory.EMPTY_DIRS -> Icons.Default.FolderOpen
    }
}

private fun getCategoryColor(category: JunkCategory): Color {
    return when (category) {
        JunkCategory.SYSTEM_CACHE -> Color(0xFF2196F3)
        JunkCategory.THUMBNAILS -> Color(0xFF4CAF50)
        JunkCategory.LOG_FILES -> Color(0xFFFF9800)
        JunkCategory.OBSOLETE_APK -> Color(0xFF009688)
        JunkCategory.EMPTY_DIRS -> Color(0xFF9C27B0)
    }
}
