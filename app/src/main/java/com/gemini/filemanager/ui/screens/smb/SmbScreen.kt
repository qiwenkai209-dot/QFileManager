package com.gemini.filemanager.ui.screens.smb

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import com.gemini.filemanager.data.model.SmbCategoryFilter
import com.gemini.filemanager.data.model.SmbClipboardAction
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbDiscoveredHost
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbSortOption
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import java.io.File

private data class PreviewGalleryState(
    val initialFile: File,
    val images: List<SmbRemoteFile>,
    val initialIndex: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbScreen(
    initialUploadFiles: List<File> = emptyList(),
    onBack: () -> Unit,
    viewModel: SmbViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val transferTasks by viewModel.transferTasks.collectAsState()

    var editingConfig by remember { mutableStateOf<SmbConnectionConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillHost by remember { mutableStateOf("") }
    var prefillName by remember { mutableStateOf("") }
    var prefillPort by remember { mutableIntStateOf(445) }

    var showTransferDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showCustomShareDialog by remember { mutableStateOf(false) }
    var renamingItem by remember { mutableStateOf<SmbRemoteFile?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var streamingMediaUrl by remember { mutableStateOf<String?>(null) }
    var streamingMediaItem by remember { mutableStateOf<SmbRemoteFile?>(null) }
    var previewGalleryState by remember { mutableStateOf<PreviewGalleryState?>(null) }

    LaunchedEffect(initialUploadFiles) {
        if (initialUploadFiles.isNotEmpty()) {
            viewModel.setPendingUploadFiles(initialUploadFiles)
        }
    }

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            showTransferDialog = true
            viewModel.uploadUris(uris) { count ->
                Toast.makeText(context, "已加入 $count 个上传任务（支持断点续传）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = state.activeConfig != null || state.isSelectionMode) {
        if (!viewModel.navigateUp()) {
            onBack()
        }
    }

    val activeTransferCount = transferTasks.count { it.isActive }
    val resumableCount = transferTasks.count { it.canResume }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        state.isSelectionMode -> {
                            Text("已选择 ${state.selectedUrls.size} 项")
                        }
                        state.activeConfig != null -> {
                            Column {
                                Text(
                                    text = state.activeConfig!!.effectiveLabel(),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = state.currentUrl,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        else -> {
                            Column {
                                Text(
                                    text = "SMB 局域网共享",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "SMB1/2/3 · 断点续传 · 局域网探测 · 在线播片",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.navigateUp()) {
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
                    if (transferTasks.isNotEmpty()) {
                        IconButton(onClick = { showTransferDialog = true }) {
                            BadgedBox(
                                badge = {
                                    val badgeCount = if (activeTransferCount > 0) activeTransferCount else resumableCount
                                    if (badgeCount > 0) {
                                        Badge { Text(badgeCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = "传输队列")
                            }
                        }
                    }

                    if (state.activeConfig == null) {
                        IconButton(onClick = { viewModel.scanLanHosts() }) {
                            Icon(Icons.Default.Radar, contentDescription = "扫描局域网设备")
                        }
                        IconButton(onClick = {
                            editingConfig = null
                            prefillHost = ""
                            prefillName = ""
                            prefillPort = 445
                            showAddDialog = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "添加服务器")
                        }
                    } else if (state.isSelectionMode) {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                    } else {
                        if (!state.isAtServerRoot) {
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                            }
                            IconButton(onClick = { uploadPickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.CloudUpload, contentDescription = "上传文件")
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
            if (state.isSelectionMode && state.selectedUrls.isNotEmpty()) {
                Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionBottomItem(
                            icon = Icons.Default.CloudDownload,
                            label = "下载",
                            tint = MaterialTheme.colorScheme.primary
                        ) {
                            showTransferDialog = true
                            viewModel.downloadSelected { count, err ->
                                val msg = err ?: "已加入 $count 个下载任务（支持断点续传）"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        ActionBottomItem(
                            icon = Icons.Default.ContentCopy,
                            label = "复制"
                        ) {
                            viewModel.copySelected()
                            Toast.makeText(context, "已复制，请进入目标目录粘贴", Toast.LENGTH_SHORT).show()
                        }
                        ActionBottomItem(
                            icon = Icons.Default.ContentCut,
                            label = "剪切"
                        ) {
                            viewModel.cutSelected()
                            Toast.makeText(context, "已剪切，请进入目标目录粘贴", Toast.LENGTH_SHORT).show()
                        }
                        ActionBottomItem(
                            icon = Icons.Default.Delete,
                            label = "删除",
                            tint = MaterialTheme.colorScheme.error
                        ) {
                            showDeleteConfirmDialog = true
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
            // 正在传输或可断点续传的迷你快捷条
            val runningTask = transferTasks.lastOrNull { it.status == SmbTransferStatus.TRANSFERRING }
            if (runningTask != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTransferDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${runningTask.fileName} · ${(runningTask.progress * 100).toInt()}% ${runningTask.speed}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.pauseTransfer(runningTask.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("暂停", fontSize = 12.sp)
                        }
                    }
                }
            } else if (resumableCount > 0) {
                Surface(
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTransferDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PauseCircleOutline,
                            contentDescription = null,
                            tint = Color(0xFFEF6C00),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "有 $resumableCount 个断点续传任务未完成",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF5D4037),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                showTransferDialog = true
                                viewModel.resumeAllTransfers()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("全部续传", fontSize = 12.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 待上传本机文件提醒条
            if (state.pendingUploadFiles.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "待上传本机文件 (${state.pendingUploadFiles.size} 个)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when {
                                    state.activeConfig == null -> "请连接 SMB 服务器并进入目标共享目录"
                                    state.isAtServerRoot -> "请先选择一个共享文件夹，再点击上传"
                                    else -> "可一键上传到当前 SMB 目录（支持断点续传）"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            TextButton(onClick = { viewModel.clearPendingUploadFiles() }) {
                                Text("取消", fontSize = 12.sp)
                            }
                            if (state.activeConfig != null && !state.isAtServerRoot) {
                                Button(
                                    onClick = {
                                        showTransferDialog = true
                                        viewModel.uploadPendingFiles { count ->
                                            Toast.makeText(context, "已加入 $count 个上传任务", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("上传到此", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (state.activeConfig == null) {
                SmbConnectionHubContent(
                    state = state,
                    onAddClick = {
                        editingConfig = null
                        prefillHost = ""
                        prefillName = ""
                        prefillPort = 445
                        showAddDialog = true
                    },
                    onConnectClick = { viewModel.connect(it) },
                    onEditClick = {
                        editingConfig = it
                        showAddDialog = true
                    },
                    onDeleteClick = { viewModel.deleteConnection(it.id) },
                    onDiscoveredHostClick = { host ->
                        val existingSaved = state.savedConnections.find {
                            it.host.equals(host.ip, ignoreCase = true)
                        }
                        if (existingSaved != null) {
                            viewModel.connect(existingSaved)
                        } else {
                            editingConfig = null
                            prefillHost = host.ip
                            prefillName = host.hostName
                            prefillPort = host.port
                            showAddDialog = true
                        }
                    }
                )
            } else {
                SmbRemoteBrowserContent(
                    state = state,
                    showSortMenu = showSortMenu,
                    onToggleSortMenu = { showSortMenu = it },
                    onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
                    onSearchChange = { viewModel.onSearchQueryChange(it) },
                    onSortSelect = { viewModel.setSortOption(it) },
                    onCategoryFilterSelect = { viewModel.setCategoryFilter(it) },
                    onRetry = { viewModel.refreshCurrentDirectory() },
                    onSpecifyShare = { showCustomShareDialog = true },
                    onEditConnection = {
                        editingConfig = state.activeConfig
                        showAddDialog = true
                    },
                    onClearClipboard = { viewModel.clearClipboard() },
                    onPasteClipboard = {
                        viewModel.pasteClipboard { ok, msg ->
                            Toast.makeText(context, msg ?: if (ok) "粘贴完成" else "粘贴失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onItemClick = { item ->
                        if (state.isSelectionMode) {
                            viewModel.toggleSelection(item.smbUrl)
                        } else if (item.isDirectory) {
                            viewModel.openDirectory(item)
                        } else {
                            when (item.category) {
                                FileCategory.VIDEO, FileCategory.AUDIO -> {
                                    viewModel.prepareStreamUrl(
                                        item = item,
                                        onReady = { url ->
                                            streamingMediaItem = item
                                            streamingMediaUrl = url
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                                FileCategory.IMAGE -> {
                                    val images = viewModel.getCurrentFolderImages()
                                    val idx = images.indexOfFirst { it.smbUrl == item.smbUrl }.coerceAtLeast(0)
                                    viewModel.downloadForPreview(
                                        item = item,
                                        onReady = { file ->
                                            previewGalleryState = PreviewGalleryState(
                                                initialFile = file,
                                                images = if (images.isEmpty()) listOf(item) else images,
                                                initialIndex = idx
                                            )
                                        },
                                        onError = { err -> Toast.makeText(context, err, Toast.LENGTH_LONG).show() }
                                    )
                                }
                                else -> {
                                    viewModel.downloadForPreview(
                                        item = item,
                                        onReady = { file -> FileUtils.openFile(context, file) },
                                        onError = { err -> Toast.makeText(context, err, Toast.LENGTH_LONG).show() }
                                    )
                                }
                            }
                        }
                    },
                    onItemLongClick = { item ->
                        if (!item.isShareRoot && !state.isSelectionMode) {
                            viewModel.toggleSelection(item.smbUrl)
                        }
                    },
                    onDownloadItem = { item ->
                        showTransferDialog = true
                        viewModel.downloadItems(listOf(item)) { count, err ->
                            val msg = err ?: "已加入 $count 个下载任务（支持断点续传）"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRenameItem = { renamingItem = it },
                    onCopySingleItem = { item ->
                        viewModel.clearSelection()
                        viewModel.toggleSelection(item.smbUrl)
                        viewModel.copySelected()
                        Toast.makeText(context, "已复制，请进入目标目录粘贴", Toast.LENGTH_SHORT).show()
                    },
                    onCutSingleItem = { item ->
                        viewModel.clearSelection()
                        viewModel.toggleSelection(item.smbUrl)
                        viewModel.cutSelected()
                        Toast.makeText(context, "已剪切，请进入目标目录粘贴", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSingleItem = { item ->
                        viewModel.clearSelection()
                        viewModel.toggleSelection(item.smbUrl)
                        showDeleteConfirmDialog = true
                    }
                )
            }
        }
    }

    // 预览缓存加载遮罩弹窗
    if (state.isPreparingPreview) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在加载远程文件...", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { state.previewProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "进度：${(state.previewProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }

    // 添加 / 编辑连接弹窗
    if (showAddDialog) {
        SmbConnectionEditorDialog(
            initialConfig = editingConfig,
            prefillHost = prefillHost,
            prefillName = prefillName,
            prefillPort = prefillPort,
            onTestConnection = { cfg, cb -> viewModel.testConnection(cfg, cb) },
            onDismiss = { showAddDialog = false },
            onSave = { config, connectNow ->
                showAddDialog = false
                viewModel.saveConnection(config, connectImmediately = connectNow)
            }
        )
    }

    // 传输队列弹窗（支持断点续传、暂停、取消、清理）
    if (showTransferDialog) {
        SmbTransferListDialog(
            tasks = transferTasks,
            downloadDirPath = viewModel.defaultDownloadDir.absolutePath,
            onPauseTask = { viewModel.pauseTransfer(it) },
            onResumeTask = { id ->
                if (!viewModel.resumeTransfer(id)) {
                    Toast.makeText(context, "无法恢复任务：连接配置不存在", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelTask = { viewModel.removeTransfer(it) },
            onPauseAll = { viewModel.pauseAllTransfers() },
            onResumeAll = { viewModel.resumeAllTransfers() },
            onClearFinished = { viewModel.clearFinishedTransfers() },
            onDismiss = { showTransferDialog = false }
        )
    }

    // 新建文件夹弹窗
    if (showNewFolderDialog) {
        SimpleInputDialog(
            title = "新建远程文件夹",
            label = "文件夹名称",
            confirmText = "创建",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                viewModel.createFolder(name) { ok, err ->
                    Toast.makeText(context, if (ok) "创建成功" else (err ?: "创建失败"), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 直接指定共享目录名弹窗（应对禁止枚举共享的 Windows/NAS）
    if (showCustomShareDialog) {
        SimpleInputDialog(
            title = "直接进入共享目录",
            label = "共享目录名称",
            placeholder = "例如 Movies / Public / D",
            confirmText = "进入",
            onDismiss = { showCustomShareDialog = false },
            onConfirm = { share ->
                showCustomShareDialog = false
                viewModel.openCustomShare(share)
            }
        )
    }

    // 重命名弹窗
    if (renamingItem != null) {
        val target = renamingItem!!
        SimpleInputDialog(
            title = "重命名",
            label = "新名称",
            initialValue = target.name,
            confirmText = "确定",
            onDismiss = { renamingItem = null },
            onConfirm = { newName ->
                renamingItem = null
                viewModel.renameItem(target, newName) { ok, err ->
                    Toast.makeText(context, if (ok) "重命名成功" else (err ?: "重命名失败"), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 确认删除弹窗
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("确认删除远程文件") },
            text = { Text("确定要从 SMB 服务器永久删除选中的 ${state.selectedUrls.size} 项吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    viewModel.deleteSelected { ok, msg ->
                        Toast.makeText(context, msg ?: if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }) {
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

    // 在线流媒体音视频播放器
    if (streamingMediaUrl != null && streamingMediaItem != null) {
        val item = streamingMediaItem!!
        val url = streamingMediaUrl!!
        val isAudio = item.category == FileCategory.AUDIO
        VideoPlayerDialog(
            videoUri = Uri.parse(url),
            title = item.name,
            fileSizeBytes = item.size,
            isAudio = isAudio,
            onExternalOpen = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), if (isAudio) "audio/*" else "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "选择外部播放器"))
                } catch (_: Exception) {
                    Toast.makeText(context, "未找到可用的外部播放器，可先下载到本机", Toast.LENGTH_SHORT).show()
                }
            },
            onDismissRequest = {
                streamingMediaUrl = null
                streamingMediaItem = null
            }
        )
    }

    // 远程图片多图连续预览
    if (previewGalleryState != null) {
        val gallery = previewGalleryState!!
        SmbImagePreviewDialog(
            initialFile = gallery.initialFile,
            imageItems = gallery.images,
            initialIndex = gallery.initialIndex,
            onLoadImage = { imgItem, onLoaded, onErr ->
                viewModel.loadPreviewImageSilent(imgItem, onLoaded, onErr)
            },
            onDownloadToLocal = { imgItem ->
                viewModel.downloadItems(listOf(imgItem)) { _, _ -> }
            },
            onDismiss = { previewGalleryState = null }
        )
    }
}

@Composable
private fun SmbConnectionHubContent(
    state: SmbUiState,
    onAddClick: () -> Unit,
    onConnectClick: (SmbConnectionConfig) -> Unit,
    onEditClick: (SmbConnectionConfig) -> Unit,
    onDeleteClick: (SmbConnectionConfig) -> Unit,
    onDiscoveredHostClick: (SmbDiscoveredHost) -> Unit
) {
    val savedHostsSet = remember(state.savedConnections) {
        state.savedConnections.map { it.host.lowercase() }.toSet()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SMB 协议客户端已就绪",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "本机局域网 IP：${state.localIp}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onAddClick,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Text(
                text = "已保存的共享主机 (${state.savedConnections.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (state.savedConnections.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderShared,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("还没有保存的 SMB 连接", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击右上角 + 手动输入电脑 / NAS 地址，或直接点击下方探测到的局域网设备。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(state.savedConnections, key = { it.id }) { config ->
                SavedSmbConfigCard(
                    config = config,
                    onClick = { onConnectClick(config) },
                    onEdit = { onEditClick(config) },
                    onDelete = { onDeleteClick(config) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "局域网 SMB 设备探测 (${state.discoveredHosts.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (state.isDiscovering) {
                        Text(
                            text = "正在扫描 445 端口与 mDNS 服务... ${(state.discoveryProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.isDiscovering) {
            item {
                LinearProgressIndicator(
                    progress = { state.discoveryProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }

        if (state.discoveredHosts.isEmpty() && !state.isDiscovering) {
            item {
                Text(
                    text = "暂未扫描到开启 445 端口的局域网设备（部分路由器可能隔离客户端或防火墙未放行），可点击右上角 + 手动输入 IP 连接。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.discoveredHosts, key = { it.ip }) { host ->
                DiscoveredSmbHostCard(
                    host = host,
                    isAlreadySaved = savedHostsSet.contains(host.ip.lowercase()),
                    onClick = { onDiscoveredHostClick(host) }
                )
            }
        }
    }
}

@Composable
private fun SavedSmbConfigCard(
    config: SmbConnectionConfig,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3F51B5).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = Color(0xFF3F51B5)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.effectiveLabel(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val pathSuffix = if (config.shareName.isNotBlank()) "/${config.shareName}" else ""
                val authLabel = if (config.isGuest) "匿名访客" else config.username.ifBlank { "无用户名" }
                Text(
                    text = "${config.host}:${config.port}$pathSuffix · $authLabel",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DiscoveredSmbHostCard(
    host: SmbDiscoveredHost,
    isAlreadySaved: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00897B).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = Color(0xFF00897B))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.hostName.ifBlank { host.ip },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${host.ip}:${host.port} · ${host.source}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(if (isAlreadySaved) "直连" else "添加", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SmbRemoteBrowserContent(
    state: SmbUiState,
    showSortMenu: Boolean,
    onToggleSortMenu: (Boolean) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortSelect: (SmbSortOption) -> Unit,
    onCategoryFilterSelect: (SmbCategoryFilter) -> Unit,
    onRetry: () -> Unit,
    onSpecifyShare: () -> Unit,
    onEditConnection: () -> Unit,
    onClearClipboard: () -> Unit,
    onPasteClipboard: () -> Unit,
    onItemClick: (SmbRemoteFile) -> Unit,
    onItemLongClick: (SmbRemoteFile) -> Unit,
    onDownloadItem: (SmbRemoteFile) -> Unit,
    onRenameItem: (SmbRemoteFile) -> Unit,
    onCopySingleItem: (SmbRemoteFile) -> Unit,
    onCutSingleItem: (SmbRemoteFile) -> Unit,
    onDeleteSingleItem: (SmbRemoteFile) -> Unit
) {
    val breadcrumbListState = rememberLazyListState()
    LaunchedEffect(state.breadcrumbs.size) {
        if (state.breadcrumbs.isNotEmpty()) {
            breadcrumbListState.animateScrollToItem(state.breadcrumbs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 面包屑导航栏
        LazyRow(
            state = breadcrumbListState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(state.breadcrumbs) { index, crumb ->
                val isLast = index == state.breadcrumbs.lastIndex
                FilterChip(
                    selected = isLast,
                    onClick = { onBreadcrumbClick(index) },
                    label = {
                        Text(
                            text = crumb.label,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                if (!isLast) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 远程剪贴板粘贴提醒栏
        val clipboard = state.clipboard
        if (clipboard != null && clipboard.configId == state.activeConfig?.id && !state.isAtServerRoot) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已${if (clipboard.action == SmbClipboardAction.CUT) "剪切" else "复制"} ${clipboard.items.size} 项",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row {
                        TextButton(onClick = onClearClipboard) {
                            Text("取消", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onPasteClipboard,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("粘贴到此", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 搜索框
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("搜索当前目录文件...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除搜索", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // 分类快捷筛选（仅在共享目录内部显示）
        if (!state.isAtServerRoot && state.files.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SmbCategoryFilter.entries) { filter ->
                    FilterChip(
                        selected = state.categoryFilter == filter,
                        onClick = { onCategoryFilterSelect(filter) },
                        label = { Text(filter.displayName, fontSize = 12.sp) }
                    )
                }
            }
        }

        // 统计、排序与快捷共享名入口
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isAtServerRoot) {
                    TextButton(
                        onClick = onSpecifyShare,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("输入共享名", fontSize = 12.sp)
                    }
                }
                Box {
                    TextButton(
                        onClick = { onToggleSortMenu(true) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(state.sortOption.displayName, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { onToggleSortMenu(false) }
                    ) {
                        SmbSortOption.entries.forEach { option ->
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
                                            fontWeight = if (option == state.sortOption) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = {
                                    onSortSelect(option)
                                    onToggleSortMenu(false)
                                }
                            )
                        }
                    }
                }
            }
        }

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("正在读取 SMB 共享目录...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "连接或读取失败",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.errorMessage,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onEditConnection) {
                                    Text("修改配置")
                                }
                                if (state.isAtServerRoot) {
                                    OutlinedButton(onClick = onSpecifyShare) {
                                        Text("填共享名")
                                    }
                                }
                                Button(onClick = onRetry) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                }
            }
            state.filteredFiles.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.isAtServerRoot) "未发现可见的共享目录" else "当前筛选条件下无文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.isAtServerRoot) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(onClick = onSpecifyShare) {
                                Text("手动输入共享目录名进入")
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.filteredFiles, key = { it.smbUrl }) { item ->
                        SmbFileListItemCard(
                            item = item,
                            isSelected = state.selectedUrls.contains(item.smbUrl),
                            isSelectionMode = state.isSelectionMode,
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongClick(item) },
                            onDownload = { onDownloadItem(item) },
                            onRename = { onRenameItem(item) },
                            onCopy = { onCopySingleItem(item) },
                            onCut = { onCutSingleItem(item) },
                            onDelete = { onDeleteSingleItem(item) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmbFileListItemCard(
    item: SmbRemoteFile,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

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
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode && !item.isShareRoot) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            val (icon, tint) = when {
                item.isShareRoot -> Icons.Default.FolderShared to Color(0xFF3F51B5)
                item.isDirectory -> Icons.Default.Folder to Color(0xFFFFA000)
                item.category == FileCategory.IMAGE -> Icons.Default.Image to Color(0xFF4CAF50)
                item.category == FileCategory.AUDIO -> Icons.Default.Audiotrack to Color(0xFFE91E63)
                item.category == FileCategory.VIDEO -> Icons.Default.Movie to Color(0xFF9C27B0)
                item.category == FileCategory.APK -> Icons.Default.Android to Color(0xFF009688)
                item.category == FileCategory.ARCHIVE -> Icons.Default.Archive to Color(0xFFFF9800)
                else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (item.isShareRoot) {
                    Text(
                        text = "共享根目录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (item.isDirectory) "文件夹" else FileUtils.formatFileSize(item.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.lastModified > 0L) {
                            Text(
                                text = FileUtils.formatDate(item.lastModified),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!isSelectionMode && !item.isShareRoot) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("下载到本机") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onDownload()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onCopy()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("剪切") },
                            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onCut()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBottomItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            Text(text = label, fontSize = 10.sp, color = tint)
        }
    }
}
