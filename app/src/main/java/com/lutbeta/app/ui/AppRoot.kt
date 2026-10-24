package com.lutbeta.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lutbeta.app.core.Shot
import com.lutbeta.app.ui.screens.HomeScreen
import com.lutbeta.app.ui.screens.LutScreen
import com.lutbeta.app.ui.screens.SettingsScreen
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper
import java.io.File

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var selectedShotId by remember { mutableStateOf<String?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = PanelHigh,
                    contentColor = Paper,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        bottomBar = {
            LutBottomBar(selected = tab, onSelect = { tab = it })
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomeScreen(
                    state = state,
                    vm = vm,
                    onOpenConnect = { showConnect = true },
                    onOpenShot = { selectedShotId = it.id }
                )
                1 -> LutScreen(state, vm)
                else -> SettingsScreen(state, vm)
            }
        }
    }

    if (showConnect) {
        CameraConnectSheet(
            state = state,
            vm = vm,
            onDismiss = { showConnect = false }
        )
    }

    val shot = selectedShotId?.let { id -> state.shots.firstOrNull { it.id == id } }
    if (shot != null) {
        ShotEditorSheet(
            shot = shot,
            luts = state.luts,
            previewBase = state.settings.previewBaseIndex,
            vm = vm,
            onDismiss = { selectedShotId = null },
            onRemove = {
                vm.removeShot(shot.id)
                selectedShotId = null
            }
        )
    }
}

@Composable
private fun LutBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = Ink) {
        Column {
            HorizontalDivider(color = Line.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem("图库", Icons.Filled.Home, selected == 0) { onSelect(0) }
                TabItem("LUT", Icons.Filled.Star, selected == 1) { onSelect(1) }
                TabItem("设置", Icons.Filled.Settings, selected == 2) { onSelect(2) }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(16.dp)
                .height(2.dp)
                .background(if (selected) Lime else Color.Transparent, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.height(7.dp))
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Lime else Muted,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Lime else Muted,
            fontSize = 9.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShotDetailSheet(shot: Shot, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showOriginal by remember { mutableStateOf(false) }

    val originalUri = remember(shot.originalPath) {
        shot.originalPath?.let { path ->
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(path)
                )
            }.getOrNull()
        }
    }
    val outputUri = shot.outputUri?.let { Uri.parse(it) }
    val shownModel: Any? = if (showOriginal) originalUri else outputUri
    val canShow = shownModel != null

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
                .padding(bottom = 30.dp)
        ) {
            Text(
                shot.fileName,
                style = MaterialTheme.typography.headlineMedium,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${shot.lutName ?: "LUT"} · ${shot.width}×${shot.height}",
                style = MaterialTheme.typography.labelSmall,
                color = Lime
            )
            Spacer(Modifier.height(15.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelHigh)
            ) {
                if (canShow) {
                    AsyncImage(
                        model = shownModel,
                        contentDescription = if (showOriginal) "原片" else "成片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            shot.error ?: "图片不可用",
                            color = Danger,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            }

            if (outputUri != null && originalUri != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TogglePill("成片", !showOriginal) { showOriginal = false }
                    TogglePill("原片", showOriginal) { showOriginal = true }
                }
            }

            Spacer(Modifier.height(18.dp))
            Surface(
                color = Ink,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line)
            ) {
                Column(Modifier.padding(14.dp)) {
                    InfoLine("拍摄文件", shot.fileName)
                    InfoLine("套用 LUT", shot.lutName ?: "—")
                    InfoLine("输出尺寸", if (shot.width > 0) "${shot.width} × ${shot.height}" else "—")
                    InfoLine("来源", if (shot.source == "gallery") "相册监听" else "相机直传")
                    if (shot.error != null) InfoLine("错误", shot.error)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (outputUri != null) {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, outputUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "分享成片"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                    ) {
                        Text("分享成片", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedTextButton(
                    text = "从记录移除",
                    modifier = Modifier.weight(1f),
                    onClick = onRemove
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "提示：原片与成片都在手机本地。长按照片或用相册应用可进一步查看 EXIF。",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Lime else PanelHigh,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Ink else Muted
        )
    }
}

@Composable
private fun OutlinedTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable { onClick() },
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Danger, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Paper,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
