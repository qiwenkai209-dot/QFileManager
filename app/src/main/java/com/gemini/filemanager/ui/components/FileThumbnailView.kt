package com.gemini.filemanager.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gemini.filemanager.data.model.FileCategory
import com.gemini.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 视频与媒体缩略图缓存及并发调度器
 * 限制最大并发数为 2，有效避免多图列表快速滑动时爆 CPU / 内存导致卡死
 */
object MediaThumbnailCacheManager {
    // 内存 LruCache：最大缓存 150 张位图
    private val frameCache = object : LruCache<String, Bitmap>(150) {}
    private val apkIconCache = object : LruCache<String, Bitmap>(80) {}
    private val audioCoverCache = object : LruCache<String, Bitmap>(80) {}

    // 并发限流信号量，确保低端机型不卡顿
    private val videoExtractSemaphore = Semaphore(2)

    fun getCachedFrame(key: String): Bitmap? = frameCache.get(key)
    fun putCachedFrame(key: String, bitmap: Bitmap) {
        frameCache.put(key, bitmap)
    }

    fun getCachedApkIcon(key: String): Bitmap? = apkIconCache.get(key)
    fun putCachedApkIcon(key: String, bitmap: Bitmap) {
        apkIconCache.put(key, bitmap)
    }

    fun getCachedAudioCover(key: String): Bitmap? = audioCoverCache.get(key)
    fun putCachedAudioCover(key: String, bitmap: Bitmap) {
        audioCoverCache.put(key, bitmap)
    }

    /**
     * 抽取视频几帧用于轮播预览（按 15%, 40%, 65%, 85% 抽取 3~4 帧）
     */
    suspend fun extractVideoCarouselFrames(videoPath: String): List<Bitmap> = withContext(Dispatchers.IO) {
        val file = File(videoPath)
        if (!file.exists() || file.length() <= 0L) return@withContext emptyList()

        val cachedFrames = mutableListOf<Bitmap>()
        var allCached = true
        for (i in 0 until 4) {
            val key = "${videoPath}_frame_$i"
            val cached = getCachedFrame(key)
            if (cached != null && !cached.isRecycled) {
                cachedFrames.add(cached)
            } else {
                allCached = false
                break
            }
        }
        if (allCached && cachedFrames.isNotEmpty()) {
            return@withContext cachedFrames
        }

        // 限流提取
        videoExtractSemaphore.withPermit {
            val retriever = MediaMetadataRetriever()
            val frames = mutableListOf<Bitmap>()
            try {
                retriever.setDataSource(videoPath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durStr?.toLongOrNull() ?: 0L

                val fractions = if (durationMs > 2000L) {
                    listOf(0.12f, 0.38f, 0.65f, 0.88f)
                } else {
                    listOf(0.5f)
                }

                fractions.forEachIndexed { idx, frac ->
                    val timeUs = if (durationMs > 0L) (durationMs * 1000 * frac).toLong() else -1L
                    val rawBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        try {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                180,
                                180
                            )
                        } catch (_: Throwable) {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }

                    if (rawBitmap != null) {
                        val scaled = if (rawBitmap.width > 240 || rawBitmap.height > 240) {
                            Bitmap.createScaledBitmap(rawBitmap, 180, 180, true)
                        } else rawBitmap

                        val key = "${videoPath}_frame_$idx"
                        putCachedFrame(key, scaled)
                        frames.add(scaled)
                    }
                }
            } catch (_: Throwable) {
                // 忽略解码错误，容错处理
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {}
            }
            frames
        }
    }

    /**
     * 提取音频封面图
     */
    suspend fun extractAudioCover(audioPath: String): Bitmap? = withContext(Dispatchers.IO) {
        val cached = getCachedAudioCover(audioPath)
        if (cached != null && !cached.isRecycled) return@withContext cached

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val picBytes = retriever.embeddedPicture
            if (picBytes != null) {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, opts)
                var inSampleSize = 1
                while (opts.outWidth / inSampleSize > 240 || opts.outHeight / inSampleSize > 240) {
                    inSampleSize *= 2
                }
                val realOpts = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                val bmp = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size, realOpts)
                if (bmp != null) {
                    putCachedAudioCover(audioPath, bmp)
                    return@withContext bmp
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {}
        }
        null
    }

    /**
     * 提取 APK 图标
     */
    suspend fun extractApkIcon(context: Context, apkPath: String): Bitmap? = withContext(Dispatchers.IO) {
        val cached = getCachedApkIcon(apkPath)
        if (cached != null && !cached.isRecycled) return@withContext cached

        try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            if (info != null) {
                val appInfo = info.applicationInfo
                appInfo.sourceDir = apkPath
                appInfo.publicSourceDir = apkPath
                val drawable = appInfo.loadIcon(pm)
                if (drawable != null) {
                    val width = drawable.intrinsicWidth.coerceIn(48, 160)
                    val height = drawable.intrinsicHeight.coerceIn(48, 160)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    putCachedApkIcon(apkPath, bitmap)
                    return@withContext bitmap
                }
            }
        } catch (_: Throwable) {}
        null
    }
}

/**
 * 通用文件预览缩略图组件
 * 满足：
 * 1. 视频文件：抽取几帧按设定时序自动轮播切换
 * 2. 图片文件：高质量缩略图
 * 3. 音频文件：内嵌专辑封面或动态音轨卡片
 * 4. 安装包：真实应用图标
 * 5. 文档/压缩包：高辨识度彩色卡片图样
 */
@Composable
fun FileThumbnailView(
    path: String,
    category: FileCategory,
    extension: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp)
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        when (category) {
            FileCategory.IMAGE -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(path))
                        .crossfade(true)
                        .size(160, 160)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            FileCategory.VIDEO -> {
                // 视频轮播预览（抽取几帧平滑轮播）
                var carouselFrames by remember(path) { mutableStateOf<List<Bitmap>>(emptyList()) }
                var currentFrameIndex by remember { mutableIntStateOf(0) }

                LaunchedEffect(path) {
                    val frames = MediaThumbnailCacheManager.extractVideoCarouselFrames(path)
                    if (frames.isNotEmpty()) {
                        carouselFrames = frames
                        // 启动帧轮播
                        if (frames.size > 1) {
                            while (isActive) {
                                delay(950)
                                currentFrameIndex = (currentFrameIndex + 1) % frames.size
                            }
                        }
                    }
                }

                if (carouselFrames.isNotEmpty()) {
                    val activeBitmap = carouselFrames.getOrNull(currentFrameIndex) ?: carouselFrames[0]
                    Crossfade(
                        targetState = activeBitmap,
                        animationSpec = tween(durationMillis = 350),
                        label = "video_frame_crossfade"
                    ) { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // 视频播放小徽标指示
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                } else {
                    // 默认视频占位背景
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF263238)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(size * 0.55f)
                            )
                        }
                    }
                }
            }

            FileCategory.APK -> {
                var apkIcon by remember(path) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(path) {
                    apkIcon = MediaThumbnailCacheManager.extractApkIcon(context, path)
                }

                if (apkIcon != null) {
                    Image(
                        bitmap = apkIcon!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Android,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(size * 0.55f)
                        )
                    }
                }
            }

            FileCategory.AUDIO -> {
                var audioCover by remember(path) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(path) {
                    audioCover = MediaThumbnailCacheManager.extractAudioCover(path)
                }

                if (audioCover != null) {
                    Image(
                        bitmap = audioCover!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF8E24AA), Color(0xFFE91E63))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(size * 0.55f)
                        )
                    }
                }
            }

            FileCategory.DOCUMENT -> {
                val (docBg, docColor, label) = when (extension.lowercase(Locale.ROOT)) {
                    "pdf" -> Triple(Color(0xFFFFEBEE), Color(0xFFD32F2F), "PDF")
                    "doc", "docx" -> Triple(Color(0xFFE3F2FD), Color(0xFF1976D2), "DOC")
                    "xls", "xlsx", "csv" -> Triple(Color(0xFFE8F5E9), Color(0xFF388E3C), "XLS")
                    "ppt", "pptx" -> Triple(Color(0xFFFFF3E0), Color(0xFFF57C00), "PPT")
                    "txt" -> Triple(Color(0xFFECEFF1), Color(0xFF546E7A), "TXT")
                    else -> Triple(Color(0xFFEDE7F6), Color(0xFF7E57C2), extension.uppercase().take(4))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(docBg),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = docColor,
                            modifier = Modifier.size(size * 0.45f)
                        )
                        if (label.isNotBlank()) {
                            Text(
                                text = label,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = docColor
                            )
                        }
                    }
                }
            }

            FileCategory.ARCHIVE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFFF8E1)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = null,
                            tint = Color(0xFFFFA000),
                            modifier = Modifier.size(size * 0.48f)
                        )
                        Text(
                            text = extension.uppercase().take(4),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFA000)
                        )
                    }
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(size * 0.55f)
                    )
                }
            }
        }
    }
}
