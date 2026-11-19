package com.gemini.filemanager.ui.screens.explorer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.gemini.filemanager.ui.components.FileThumbnailView
import com.gemini.filemanager.ui.components.ImagePreviewDialog
import com.gemini.filemanager.ui.components.PlayableMediaItem
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import com.gemini.filemanager.ui.screens.category.ClipboardAction
import com.gemini.filemanager.ui.screens.category.SortOption
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageExplorerScreen(
    onBack: () -> Unit,
    onSendViaLocalSend: ((List<File>) -> Unit)? = null,
    onUploadToSmb: ((List<File>) -> Unit)? = null,
    viewModel: StorageExplorerViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }

    var renamingItem by remember { mutableStateOf<FileItem?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // 媒体全屏播放与预览状态
    var previewImageList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var previewImageIndex by remember { mutableIntStateOf(0) }
    var playingMediaFile by remember { mutableStateOf<File?>(null) }
    var playingIsAudio by remember { mutableStateOf(false) }
    var playingQueue by remember { mutableStateOf<List<PlayableMediaItem>>(emptyList()) }
    var playingQueueIndex by remember { mutableIntStateOf(0) }

    val breadcrumbListState = rememberLazyListState()
    val fileListState = rememberLazyListState()

    // 自动平滑滚动到面包屑末端
    LaunchedEffect(state.breadcrumbs.size) {
        if (state.breadcrumbs.isNotEmpty()) {
            breadcrumbListState.animateScrollToItem(state.breadcrumbs.lastIndex)
        }
    }

    // 拦截返回按键：多选 -> 搜索 -> 上级目录 -> 退出
    BackHandler {
        when {
            state.isSelectionMode -> viewModel.clearSelection()
            showSearch -> {
                showSearch = false
                viewModel.onSearchQueryChange("")
            }
            viewModel.navigateUp() -> {
                // 已成功返回上一层目录
            }
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChange(it) },
                            placeholder = { Text("搜索当前目录文件...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除搜索")
                                    }
                                }
                            }
                        )
                    } else {
                        Column {
                            Text(
                                text = "手机存储",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (state.filteredFiles.isNotEmpty()) "共 ${state.filteredFiles.size} 项" else "空目录",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.isSelectionMode) {
                            viewModel.clearSelection()
                        } else if (showSearch) {
                            showSearch = false
                            viewModel.onSearchQueryChange("")
                        } else if (!viewModel.navigateUp()) {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (state.isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
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
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showNewFolderDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortOption.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName) },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (state.sortOption == option) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.refreshCurrentDirectory() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 剪贴板粘贴浮动栏
            AnimatedVisibility(
                visible = state.clipboardAction != null && state.clipboardFiles.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${if (state.clipboardAction == ClipboardAction.COPY) "复制" else "剪切"} ${state.clipboardFiles.size} 个项目",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.clearClipboard() }) {
                                Text("取消")
                            }
                            Button(onClick = {
                                viewModel.pasteClipboard { count ->
                                    Toast.makeText(context, "成功粘贴 $count 个文件", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("粘贴到此处")
                            }
                        }
                    }
                }
            }

            // 多选操作底栏
            AnimatedVisibility(
                visible = state.isSelectionMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            viewModel.copySelected()
                            Toast.makeText(context, "已复制，请导航到目标文件夹后粘贴", Toast.LENGTH_SHORT).show()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(22.dp))
                                Text("复制", fontSize = 10.sp)
                            }
                        }

                        IconButton(onClick = {
                            viewModel.cutSelected()
                            Toast.makeText(context, "已剪切，请导航到目标文件夹后粘贴", Toast.LENGTH_SHORT).show()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ContentCut, contentDescription = "剪切", modifier = Modifier.size(22.dp))
                                Text("剪切", fontSize = 10.sp)
                            }
                        }

                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                                Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (onSendViaLocalSend != null) {
                            IconButton(onClick = {
                                val selected = viewModel.getSelectedFiles()
                                onSendViaLocalSend(selected)
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.NearMe, contentDescription = "互传", modifier = Modifier.size(22.dp))
                                    Text("互传", fontSize = 10.sp)
                                }
                            }
                        }

                        if (onUploadToSmb != null) {
                            IconButton(onClick = {
                                val selected = viewModel.getSelectedFiles()
                                onUploadToSmb(selected)
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = "上传SMB", modifier = Modifier.size(22.dp))
                                    Text("上传SMB", fontSize = 10.sp)
                                }
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
            // 面包屑层级导航栏
            LazyRow(
                state = breadcrumbListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(state.breadcrumbs) { index, crumb ->
                    val isLast = index == state.breadcrumbs.lastIndex
                    FilterChip(
                        selected = isLast,
                        onClick = { viewModel.navigateToBreadcrumb(index) },
                        label = {
                            Text(
                                text = crumb.label,
                                fontSize = 12.sp,
                                maxLines = 1,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (index == 0) {
                            { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.filteredFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("空目录或未搜索到匹配项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    state = fileListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.filteredFiles, key = { it.path }) { item ->
                        val isSelected = state.selectedFilePaths.contains(item.path)
                        ExplorerItemCard(
                            item = item,
                            isSelected = isSelected,
                            isSelectionMode = state.isSelectionMode,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(item.path)
                                } else if (item.isDirectory) {
                                    viewModel.openDirectory(File(item.path))
                                } else if (item.category == FileCategory.IMAGE) {
                                    val images = state.filteredFiles.filter { !it.isDirectory && it.category == FileCategory.IMAGE }
                                    val idx = images.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                                    previewImageList = images
                                    previewImageIndex = idx
                                } else if (item.category == FileCategory.VIDEO || item.category == FileCategory.AUDIO) {
                                    val medias = state.filteredFiles.filter { !it.isDirectory && it.category == item.category }
                                    val isAud = (item.category == FileCategory.AUDIO)
                                    val queue = medias.map { m ->
                                        val mFile = File(m.path)
                                        PlayableMediaItem(
                                            id = m.path,
                                            title = m.name,
                                            fileSizeBytes = m.size,
                                            isAudio = isAud,
                                            directUri = Uri.fromFile(mFile),
                                            mimeType = FileUtils.getMimeType(mFile)
                                        )
                                    }
                                    val idx = queue.indexOfFirst { it.id == item.path }.coerceAtLeast(0)
                                    playingQueue = queue
                                    playingQueueIndex = idx
                                    playingMediaFile = File(item.path)
                                    playingIsAudio = isAud
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
                            },
                            onDeleteClick = {
                                itemToDelete = item
                            }
                        )
                    }
                }
            }
        }
    }

    // 新建文件夹弹窗
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewFolderDialog = false
                newFolderNameInput = ""
            },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderNameInput,
                    onValueChange = { newFolderNameInput = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = newFolderNameInput.trim()
                    if (name.isNotEmpty()) {
                        viewModel.createNewFolder(name) { success, err ->
                            if (!success) {
                                Toast.makeText(context, err ?: "创建文件夹失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    showNewFolderDialog = false
                    newFolderNameInput = ""
                }) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewFolderDialog = false
                    newFolderNameInput = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    // 重命名对话框
    if (renamingItem != null) {
        AlertDialog(
            onDismissRequest = { renamingItem = null },
            title = { Text(if (renamingItem?.isDirectory == true) "重命名文件夹" else "重命名文件") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = renamingItem
                    val name = renameInputText.trim()
                    if (target != null && name.isNotBlank()) {
                        viewModel.renameItem(target.path, name) { success, err ->
                            if (!success) {
                                Toast.makeText(context, err ?: "重命名失败", Toast.LENGTH_SHORT).show()
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

    // 单项删除确认
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要永久删除「${itemToDelete?.name}」吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        itemToDelete?.let { target ->
                            viewModel.deleteSingleFile(File(target.path)) { success ->
                                if (success) {
                                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 多选批量删除确认
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("批量删除") },
            text = { Text("确定要永久删除选中的 ${state.selectedFilePaths.size} 个项目吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelectedFiles { count ->
                            Toast.makeText(context, "已删除 $count 个项目", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除全部")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 全屏画廊图片预览
    if (previewImageList.isNotEmpty()) {
        ImagePreviewDialog(
            initialIndex = previewImageIndex,
            imageList = previewImageList,
            onRenameSuccess = { _, _ ->
                viewModel.refreshCurrentDirectory()
            },
            onDismissRequest = { previewImageList = emptyList() }
        )
    }

    // 高级音视频播放器弹窗
    if (playingMediaFile != null) {
        VideoPlayerDialog(
            videoFile = playingMediaFile,
            title = playingMediaFile?.name ?: "媒体播放",
            isAudio = playingIsAudio,
            mediaQueue = playingQueue,
            initialIndex = playingQueueIndex,
            onRenameMedia = { _, _ ->
                viewModel.refreshCurrentDirectory()
            },
            onDismissRequest = {
                playingMediaFile = null
                playingQueue = emptyList()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerItemCard(
    item: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
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
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
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

            if (item.isDirectory) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFFB300).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "文件夹",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                FileThumbnailView(
                    path = item.path,
                    category = item.category,
                    extension = item.extension,
                    size = 46.dp,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.isDirectory) "${item.size} 项" else FileUtils.formatFileSize(item.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ${FileUtils.formatDate(item.lastModified)}",
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
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
