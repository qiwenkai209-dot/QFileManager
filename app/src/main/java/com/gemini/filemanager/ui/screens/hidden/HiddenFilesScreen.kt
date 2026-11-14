package com.gemini.filemanager.ui.screens.hidden

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.core.file.HiddenFileItem
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import com.gemini.filemanager.ui.components.FileThumbnailView
import com.gemini.filemanager.ui.components.ImagePreviewDialog
import com.gemini.filemanager.ui.components.PlayableMediaItem
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenFilesScreen(
    onBack: () -> Unit,
    viewModel: HiddenFilesViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    var playingMediaFile by remember { mutableStateOf<File?>(null) }
    var playingIsAudio by remember { mutableStateOf(false) }
    var playingQueue by remember { mutableStateOf<List<PlayableMediaItem>>(emptyList()) }
    var playingQueueIndex by remember { mutableIntStateOf(0) }
    var previewImageList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var previewImageIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.isSelectionMode) "已选择 ${state.selectedPaths.size} 项" else "隐藏文件嗅探与恢复",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "小米私密相册 · .nomedia · 点文件 · 魔数识别恢复",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.isSelectionMode) {
                            viewModel.clearSelection()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            if (state.isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (state.isSelectionMode) {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                    } else {
                        IconButton(onClick = { viewModel.startScan() }, enabled = !state.isScanning) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 底部多选批量恢复栏
            if (state.isSelectionMode && state.selectedPaths.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${state.selectedPaths.size} 个隐藏文件",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = {
                                viewModel.restoreSelected { count ->
                                    Toast.makeText(context, "成功恢复 $count 个文件到相册/常规目录！", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("一键恢复为常规文件", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            // 后台静默扫描微指示条
            if (state.isScanning || state.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 分类快捷 Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.filterCategory == null,
                    onClick = { viewModel.setFilterCategory(null) },
                    label = { Text("全部 (${state.items.size})") }
                )
                FilterChip(
                    selected = state.filterCategory == FileCategory.IMAGE,
                    onClick = { viewModel.setFilterCategory(FileCategory.IMAGE) },
                    label = { Text("隐藏照片") }
                )
                FilterChip(
                    selected = state.filterCategory == FileCategory.VIDEO,
                    onClick = { viewModel.setFilterCategory(FileCategory.VIDEO) },
                    label = { Text("隐藏视频") }
                )
            }

            if (state.isScanning && state.filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在深度嗅探手机中所有隐藏与私密文件...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (state.filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未发现符合条件的隐藏文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = state.filteredItems,
                        key = { index, item -> "${item.fileItem.path}_$index" }
                    ) { _, item ->
                        val isSelected = state.selectedPaths.contains(item.fileItem.path)
                        HiddenFileItemRow(
                            item = item,
                            isSelected = isSelected,
                            isSelectionMode = state.isSelectionMode,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(item.fileItem.path)
                                } else if (item.fileItem.category == FileCategory.IMAGE) {
                                    val allImages = state.filteredItems.filter { it.fileItem.category == FileCategory.IMAGE }.map { it.fileItem }
                                    val idx = allImages.indexOfFirst { it.path == item.fileItem.path }.coerceAtLeast(0)
                                    previewImageList = allImages
                                    previewImageIndex = idx
                                } else if (item.fileItem.category == FileCategory.VIDEO || item.fileItem.category == FileCategory.AUDIO) {
                                    val allMedia = state.filteredItems.filter { it.fileItem.category == item.fileItem.category }
                                    val isAud = (item.fileItem.category == FileCategory.AUDIO)
                                    val queue = allMedia.map { m ->
                                        PlayableMediaItem(
                                            id = m.fileItem.path,
                                            title = m.fileItem.name,
                                            fileSizeBytes = m.fileItem.size,
                                            isAudio = isAud,
                                            directUri = Uri.fromFile(File(m.fileItem.path))
                                        )
                                    }
                                    val idx = queue.indexOfFirst { it.id == item.fileItem.path }.coerceAtLeast(0)
                                    playingQueue = queue
                                    playingQueueIndex = idx
                                    playingMediaFile = File(item.fileItem.path)
                                    playingIsAudio = isAud
                                } else {
                                    FileUtils.openFile(context, File(item.fileItem.path))
                                }
                            },
                            onLongClick = {
                                if (!state.isSelectionMode) {
                                    viewModel.toggleSelection(item.fileItem.path)
                                }
                            },
                            onRestoreClick = {
                                viewModel.restoreSingle(item) { success ->
                                    if (success) {
                                        Toast.makeText(context, "已恢复为常规文件，相册立即可见！", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "恢复失败，可能目标同名文件已存在", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 图片全屏画廊预览弹窗
    if (previewImageList.isNotEmpty()) {
        ImagePreviewDialog(
            initialIndex = previewImageIndex,
            imageList = previewImageList,
            onDismissRequest = { previewImageList = emptyList() }
        )
    }

    // 视频 / 音乐媒体播放弹窗（支持多选队列列表连续播放、上一首/下一首与进度拖拽）
    if (playingMediaFile != null) {
        VideoPlayerDialog(
            videoFile = playingMediaFile,
            title = playingMediaFile?.name ?: "媒体播放",
            isAudio = playingIsAudio,
            mediaQueue = playingQueue,
            initialIndex = playingQueueIndex,
            onDismissRequest = {
                playingMediaFile = null
                playingQueue = emptyList()
            }
        )
    }
}

@Composable
fun HiddenFileItemRow(
    item: HiddenFileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // 文件缩略图（即使是隐藏图片或隐藏视频，同样支持多帧轮播预览！）
            FileThumbnailView(
                path = item.fileItem.path,
                category = item.fileItem.category,
                extension = item.realExtension,
                size = 46.dp,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileItem.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isMiuiSecretAlbum) {
                        Surface(
                            color = Color(0xFFFF5722).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "小米私密相册",
                                color = Color(0xFFFF5722),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (item.isInNoMediaFolder) {
                        Surface(
                            color = Color(0xFF673AB7).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = ".nomedia 隐藏",
                                color = Color(0xFF673AB7),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "${FileUtils.formatFileSize(item.fileItem.size)} · 真实类型: .${item.realExtension.uppercase()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isSelectionMode) {
                FilledTonalButton(
                    onClick = onRestoreClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("恢复", fontSize = 12.sp)
                }
            }
        }
    }
}
