package com.gemini.filemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gemini.filemanager.core.background.AppBackgroundKeepAliveManager
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.ui.screens.category.CategoryFileListScreen
import com.gemini.filemanager.ui.screens.cleaner.StorageCleanerScreen
import com.gemini.filemanager.ui.screens.duplicate.DuplicateFilesScreen
import com.gemini.filemanager.ui.screens.hidden.HiddenFilesScreen
import com.gemini.filemanager.ui.screens.home.HomeScreen
import com.gemini.filemanager.ui.screens.localsend.LocalSendScreen
import com.gemini.filemanager.ui.screens.m3u8.M3U8ConvertScreen
import com.gemini.filemanager.ui.screens.smb.SmbScreen
import com.gemini.filemanager.ui.theme.AndroidFileManagerTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)
    private var sharedFilesToTransmit by mutableStateOf<List<File>>(emptyList())
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

        handleIncomingShareIntent(intent)
        checkStoragePermission()
        requestNotificationPermissionIfNeeded()

        setContent {
            AndroidFileManagerTheme {
                if (!hasStoragePermission) {
                    PermissionGuideScreen(
                        onRequestPermission = { requestStoragePermission() }
                    )
                } else {
                    MainNavigation(initialFiles = sharedFilesToTransmit)
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
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            resolveSharedUriToFile(uri)?.let {
                sharedFilesToTransmit = listOf(it)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            @Suppress("DEPRECATION")
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            val list = uris?.mapNotNull { resolveSharedUriToFile(it) } ?: emptyList()
            if (list.isNotEmpty()) {
                sharedFilesToTransmit = list
            }
        }
    }

    private fun resolveSharedUriToFile(uri: Uri?): File? {
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
    }
}
