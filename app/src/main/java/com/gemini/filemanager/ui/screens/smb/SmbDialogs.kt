package com.gemini.filemanager.ui.screens.smb

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
                    onValueChange = { host = it },
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
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f)
                    )
                }

                OutlinedTextField(
                    value = shareName,
                    onValueChange = { shareName = it },
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
                    Switch(checked = isGuest, onCheckedChange = { isGuest = it })
                }

                if (!isGuest) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
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
                        onValueChange = { domain = it },
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
                                .clickable { protocolMode = mode }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = protocolMode == mode,
                                onClick = { protocolMode = mode }
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
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SMB 传输队列 (${tasks.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (tasks.any { it.status != SmbTransferStatus.TRANSFERRING && it.status != SmbTransferStatus.WAITING }) {
                    TextButton(onClick = onClearFinished) {
                        Text("清理已完成", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本地下载目录：$downloadDirPath",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
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
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tasks.reversed(), key = { it.id }) { task ->
                            SmbTransferTaskCard(task = task)
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
fun SmbTransferTaskCard(task: SmbTransferTask) {
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
                Text(
                    text = task.fileName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val statusText = when (task.status) {
                    SmbTransferStatus.COMPLETED -> "已完成"
                    SmbTransferStatus.FAILED -> "失败"
                    SmbTransferStatus.CANCELLED -> "已取消"
                    else -> if (task.speed.isNotBlank()) task.speed else "${(task.progress * 100).toInt()}%"
                }
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (task.status) {
                        SmbTransferStatus.COMPLETED -> Color(0xFF00897B)
                        SmbTransferStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            if (!task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.errorMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
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
}

@Composable
fun SmbImagePreviewDialog(
    imageFile: File,
    remoteItem: SmbRemoteFile,
    onDownloadToLocal: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                    model = imageFile,
                    contentDescription = remoteItem.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = remoteItem.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = FileUtils.formatFileSize(remoteItem.size),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = {
                        onDownloadToLocal()
                        Toast.makeText(context, "开始保存到 Download/SMB", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "下载保存", tint = Color.White)
                    }
                    IconButton(onClick = { FileUtils.openFile(context, imageFile) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "系统查看", tint = Color.White)
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

