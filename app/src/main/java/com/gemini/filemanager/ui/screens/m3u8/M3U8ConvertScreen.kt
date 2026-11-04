package com.gemini.filemanager.ui.screens.m3u8

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.gemini.filemanager.data.model.M3U8Item
import com.gemini.filemanager.data.model.M3U8Status
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3U8ConvertScreen(
    onBack: () -> Unit,
    onSendFile: (File) -> Unit,
    viewModel: M3U8ViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewTitle by remember { mutableStateOf("") }

    var editingKeyItem by remember { mutableStateOf<M3U8Item?>(null) }
    var inputKeyText by remember { mutableStateOf("") }

    var renamingTaskItem by remember { mutableStateOf<M3U8Item?>(null) }
    var renameTaskInputText by remember { mutableStateOf("") }
    var showM3U8SortMenu by remember { mutableStateOf(false) }

    // 手动选文件选择器
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val path = FileUtils.getPathFromUri(context, uri)
            if (!path.isNullOrBlank()) {
                viewModel.addManualM3U8(File(path))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("M3U8批量转MP4")
                        if (state.items.isNotEmpty()) {
                            Text(
                                text = "共发现 ${state.items.size} 部视频" + if (state.isScanning) " (仍在嗅探中...)" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { filePicker.launch("*/*") }) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "手动选文件")
                    }
                    IconButton(onClick = { viewModel.scanTasks() }, enabled = !state.isScanning) {
                        if (state.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                val uncompletedCount = state.items.count { it.status != M3U8Status.COMPLETED }
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 勾选是否删除源文件
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setDeleteSourceAfterConvert(!state.deleteSourceAfterConvert) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.deleteSourceAfterConvert,
                                onCheckedChange = { viewModel.setDeleteSourceAfterConvert(it) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "转码成功后自动删除源文件 (清理 TS 切片及 M3U8)",
                                fontSize = 12.sp,
                                fontWeight = if (state.deleteSourceAfterConvert) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (state.deleteSourceAfterConvert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleSelectAll() }
                            ) {
                                Checkbox(
                                    checked = state.selectedIds.size == uncompletedCount && uncompletedCount > 0,
                                    onCheckedChange = { viewModel.toggleSelectAll() }
                                )
                                Text("全选待转 (${state.selectedIds.size}/$uncompletedCount)")
                            }

                            if (state.isBatchConverting) {
                                val convertingItem = state.items.firstOrNull { it.status == M3U8Status.CONVERTING }
                                val currentPercent = if (convertingItem != null) (convertingItem.progress * 100).toInt() else 0
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "后台转码中 $currentPercent%",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.cancelConvert() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("停止", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.convertSelected() },
                                    enabled = state.selectedIds.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("一键批量转码 (${state.selectedIds.size})")
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
            // 扫描进度指示条（非阻塞，只在顶部轻巧显示）
            if (state.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 提示卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "已全面支持 QQ 浏览器 / 夸克离线 M3U8 深度去重；支持常驻后台转码，退出界面或锁屏仍将继续执行，并在通知栏实时显示进度与速率。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            // 排序与统计栏
            if (state.items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${state.items.size} 部视频",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box {
                        TextButton(
                            onClick = { showM3U8SortMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = "排序", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = state.sortOption.displayName, fontSize = 12.sp)
                        }

                        DropdownMenu(
                            expanded = showM3U8SortMenu,
                            onDismissRequest = { showM3U8SortMenu = false }
                        ) {
                            M3U8SortOption.entries.forEach { option ->
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
                                        showM3U8SortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (state.items.isEmpty()) {
                if (state.isScanning) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在快速定位 QQ 浏览器与下载缓存...")
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VideoFile,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("未发现离线 M3U8 缓存视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { filePicker.launch("*/*") }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("手动选择文件")
                                }
                                Button(onClick = { viewModel.scanTasks() }) {
                                    Text("再次嗅探")
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        val isSelected = state.selectedIds.contains(item.id)
                        M3U8TaskCard(
                            item = item,
                            isSelected = isSelected,
                            onToggleSelect = { viewModel.toggleSelection(item.id) },
                            onPreview = {
                                viewModel.preparePreviewFile(item) { file ->
                                    previewTitle = item.title
                                    previewFile = file
                                }
                            },
                            onEditKey = {
                                editingKeyItem = item
                                inputKeyText = item.customKeyHex ?: ""
                            },
                            onRenameTitle = {
                                renamingTaskItem = item
                                renameTaskInputText = item.title
                            },
                            onSend = {
                                if (item.outputPath != null) {
                                    onSendFile(File(item.outputPath))
                                }
                            },
                            onDeleteSource = {
                                viewModel.deleteSourceFiles(item) {
                                    android.widget.Toast.makeText(context, "已清理源切片，释放存储空间", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 预览弹窗
    if (previewFile != null) {
        VideoPlayerDialog(
            videoFile = previewFile!!,
            title = previewTitle,
            onDismissRequest = { previewFile = null }
        )
    }

    // 影片重命名弹窗
    if (renamingTaskItem != null) {
        AlertDialog(
            onDismissRequest = { renamingTaskItem = null },
            title = { Text("自定义影片导出名称") },
            text = {
                OutlinedTextField(
                    value = renameTaskInputText,
                    onValueChange = { renameTaskInputText = it },
                    label = { Text("影片名称（将作为 MP4 文件名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renamingTaskItem?.let { item ->
                            viewModel.renameTaskTitle(item.id, renameTaskInputText)
                        }
                        renamingTaskItem = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTaskItem = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 自定义密钥输入弹窗
    if (editingKeyItem != null) {
        AlertDialog(
            onDismissRequest = { editingKeyItem = null },
            title = { Text("配置 AES-128 解密 Key") },
            text = {
                Column {
                    Text(
                        text = "若切片需要特定密钥，可输入 32 位 Hex 密钥串（16 字节）：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputKeyText,
                        onValueChange = { inputKeyText = it },
                        placeholder = { Text("例如：a1b2c3d4e5f6...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingKeyItem?.let { item ->
                            viewModel.setCustomKey(item.id, inputKeyText)
                        }
                        editingKeyItem = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingKeyItem = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun M3U8TaskCard(
    item: M3U8Item,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onEditKey: () -> Unit,
    onRenameTitle: () -> Unit,
    onSend: () -> Unit,
    onDeleteSource: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.status == M3U8Status.COMPLETED)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.status != M3U8Status.COMPLETED) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "修改影片名",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRenameTitle() }
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.parentDir,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 状态徽标
                StatusBadge(status = item.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 属性标签与操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "切片: ${item.segmentCount}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = FileUtils.formatFileSize(item.totalSize),
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
                    if (item.isEncrypted || item.customKeyHex != null) {
                        Text(
                            text = "🔒 加密",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { onEditKey() }
                        )
                    }
                }

                // 预览与 Key 配置入口
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onPreview,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("预览", fontSize = 12.sp)
                    }

                    if (item.isEncrypted) {
                        TextButton(
                            onClick = onEditKey,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("密钥", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 实时进度与状态展示区（转换中）
            if (item.status == M3U8Status.CONVERTING) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "转码合并中 ${(item.progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (item.speed.isNotBlank()) {
                        Text(
                            text = item.speed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // 操作栏（完成后提供一键播放和一键LocalSend传出）
            if (item.status == M3U8Status.COMPLETED && item.outputPath != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.segmentCount > 0 || item.m3u8Path.isNotBlank()) {
                        TextButton(
                            onClick = onDeleteSource,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清理源切片", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text(
                            text = "✓ 源切片已清理",
                            fontSize = 11.sp,
                            color = Color(0xFF00C853),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    Row {
                        OutlinedButton(
                            onClick = onSend,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("快传", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = onPreview,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("播放 MP4", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: M3U8Status) {
    val (text, color) = when (status) {
        M3U8Status.PENDING -> "待转换" to MaterialTheme.colorScheme.outline
        M3U8Status.SCANNING -> "准备中" to MaterialTheme.colorScheme.secondary
        M3U8Status.CONVERTING -> "转码中" to MaterialTheme.colorScheme.primary
        M3U8Status.COMPLETED -> "已合成MP4" to Color(0xFF00C853)
        M3U8Status.FAILED -> "失败" to MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
