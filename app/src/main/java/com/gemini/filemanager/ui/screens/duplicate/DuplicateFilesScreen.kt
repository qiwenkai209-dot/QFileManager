package com.gemini.filemanager.ui.screens.duplicate

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.DuplicateFileItem
import com.gemini.filemanager.data.model.DuplicateFilterType
import com.gemini.filemanager.data.model.DuplicateGroup
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFilesScreen(
    onBack: () -> Unit,
    viewModel: DuplicateViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var previewMediaFile by remember { mutableStateOf<File?>(null) }
    var previewIsAudio by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "重复文件筛查",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isScanning) "正在高速筛查全盘冗余副本..." else "已发现 ${state.groups.size} 组重复文件",
                            fontSize = 12.sp,
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
            // 底部操作栏：有选中的重复文件时浮现
            AnimatedVisibility(
                visible = state.selectedFileCount > 0,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
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
                                text = "已选中 ${state.selectedFileCount} 个副本",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "预计释放 ${FileUtils.formatFileSize(state.selectedReclaimableBytes)}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { showDeleteConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键清理", fontWeight = FontWeight.Bold)
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
            // 分类筛选 Chip 滚动栏
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DuplicateFilterType.values()) { filter ->
                    FilterChip(
                        selected = state.currentFilter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.displayName) },
                        leadingIcon = if (state.currentFilter == filter) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            // 扫描中状态
            if (state.isScanning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { state.scanProgress },
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.scanStage,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (state.scanCurrentFile.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = state.scanCurrentFile,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { state.scanProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            // 扫描完成后：概览统计与快捷选择工具条
            if (!state.isScanning && state.groups.isNotEmpty()) {
                OverviewAndActionBar(
                    totalGroups = state.groups.size,
                    totalDuplicates = state.totalDuplicateCount,
                    totalReclaimBytes = state.totalReclaimableBytes,
                    onKeepOldest = { viewModel.smartSelectKeepOldest() },
                    onKeepNewest = { viewModel.smartSelectKeepNewest() },
                    onSelectAllDuplicates = { viewModel.selectAllDuplicates() },
                    onClearSelection = { viewModel.clearSelection() }
                )
            }

            // 列表或空状态
            if (!state.isScanning && state.groups.isEmpty()) {
                EmptyDuplicateView(onRescan = { viewModel.startScan() })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        items = state.groups,
                        key = { it.hash }
                    ) { group ->
                        DuplicateGroupCard(
                            group = group,
                            onToggleItem = { path ->
                                viewModel.toggleItemSelection(group.hash, path)
                            },
                            onOpenFile = { fileItem ->
                                if (fileItem.category == FileCategory.VIDEO) {
                                    previewMediaFile = fileItem.file
                                    previewIsAudio = false
                                } else if (fileItem.category == FileCategory.AUDIO) {
                                    previewMediaFile = fileItem.file
                                    previewIsAudio = true
                                } else {
                                    FileUtils.openFile(context, fileItem.file)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认清理重复文件？") },
            text = {
                Text(
                    text = "即将删除选中的 ${state.selectedFileCount} 个重复副本，预计释放 ${FileUtils.formatFileSize(state.selectedReclaimableBytes)} 空间。\n\n已为您自动保留 1 份原始文件，确认后将永久删除副本。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedFiles { count, reclaimed ->
                            Toast.makeText(context, "已成功清理 $count 个文件，释放 ${FileUtils.formatFileSize(reclaimed)}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("立即删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 视频 / 音频预览播放弹窗（内置全新进度条自由拖拽）
    previewMediaFile?.let { mediaFile ->
        VideoPlayerDialog(
            videoFile = mediaFile,
            title = mediaFile.name,
            isAudio = previewIsAudio,
            onDismissRequest = {
                previewMediaFile = null
            }
        )
    }
}

/**
 * 统计横幅与智能快捷操作条
 */
@Composable
fun OverviewAndActionBar(
    totalGroups: Int,
    totalDuplicates: Int,
    totalReclaimBytes: Long,
    onKeepOldest: () -> Unit,
    onKeepNewest: () -> Unit,
    onSelectAllDuplicates: () -> Unit,
    onClearSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "发现 $totalGroups 组完全一致文件",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "共有 $totalDuplicates 个重复副本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "可省 ${FileUtils.formatFileSize(totalReclaimBytes)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // 智能预设选择按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onKeepOldest,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保留最早", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onKeepNewest,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保留最新", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onSelectAllDuplicates,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("全选副本", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onClearSelection,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("全不选", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * 重复组卡片
 */
@Composable
fun DuplicateGroupCard(
    group: DuplicateGroup,
    onToggleItem: (String) -> Unit,
    onOpenFile: (DuplicateFileItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 卡片头部：类型、文件大小、重复数量、可释放大小
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(getCategoryColor(group.category).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getCategoryIcon(group.category),
                        contentDescription = null,
                        tint = getCategoryColor(group.category),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "单份大小: ${FileUtils.formatFileSize(group.fileSize)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "共 ${group.totalCount} 个文件 · ${group.duplicateCount} 个重复副本",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "可释放 ${FileUtils.formatFileSize(group.potentialReclaimBytes)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(4.dp))

            // 组内每个文件
            group.files.forEach { item ->
                DuplicateFileRow(
                    item = item,
                    onToggle = { onToggleItem(item.path) },
                    onOpen = { onOpenFile(item) }
                )
            }
        }
    }
}

/**
 * 组内单文件条目
 */
@Composable
fun DuplicateFileRow(
    item: DuplicateFileItem,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
            modifier = Modifier.size(28.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isOriginal) {
                    Surface(
                        color = Color(0xFF4CAF50).copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "原文件",
                            color = Color(0xFF2E7D32),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "副本",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = item.path,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "修改时间: ${dateFormat.format(Date(item.lastModified))}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        IconButton(
            onClick = onOpen,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (item.category == FileCategory.VIDEO || item.category == FileCategory.AUDIO)
                    Icons.Default.PlayCircleOutline else Icons.Default.Visibility,
                contentDescription = "查看或播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 暂无重复文件空状态
 */
@Composable
fun EmptyDuplicateView(onRescan: () -> Unit) {
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
                text = "太棒了，未发现重复文件！",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "当前分类下所有文件都是独一无二的，没有占用额外的手机存储空间。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRescan) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新筛查全盘")
            }
        }
    }
}

private fun getCategoryIcon(category: FileCategory): ImageVector {
    return when (category) {
        FileCategory.IMAGE -> Icons.Default.Image
        FileCategory.VIDEO -> Icons.Default.Movie
        FileCategory.AUDIO -> Icons.Default.Audiotrack
        FileCategory.DOCUMENT -> Icons.Default.Description
        FileCategory.APK -> Icons.Default.Android
        FileCategory.ARCHIVE -> Icons.Default.Archive
        FileCategory.M3U8 -> Icons.Default.VideoLibrary
    }
}

private fun getCategoryColor(category: FileCategory): Color {
    return when (category) {
        FileCategory.IMAGE -> Color(0xFF4CAF50)
        FileCategory.VIDEO -> Color(0xFF9C27B0)
        FileCategory.AUDIO -> Color(0xFFE91E63)
        FileCategory.DOCUMENT -> Color(0xFF2196F3)
        FileCategory.APK -> Color(0xFF009688)
        FileCategory.ARCHIVE -> Color(0xFFFF9800)
        FileCategory.M3U8 -> Color(0xFFFF5722)
    }
}
