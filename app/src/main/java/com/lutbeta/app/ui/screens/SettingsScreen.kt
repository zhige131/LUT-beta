package com.lutbeta.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lutbeta.app.BuildConfig
import com.lutbeta.app.ui.AppViewModel
import com.lutbeta.app.ui.UiState
import com.lutbeta.app.ui.components.PageHeader
import com.lutbeta.app.ui.components.SectionLabel
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val settings = state.settings

    var quality by remember(settings.quality) { mutableIntStateOf(settings.quality.coerceIn(95, 100)) }
    var keepOriginal by remember(settings.keepOriginal) { mutableStateOf(settings.keepOriginal) }

    var port by remember(settings.ftpPort) { mutableStateOf(settings.ftpPort.toString()) }
    var user by remember(settings.ftpUser) { mutableStateOf(settings.ftpUser) }
    var pass by remember(settings.ftpPassword) { mutableStateOf(settings.ftpPassword) }
    var showAdvanced by remember { mutableStateOf(false) }

    val galleryPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setGalleryWatch(true)
        else vm.postMessage("未授予相册权限，无法监听新照片")
    }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
        if (!granted) vm.postMessage("通知权限被拒绝；可在系统设置中开启")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(22.dp))
        PageHeader(eyebrow = "System", title = "设置")
        Spacer(Modifier.height(6.dp))
        Text(
            "输出、相机接收和后台运行选项。",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        Spacer(Modifier.height(26.dp))

        SectionLabel("处理与输出")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            Text("输出尺寸", style = MaterialTheme.typography.titleMedium, color = Paper)
            Spacer(Modifier.height(6.dp))
            Text(
                "固定使用原图原始分辨率。720px 预览只用于界面，不参与导出。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Divider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JPEG 质量", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text("$quality", style = MaterialTheme.typography.bodyMedium, color = Lime)
                }
                Text("高质量", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.roundToInt() },
                valueRange = 95f..100f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = Lime,
                    activeTrackColor = Lime,
                    inactiveTrackColor = Line
                )
            )

            Divider()

            SettingSwitchRow(
                title = "同时保留原图副本",
                subtitle = "原图会另存到相册 Pictures/LUT Beta/Originals",
                checked = keepOriginal,
                onCheckedChange = { keepOriginal = it }
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.updateProcessing(0, quality.coerceAtLeast(95), keepOriginal) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("保存输出设置", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("相机连接（高级）")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("接收参数", style = MaterialTheme.typography.titleMedium, color = Paper)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.running) "运行中" else "未运行",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.running) Lime else Muted
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "高级/排错用。正常连接不需要修改；修改后服务会自动重启，相机端也要同步。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(if (showAdvanced) "收起高级参数" else "展开高级参数", color = Lime)
            }
            if (showAdvanced) {
            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.take(64) },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it.take(64) },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 0
                    vm.updateFtp(p, user, pass)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) {
                Text("保存并重启服务", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "接收服务为局域网明文传输，仅建议在手机热点或可信 Wi-Fi 下使用。端口范围 1024–65535。",
                style = MaterialTheme.typography.bodyMedium,
                color = Danger.copy(alpha = 0.8f)
            )
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("相册监听（备用）")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            SettingSwitchRow(
                title = "监听相册中的新照片",
                subtitle = "适合用 Sony Creators' App 自动传到相册的场景；本 App 会复制并处理新照片。",
                checked = settings.watchGallery,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        vm.setGalleryWatch(false)
                    } else if (ContextCompat.checkSelfPermission(context, galleryPermission) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.setGalleryWatch(true)
                    } else {
                        galleryLauncher.launch(galleryPermission)
                    }
                }
            )
            if (settings.watchGallery) {
                Divider()
                SettingSwitchRow(
                    title = "只处理索尼 A7C II 照片",
                    subtitle = "通过 EXIF 机型识别；关闭后会处理相册中所有新图片（包括截图）。",
                    checked = settings.sonyOnly,
                    onCheckedChange = { vm.setSonyOnly(it) }
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("系统与权限")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知权限", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(
                        if (notificationGranted) "已授权，前台服务通知可见" else "未授权，后台服务仍会运行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                }
                if (notificationGranted) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    }) { Text("设置", color = Muted) }
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                    ) { Text("授权", style = MaterialTheme.typography.labelLarge) }
                }
            }
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("电池优化", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(
                        "三星可能限制后台网络。建议设为「不受限制」，并关闭「让未使用的应用进入休眠」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                }
                OutlinedButton(
                    onClick = {
                        val packageUri = Uri.parse("package:${context.packageName}")
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
                        runCatching { context.startActivity(intent) }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Line),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("去设置", color = Lime) }
            }
            Divider()
            Text(
                "服务使用前台 dataSync 通知保持运行。Android 15 对 dataSync 有单次时长限制，长时间拍摄后回 App 重新开启即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("数据")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("处理历史", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(
                        "${state.shots.size} 条记录 · 已处理原图保存在 App 私有目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                }
                TextButton(onClick = { vm.retryPending() }) { Text("重试", color = Lime) }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { vm.clearHistory() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Line)
            ) { Text("清空历史记录", color = Danger) }
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel("关于")
        Spacer(Modifier.height(10.dp))
        SettingsPanel {
            AboutRow("版本", BuildConfig.VERSION_NAME)
            AboutRow("处理管线", "OpenGL ES 3.0 · 33³ 3D LUT")
            AboutRow("输入格式", ".cube / LUT_3D_SIZE 2–129")
            AboutRow("开发者", "LUT beta")
            Spacer(Modifier.height(8.dp))
            Text(
                "LUT beta 是独立工具，与 Sony 无隶属关系。所有照片均在本机处理，不上传任何服务器。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Lime else PanelHigh,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) Lime else Line),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Ink else Paper
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Paper)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Lime,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = PanelHigh,
                uncheckedBorderColor = Line
            )
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Paper,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = Line.copy(alpha = 0.65f))
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lime,
    unfocusedBorderColor = Line,
    focusedLabelColor = Lime,
    unfocusedLabelColor = Muted,
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    cursorColor = Lime,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent
)
