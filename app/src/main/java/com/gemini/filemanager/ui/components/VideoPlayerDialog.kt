package com.gemini.filemanager.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gemini.filemanager.core.file.FileUtils
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun VideoPlayerDialog(
    videoFile: File? = null,
    videoUri: Uri? = null,
    title: String = videoFile?.name ?: "媒体播放",
    fileSizeBytes: Long = videoFile?.length() ?: 0L,
    isAudio: Boolean = false,
    onExternalOpen: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val resolvedUri = remember(videoFile, videoUri) {
        videoUri ?: videoFile?.let { Uri.fromFile(it) }
    }
    if (resolvedUri == null) {
        LaunchedEffect(Unit) { onDismissRequest() }
        return
    }
    val context = LocalContext.current
    val activity = context as? Activity

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf(0) }
    var currentPosition by remember { mutableStateOf(0) }
    var isFullScreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    // 视频尺寸与铺满全屏模式
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var isCropFill by remember { mutableStateOf(false) } // 铺满全屏 (消灭黑边) vs 自适应居中
    var isLandscapeOrientation by remember { mutableStateOf(true) } // 全屏时的屏幕旋转状态

    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    // 屏幕旋转状态 (0: 横屏, 1: 竖屏)
    var orientationMode by remember { mutableIntStateOf(0) }

    // 播放跳转工具函数
    val handleSeekTo: (Int) -> Unit = { targetMs ->
        val safePos = targetMs.coerceIn(0, duration.coerceAtLeast(1000))
        seekTo(isAudio, mediaPlayerRef, videoViewRef, safePos)
        currentPosition = safePos
    }

    val handleSeekBy: (Int) -> Unit = { delta ->
        val safePos = (currentPosition + delta).coerceIn(0, duration.coerceAtLeast(1000))
        seekTo(isAudio, mediaPlayerRef, videoViewRef, safePos)
        currentPosition = safePos
    }

    // 音频唱片旋转动效
    val infiniteTransition = rememberInfiniteTransition(label = "music_disc")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // 全屏时自动隐藏系统状态栏与屏幕旋转控制
    LaunchedEffect(isFullScreen, orientationMode) {
        if (!isAudio && activity != null) {
            val win = activity.window
            val insetsController = WindowCompat.getInsetsController(win, win.decorView)
            if (isFullScreen) {
                activity.requestedOrientation = when (orientationMode) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 全屏时控制栏 4 秒后自动淡出隐藏（拖动进度条时不隐藏）
    LaunchedEffect(showControls, isPlaying, isFullScreen, isDraggingSlider) {
        if (isFullScreen && showControls && isPlaying && !isDraggingSlider) {
            delay(4000)
            showControls = false
        }
    }

    // 定时轮询更新播放进度（拖动时不覆盖当前拖拽位置）
    LaunchedEffect(isPlaying, isDraggingSlider) {
        while (isPlaying) {
            if (!isDraggingSlider) {
                val pos = if (isAudio) {
                    mediaPlayerRef?.currentPosition ?: 0
                } else {
                    videoViewRef?.currentPosition ?: 0
                }
                currentPosition = pos
            }
            delay(500)
        }
    }

    // 资源清理与恢复屏幕
    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayerRef?.stop()
                mediaPlayerRef?.release()
            } catch (ignored: Exception) {}
            try {
                videoViewRef?.stopPlayback()
            } catch (ignored: Exception) {}
            if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isFullScreen) {
                isFullScreen = false
            } else {
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = !isFullScreen,
            decorFitsSystemWindows = !isFullScreen,
            dismissOnBackPress = true,
            dismissOnClickOutside = !isFullScreen
        )
    ) {
        // 彻底突破系统 Dialog 窗口边框，实现真正的全面屏沉浸与刘海屏穿透
        val view = LocalView.current
        DisposableEffect(view, isFullScreen) {
            var parent = view.parent
            while (parent != null && parent !is DialogWindowProvider && parent !is android.app.Dialog) {
                parent = parent.parent
            }
            val dialogWindow = when (parent) {
                is DialogWindowProvider -> parent.window
                is android.app.Dialog -> parent.window
                else -> null
            }

            dialogWindow?.let { win ->
                if (isFullScreen) {
                    win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    win.setFlags(
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        win.attributes.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    val insets = WindowCompat.getInsetsController(win, win.decorView)
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                    insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    win.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                    val insets = WindowCompat.getInsetsController(win, win.decorView)
                    insets.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }

        Surface(
            modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(0.96f).wrapContentHeight(),
            shape = if (isFullScreen) RectangleShape else RoundedCornerShape(20.dp),
            color = if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isFullScreen) 0.dp else 8.dp
        ) {
            Box(
                modifier = if (isFullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 非全屏模式下的顶部栏
                    if (!isFullScreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isAudio) "正在播放音频" else "正在播放视频",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!isAudio) {
                                    IconButton(onClick = { isFullScreen = true }) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = "全屏播放")
                                    }
                                }
                                if (videoFile != null || onExternalOpen != null) {
                                    IconButton(onClick = {
                                        if (videoFile != null) {
                                            FileUtils.openFile(context, videoFile)
                                        } else {
                                            onExternalOpen?.invoke()
                                        }
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "系统播放器打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = onDismissRequest) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 核心播放视图区
                    if (isAudio) {
                        // 音频播放器样式
                        AudioPlayerView(
                            audioUri = resolvedUri,
                            fileSizeBytes = fileSizeBytes,
                            isPlaying = isPlaying,
                            rotation = rotation,
                            onPlayerReady = { mp ->
                                mediaPlayerRef = mp
                                duration = mp.duration
                                isBuffering = false
                                isPlaying = true
                            },
                            onCompletion = { isPlaying = false },
                            onError = {
                                isBuffering = false
                                isPlaying = false
                                Toast.makeText(context, "音频加载失败或暂不支持该格式", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        // 视频播放器样式（支持自适应与铺满全屏、双击切换）
                        BoxWithConstraints(
                            modifier = if (isFullScreen)
                                Modifier.fillMaxSize().background(Color.Black)
                            else
                                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            val containerWidth = maxWidth.value
                            val containerHeight = maxHeight.value

                            // 计算铺满全屏消灭黑边的缩放比例
                            val cropScale = if (isFullScreen && isCropFill && videoWidth > 0 && videoHeight > 0 && containerWidth > 0 && containerHeight > 0) {
                                val screenAspect = containerWidth / containerHeight
                                val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
                                if (screenAspect > videoAspect) (screenAspect / videoAspect) else (videoAspect / screenAspect)
                            } else {
                                1f
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(cropScale.coerceIn(1f, 3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        VideoView(ctx).apply {
                                            setVideoURI(resolvedUri)
                                            setOnPreparedListener { mp ->
                                                duration = duration.coerceAtLeast(mp.duration)
                                                videoWidth = mp.videoWidth
                                                videoHeight = mp.videoHeight
                                                mp.isLooping = true
                                                isBuffering = false
                                                start()
                                                isPlaying = true
                                            }
                                            setOnInfoListener { _, what, _ ->
                                                when (what) {
                                                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                                                    MediaPlayer.MEDIA_INFO_BUFFERING_END,
                                                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isBuffering = false
                                                }
                                                false
                                            }
                                            setOnErrorListener { _, _, _ ->
                                                isBuffering = false
                                                isPlaying = false
                                                Toast.makeText(ctx, "播放器暂不支持此格式或网络流中断", Toast.LENGTH_SHORT).show()
                                                true
                                            }
                                            setOnCompletionListener {
                                                isPlaying = false
                                            }
                                            videoViewRef = this
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            if (isBuffering) {
                                CircularProgressIndicator(color = Color.White)
                            }

                            // 触摸手势交互层：单击显隐控制栏，双击快速在【铺满全屏】与【适应屏幕】之间切换
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(isFullScreen, isCropFill) {
                                        detectTapGestures(
                                            onTap = { showControls = !showControls },
                                            onDoubleTap = {
                                                if (isFullScreen) {
                                                    isCropFill = !isCropFill
                                                    Toast.makeText(
                                                        context,
                                                        if (isCropFill) "已切换为：铺满全屏（无黑边）" else "已切换为：适应屏幕（原比例）",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    isPlaying = togglePlayState(isAudio, mediaPlayerRef, videoViewRef)
                                                }
                                            }
                                        )
                                    }
                            )
                        }
                    }

                    // 非全屏模式下的底部控制条
                    if (!isFullScreen) {
                        Spacer(modifier = Modifier.height(14.dp))
                        PlayerControlPanel(
                            isPlaying = isPlaying,
                            currentPosition = currentPosition,
                            duration = duration,
                            isAudio = isAudio,
                            isFullScreen = false,
                            onTogglePlay = {
                                isPlaying = togglePlayState(isAudio, mediaPlayerRef, videoViewRef)
                            },
                            onSeekTo = handleSeekTo,
                            onSeekBy = handleSeekBy,
                            onToggleFullScreen = { isFullScreen = true }
                        )
                    }
                }

                // 全屏模式下的浮动浮层控制栏（淡入淡出动画）
                if (isFullScreen && !isAudio) {
                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        FullScreenOverlayControls(
                            title = title,
                            isPlaying = isPlaying,
                            currentPosition = currentPosition,
                            duration = duration,
                            videoFile = videoFile,
                            onExternalOpen = onExternalOpen,
                            isCropFill = isCropFill,
                            onToggleCropFill = {
                                isCropFill = !isCropFill
                                Toast.makeText(
                                    context,
                                    if (isCropFill) "画面模式：铺满全屏（无黑边）" else "画面模式：适应屏幕（原比例）",
                                    Toast.LENGTH_SHORT
                               ).show()
                            },
                            onToggleOrientation = {
                                orientationMode = if (orientationMode == 0) 1 else 0
                                Toast.makeText(
                                    context,
                                    if (orientationMode == 1) "已切换为：竖屏全屏" else "已切换为：横屏全屏",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onBack = { isFullScreen = false },
                            onTogglePlay = {
                                isPlaying = togglePlayState(false, null, videoViewRef)
                            },
                            onSeekTo = handleSeekTo,
                            onSeekBy = handleSeekBy,
                            onToggleFullScreen = { isFullScreen = false },
                            onDraggingChange = { dragging ->
                                isDraggingSlider = dragging
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlayerView(
    audioUri: Uri,
    fileSizeBytes: Long,
    isPlaying: Boolean,
    rotation: Float,
    onPlayerReady: (MediaPlayer) -> Unit,
    onCompletion: () -> Unit,
    onError: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .rotate(if (isPlaying) rotation else 0f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            if (fileSizeBytes > 0L) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = FileUtils.formatFileSize(fileSizeBytes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AndroidView(
            factory = { ctx ->
                android.view.View(ctx).apply {
                    val mp = MediaPlayer().apply {
                        setDataSource(ctx, audioUri)
                        setOnPreparedListener { player ->
                            onPlayerReady(player)
                            player.start()
                        }
                        setOnCompletionListener {
                            onCompletion()
                        }
                        setOnErrorListener { _, _, _ ->
                            onError()
                            true
                        }
                        prepareAsync()
                    }
                }
            },
            modifier = Modifier.size(0.dp)
        )
    }
}

@Composable
fun FullScreenOverlayControls(
    title: String,
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    videoFile: File? = null,
    onExternalOpen: (() -> Unit)? = null,
    isCropFill: Boolean,
    onToggleCropFill: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onToggleFullScreen: () -> Unit,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部浮动半透明条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 屏幕旋转按钮（横竖屏切换）
            IconButton(onClick = onToggleOrientation) {
                Icon(
                    imageVector = Icons.Default.ScreenRotation,
                    contentDescription = "旋转屏幕",
                    tint = Color.White
                )
            }

            // 画面比例按钮（铺满全屏 vs 适应比例）
            IconButton(onClick = onToggleCropFill) {
                Icon(
                    imageVector = if (isCropFill) Icons.Default.FitScreen else Icons.Default.CropFree,
                    contentDescription = if (isCropFill) "适应屏幕" else "铺满全屏",
                    tint = if (isCropFill) MaterialTheme.colorScheme.primary else Color.White
                )
            }

            // 外部系统播放器打开
            if (videoFile != null || onExternalOpen != null) {
                IconButton(onClick = {
                    if (videoFile != null) {
                        FileUtils.openFile(context, videoFile)
                    } else {
                        onExternalOpen?.invoke()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "外部播放", tint = Color.White)
                }
            }
        }

        // 居中大播放/暂停手势按钮
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        // 底部浮动半透明控制栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            PlayerControlPanel(
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                isAudio = false,
                isFullScreen = true,
                onTogglePlay = onTogglePlay,
                onSeekTo = onSeekTo,
                onSeekBy = onSeekBy,
                onToggleFullScreen = onToggleFullScreen,
                onDraggingChange = onDraggingChange
            )
        }
    }
}

@Composable
fun PlayerControlPanel(
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    isAudio: Boolean,
    isFullScreen: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onToggleFullScreen: () -> Unit,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val maxRange = if (duration > 0) duration.toFloat() else 1f
    val displayPosition = if (isDragging) dragPosition.toInt() else currentPosition
    val sliderValue = if (isDragging) dragPosition else currentPosition.toFloat()

    val textColor = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        // 自由拖拽进度条 (Slider)
        Slider(
            value = sliderValue.coerceIn(0f, maxRange),
            onValueChange = { newPos ->
                if (!isDragging) {
                    isDragging = true
                    onDraggingChange(true)
                }
                dragPosition = newPos
            },
            onValueChangeFinished = {
                val targetPos = dragPosition.toInt()
                isDragging = false
                onDraggingChange(false)
                onSeekTo(targetPos)
            },
            valueRange = 0f..maxRange,
            enabled = duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (isFullScreen) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimeMs(displayPosition),
                fontSize = 12.sp,
                color = textColor,
                fontWeight = FontWeight.Medium
            )

            // 控制中心
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = { onSeekBy(-10000) }) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "快退10秒",
                        tint = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(onClick = { onSeekBy(10000) }) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "快进10秒",
                        tint = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (duration > 0) formatTimeMs(duration) else "--:--",
                    fontSize = 12.sp,
                    color = textColor,
                    fontWeight = FontWeight.Medium
                )
                if (!isAudio) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onToggleFullScreen) {
                        Icon(
                            imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullScreen) "退出全屏" else "全屏播放",
                            tint = if (isFullScreen) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun togglePlayState(isAudio: Boolean, mp: MediaPlayer?, vv: VideoView?): Boolean {
    return if (isAudio) {
        mp?.let {
            if (it.isPlaying) {
                it.pause()
                false
            } else {
                it.start()
                true
            }
        } ?: false
    } else {
        vv?.let {
            if (it.isPlaying) {
                it.pause()
                false
            } else {
                it.start()
                true
            }
        } ?: false
    }
}

private fun seekTo(isAudio: Boolean, mp: MediaPlayer?, vv: VideoView?, newPos: Int) {
    if (isAudio) {
        mp?.seekTo(newPos)
    } else {
        vv?.seekTo(newPos)
    }
}

private fun formatTimeMs(timeMs: Int): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
