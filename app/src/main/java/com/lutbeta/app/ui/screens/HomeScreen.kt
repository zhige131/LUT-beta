package com.lutbeta.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lutbeta.app.core.Shot
import com.lutbeta.app.ui.AppViewModel
import com.lutbeta.app.ui.UiState
import com.lutbeta.app.ui.components.EmptyState
import com.lutbeta.app.ui.components.PageHeader
import com.lutbeta.app.ui.components.PanelCard
import com.lutbeta.app.ui.components.SectionLabel
import com.lutbeta.app.ui.components.StatusDot
import com.lutbeta.app.ui.components.relativeTime
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper

@Composable
fun HomeScreen(
    state: UiState,
    vm: AppViewModel,
    onOpenConnect: () -> Unit,
    onOpenShot: (Shot) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        PageHeader(
            eyebrow = "Sony A7C II · Auto Import",
            title = "图库",
            trailing = {
                Surface(color = Lime.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "BETA",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Lime
                    )
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "相机拍完，自动套用 LUT 并出现在这里。",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )

        Spacer(Modifier.height(22.dp))
        CameraCard(state, vm, onOpenConnect)

        Spacer(Modifier.height(28.dp))
        SectionLabel(
            "最近作品",
            trailing = if (state.shots.isEmpty()) null else "${state.shots.size} 张"
        )
        Spacer(Modifier.height(12.dp))
        if (state.shots.isEmpty()) {
            EmptyState(
                title = "还没有照片",
                subtitle = "先把相机连接到手机，拍一张后会立即出现在这里；也可以先用测试照片体验换 LUT。",
                modifier = Modifier.fillMaxWidth(),
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenConnect,
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                        ) { Text("连接相机") }
                        TextButton(onClick = { vm.sendTestShot() }) {
                            Text("发送测试照片", color = Lime)
                        }
                    }
                }
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                items(state.shots.take(12), key = { it.id }) { shot ->
                    RecentShotCard(shot) { onOpenShot(shot) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "点开任意一张，可以换 LUT、调强度并导出新成片。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }

        Spacer(Modifier.height(28.dp))
        PanelCard(Modifier.fillMaxWidth(), color = Panel.copy(alpha = 0.5f)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "所有处理都在本机完成，照片不会上传到任何服务器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun CameraCard(state: UiState, vm: AppViewModel, onOpenConnect: () -> Unit) {
    val status = when {
        state.ptpConnected || (state.running && state.ftpReady && state.cameraConnected) -> "已连接"
        state.ptpRunning -> "连接中"
        state.running && state.ftpReady -> "等待相机"
        state.running -> "连接异常"
        else -> "未连接"
    }
    val statusColor = when {
        state.cameraConnected || state.ptpConnected -> Lime
        else -> Muted
    }
    PanelCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = state.cameraConnected || state.ptpConnected)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sony A7C II", style = MaterialTheme.typography.titleLarge, color = Paper)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = statusColor)
                }
                if (state.running || state.ptpRunning) {
                    TextButton(onClick = {
                        if (state.ptpRunning) vm.stopCameraWifi() else vm.setService(false)
                    }) {
                        Text("断开", color = Muted, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    state.ptpConnected -> "相机已通过 PTP/IP 连接，拍照后会自动读取。"
                    state.ptpRunning -> "正在连接相机 PTP/IP…"
                    state.cameraConnected -> "相机已连接，拍照后会自动导入。"
                    state.running -> "正在等待相机上传照片…"
                    else -> "连接相机后，拍完的照片会自动进入下面。"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Paper
            )
            if (state.lastCameraSeen > 0 && !state.cameraConnected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "最近收到照片：${relativeTime(state.lastCameraSeen)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
            if (state.cameraConnected || state.ptpConnected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "等待相机拍摄…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lime
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text(
                    if (state.running) "连接设置" else "连接相机",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "支持手机热点直连，也支持手机和相机连同一个 Wi-Fi。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            val error = state.lastError
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodyMedium, color = Danger)
            }
        }
    }
}

@Composable
private fun RecentShotCard(shot: Shot, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(154.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = Panel,
        border = BorderStroke(1.dp, Line)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .background(PanelHigh)
        ) {
            if (shot.outputUri != null) {
                AsyncImage(
                    model = android.net.Uri.parse(shot.outputUri),
                    contentDescription = shot.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (shot.status == "error") "失败" else "处理中",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (shot.status == "error") Danger else Muted
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    shot.fileName.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    shot.lutName ?: if (shot.status == "error") "处理失败" else "等待 LUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lime,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (shot.status == "processing") {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Text("处理中", style = MaterialTheme.typography.labelSmall, color = Lime, fontSize = 8.sp)
                }
            }
        }
    }
}
