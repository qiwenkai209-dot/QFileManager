package com.gemini.filemanager.ui.screens.cleaner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.filemanager.core.file.FileUtils
import com.gemini.filemanager.core.file.WeChatDuplicateGroup
import com.gemini.filemanager.core.file.WeChatFileItem
import com.gemini.filemanager.ui.components.FileThumbnailView

@Composable
fun WeChatCleanerView(
    state: CleanerUiState,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onRescan: () -> Unit
) {
    if (state.isScanningWeChat && state.weChatDuplicateGroups.isEmpty() && state.weChatDeprecatedFiles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(14.dp))
                Text("正在使用最新算法校对微信占用（根目录隐藏弃用、文件名与大小比对）...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (state.weChatDuplicateGroups.isEmpty() && state.weChatDeprecatedFiles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00C853), modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("微信存储非常清爽！", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("未发现冗余历史版本或废弃残留文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onRescan) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新精准扫描")
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 顶部概览看板
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("微信存储深度分析 (最新算法)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (state.isScanningWeChat) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = onRescan, contentPadding = PaddingValues(0.dp)) {
                                Text("刷新分析", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("微信总占用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(FileUtils.formatFileSize(state.weChatTotalBytes), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("可释放冗余", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            Text(FileUtils.formatFileSize(state.weChatReclaimableBytes), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("重复/弃用组", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${state.weChatDuplicateGroups.size} 组", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "校对规则：按文件名与文件大小精准比对，默认保留时间最晚（最新）的版本，旧版历史副本与弃用日志默认勾选待删。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 快捷勾选控制栏
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待处理项 (最晚版已自动保护)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("全不选", fontSize = 12.sp)
                    }
                    TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("恢复默认勾选", fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. 根目录弃用与隐藏文件区
        if (state.weChatDeprecatedFiles.isNotEmpty()) {
            item {
                Text(
                    text = "根目录隐藏与弃用残留 (${state.weChatDeprecatedFiles.size} 项 · ${FileUtils.formatFileSize(state.weChatDeprecatedFiles.sumOf { it.fileItem.size })})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5722)
                )
            }

            items(state.weChatDeprecatedFiles, key = { "dep_${it.fileItem.path}" }) { depItem ->
                val isSelected = state.weChatSelectedPaths.contains(depItem.fileItem.path)
                WeChatFileCard(
                    item = depItem,
                    isSelected = isSelected,
                    isProtected = false,
                    badgeText = depItem.deprecateReason ?: "弃用残留",
                    badgeColor = Color(0xFFFF5722),
                    onToggleSelect = { onToggleSelect(depItem.fileItem.path) }
                )
            }
        }

        // 3. 文件名与大小校对重复组区
        if (state.weChatDuplicateGroups.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "文件名与大小精确校对重复组 (${state.weChatDuplicateGroups.size} 组 · 可省 ${FileUtils.formatFileSize(state.weChatDuplicateGroups.sumOf { it.totalWastedBytes })})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(state.weChatDuplicateGroups, key = { "group_${it.groupKey}" }) { group ->
                WeChatDuplicateGroupCard(
                    group = group,
                    selectedPaths = state.weChatSelectedPaths,
                    onToggleSelect = onToggleSelect
                )
            }
        }
    }
}

@Composable
fun WeChatDuplicateGroupCard(
    group: WeChatDuplicateGroup,
    selectedPaths: Set<String>,
    onToggleSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 组标题头
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "单份: ${FileUtils.formatFileSize(group.fileSize)} · 共 ${group.redundantItems.size + 1} 份",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 1. 最晚修改版（默认保留，受保护）
            val isLatestSelected = selectedPaths.contains(group.latestItem.fileItem.path)
            WeChatFileCard(
                item = group.latestItem,
                isSelected = isLatestSelected,
                isProtected = true,
                badgeText = "✓ 最晚版（保留）",
                badgeColor = Color(0xFF00C853),
                onToggleSelect = { onToggleSelect(group.latestItem.fileItem.path) }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 2. 其余历史/冗余副本（默认勾选待删）
            group.redundantItems.forEach { redItem ->
                val isRedSelected = selectedPaths.contains(redItem.fileItem.path)
                WeChatFileCard(
                    item = redItem,
                    isSelected = isRedSelected,
                    isProtected = false,
                    badgeText = "历史冗余副本",
                    badgeColor = Color(0xFFFF9800),
                    onToggleSelect = { onToggleSelect(redItem.fileItem.path) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun WeChatFileCard(
    item: WeChatFileItem,
    isSelected: Boolean,
    isProtected: Boolean,
    badgeText: String,
    badgeColor: Color,
    onToggleSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggleSelect() },
        color = if (isSelected)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else if (isProtected)
            Color(0xFFE8F5E9).copy(alpha = 0.7f)
        else
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 缩略图与多帧轮播预览画面
            FileThumbnailView(
                path = item.fileItem.path,
                category = item.fileItem.category,
                extension = item.fileItem.extension,
                size = 40.dp,
                shape = RoundedCornerShape(6.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = FileUtils.formatDate(item.fileItem.lastModified),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.fileItem.path,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
