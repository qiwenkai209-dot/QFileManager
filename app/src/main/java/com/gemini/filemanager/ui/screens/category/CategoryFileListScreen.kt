package com.gemini.filemanager.ui.screens.category

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFileListScreen(
    category: FileCategory,
    onBack: () -> Unit,
    onSendViaLocalSend: (List<File>) -> Unit,
    onUploadToSmb: (List<File>) -> Unit = {},
    viewModel: CategoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var playingMediaFile by remember { mutableStateOf<File?>(null) }
    var playingIsAudio by remember { mutableStateOf(false) }

    var renamingItem by remember { mutableStateOf<FileItem?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(category) {
        viewModel.initCategory(category)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSelectionMode) {
                        Text("已选择 ${state.selectedFilePaths.size} 项")
                    } else {
                        Text(category.displayName)
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
                        IconButton(onClick = { viewModel.loadFiles() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 多选操作栏：剪切、复制、删除、快传
            if (state.isSelectionMode && state.selectedFilePaths.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 剪切
                        IconButton(onClick = {
                            viewModel.cutSelected()
                            Toast.makeText(context, "已剪切到剪贴板，请到目标目录点击【粘贴】", Toast.LENGTH_SHORT).show()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ContentCut, contentDescription = "剪切", modifier = Modifier.size(20.dp))
                                Text("剪切", fontSize = 10.sp)
                            }
                        }

                        // 复制
                        IconButton(onClick = {
                            viewModel.copySelected()
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(20.dp))
                                Text("复制", fontSize = 10.sp)
                            }
                        }

                        // LocalSend 快传
                        IconButton(onClick = {
                            val files = viewModel.getSelectedFiles()
                            onSendViaLocalSend(files)
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.NearMe, contentDescription = "快传", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("快传", fontSize = 10.sp)
                            }
                        }

                        // 上传到 SMB
                        IconButton(onClick = {
                            val files = viewModel.getSelectedFiles()
                            onUploadToSmb(files)
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderShared, contentDescription = "SMB", tint = Color(0xFF3F51B5), modifier = Modifier.size(20.dp))
                                Text("SMB", fontSize = 10.sp)
                            }
                        }

                        // 分享
                        IconButton(onClick = {
                            FileUtils.shareFiles(context, viewModel.getSelectedFiles())
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Share, contentDescription = "分享", modifier = Modifier.size(20.dp))
                                Text("分享", fontSize = 10.sp)
                            }
                        }

                        // 删除
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            }
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
            // 剪贴板粘贴浮动提醒栏
            if (state.clipboardAction != null && state.clipboardFiles.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "已${if (state.clipboardAction == ClipboardAction.CUT) "剪切" else "复制"} ${state.clipboardFiles.size} 项",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row {
                            TextButton(onClick = { viewModel.clearClipboard() }) {
                                Text("取消", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    viewModel.pasteClipboard { count ->
                                        Toast.makeText(context, "成功粘贴 $count 个文件到 Download 目录", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("粘贴到 Download", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 搜索条
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("搜索文件名或包名...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 排序与统计栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "共 ${state.filteredFiles.size} 项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box {
                    TextButton(
                        onClick = { showSortMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Sort,
                            contentDescription = "排序",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.sortOption.displayName,
                            fontSize = 12.sp
                        )
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (option == state.sortOption) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = option.displayName,
                                            fontWeight = if (option == state.sortOption) FontWeight.Bold else FontWeight.Normal,
                                            color = if (option == state.sortOption) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.filteredFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无此类文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.filteredFiles, key = { it.path }) { item ->
                        val isSelected = state.selectedFilePaths.contains(item.path)
                        FileListItemView(
                            item = item,
                            isSelected = isSelected,
                            isSelectionMode = state.isSelectionMode,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(item.path)
                                } else if (item.category == FileCategory.VIDEO || item.category == FileCategory.AUDIO) {
                                    playingMediaFile = File(item.path)
                                    playingIsAudio = (item.category == FileCategory.AUDIO)
                                } else {
                                    FileUtils.openFile(context, File(item.path))
                                }
                            },
                            onLongClick = {
                                if (!state.isSelectionMode) {
                                    viewModel.toggleSelection(item.path)
                                }
                            },
                            onRenameClick = {
                                renamingItem = item
                                renameInputText = item.name
                            }
                        )
                    }
                }
            }
        }
    }

    // 重命名对话框
    if (renamingItem != null) {
        AlertDialog(
            onDismissRequest = { renamingItem = null },
            title = { Text("文件重命名") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("新文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = renamingItem
                    if (target != null && renameInputText.isNotBlank()) {
                        viewModel.renameFile(target.path, renameInputText.trim()) { success ->
                            if (!success) {
                                Toast.makeText(context, "重命名失败，同名文件可能已存在", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    renamingItem = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingItem = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 视频/音频直接播放弹窗（支持全屏和进度拖动）
    if (playingMediaFile != null) {
        VideoPlayerDialog(
            videoFile = playingMediaFile!!,
            title = playingMediaFile!!.name,
            isAudio = playingIsAudio,
            onDismissRequest = { playingMediaFile = null }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${state.selectedFilePaths.size} 个文件吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteSelectedFiles()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItemView(
    item: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
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

            // 文件类型图标
            val icon = when (item.category) {
                FileCategory.IMAGE -> Icons.Default.Image
                FileCategory.AUDIO -> Icons.Default.Audiotrack
                FileCategory.VIDEO -> Icons.Default.Movie
                FileCategory.APK -> Icons.Default.Android
                FileCategory.ARCHIVE -> Icons.Default.Archive
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.appName ?: item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.packageName != null) {
                    Text(
                        text = "${item.packageName} (${item.versionName})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = FileUtils.formatFileSize(item.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = FileUtils.formatDate(item.lastModified),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isSelectionMode) {
                IconButton(onClick = onRenameClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "重命名",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
