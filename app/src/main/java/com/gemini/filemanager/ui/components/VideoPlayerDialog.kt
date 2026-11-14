package com.gemini.filemanager.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.core.smb.QMediaDataSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class PlayableSubtitleItem(
    val uri: Uri,
    val label: String,
    val mimeType: String,
    val language: String? = null
)

data class PlayableMediaItem(
    val id: String,
    val title: String,
    val fileSizeBytes: Long = 0L,
    val isAudio: Boolean = false,
    val directUri: Uri? = null,
    val externalUrl: String? = null,
    val mimeType: String? = null,
    val subtitles: List<PlayableSubtitleItem> = emptyList()
)

data class PreparedMediaSource(
    val mediaItem: PlayableMediaItem,
    val streamToken: String? = null
)

private enum class VideoAspectMode(val label: String) {
    FIT("适应屏幕"),
    CROP_FILL("铺满无黑边"),
    STRETCH("全屏拉伸");

    fun next(): VideoAspectMode = when (this) {
        FIT -> CROP_FILL
        CROP_FILL -> STRETCH
        STRETCH -> FIT
    }
}

private data class SelectableTrackInfo(
    val group: Tracks.Group,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

private enum class GestureHudType {
    BRIGHTNESS,
    VOLUME,
    SEEK,
    SPEED_2X,
    STEP_SEEK
}

private data class GestureHudState(
    val type: GestureHudType,
    val primaryText: String,
    val secondaryText: String = "",
    val progress: Float? = null
)

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    videoFile: File? = null,
    videoUri: Uri? = null,
    title: String = videoFile?.name ?: "媒体播放",
    fileSizeBytes: Long = videoFile?.length() ?: 0L,
    isAudio: Boolean = false,
    onExternalOpen: (() -> Unit)? = null,
    mediaQueue: List<PlayableMediaItem> = emptyList(),
    initialIndex: Int = 0,
    onPrepareMedia: (suspend (PlayableMediaItem) -> PreparedMediaSource?)? = null,
    onGetSavedPosition: ((mediaId: String) -> Long)? = null,
    onSaveProgress: ((mediaId: String, positionMs: Long, durationMs: Long) -> Unit)? = null,
    onClearProgress: ((mediaId: String) -> Unit)? = null,
    onGetStreamSpeedBytesPerSec: ((streamToken: String) -> Long)? = null,
    onExternalOpenItem: ((PreparedMediaSource) -> Unit)? = null,
    onReleaseMediaToken: ((streamToken: String) -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val fallbackUri = remember(videoFile, videoUri) {
        videoUri ?: videoFile?.let { Uri.fromFile(it) }
    }
    val effectiveQueue = remember(mediaQueue, fallbackUri, title, fileSizeBytes, isAudio) {
        if (mediaQueue.isNotEmpty()) {
            mediaQueue
        } else if (fallbackUri != null) {
            listOf(
                PlayableMediaItem(
                    id = fallbackUri.toString(),
                    title = title,
                    fileSizeBytes = fileSizeBytes,
                    isAudio = isAudio,
                    directUri = fallbackUri
                )
            )
        } else {
            emptyList()
        }
    }

    if (effectiveQueue.isEmpty()) {
        LaunchedEffect(Unit) { onDismissRequest() }
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var currentIndex by remember(effectiveQueue, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, effectiveQueue.lastIndex))
    }
    val currentQueueItem = effectiveQueue[currentIndex]
    val activeIsAudio = currentQueueItem.isAudio

    var currentPrepared by remember { mutableStateOf<PreparedMediaSource?>(null) }
    val allocatedTokens = remember { mutableStateListOf<String>() }
    val externalOpenedTokens = remember { mutableSetOf<String>() }

    var isPreparingSource by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var streamSpeedBytesPerSec by remember { mutableLongStateOf(0L) }
    var playerErrorMessage by remember { mutableStateOf<String?>(null) }

    var isFullScreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var orientationMode by remember { mutableIntStateOf(0) } // 0: 横屏, 1: 竖屏
    var aspectMode by remember { mutableStateOf(VideoAspectMode.FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isLongPressBoost by remember { mutableStateOf(false) }
    var loopSingleItem by remember { mutableStateOf(false) }

    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var resumePromptPosMs by remember { mutableLongStateOf(0L) }

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }
    var cueLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<SelectableTrackInfo>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<SelectableTrackInfo>>(emptyList()) }
    var isTextTrackDisabled by remember { mutableStateOf(false) }

    var gestureHud by remember { mutableStateOf<GestureHudState?>(null) }
    var transientHudNonce by remember { mutableIntStateOf(0) }
    var dialogWindowRef by remember { mutableStateOf<android.view.Window?>(null) }
    var windowBrightness by remember { mutableFloatStateOf(-1f) }

    // 初始化 Media3 ExoPlayer（快速起播缓冲策略 + 全格式提取器 + 直连 SMB 数据源）
    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage("zh"))
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                45_000,
                800,
                1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            QMediaDataSourceFactory(context),
            extractorsFactory
        )
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(if (isAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    var retryNonce by remember { mutableIntStateOf(0) }

    fun saveCurrentPositionNow() {
        val prepared = currentPrepared ?: return
        val dur = exoPlayer.duration
        val pos = exoPlayer.currentPosition
        if (dur > 0L && pos >= 0L) {
            onSaveProgress?.invoke(prepared.mediaItem.id, pos, dur)
        }
    }

    // 切换队列项或重试时加载媒体源
    LaunchedEffect(currentIndex, retryNonce) {
        saveCurrentPositionNow()
        val targetItem = effectiveQueue[currentIndex]
        isPreparingSource = true
        isBuffering = true
        playerErrorMessage = null
        cueLines = emptyList()
        durationMs = 0L
        currentPositionMs = 0L
        bufferedPositionMs = 0L
        videoWidth = 0
        videoHeight = 0
        resumePromptPosMs = 0L

        val prepared = try {
            if (onPrepareMedia != null) {
                onPrepareMedia(targetItem)
            } else {
                PreparedMediaSource(targetItem, null)
            }
        } catch (e: Exception) {
            playerErrorMessage = e.message ?: "无法加载媒体流"
            null
        }

        if (prepared == null || prepared.mediaItem.directUri == null) {
            isPreparingSource = false
            isBuffering = false
            if (playerErrorMessage == null) {
                playerErrorMessage = "无法创建媒体播放地址"
            }
            return@LaunchedEffect
        }

        prepared.streamToken?.let { token ->
            if (!allocatedTokens.contains(token)) {
                allocatedTokens.add(token)
            }
        }
        currentPrepared = prepared
        isPreparingSource = false

        val mediaBuilder = MediaItem.Builder()
            .setUri(prepared.mediaItem.directUri)
            .setMediaId(prepared.mediaItem.id)
        if (!prepared.mediaItem.mimeType.isNullOrBlank()) {
            mediaBuilder.setMimeType(prepared.mediaItem.mimeType)
        }
        if (prepared.mediaItem.subtitles.isNotEmpty()) {
            val subtitleConfigs = prepared.mediaItem.subtitles.mapIndexed { idx, sub ->
                MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(sub.mimeType)
                    .setLabel(sub.label)
                    .setLanguage(sub.language)
                    .setSelectionFlags(if (idx == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            mediaBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        val savedPos = onGetSavedPosition?.invoke(prepared.mediaItem.id) ?: 0L
        exoPlayer.setMediaItem(mediaBuilder.build())
        if (savedPos > 0L) {
            exoPlayer.seekTo(savedPos)
            resumePromptPosMs = savedPos
        }
        exoPlayer.repeatMode = if (loopSingleItem) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.playbackParameters = PlaybackParameters(if (isLongPressBoost) 2.0f else playbackSpeed)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // 4秒自动淡出断点续播提示条
    LaunchedEffect(resumePromptPosMs) {
        if (resumePromptPosMs > 0L) {
            delay(4500)
            resumePromptPosMs = 0L
        }
    }

    // 瞬时手势提示自动隐藏
    LaunchedEffect(transientHudNonce) {
        if (transientHudNonce > 0) {
            delay(900)
            if (gestureHud?.type == GestureHudType.STEP_SEEK) {
                gestureHud = null
            }
        }
    }

    // 监听 ExoPlayer 事件
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        playerErrorMessage = null
                        val d = exoPlayer.duration
                        if (d > 0L) durationMs = d
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        currentPrepared?.mediaItem?.id?.let { id ->
                            onClearProgress?.invoke(id)
                        }
                        if (!loopSingleItem && currentIndex < effectiveQueue.lastIndex) {
                            currentIndex++
                        } else {
                            isPlaying = false
                        }
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    pixelWidthHeightRatio = if (videoSize.pixelWidthHeightRatio > 0f) {
                        videoSize.pixelWidthHeightRatio
                    } else {
                        1f
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = extractTracksOfType(tracks, C.TRACK_TYPE_AUDIO, "音轨")
                textTracks = extractTracksOfType(tracks, C.TRACK_TYPE_TEXT, "字幕")
                isTextTrackDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            }

            override fun onCues(cueGroup: CueGroup) {
                cueLines = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim() }
                    .filter { it.isNotEmpty() }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                isPlaying = false
                playerErrorMessage = formatPlayerError(error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // 同步倍速和循环模式
    LaunchedEffect(playbackSpeed, isLongPressBoost) {
        val activeSpeed = if (isLongPressBoost) 2.0f else playbackSpeed
        exoPlayer.playbackParameters = PlaybackParameters(activeSpeed)
    }

    LaunchedEffect(loopSingleItem) {
        exoPlayer.repeatMode = if (loopSingleItem) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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

    // 全屏状态栏与方向控制
    LaunchedEffect(isFullScreen, orientationMode, activeIsAudio) {
        if (!activeIsAudio && activity != null) {
            val win = activity.window
            val insetsController = WindowCompat.getInsetsController(win, win.decorView)
            if (isFullScreen) {
                activity.requestedOrientation = when (orientationMode) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 控制栏 4 秒自动淡出
    LaunchedEffect(showControls, isPlaying, isFullScreen, isDraggingSlider, showSpeedMenu, showTracksSheet, showPlaylistSheet) {
        if (isFullScreen && showControls && isPlaying && !isDraggingSlider && !showSpeedMenu && !showTracksSheet && !showPlaylistSheet) {
            delay(4000)
            showControls = false
        }
    }

    // 定时轮询播放进度、缓冲进度、实时拉流速率以及断点保存
    LaunchedEffect(exoPlayer, currentIndex, isDraggingSlider) {
        var tickCount = 0
        while (true) {
            if (!isDraggingSlider) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                val buf = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                currentPositionMs = pos
                if (dur > 0L) durationMs = dur
                bufferedPositionMs = buf
                if (tickCount % 10 == 0 && dur > 0L) {
                    currentPrepared?.mediaItem?.id?.let { id ->
                        onSaveProgress?.invoke(id, pos, dur)
                    }
                }
            }
            streamSpeedBytesPerSec = currentPrepared?.streamToken?.let { token ->
                onGetStreamSpeedBytesPerSec?.invoke(token) ?: 0L
            } ?: 0L
            tickCount++
            delay(300)
        }
    }

    // 资源释放
    DisposableEffect(Unit) {
        onDispose {
            saveCurrentPositionNow()
            try {
                exoPlayer.setVideoTextureView(null)
                exoPlayer.release()
            } catch (_: Exception) {
            }
            allocatedTokens.forEach { token ->
                if (!externalOpenedTokens.contains(token)) {
                    onReleaseMediaToken?.invoke(token)
                }
            }
            if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val handleSeekTo: (Long) -> Unit = { targetMs ->
        val safeMax = durationMs.coerceAtLeast(1000L)
        val safePos = targetMs.coerceIn(0L, safeMax)
        exoPlayer.seekTo(safePos)
        currentPositionMs = safePos
    }

    val handleSeekBy: (Long) -> Unit = { deltaMs ->
        handleSeekTo(currentPositionMs + deltaMs)
    }

    val handleTogglePlay: () -> Unit = {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0L)
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    val handleOpenExternal: () -> Unit = {
        val prepared = currentPrepared
        if (videoFile != null) {
            FileUtils.openFile(context, videoFile)
        } else if (prepared != null && onExternalOpenItem != null) {
            prepared.streamToken?.let { externalOpenedTokens.add(it) }
            onExternalOpenItem(prepared)
        } else {
            onExternalOpen?.invoke()
        }
    }

    val hasPrev = currentIndex > 0
    val hasNext = currentIndex < effectiveQueue.lastIndex
    val activeTitle = currentPrepared?.mediaItem?.title ?: currentQueueItem.title
    val activeSize = currentPrepared?.mediaItem?.fileSizeBytes ?: currentQueueItem.fileSizeBytes
    val speedLabel = formatSpeedText(streamSpeedBytesPerSec)

    Dialog(
        onDismissRequest = {
            if (showPlaylistSheet) {
                showPlaylistSheet = false
            } else if (showTracksSheet) {
                showTracksSheet = false
            } else if (isFullScreen) {
                isFullScreen = false
            } else {
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
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
            dialogWindowRef = dialogWindow

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
                    insets.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 非全屏顶部栏
                    if (!isFullScreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (activeIsAudio) "正在播放音频" else "正在播放视频",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (speedLabel.isNotEmpty()) {
                                        Text(
                                            text = "· $speedLabel",
                                            fontSize = 11.sp,
                                            color = Color(0xFF00897B),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (effectiveQueue.size > 1) {
                                        Text(
                                            text = "· ${currentIndex + 1}/${effectiveQueue.size}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    text = activeTitle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!activeIsAudio) {
                                    IconButton(onClick = { isFullScreen = true }) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = "全屏播放")
                                    }
                                }
                                if (videoFile != null || onExternalOpen != null || onExternalOpenItem != null) {
                                    IconButton(onClick = handleOpenExternal) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "系统播放器打开",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
                    if (activeIsAudio) {
                        AudioDiscStage(
                            fileSizeBytes = activeSize,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering || isPreparingSource,
                            speedLabel = speedLabel,
                            rotation = rotation,
                            errorMessage = playerErrorMessage,
                            onRetry = { retryNonce++ },
                            onExternalOpen = handleOpenExternal
                        )
                    } else {
                        BoxWithConstraints(
                            modifier = if (isFullScreen) {
                                Modifier.fillMaxSize().background(Color.Black).clipToBounds()
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            val containerWidth = maxWidth.value.coerceAtLeast(1f)
                            val containerHeight = maxHeight.value.coerceAtLeast(1f)
                            val containerAspect = containerWidth / containerHeight
                            val videoAspect = if (videoWidth > 0 && videoHeight > 0) {
                                ((videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat())
                                    .coerceIn(0.2f, 5.0f)
                            } else {
                                containerAspect
                            }

                            val (surfaceModifier, surfaceScale) = when (aspectMode) {
                                VideoAspectMode.STRETCH -> Modifier.fillMaxSize() to 1f
                                VideoAspectMode.CROP_FILL -> {
                                    val scale = if (containerAspect > videoAspect) {
                                        containerAspect / videoAspect
                                    } else {
                                        videoAspect / containerAspect
                                    }
                                    Modifier.aspectRatio(videoAspect) to scale.coerceIn(1f, 4f)
                                }
                                VideoAspectMode.FIT -> {
                                    val fitModifier = if (containerAspect > videoAspect) {
                                        Modifier.fillMaxHeight().aspectRatio(videoAspect)
                                    } else {
                                        Modifier.fillMaxWidth().aspectRatio(videoAspect)
                                    }
                                    fitModifier to 1f
                                }
                            }

                            Box(
                                modifier = surfaceModifier.scale(surfaceScale),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        TextureView(ctx).apply {
                                            exoPlayer.setVideoTextureView(this)
                                        }
                                    },
                                    update = { viewRef ->
                                        exoPlayer.setVideoTextureView(viewRef)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // 字幕渲染层
                            if (cueLines.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(
                                            horizontal = 24.dp,
                                            vertical = if (isFullScreen && showControls) 108.dp else 18.dp
                                        )
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.68f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = cueLines.joinToString("\n"),
                                        color = Color.White,
                                        fontSize = if (isFullScreen) 16.sp else 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // 缓冲提示层
                            if ((isBuffering || isPreparingSource) && playerErrorMessage == null) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(30.dp),
                                        strokeWidth = 2.8.dp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = when {
                                            isPreparingSource -> "正在建立高速 SMB 流通道..."
                                            speedLabel.isNotEmpty() -> "缓冲中 · $speedLabel"
                                            else -> "正在缓冲..."
                                        },
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // 错误兜底交互层
                            if (playerErrorMessage != null) {
                                ErrorFallbackOverlay(
                                    message = playerErrorMessage!!,
                                    canExternalOpen = videoFile != null || onExternalOpen != null || onExternalOpenItem != null,
                                    onRetry = { retryNonce++ },
                                    onExternalOpen = handleOpenExternal
                                )
                            }

                            // 断点续播温和提醒条
                            if (resumePromptPosMs > 0L && playerErrorMessage == null) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(
                                            start = 14.dp,
                                            bottom = if (isFullScreen && showControls) 104.dp else 12.dp
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "已从 ${formatTimeMs(resumePromptPosMs)} 处续播",
                                            color = Color.White,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "从头播放",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                resumePromptPosMs = 0L
                                                handleSeekTo(0L)
                                            }
                                        )
                                    }
                                }
                            }

                            // 交互手势层：单击、双击、长按 2.0x 快进、水平拖进度、左右上下调亮度/音量
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(isFullScreen, durationMs, currentPositionMs) {
                                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        val touchSlop = viewConfiguration.touchSlop

                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var mode = 0 // 0=未定, 1=水平进度, 2=左亮度, 3=右音量
                                            var totalDx = 0f
                                            var totalDy = 0f
                                            var dragStartPosMs = currentPositionMs
                                            var previewTargetMs = currentPositionMs
                                            val startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                            var currentVolFloat = startVolume
                                            val startBrightness = if (windowBrightness >= 0f) {
                                                windowBrightness
                                            } else {
                                                val attrVal = dialogWindowRef?.attributes?.screenBrightness ?: -1f
                                                if (attrVal >= 0f) attrVal else 0.5f
                                            }
                                            var currentBright = startBrightness
                                            val isLeftHalf = down.position.x < size.width / 2f

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) {
                                                    if (mode == 1) {
                                                        handleSeekTo(previewTargetMs)
                                                    }
                                                    if (mode != 0) {
                                                        gestureHud = null
                                                    }
                                                    break
                                                }
                                                val delta = change.positionChange()
                                                totalDx += delta.x
                                                totalDy += delta.y

                                                if (mode == 0) {
                                                    if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                                        if (abs(totalDx) > abs(totalDy) && durationMs > 0L) {
                                                            mode = 1
                                                            dragStartPosMs = currentPositionMs
                                                        } else if (isFullScreen && abs(totalDy) >= abs(totalDx)) {
                                                            mode = if (isLeftHalf) 2 else 3
                                                        } else {
                                                            break
                                                        }
                                                    }
                                                }

                                                when (mode) {
                                                    1 -> {
                                                        change.consume()
                                                        val ratio = totalDx / size.width.coerceAtLeast(1)
                                                        val deltaMs = (ratio * 90_000L).toLong()
                                                        previewTargetMs = (dragStartPosMs + deltaMs).coerceIn(0L, durationMs)
                                                        val sign = if (deltaMs >= 0L) "+" else "-"
                                                        gestureHud = GestureHudState(
                                                            type = GestureHudType.SEEK,
                                                            primaryText = "${formatTimeMs(previewTargetMs)} / ${formatTimeMs(durationMs)}",
                                                            secondaryText = "$sign${abs(deltaMs) / 1000}s",
                                                            progress = (previewTargetMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                                        )
                                                    }
                                                    2 -> {
                                                        change.consume()
                                                        currentBright = (currentBright - delta.y / (size.height * 0.7f)).coerceIn(0.02f, 1f)
                                                        windowBrightness = currentBright
                                                        dialogWindowRef?.let { win ->
                                                            val lp = win.attributes
                                                            lp.screenBrightness = currentBright
                                                            win.attributes = lp
                                                        }
                                                        gestureHud = GestureHudState(
                                                            type = GestureHudType.BRIGHTNESS,
                                                            primaryText = "亮度 ${(currentBright * 100).toInt()}%",
                                                            progress = currentBright
                                                        )
                                                    }
                                                    3 -> {
                                                        change.consume()
                                                        currentVolFloat = (currentVolFloat - (delta.y / (size.height * 0.7f)) * maxVolume)
                                                            .coerceIn(0f, maxVolume.toFloat())
                                                        val newVol = currentVolFloat.toInt().coerceIn(0, maxVolume)
                                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                                        val volRatio = currentVolFloat / maxVolume.toFloat()
                                                        gestureHud = GestureHudState(
                                                            type = GestureHudType.VOLUME,
                                                            primaryText = "音量 ${(volRatio * 100).toInt()}%",
                                                            progress = volRatio
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .pointerInput(isFullScreen, playbackSpeed) {
                                        detectTapGestures(
                                            onTap = { showControls = !showControls },
                                            onDoubleTap = { tapOffset ->
                                                if (isFullScreen) {
                                                    when {
                                                        tapOffset.x < size.width * 0.33f -> {
                                                            handleSeekBy(-10_000L)
                                                            gestureHud = GestureHudState(GestureHudType.STEP_SEEK, "快退 10 秒")
                                                            transientHudNonce++
                                                        }
                                                        tapOffset.x > size.width * 0.67f -> {
                                                            handleSeekBy(10_000L)
                                                            gestureHud = GestureHudState(GestureHudType.STEP_SEEK, "快进 10 秒")
                                                            transientHudNonce++
                                                        }
                                                        else -> handleTogglePlay()
                                                    }
                                                } else {
                                                    handleTogglePlay()
                                                }
                                            },
                                            onPress = {
                                                val boostJob = scope.launch {
                                                    delay(420)
                                                    isLongPressBoost = true
                                                    gestureHud = GestureHudState(
                                                        type = GestureHudType.SPEED_2X,
                                                        primaryText = "2.0x 倍速快进中"
                                                    )
                                                }
                                                try {
                                                    tryAwaitRelease()
                                                } finally {
                                                    boostJob.cancel()
                                                    if (isLongPressBoost) {
                                                        isLongPressBoost = false
                                                        gestureHud = null
                                                    }
                                                }
                                            }
                                        )
                                    }
                            )

                            // 手势 HUD 浮层提示（亮度 / 音量 / 进度 / 长按2x / 双击10s）
                            gestureHud?.let { hud ->
                                GestureHudOverlay(
                                    hud = hud,
                                    modifier = Modifier.align(
                                        if (hud.type == GestureHudType.SPEED_2X) Alignment.TopCenter else Alignment.Center
                                    )
                                )
                            }
                        }
                    }

                    // 非全屏模式下的控制条与高级功能快捷行
                    if (!isFullScreen) {
                        Spacer(modifier = Modifier.height(10.dp))
                        PlayerControlPanel(
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            bufferedPositionMs = bufferedPositionMs,
                            durationMs = durationMs,
                            isAudio = activeIsAudio,
                            isFullScreen = false,
                            playbackSpeed = playbackSpeed,
                            hasPrev = hasPrev,
                            hasNext = hasNext,
                            queueSize = effectiveQueue.size,
                            currentQueueIndex = currentIndex,
                            loopSingle = loopSingleItem,
                            hasTracks = audioTracks.size > 1 || textTracks.isNotEmpty(),
                            onTogglePlay = handleTogglePlay,
                            onSeekTo = handleSeekTo,
                            onSeekBy = handleSeekBy,
                            onPrevItem = { if (hasPrev) currentIndex-- },
                            onNextItem = { if (hasNext) currentIndex++ },
                            onToggleLoop = { loopSingleItem = !loopSingleItem },
                            onOpenSpeedMenu = { showSpeedMenu = true },
                            onOpenTracksSheet = { showTracksSheet = true },
                            onOpenPlaylistSheet = { showPlaylistSheet = true },
                            onToggleFullScreen = { isFullScreen = true }
                        )
                    }
                }

                // 全屏浮动控制栏
                if (isFullScreen && !activeIsAudio) {
                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        FullScreenOverlayControls(
                            title = activeTitle,
                            speedLabel = speedLabel,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            bufferedPositionMs = bufferedPositionMs,
                            durationMs = durationMs,
                            playbackSpeed = playbackSpeed,
                            aspectMode = aspectMode,
                            hasPrev = hasPrev,
                            hasNext = hasNext,
                            queueSize = effectiveQueue.size,
                            currentQueueIndex = currentIndex,
                            loopSingle = loopSingleItem,
                            hasTracks = audioTracks.size > 1 || textTracks.isNotEmpty(),
                            canExternalOpen = videoFile != null || onExternalOpen != null || onExternalOpenItem != null,
                            onExternalOpen = handleOpenExternal,
                            onCycleAspectMode = {
                                aspectMode = aspectMode.next()
                                Toast.makeText(context, "画面比例：${aspectMode.label}", Toast.LENGTH_SHORT).show()
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
                            onTogglePlay = handleTogglePlay,
                            onSeekTo = handleSeekTo,
                            onSeekBy = handleSeekBy,
                            onPrevItem = { if (hasPrev) currentIndex-- },
                            onNextItem = { if (hasNext) currentIndex++ },
                            onToggleLoop = { loopSingleItem = !loopSingleItem },
                            onOpenSpeedMenu = { showSpeedMenu = true },
                            onOpenTracksSheet = { showTracksSheet = true },
                            onOpenPlaylistSheet = { showPlaylistSheet = true },
                            onToggleFullScreen = { isFullScreen = false },
                            onDraggingChange = { isDraggingSlider = it }
                        )
                    }
                }

                // 倍速选择弹窗
                if (showSpeedMenu) {
                    SpeedSelectorOverlay(
                        selectedSpeed = playbackSpeed,
                        onSelectSpeed = {
                            playbackSpeed = it
                            showSpeedMenu = false
                        },
                        onDismiss = { showSpeedMenu = false }
                    )
                }

                // 音轨与字幕选择弹窗
                if (showTracksSheet) {
                    TracksSelectorOverlay(
                        audioTracks = audioTracks,
                        textTracks = textTracks,
                        isTextDisabled = isTextTrackDisabled,
                        onSelectAudioTrack = { track ->
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                                .build()
                            showTracksSheet = false
                        },
                        onSelectTextTrack = { track ->
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                                .build()
                            isTextTrackDisabled = false
                            showTracksSheet = false
                        },
                        onDisableTextTrack = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            isTextTrackDisabled = true
                            cueLines = emptyList()
                            showTracksSheet = false
                        },
                        onDismiss = { showTracksSheet = false }
                    )
                }

                // 选集 / 播放列表弹窗
                if (showPlaylistSheet && effectiveQueue.size > 1) {
                    PlaylistSelectorOverlay(
                        queue = effectiveQueue,
                        currentIndex = currentIndex,
                        onSelectIndex = { idx ->
                            currentIndex = idx
                            showPlaylistSheet = false
                        },
                        onDismiss = { showPlaylistSheet = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDiscStage(
    fileSizeBytes: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    speedLabel: String,
    rotation: Float,
    errorMessage: String?,
    onRetry: () -> Unit,
    onExternalOpen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        if (errorMessage != null) {
            ErrorFallbackOverlay(
                message = errorMessage,
                canExternalOpen = true,
                onRetry = onRetry,
                onExternalOpen = onExternalOpen
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
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
                Spacer(modifier = Modifier.height(10.dp))
                val subtitle = buildList {
                    if (fileSizeBytes > 0L) add(FileUtils.formatFileSize(fileSizeBytes))
                    if (isBuffering) add("缓冲中")
                    if (speedLabel.isNotEmpty()) add(speedLabel)
                }.joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorFallbackOverlay(
    message: String,
    canExternalOpen: Boolean,
    onRetry: () -> Unit,
    onExternalOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFFF8A65),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRetry) {
                Text("重试", color = Color.White)
            }
            if (canExternalOpen) {
                Button(onClick = onExternalOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("外部播放器打开")
                }
            }
        }
    }
}

@Composable
private fun GestureHudOverlay(
    hud: GestureHudState,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(top = 28.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val icon = when (hud.type) {
                    GestureHudType.BRIGHTNESS -> Icons.Default.BrightnessHigh
                    GestureHudType.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
                    GestureHudType.SEEK, GestureHudType.STEP_SEEK -> Icons.Default.FastForward
                    GestureHudType.SPEED_2X -> Icons.Default.Speed
                }
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(
                    text = hud.primaryText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (hud.secondaryText.isNotEmpty()) {
                    Text(
                        text = hud.secondaryText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (hud.progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { hud.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(140.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }
    }
}

@Composable
private fun FullScreenOverlayControls(
    title: String,
    speedLabel: String,
    isPlaying: Boolean,
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    aspectMode: VideoAspectMode,
    hasPrev: Boolean,
    hasNext: Boolean,
    queueSize: Int,
    currentQueueIndex: Int,
    loopSingle: Boolean,
    hasTracks: Boolean,
    canExternalOpen: Boolean,
    onExternalOpen: () -> Unit,
    onCycleAspectMode: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevItem: () -> Unit,
    onNextItem: () -> Unit,
    onToggleLoop: () -> Unit,
    onOpenSpeedMenu: () -> Unit,
    onOpenTracksSheet: () -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部控制栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                    )
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (speedLabel.isNotEmpty() || queueSize > 1) {
                    val info = buildList {
                        if (queueSize > 1) add("第 ${currentQueueIndex + 1}/$queueSize 集")
                        if (speedLabel.isNotEmpty()) add("SMB $speedLabel")
                    }.joinToString(" · ")
                    Text(
                        text = info,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp
                    )
                }
            }

            IconButton(onClick = onToggleOrientation) {
                Icon(Icons.Default.ScreenRotation, contentDescription = "旋转屏幕", tint = Color.White)
            }
            TextButton(
                onClick = onCycleAspectMode,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(aspectMode.label, color = Color.White, fontSize = 12.sp)
            }
            if (canExternalOpen) {
                IconButton(onClick = onExternalOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "外部播放", tint = Color.White)
                }
            }
        }

        // 居中播放/暂停按钮
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.48f))
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        // 底部控制条
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            PlayerControlPanel(
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                isAudio = false,
                isFullScreen = true,
                playbackSpeed = playbackSpeed,
                hasPrev = hasPrev,
                hasNext = hasNext,
                queueSize = queueSize,
                currentQueueIndex = currentQueueIndex,
                loopSingle = loopSingle,
                hasTracks = hasTracks,
                onTogglePlay = onTogglePlay,
                onSeekTo = onSeekTo,
                onSeekBy = onSeekBy,
                onPrevItem = onPrevItem,
                onNextItem = onNextItem,
                onToggleLoop = onToggleLoop,
                onOpenSpeedMenu = onOpenSpeedMenu,
                onOpenTracksSheet = onOpenTracksSheet,
                onOpenPlaylistSheet = onOpenPlaylistSheet,
                onToggleFullScreen = onToggleFullScreen,
                onDraggingChange = onDraggingChange
            )
        }
    }
}

@Composable
private fun PlayerControlPanel(
    isPlaying: Boolean,
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    isAudio: Boolean,
    isFullScreen: Boolean,
    playbackSpeed: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    queueSize: Int,
    currentQueueIndex: Int,
    loopSingle: Boolean,
    hasTracks: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevItem: () -> Unit,
    onNextItem: () -> Unit,
    onToggleLoop: () -> Unit,
    onOpenSpeedMenu: () -> Unit,
    onOpenTracksSheet: () -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onDraggingChange: (Boolean) -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val maxRange = if (durationMs > 0L) durationMs.toFloat() else 1f
    val displayPosition = if (isDragging) dragPosition.toLong() else currentPositionMs
    val sliderValue = if (isDragging) dragPosition else currentPositionMs.toFloat()
    val bufferedRatio = if (durationMs > 0L) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val textColor = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val primaryTint = if (isFullScreen) Color.White else MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxWidth()) {
        // 带有已缓冲轨底显示的自由拖拽进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isFullScreen) Color.White.copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferedRatio)
                        .fillMaxHeight()
                        .background(
                            if (isFullScreen) Color.White.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        )
                )
            }
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
                    val targetPos = dragPosition.toLong()
                    isDragging = false
                    onDraggingChange(false)
                    onSeekTo(targetPos)
                },
                valueRange = 0f..maxRange,
                enabled = durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.Transparent,
                    disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledInactiveTrackColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTimeMs(displayPosition)} / ${if (durationMs > 0L) formatTimeMs(durationMs) else "--:--"}",
                fontSize = 12.sp,
                color = textColor,
                fontWeight = FontWeight.Medium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onOpenSpeedMenu,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (playbackSpeed == 1.0f) "倍速" else "${playbackSpeed}x",
                        fontSize = 12.sp,
                        color = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary else primaryTint,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (hasTracks) {
                    TextButton(
                        onClick = onOpenTracksSheet,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("音轨/字幕", fontSize = 12.sp, color = primaryTint)
                    }
                }

                if (queueSize > 1) {
                    TextButton(
                        onClick = onOpenPlaylistSheet,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = "选集",
                            tint = primaryTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("选集(${currentQueueIndex + 1}/$queueSize)", fontSize = 12.sp, color = primaryTint)
                    }
                }

                if (!isAudio) {
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

        // 主播控按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleLoop) {
                Icon(
                    imageVector = if (loopSingle) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = if (loopSingle) "单曲循环" else "顺序播放",
                    tint = if (loopSingle) MaterialTheme.colorScheme.primary else textColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (queueSize > 1) {
                IconButton(enabled = hasPrev, onClick = onPrevItem) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "上一集",
                        tint = if (hasPrev) primaryTint else textColor.copy(alpha = 0.35f)
                    )
                }
            }

            IconButton(onClick = { onSeekBy(-10_000L) }) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "快退10秒",
                    tint = primaryTint
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(46.dp)
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

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = { onSeekBy(10_000L) }) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "快进10秒",
                    tint = primaryTint
                )
            }

            if (queueSize > 1) {
                IconButton(enabled = hasNext, onClick = onNextItem) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一集",
                        tint = if (hasNext) primaryTint else textColor.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedSelectorOverlay(
    selectedSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(min = 240.dp, max = 320.dp)
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("选择播放倍速", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                PLAYBACK_SPEEDS.forEach { spd ->
                    val isSelected = spd == selectedSpeed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else Color.Transparent
                            )
                            .clickable { onSelectSpeed(spd) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (spd == 1.0f) "1.0x (正常)" else "${spd}x",
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TracksSelectorOverlay(
    audioTracks: List<SelectableTrackInfo>,
    textTracks: List<SelectableTrackInfo>,
    isTextDisabled: Boolean,
    onSelectAudioTrack: (SelectableTrackInfo) -> Unit,
    onSelectTextTrack: (SelectableTrackInfo) -> Unit,
    onDisableTextTrack: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .heightIn(max = 420.dp)
                .clickable(enabled = false) {}
        ) {
            LazyColumn(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text("音轨与字幕切换", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (audioTracks.isNotEmpty()) {
                    item {
                        Text("音轨", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    itemsIndexed(audioTracks) { _, track ->
                        TrackOptionRow(
                            label = track.label,
                            selected = track.isSelected,
                            onClick = { onSelectAudioTrack(track) }
                        )
                    }
                }

                if (textTracks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("字幕", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    item {
                        TrackOptionRow(
                            label = "关闭字幕",
                            selected = isTextDisabled,
                            onClick = onDisableTextTrack
                        )
                    }
                    itemsIndexed(textTracks) { _, track ->
                        TrackOptionRow(
                            label = track.label,
                            selected = !isTextDisabled && track.isSelected,
                            onClick = { onSelectTextTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PlaylistSelectorOverlay(
    queue: List<PlayableMediaItem>,
    currentIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 260.dp, max = 340.dp)
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选集列表 (${queue.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(queue) { idx, item ->
                        val active = idx == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                                .clickable { onSelectIndex(idx) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.fileSizeBytes > 0L) {
                                    Text(
                                        text = FileUtils.formatFileSize(item.fileSizeBytes),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (active) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = "当前播放",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extractTracksOfType(
    tracks: Tracks,
    trackType: Int,
    prefix: String
): List<SelectableTrackInfo> {
    val result = mutableListOf<SelectableTrackInfo>()
    var counter = 1
    for (group in tracks.groups) {
        if (group.type != trackType) continue
        val mediaGroup = group.mediaTrackGroup
        for (i in 0 until mediaGroup.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = mediaGroup.getFormat(i)
            val titleParts = buildList {
                fmt.label?.takeIf { it.isNotBlank() }?.let { add(it) }
                fmt.language?.takeIf { it.isNotBlank() && it != "und" }?.let { add(it.uppercase(Locale.ROOT)) }
                fmt.sampleMimeType?.substringAfter('/')?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
            }
            val label = if (titleParts.isNotEmpty()) {
                titleParts.distinct().joinToString(" · ")
            } else {
                "$prefix $counter"
            }
            result.add(
                SelectableTrackInfo(
                    group = group,
                    trackIndex = i,
                    label = label,
                    isSelected = group.isTrackSelected(i)
                )
            )
            counter++
        }
    }
    return result
}

private fun formatPlayerError(error: PlaybackException): String {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "网络连接超时或已中断，请检查局域网连接后重试"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "当前编码或容器暂不支持硬解，可点击「外部播放器打开」使用 VLC / MX Player 播放"
        else ->
            "播放出错 (${error.errorCodeName})：${error.cause?.message ?: error.message ?: "请重试或使用外部播放器"}"
    }
}

private fun formatSpeedText(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    return when {
        bytesPerSec >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
