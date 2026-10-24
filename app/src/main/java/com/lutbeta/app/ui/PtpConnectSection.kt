package com.lutbeta.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lutbeta.app.ble.BleCameraScanner
import com.lutbeta.app.core.CameraQrInfo
import com.lutbeta.app.core.CameraQrParser
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper

private enum class NeedPermission { ScanBle, ScanQr }

@Composable
fun PtpConnectSection(
    state: UiState,
    vm: AppViewModel
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ble = remember { BleCameraScanner(context) }

    var bleDevices by remember { mutableStateOf<List<BleCameraScanner.BleCamera>>(emptyList()) }
    var bleStatus by remember { mutableStateOf<String?>(null) }
    var bleScanning by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var qrInfo by remember { mutableStateOf<CameraQrInfo?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var pendingPermission by remember { mutableStateOf<NeedPermission?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val action = pendingPermission
        pendingPermission = null
        val ok = results.values.all { it }
        if (!ok) {
            qrError = "权限被拒绝；扫描二维码需要相机权限，蓝牙配对需要附近设备权限"
            return@rememberLauncherForActivityResult
        }
        when (action) {
            NeedPermission.ScanBle -> {
                bleScanning = true
                bleStatus = "正在搜索 ILCE-7CM2…"
                ble.start(
                    onFound = { item ->
                        mainHandler.post {
                            bleDevices = (bleDevices.filterNot { it.address == item.address } + item)
                                .sortedByDescending { it.rssi }
                        }
                    },
                    onError = { text -> mainHandler.post { bleScanning = false; bleStatus = text } }
                )
            }
            NeedPermission.ScanQr -> showScanner = true
            null -> Unit
        }
    }

    fun requiredPermissions(forQr: Boolean): Array<String> {
        val list = mutableListOf<String>()
        if (forQr) {
            list.add(Manifest.permission.CAMERA)
        } else {
            if (Build.VERSION.SDK_INT >= 31) {
                list.add(Manifest.permission.BLUETOOTH_SCAN)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        // The QR path also needs to request WifiNetworkSpecifier, so Android's
        // nearby-Wi-Fi permission must be granted before scanning/connecting.
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.distinct().toTypedArray()
    }

    fun ensurePermissions(forQr: Boolean, action: NeedPermission) {
        val perms = requiredPermissions(forQr)
        if (perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            when (action) {
                NeedPermission.ScanBle -> {
                    bleScanning = true
                    bleStatus = "正在搜索 ILCE-7CM2…"
                    ble.start(
                        onFound = { item ->
                            mainHandler.post {
                                bleDevices =
                                    (bleDevices.filterNot { it.address == item.address } + item)
                                        .sortedByDescending { it.rssi }
                            }
                        },
                        onError = { text -> mainHandler.post { bleScanning = false; bleStatus = text } }
                    )
                }
                NeedPermission.ScanQr -> showScanner = true
            }
        } else {
            pendingPermission = action
            permissionLauncher.launch(perms)
        }
    }

    DisposableEffect(Unit) {
        onDispose { ble.stop() }
    }

    Column {
        Text(
            "相机 Wi-Fi Direct + PTP/IP",
            style = MaterialTheme.typography.titleMedium,
            color = Paper
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "扫描相机屏幕上的 Wi-Fi Direct 二维码，Android 会弹出自己的 Wi-Fi 确认框；随后由 PTP/IP 主动读取照片，不使用 FTP。",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )

        Spacer(Modifier.height(14.dp))
        StepCard(
            number = 1,
            title = "蓝牙配对（首次）",
            body = "相机：MENU → 网络 → 连接/遥控拍摄 → 蓝牙功能 → 开。然后在下方搜索并配对。"
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ensurePermissions(false, NeedPermission.ScanBle) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanelHigh, contentColor = Paper)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (bleScanning) "搜索中…" else "搜索蓝牙相机")
            }
            TextButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            ) { Text("系统蓝牙设置", color = Muted) }
        }
        if (bleStatus != null) {
            Text(bleStatus!!, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (bleDevices.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bleDevices.forEach { item ->
                    Surface(
                        color = PanelHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ble.pair(item.address) { ok, text ->
                                    mainHandler.post { bleStatus = text }
                                }
                            }
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = if (item.bonded) Lime else Muted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Paper, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${item.address} · RSSI ${item.rssi}",
                                    color = Muted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                if (item.bonded) "已配对" else "配对",
                                color = if (item.bonded) Lime else Paper,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        StepCard(
            number = 2,
            title = "开启相机遥控拍摄",
            body = "相机：MENU → 网络 → 连接/遥控拍摄 → 遥控拍摄功能 → 遥控拍摄 → 开。"
        )

        Spacer(Modifier.height(14.dp))
        StepCard(
            number = 3,
            title = "扫描 Wi-Fi Direct 二维码",
            body = "相机：网络 → 连接/遥控拍摄 → 遥控拍摄功能 → Wi-Fi Direct 信息 → QR Code。"
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                qrError = null
                ensurePermissions(true, NeedPermission.ScanQr)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫描相机热点", fontWeight = FontWeight.SemiBold)
        }

        qrInfo?.let { info ->
            Spacer(Modifier.height(10.dp))
            Surface(
                color = Ink,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Lime.copy(alpha = 0.45f))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("已识别相机热点", color = Lime, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    QrLine("SSID", info.ssid)
                    QrLine("密码", info.password)
                    QrLine("机型", info.model ?: "ILCE-7CM2")
                    QrLine("相机 ID", info.cameraId ?: "未提供")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("也可以手动输入", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = manualSsid,
            onValueChange = { manualSsid = it },
            label = { Text("SSID（DIRECT-…）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lime,
                unfocusedBorderColor = Line,
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                focusedLabelColor = Lime,
                unfocusedLabelColor = Muted
            )
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualPassword,
            onValueChange = { manualPassword = it },
            label = { Text("相机 Wi-Fi 密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Lime,
                unfocusedBorderColor = Line,
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                focusedLabelColor = Lime,
                unfocusedLabelColor = Muted
            )
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.connectCameraWifiManual(manualSsid, manualPassword) },
            enabled = manualSsid.isNotBlank() && manualPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PanelHigh, contentColor = Paper)
        ) { Text("连接相机热点", fontWeight = FontWeight.SemiBold) }

        if (qrError != null) {
            Spacer(Modifier.height(8.dp))
            Text(qrError!!, color = Danger, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        PtpStatusCard(state, vm)

        Spacer(Modifier.height(12.dp))
        Text(
            "安全说明：只读取并下载照片，不删除相机照片、不格式化存储卡、不修改相机系统或固件。",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }

    if (showScanner) {
        QrScannerDialog(
            onResult = { raw ->
                showScanner = false
                val parsed = CameraQrParser.parse(raw)
                if (parsed == null) {
                    qrError = "二维码内容不是 A7C II 的 Wi-Fi Direct 格式（应以 W01: 或 WIFI: 开头）"
                } else {
                    qrInfo = parsed
                    manualSsid = parsed.ssid
                    manualPassword = parsed.password
                    qrError = null
                    // The system Wi-Fi confirmation is still shown by Android.
                    vm.connectCameraWifi(parsed)
                }
            },
            onDismiss = { showScanner = false }
        )
    }
}

@Composable
private fun PtpStatusCard(state: UiState, vm: AppViewModel) {
    val connected = state.ptpConnected
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (connected) Lime.copy(alpha = 0.5f) else Line)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (connected) Lime else Muted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected) "PTP/IP 已连接" else if (state.ptpRunning) "PTP/IP 连接中" else "PTP/IP 未连接",
                    color = if (connected) Lime else Paper,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                if (state.ptpRunning) {
                    TextButton(onClick = { vm.stopCameraWifi() }) {
                        Text("断开", color = Danger)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                state.ptpStatus,
                color = Muted,
                style = MaterialTheme.typography.bodyMedium
            )
            state.ptpSsid?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "热点：$it · 已下载 ${state.ptpDownloaded} 张",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, body: String) {
    Surface(
        color = Ink,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(PanelHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(number.toString(), color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = Paper, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun QrLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(58.dp))
        Text(
            value,
            color = Paper,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}
