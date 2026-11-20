package com.gemini.filemanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.ui.components.VideoPlayerDialog
import com.gemini.filemanager.ui.screens.category.CategoryFileListScreen
import com.gemini.filemanager.ui.screens.cleaner.StorageCleanerScreen
import com.gemini.filemanager.ui.screens.duplicate.DuplicateFilesScreen
import com.gemini.filemanager.ui.screens.explorer.StorageExplorerScreen
import com.gemini.filemanager.ui.screens.hidden.HiddenFilesScreen
import com.gemini.filemanager.ui.screens.home.HomeScreen
import com.gemini.filemanager.ui.screens.localsend.LocalSendScreen
import com.gemini.filemanager.ui.screens.m3u8.M3U8ConvertScreen
import com.gemini.filemanager.ui.screens.smb.SmbScreen
import com.gemini.filemanager.ui.theme.AndroidFileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class IncomingAction {
    data class Play(
        val uri: Uri,
        val title: String,
        val isAudio: Boolean,
        val file: File? = null
    ) : IncomingAction()

    data class OtherFile(
        val uri: Uri,
        val title: String,
        val size: Long,
        val mimeType: String,
        val file: File? = null
    ) : IncomingAction()
}

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)
    private var sharedFilesToTransmit by mutableStateOf<List<File>>(emptyList())
    private var incomingAction by mutableStateOf<IncomingAction?>(null)
    private var notificationPromptedThisSession = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkStoragePermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        AppBackgroundKeepAliveManager.refreshSystemStates(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIncomingIntent(intent)
        checkStoragePermission()
        requestNotificationPermissionIfNeeded()

        setContent {
            AndroidFileManagerTheme {
                val action = incomingAction
                if (action is IncomingAction.Play) {
                    // 作为专业三方播放器直接全屏拉起播放，无论有无全盘存储权限均可秒开
                    VideoPlayerDialog(
                        videoUri = action.uri,
                        videoFile = action.file,
                        title = action.title,
                        isAudio = action.isAudio,
                        onDismissRequest = {
                            incomingAction = null
                            if (!hasStoragePermission) {
                                finish()
                            }
                        }
                    )
                } else if (!hasStoragePermission) {
                    PermissionGuideScreen(
                        onRequestPermission = { requestStoragePermission() }
                    )
                } else {
                    MainNavigation(initialFiles = sharedFilesToTransmit)

                    // 如果点击了其它非媒体文件，弹出专业的三方文件打开与处理操作对话框
                    if (action is IncomingAction.OtherFile) {
                        ExternalOtherFileDialog(
                            action = action,
                            onPlayAsMedia = {
                                incomingAction = IncomingAction.Play(
                                    uri = action.uri,
                                    title = action.title,
                                    isAudio = false,
                                    file = action.file
                                )
                            },
                            onSendViaLocalSend = { file ->
                                sharedFilesToTransmit = listOf(file)
                                incomingAction = null
                            },
                            onDismiss = {
                                incomingAction = null
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppBackgroundKeepAliveManager.ensureStartedFromForeground(this)
    }

    override fun onResume() {
        super.onResume()
        checkStoragePermission()
        AppBackgroundKeepAliveManager.refreshSystemStates(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPromptedThisSession) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !AppBackgroundKeepAliveManager.hasNotificationPermission(this)
        ) {
            notificationPromptedThisSession = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        // 1. 处理系统分享发送 (ACTION_SEND / ACTION_SEND_MULTIPLE)
        if (Intent.ACTION_SEND == action && type != null) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            resolveSharedUriToFile(uri)?.let {
                sharedFilesToTransmit = listOf(it)
            }
            return
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            @Suppress("DEPRECATION")
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            val list = uris?.mapNotNull { resolveSharedUriToFile(it) } ?: emptyList()
            if (list.isNotEmpty()) {
                sharedFilesToTransmit = list
            }
            return
        }

        // 2. 作为三方播放器与三方文件打开工具处理 (ACTION_VIEW)
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data ?: return
            var displayName = "未知文件"
            var fileSize = 0L

            if ("content".equals(uri.scheme, ignoreCase = true)) {
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0) {
                                cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                            }
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0) {
                                fileSize = cursor.getLong(sizeIdx)
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                uri.path?.let { p ->
                    val f = File(p)
                    if (f.exists()) {
                        displayName = f.name
                        fileSize = f.length()
                    }
                }
            }

            if (displayName == "未知文件") {
                uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }

            val ext = displayName.substringAfterLast(".", "").lowercase()
            val resolvedMime = type ?: contentResolver.getType(uri) ?: FileUtils.getMimeType(File(displayName))

            val isVideo = resolvedMime.startsWith("video/") || FileUtils.isVideoExtension(ext)
            val isAudio = resolvedMime.startsWith("audio/") || FileUtils.isAudioExtension(ext)
            val isMedia = isVideo || isAudio

            val localFile = if ("file".equals(uri.scheme, ignoreCase = true)) {
                uri.path?.let { File(it) }?.takeIf { it.exists() }
            } else {
                uri.path?.let { File(it) }?.takeIf { it.exists() }
            }

            if (isMedia) {
                incomingAction = IncomingAction.Play(
                    uri = uri,
                    title = displayName,
                    isAudio = isAudio,
                    file = localFile
                )
            } else {
                incomingAction = IncomingAction.OtherFile(
                    uri = uri,
                    title = displayName,
                    size = fileSize,
                    mimeType = resolvedMime,
                    file = localFile
                )
            }
        }
    }

    fun resolveSharedUriToFile(uri: Uri?): File? {
        if (uri == null) return null
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.isFile) return file
        }
        val pathFile = uri.path?.let { File(it) }
        if (pathFile != null && pathFile.exists() && pathFile.isFile) return pathFile

        // 兼容来自相册/微信等应用的 content:// 分享 URI
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            return try {
                var displayName = "shared_${System.currentTimeMillis()}"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                    }
                }
                val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val importDir = File(cacheDir, "shared_import").apply { if (!exists()) mkdirs() }
                val target = File(importDir, safeName)
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered(64 * 1024).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (target.exists() && target.length() > 0L) target else null
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun checkStoragePermission() {
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}

@Composable
fun ExternalOtherFileDialog(
    action: IncomingAction.OtherFile,
    onPlayAsMedia: () -> Unit,
    onSendViaLocalSend: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOperating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "外部文件处理",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = action.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "大小: ${if (action.size > 0) FileUtils.formatFileSize(action.size) else "流式数据"} · 类型: ${action.mimeType}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "点击其他文件已选择本应用打开，请选择您想对该文件执行的操作：",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 选项 1: 尝试以媒体播放器播放
                OutlinedButton(
                    onClick = onPlayAsMedia,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("以内置播放器尝试播放")
                }

                // 选项 2: LocalSend 局域网发送
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isOperating = true
                            val targetFile = action.file ?: withContext(Dispatchers.IO) {
                                (context as? MainActivity)?.resolveSharedUriToFile(action.uri)
                            }
                            isOperating = false
                            if (targetFile != null && targetFile.exists()) {
                                onSendViaLocalSend(targetFile)
                            } else {
                                Toast.makeText(context, "无法读取该文件进行快传", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isOperating,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("通过 LocalSend 快传给其他设备")
                }

                // 选项 3: 保存/导出到本地手机「下载」目录
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val downloadsDir = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "QFileManager"
                                ).apply { if (!exists()) mkdirs() }
                                var dest = File(downloadsDir, action.title)
                                if (dest.exists()) {
                                    val base = action.title.substringBeforeLast(".")
                                    val ext = action.title.substringAfterLast(".", "")
                                    val extStr = if (ext.isNotEmpty()) ".$ext" else ""
                                    dest = File(downloadsDir, "${base}_${System.currentTimeMillis()}$extStr")
                                }
                                context.contentResolver.openInputStream(action.uri)?.use { input ->
                                    FileOutputStream(dest).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "已保存到：Download/QFileManager/${dest.name}", Toast.LENGTH_LONG).show()
                                    onDismiss()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("保存至本地「Download/QFileManager」")
                }

                // 选项 4: 如果是 APK 提供一键安装
                if (action.title.endsWith(".apk", true)) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(action.uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, "调起安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("安装此应用 (APK)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun PermissionGuideScreen(onRequestPermission: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "需要存储管理权限",
                fontSize = 22.sp,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "为了让您能够扫描与分类查看图片、音视频、安装包、压缩包，以及嗅探合并浏览器下载的离线 M3U8 分片，需要授予「所有文件访问权限」。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("前往系统设置授予权限")
            }
        }
    }
}

@Composable
fun MainNavigation(initialFiles: List<File> = emptyList()) {
    val navController = rememberNavController()
    var targetTransmitFiles by remember { mutableStateOf(initialFiles) }
    var targetSmbUploadFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(initialFiles) {
        if (initialFiles.isNotEmpty()) {
            targetTransmitFiles = initialFiles
            navController.navigate("localsend")
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (initialFiles.isNotEmpty()) "localsend" else "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateCategory = { cat ->
                    navController.navigate("category/${cat.name}")
                },
                onNavigateM3U8 = {
                    navController.navigate("m3u8")
                },
                onNavigateLocalSend = {
                    targetTransmitFiles = emptyList()
                    navController.navigate("localsend")
                },
                onNavigateSmb = {
                    targetSmbUploadFiles = emptyList()
                    navController.navigate("smb")
                },
                onNavigateDuplicate = {
                    navController.navigate("duplicate")
                },
                onNavigateCleaner = {
                    navController.navigate("cleaner")
                },
                onNavigateHiddenFiles = {
                    navController.navigate("hidden_files")
                },
                onNavigateStorageExplorer = {
                    navController.navigate("explorer")
                }
            )
        }

        composable(
            route = "category/{categoryName}",
            arguments = listOf(navArgument("categoryName") { type = NavType.StringType })
        ) { backStackEntry ->
            val catName = backStackEntry.arguments?.getString("categoryName") ?: FileCategory.DOCUMENT.name
            val category = try {
                FileCategory.valueOf(catName)
            } catch (e: Exception) {
                FileCategory.DOCUMENT
            }

            CategoryFileListScreen(
                category = category,
                onBack = { navController.popBackStack() },
                onSendViaLocalSend = { files ->
                    targetTransmitFiles = files
                    navController.navigate("localsend")
                },
                onUploadToSmb = { files ->
                    targetSmbUploadFiles = files
                    navController.navigate("smb")
                }
            )
        }

        composable("m3u8") {
            M3U8ConvertScreen(
                onBack = { navController.popBackStack() },
                onSendFile = { file ->
                    targetTransmitFiles = listOf(file)
                    navController.navigate("localsend")
                }
            )
        }

        composable("localsend") {
            LocalSendScreen(
                initialFiles = targetTransmitFiles,
                onBack = { navController.popBackStack() }
            )
        }

        composable("smb") {
            SmbScreen(
                initialUploadFiles = targetSmbUploadFiles,
                onBack = { navController.popBackStack() }
            )
        }

        composable("duplicate") {
            DuplicateFilesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("cleaner") {
            StorageCleanerScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("hidden_files") {
            HiddenFilesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("explorer") {
            StorageExplorerScreen(
                onBack = { navController.popBackStack() },
                onSendViaLocalSend = { files ->
                    targetTransmitFiles = files
                    navController.navigate("localsend")
                },
                onUploadToSmb = { files ->
                    targetSmbUploadFiles = files
                    navController.navigate("smb")
                }
            )
        }
    }
}
