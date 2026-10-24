package com.lutbeta.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lutbeta.app.ui.components.StatusDot
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraConnectSheet(
    state: UiState,
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember(state.settings.cameraMode) {
        mutableIntStateOf(state.settings.cameraMode.coerceIn(0, 2))
    }
    var showPairing by remember { mutableStateOf(false) }

    val ip = state.displayIp
    val pairingText = buildString {
        appendLine("Sony A7C II 配对信息")
        appendLine("主机：${ip ?: "请先连接网络"}")
        appendLine("端口：${state.effectivePort}")
        appendLine("用户：${state.settings.ftpUser}")
        appendLine("密码：${state.settings.ftpPassword}")
        appendLine("安全协议：关")
        appendLine("被动模式：开")
        appendLine("目录：留空 / 标准")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        contentColor = Paper,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("连接相机", style = MaterialTheme.typography.headlineMedium, color = Paper)
                    Spacer(Modifier.height(4.dp))
                    Text("Sony A7C II · ILCE-7CM2", style = MaterialTheme.typography.labelSmall, color = Lime)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Muted)
                }
            }

            Spacer(Modifier.height(16.dp))
            StatusCard(state)
            Spacer(Modifier.height(20.dp))

            Text("选择连接方式", style = MaterialTheme.typography.titleMedium, color = Paper)
            Spacer(Modifier.height(8.dp))
            ModeCard(
                title = "手机热点直连",
                subtitle = "手机开热点，相机连手机。外拍最稳定。",
                badge = "推荐",
                selected = mode == 0,
                onClick = {
                    if (mode != 0) {
                        mode = 0
                        vm.selectCameraMode(0)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            ModeCard(
                title = "同一个 Wi-Fi",
                subtitle = "手机和相机都连同一个路由器。",
                badge = "室内",
                selected = mode == 1,
                onClick = {
                    if (mode != 1) {
                        mode = 1
                        vm.selectCameraMode(1)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            ModeCard(
                title = "相机 Wi-Fi Direct",
                subtitle = "扫描相机热点二维码，PTP/IP 主动读取照片。",
                badge = "新方式",
                selected = mode == 2,
                onClick = {
                    if (mode != 2) {
                        mode = 2
                        vm.selectCameraMode(2)
                    }
                }
            )

            Spacer(Modifier.height(18.dp))
            Surface(
                color = Ink,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(Modifier.padding(16.dp)) {
                    when (mode) {
                        0 -> {
                            Step(1, "打开手机热点", "设置 → 连接 → 移动热点和网络共享。")
                            Step(2, "让相机连接热点", "相机：MENU → 网络 → Wi-Fi → 访问点设置，选择手机热点。")
                            Step(3, "首次配对一次", "展开下面的配对信息，在相机里填一次；以后拍照会自动传。")
                        }
                        1 -> {
                            Step(1, "连同一个路由器", "手机和相机连接同一个 Wi-Fi，不需要开热点。")
                            Step(2, "首次配对一次", "展开下面的配对信息，在相机里填一次；以后拍照会自动传。")
                        }
                        else -> {
                            PtpConnectSection(state = state, vm = vm)
                        }
                    }
                }
            }

            if (mode != 2) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                        }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                        }
                    }
                ) { Text("打开热点设置", color = Muted) }
                TextButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    }
                ) { Text("打开 Wi-Fi 设置", color = Muted) }
            }

            if (state.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(state.lastError, style = MaterialTheme.typography.bodyMedium, color = Danger)
                if (state.running && !state.ftpReady) {
                    TextButton(
                        onClick = { vm.restartService() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("重试", color = Lime) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (state.running) vm.setService(false) else vm.setService(true)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text(
                    if (state.running) "断开连接" else "开始连接",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "开始后 App 会等待相机上传照片；它不会控制相机，也不能远程改相机设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Line)
            TextButton(
                onClick = { showPairing = !showPairing },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(
                    if (showPairing) "收起配对信息" else "首次在相机上设置（只需一次）",
                    color = Lime
                )
            }
            if (showPairing) {
                Text(
                    "Sony 没有开放第三方自动配置相机的接口，所以第一次必须由你在相机菜单里输入下面这组信息；App 不能代替，也无法自动发现并配置相机。以后不用再填。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Ink,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        PairRow("主机", ip ?: "请先连接网络")
                        PairRow("端口", state.effectivePort.toString())
                        PairRow("用户", state.settings.ftpUser)
                        PairRow("密码", state.settings.ftpPassword)
                        PairRow("安全协议", "关")
                        PairRow("被动模式", "开")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "相机路径：MENU → 网络 → FTP传输 → FTP传输功能 → 服务器设置 → 服务器 1。填入以上信息后，打开 FTP功能 和 自动FTP传输。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(pairingText))
                        vm.postMessage("已复制相机配对信息")
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) { Text("复制配对信息", color = Lime) }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "说明：这是 FTP 备用方式，照片由相机主动上传。新的「相机 Wi-Fi Direct」方式使用 PTP/IP，由手机主动读取。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted.copy(alpha = 0.8f)
            )
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    Surface(
        color = Ink,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(active = state.cameraConnected || state.ptpConnected)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.ptpConnected || state.cameraConnected -> "已连接"
                        state.ptpRunning -> "连接中"
                        state.running && state.ftpReady -> "正在等待相机"
                        state.running -> "连接异常"
                        else -> "未连接"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.cameraConnected || state.ptpConnected) Lime else Paper
                )
                Text(
                    when {
                        state.ptpConnected -> "PTP/IP 已连接，拍照后会自动读取"
                        state.ptpRunning -> "正在连接相机 PTP/IP"
                        state.cameraConnected -> "拍照后会自动导入"
                        state.running -> "请让相机连接所选网络"
                        else -> "选择方式后开始连接"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = if (selected) Lime.copy(alpha = 0.08f) else Panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) Lime else Line)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Paper)
                    Spacer(Modifier.width(7.dp))
                    Surface(
                        color = if (selected) Lime else PanelHigh,
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Ink else Muted,
                            fontSize = 8.sp
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (selected) Lime else Color.Transparent)
            )
        }
    }
}

@Composable
private fun Step(number: Int, title: String, detail: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Lime.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelSmall, color = Lime, fontSize = 8.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Paper)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

@Composable
private fun PairRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.width(78.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Paper,
            fontFamily = FontFamily.Monospace
        )
    }
}
