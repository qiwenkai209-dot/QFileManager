package com.gemini.filemanager.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.data.model.FileItem
import java.io.File

/**
 * 通用图片全屏画廊预览弹窗
 * 支持：
 * 1. 上一张 / 下一张平滑前后切换浏览
 * 2. 双击智能放大（1x <-> 2.5x）与双指自由缩放旋转拖动 (Pinch to Zoom & Pan)
 * 3. 顶部沉浸式导航栏：图片名称、当前张数序号（如 3 / 28 张）、文件大小及修改日期
 * 4. 便捷工具：旋转角度调节、系统一键分享、唤起系统外部应用打开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewDialog(
    initialIndex: Int = 0,
    imageList: List<FileItem>,
    onDismissRequest: () -> Unit,
    onDeleteCurrent: ((FileItem) -> Unit)? = null
) {
    if (imageList.isEmpty()) {
        onDismissRequest()
        return
    }

    val context = LocalContext.current
    var currentIndex by remember(initialIndex, imageList) {
        mutableIntStateOf(initialIndex.coerceIn(0, (imageList.size - 1).coerceAtLeast(0)))
    }

    val currentItem = imageList.getOrNull(currentIndex) ?: return
    val currentFile = remember(currentItem.path) { File(currentItem.path) }

    var scale by remember(currentIndex) { mutableFloatStateOf(1f) }
    var offset by remember(currentIndex) { mutableStateOf(Offset.Zero) }
    var rotationAngle by remember(currentIndex) { mutableFloatStateOf(0f) }
    var showControls by remember { mutableStateOf(true) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(0.7f, 6.0f)
        scale = newScale
        if (newScale > 1f) {
            offset += offsetChange
        } else {
            offset = Offset.Zero
        }
    }

    fun prevImage() {
        if (currentIndex > 0) {
            currentIndex--
        } else {
            Toast.makeText(context, "已经是第一张图片了", Toast.LENGTH_SHORT).show()
        }
    }

    fun nextImage() {
        if (currentIndex < imageList.size - 1) {
            currentIndex++
        } else {
            Toast.makeText(context, "已经是最后一张图片了", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentIndex) {
                        detectTapGestures(
                            onTap = {
                                showControls = !showControls
                            },
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
            ) {
                // 主图片展示视图区（带多指缩放与拖拽）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(currentFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = currentItem.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotationAngle
                            }
                    )
                }

                // 悬浮上一张按钮
                if (currentIndex > 0) {
                    IconButton(
                        onClick = { prevImage() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一张",
                            tint = Color.White
                        )
                    }
                }

                // 悬浮下一张按钮
                if (currentIndex < imageList.size - 1) {
                    IconButton(
                        onClick = { nextImage() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "下一张",
                            tint = Color.White
                        )
                    }
                }

                // 顶部控制栏
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = currentItem.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "第 ${currentIndex + 1} / ${imageList.size} 张 · ${FileUtils.formatFileSize(currentItem.size)}",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismissRequest) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = Color.White
                                )
                            }
                        },
                        actions = {
                            // 顺时针旋转 90 度
                            IconButton(onClick = { rotationAngle = (rotationAngle + 90f) % 360f }) {
                                Icon(
                                    imageVector = Icons.Default.RotateRight,
                                    contentDescription = "旋转图片",
                                    tint = Color.White
                                )
                            }
                            // 分享当前图片
                            IconButton(onClick = { FileUtils.shareFiles(context, listOf(currentFile)) }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "分享",
                                    tint = Color.White
                                )
                            }
                            // 外部打开
                            IconButton(onClick = { FileUtils.openFile(context, currentFile) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "外部应用打开",
                                    tint = Color.White
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.65f)
                        )
                    )
                }

                // 底部浮动信息提示栏
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "修改时间: ${FileUtils.formatDate(currentItem.lastModified)}",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                            if (scale != 1f || rotationAngle != 0f) {
                                TextButton(
                                    onClick = {
                                        scale = 1f
                                        offset = Offset.Zero
                                        rotationAngle = 0f
                                    }
                                ) {
                                    Text("重置视图", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
