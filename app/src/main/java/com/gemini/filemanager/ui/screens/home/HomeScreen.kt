package com.gemini.filemanager.ui.screens.home

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    LaunchedEffect(Unit) {
        viewModel.refreshSmbCount()
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
                        "已保存 ${state.savedSmbCount} 台共享主机 · 支持 SMB1/2/3 与在线播片"
                    } else {
                        "直连 Windows / macOS / NAS 共享目录，支持传输与在线看片"
                    },
                    icon = Icons.Default.FolderShared,
                    accentColor = Color(0xFF3F51B5),
                    onClick = onNavigateSmb
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
