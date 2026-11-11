package com.gemini.filemanager.ui.screens.smb

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.SmbConnectionConfig
import com.gemini.filemanager.data.model.SmbProtocolMode
import com.gemini.filemanager.data.model.SmbRemoteFile
import com.gemini.filemanager.data.model.SmbTransferDirection
import com.gemini.filemanager.data.model.SmbTransferStatus
import com.gemini.filemanager.data.model.SmbTransferTask
import java.io.File
import java.util.UUID

@Composable
fun SmbConnectionEditorDialog(
    initialConfig: SmbConnectionConfig?,
    prefillHost: String = "",
    prefillName: String = "",
    prefillPort: Int = 445,
    onTestConnection: (SmbConnectionConfig, (Boolean, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SmbConnectionConfig, Boolean) -> Unit
) {
    var displayName by remember { mutableStateOf(initialConfig?.displayName ?: prefillName) }
    var host by remember { mutableStateOf(initialConfig?.host ?: prefillHost) }
    var portText by remember { mutableStateOf((initialConfig?.port ?: prefillPort).toString()) }
    var shareName by remember { mutableStateOf(initialConfig?.shareName ?: "") }
    var domain by remember { mutableStateOf(initialConfig?.domain ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var isGuest by remember { mutableStateOf(initialConfig?.isGuest ?: false) }
    var showHiddenShares by remember { mutableStateOf(initialConfig?.showHiddenShares ?: false) }
    var protocolMode by remember { mutableStateOf(initialConfig?.protocolMode ?: SmbProtocolMode.AUTO) }
    var showPassword by remember { mutableStateOf(false) }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialConfig == null) "添加 SMB 服务器" else "编辑 SMB 连接",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        testResult = null
                    },
                    label = { Text("服务器 IP 或主机名 *") },
                    placeholder = { Text("例如 192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("备注名 (可选)") },
                        placeholder = { Text("家中 NAS / 电脑") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter { ch -> ch.isDigit() }
                            testResult = null
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f)
                    )
                }

                OutlinedTextField(
                    value = shareName,
                    onValueChange = {
                        shareName = it
                        testResult = null
                    },
                    label = { Text("指定共享目录 (留空自动枚举)") },
                    placeholder = { Text("如 Public / Movies") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("匿名 / 访客登录", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "无需账号密码时开启",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isGuest,
                        onCheckedChange = {
                            isGuest = it
                            testResult = null
                        }
                    )
                }

                if (!isGuest) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            testResult = null
                        },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            testResult = null
                        },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "显示或隐藏密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = domain,
                        onValueChange = {
                            domain = it
                            testResult = null
                        },
                        label = { Text("域 / 工作组 (通常留空)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "SMB 协议版本",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmbProtocolMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    protocolMode = mode
                                    testResult = null
                                }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = protocolMode == mode,
                                onClick = {
                                    protocolMode = mode
                                    testResult = null
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(mode.displayName, fontSize = 13.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示隐藏共享 ($)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "如 C$、D$ 等系统管理共享",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showHiddenShares, onCheckedChange = { showHiddenShares = it })
                }

                // 快速测试连接按钮与状态反馈
                OutlinedButton(
                    enabled = host.isNotBlank() && !isTesting,
                    onClick = {
                        isTesting = true
                        testResult = null
                        val probeConfig = buildConfig(
                            initialConfig,
                            displayName,
                            host,
                            portText,
                            shareName,
                            domain,
                            username,
                            password,
                            isGuest,
                            protocolMode,
                            showHiddenShares
                        )
                        onTestConnection(probeConfig) { ok, msg ->
                            isTesting = false
                            testResult = ok to msg
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在探测连接...")
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试连接")
                    }
                }

                if (testResult != null) {
                    val (ok, msg) = testResult!!
                    Surface(
                        color = if (ok) Color(0xFF00897B).copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = if (ok) Color(0xFF00695C) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    enabled = host.isNotBlank(),
                    onClick = {
                        val cfg = buildConfig(
                            initialConfig,
                            displayName,
                            host,
                            portText,
                            shareName,
                            domain,
                            username,
                            password,
                            isGuest,
                            protocolMode,
                            showHiddenShares
                        )
                        onSave(cfg, false)
                    }
                ) {
                    Text("仅保存")
                }
                Button(
                    enabled = host.isNotBlank(),
                    onClick = {
                        val cfg = buildConfig(
                            initialConfig,
                            displayName,
                            host,
                            portText,
                            shareName,
                            domain,
                            username,
                            password,
                            isGuest,
                            protocolMode,
                            showHiddenShares
                        )
                        onSave(cfg, true)
                    }
                ) {
                    Text("保存并连接")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun buildConfig(
    existing: SmbConnectionConfig?,
    displayName: String,
    host: String,
    portText: String,
    shareName: String,
    domain: String,
    username: String,
    password: String,
    isGuest: Boolean,
    protocolMode: SmbProtocolMode,
    showHiddenShares: Boolean
): SmbConnectionConfig {
    val parsedPort = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 445
    return SmbConnectionConfig(
        id = existing?.id ?: UUID.randomUUID().toString(),
        displayName = displayName.trim(),
        host = host.trim().removePrefix("smb://").removePrefix("\\\\").trim('/', '\\'),
        port = parsedPort,
        shareName = shareName.trim().trim('/', '\\'),
        domain = domain.trim(),
        username = username.trim(),
        password = password,
        isGuest = isGuest,
        protocolMode = protocolMode,
        showHiddenShares = showHiddenShares,
        lastConnectedAt = existing?.lastConnectedAt ?: System.currentTimeMillis()
    )
}

@Composable
fun SmbTransferListDialog(
    tasks: List<SmbTransferTask>,
    downloadDirPath: String,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasActive = tasks.any { it.isActive }
    val hasResumable = tasks.any { it.canResume }
    val hasFinished = tasks.any {
        it.status == SmbTransferStatus.COMPLETED || it.status == SmbTransferStatus.CANCELLED
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("SMB 传输队列 (${tasks.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "支持断点续传 · 本地保存至 $downloadDirPath",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasActive || hasResumable || hasFinished) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasActive) {
                            OutlinedButton(
                                onClick = onPauseAll,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("全部暂停", fontSize = 11.sp)
                            }
                        }
                        if (hasResumable) {
                            Button(
                                onClick = onResumeAll,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("全部续传", fontSize = 11.sp)
                            }
                        }
                        if (hasFinished) {
                            TextButton(
                                onClick = onClearFinished,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("清理已完成", fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (tasks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无传输任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tasks.reversed(), key = { it.id }) { task ->
                            SmbTransferTaskCard(
                                task = task,
                                onPause = { onPauseTask(task.id) },
                                onResume = { onResumeTask(task.id) },
                                onCancel = { onCancelTask(task.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun SmbTransferTaskCard(
    task: SmbTransferTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (task.direction == SmbTransferDirection.UPLOAD)
                        Icons.Default.ArrowUpward
                    else
                        Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = if (task.direction == SmbTransferDirection.UPLOAD)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFF00897B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val pct = (task.progress * 100).toInt()
                    val statusText = when (task.status) {
                        SmbTransferStatus.WAITING -> "排队中..."
                        SmbTransferStatus.TRANSFERRING -> if (task.speed.isNotBlank()) "$pct% · ${task.speed}" else "$pct%"
                        SmbTransferStatus.PAUSED -> "已暂停 ($pct% · 可断点续传)"
                        SmbTransferStatus.COMPLETED -> "已完成"
                        SmbTransferStatus.FAILED -> "传输中断 ($pct% · 可续传)"
                        SmbTransferStatus.CANCELLED -> "已取消"
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (task.status) {
                            SmbTransferStatus.COMPLETED -> Color(0xFF00897B)
                            SmbTransferStatus.PAUSED -> Color(0xFFEF6C00)
                            SmbTransferStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }

                when {
                    task.isActive -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消并清理",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    task.canResume -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "断点续传",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消并清理",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    task.status == SmbTransferStatus.COMPLETED -> {
                        if (task.direction == SmbTransferDirection.DOWNLOAD) {
                            val localFile = remember(task.localPath) { File(task.localPath) }
                            if (localFile.exists()) {
                                IconButton(
                                    onClick = { FileUtils.openFile(context, localFile) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "打开",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除记录",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (!task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.errorMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when (task.status) {
                    SmbTransferStatus.PAUSED -> Color(0xFFEF6C00)
                    SmbTransferStatus.FAILED -> MaterialTheme.colorScheme.error
                    SmbTransferStatus.COMPLETED -> Color(0xFF00897B)
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            if (task.size > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${FileUtils.formatFileSize(task.bytesTransferred)} / ${FileUtils.formatFileSize(task.size)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SmbImagePreviewDialog(
    initialFile: File,
    imageItems: List<SmbRemoteFile>,
    initialIndex: Int,
    onLoadImage: (SmbRemoteFile, onLoaded: (File) -> Unit, onError: (String) -> Unit) -> Unit,
    onDownloadToLocal: (SmbRemoteFile) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentIndex by remember(initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, (imageItems.size - 1).coerceAtLeast(0)))
    }
    var currentFile by remember(initialFile) { mutableStateOf(initialFile) }
    var isLoadingNext by remember { mutableStateOf(false) }

    val currentItem = imageItems.getOrNull(currentIndex) ?: return

    fun switchTo(targetIdx: Int) {
        if (targetIdx !in imageItems.indices || isLoadingNext) return
        val nextItem = imageItems[targetIdx]
        isLoadingNext = true
        onLoadImage(
            nextItem,
            { loadedFile ->
                currentIndex = targetIdx
                currentFile = loadedFile
                isLoadingNext = false
            },
            { err ->
                isLoadingNext = false
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = currentFile,
                    contentDescription = currentItem.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoadingNext) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                // 顶部栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentItem.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentIndex + 1} / ${imageItems.size} · ${FileUtils.formatFileSize(currentItem.size)}",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = {
                        onDownloadToLocal(currentItem)
                        Toast.makeText(context, "已加入下载队列 (Download/SMB)", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "下载保存", tint = Color.White)
                    }
                    IconButton(onClick = { FileUtils.openFile(context, currentFile) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "系统查看", tint = Color.White)
                    }
                }

                // 左右切换按钮（多张图片时展示）
                if (imageItems.size > 1) {
                    if (currentIndex > 0) {
                        IconButton(
                            onClick = { switchTo(currentIndex - 1) },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp)
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一张", tint = Color.White)
                        }
                    }
                    if (currentIndex < imageItems.lastIndex) {
                        IconButton(
                            onClick = { switchTo(currentIndex + 1) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一张", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimpleInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    placeholder: String = "",
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) }
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
