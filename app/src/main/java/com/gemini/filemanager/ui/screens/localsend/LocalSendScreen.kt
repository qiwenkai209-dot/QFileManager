package com.gemini.filemanager.ui.screens.localsend

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.gemini.filemanager.core.localsend.LocalSendManager
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.TransferDirection
import com.gemini.filemanager.data.model.TransferItem
import com.gemini.filemanager.data.model.TransferStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendScreen(
    initialFiles: List<File> = emptyList(),
    onBack: () -> Unit,
    viewModel: LocalSendViewModel = viewModel()
) {
    val context = LocalContext.current
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val pendingFiles by viewModel.pendingFilesToSend.collectAsState()
    val isSubnetScanning by viewModel.isSubnetScanning.collectAsState()
    val subnetScanProgress by viewModel.subnetScanProgress.collectAsState()

    var showManualIpDialog by remember { mutableStateOf(false) }
    var manualIpText by remember { mutableStateOf("") }
    var isConnectingManualIp by remember { mutableStateOf(false) }

    LaunchedEffect(initialFiles) {
        if (initialFiles.isNotEmpty()) {
            viewModel.setPendingFiles(initialFiles)
        }
    }

    // 系统文件挑选器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val files = uris.mapNotNull { uri ->
                uri.path?.let { File(it) }
            }
            if (files.isNotEmpty()) {
                viewModel.setPendingFiles(files)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalSend 局域网快传") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showManualIpDialog = true }) {
                        Icon(Icons.Default.AddLink, contentDescription = "输入目标IP")
                    }
                    IconButton(onClick = { viewModel.rescan() }) {
                        Icon(Icons.Default.Radar, contentDescription = "重新扫描设备")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 本机状态卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = LocalSendManager.myAlias,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "本机 IP: ${viewModel.localIp} · 端口 53317",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00C853).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "在线监听",
                                color = Color(0xFF00C853),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 待发送文件状态
            if (pendingFiles.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "待发送文件 (${pendingFiles.size} 个)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "点击下方附近设备即可立即发送",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { viewModel.setPendingFiles(emptyList()) }) {
                                Text("清空")
                            }
                        }
                    }
                }
            }

            // 附近设备标题与快捷操作
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "附近在线设备 (${discoveredDevices.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isSubnetScanning) {
                            Text(
                                text = "全网段并发深度嗅探中... ${(subnetScanProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showManualIpDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("指定IP", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("选文件", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (isSubnetScanning) {
                item {
                    LinearProgressIndicator(
                        progress = { subnetScanProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }

            if (discoveredDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "正在全网段双重嗅探 LocalSend 电脑或手机设备...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "提示：若路由器开启了AP隔离导致搜不到，可点击右上角【指定IP】直接输入电脑显示的IP快速连接！",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            } else {
                items(discoveredDevices) { device ->
                    DeviceItemCard(
                        device = device,
                        isReadyToSend = pendingFiles.isNotEmpty(),
                        onClick = {
                            if (pendingFiles.isNotEmpty()) {
                                viewModel.sendToDevice(device)
                            } else {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        }
                    )
                }
            }

            // 传输历史与进行中列表
            if (transfers.isNotEmpty()) {
                item {
                    Text(
                        text = "传输进度与任务",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(transfers.reversed()) { transfer ->
                    TransferItemCard(transfer = transfer)
                }
            }
        }
    }

    // 手动输入 IP 对话框
    if (showManualIpDialog) {
        AlertDialog(
            onDismissRequest = { showManualIpDialog = false },
            title = { Text("手动连接 LocalSend 设备") },
            text = {
                Column {
                    Text(
                        text = "输入目标设备（例如您的 Windows / Mac 电脑端 LocalSend）显示的局域网 IP：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualIpText,
                        onValueChange = { manualIpText = it },
                        placeholder = { Text("例如：192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "默认端口 53317，系统将自动尝试 HTTP/HTTPS 握手连接。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanIp = manualIpText.trim()
                        if (cleanIp.isNotBlank()) {
                            isConnectingManualIp = true
                            viewModel.manualConnectIp(cleanIp) { success ->
                                isConnectingManualIp = false
                                if (success) {
                                    Toast.makeText(context, "成功连接到 $cleanIp", Toast.LENGTH_SHORT).show()
                                    showManualIpDialog = false
                                } else {
                                    Toast.makeText(context, "连接失败，请确认对端已开启 LocalSend 且 IP 正确", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isConnectingManualIp && manualIpText.isNotBlank()
                ) {
                    if (isConnectingManualIp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("连接")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIpDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun DeviceItemCard(
    device: DiscoveredDevice,
    isReadyToSend: Boolean,
    onClick: () -> Unit
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
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isDesktop = device.device.deviceType.lowercase() == "desktop"
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isDesktop) Color(0xFF2196F3).copy(alpha = 0.2f) else Color(0xFF00C853).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDesktop) Icons.Default.Computer else Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = if (isDesktop) Color(0xFF1976D2) else Color(0xFF009688)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.device.alias,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${device.ip}:${device.port} · ${device.device.protocol.uppercase()} · ${device.device.deviceModel ?: "LocalSend"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isReadyToSend) {
                Button(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("发送", fontSize = 12.sp)
                }
            } else {
                IconButton(onClick = onClick) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送文件")
                }
            }
        }
    }
}

@Composable
fun TransferItemCard(transfer: TransferItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (transfer.direction == TransferDirection.SEND)
                        Icons.Default.ArrowUpward
                    else
                        Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = if (transfer.direction == TransferDirection.SEND)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFF00C853),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = transfer.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (transfer.status == TransferStatus.COMPLETED) "已完成"
                    else if (transfer.speed.isNotBlank()) transfer.speed
                    else "${(transfer.progress * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}
