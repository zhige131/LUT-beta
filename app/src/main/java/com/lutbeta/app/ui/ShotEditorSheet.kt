package com.lutbeta.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.lutbeta.app.core.LutInfo
import com.lutbeta.app.core.PreviewFactory
import com.lutbeta.app.core.Shot
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.PanelHigh
import com.lutbeta.app.ui.theme.Paper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotEditorSheet(
    shot: Shot,
    luts: List<LutInfo>,
    previewBase: Int,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editing by remember(shot.id) { mutableStateOf(false) }
    var editLutId by remember(shot.id) {
        mutableStateOf(
            shot.lutId ?: luts.firstOrNull { it.name == shot.lutName }?.id ?: luts.firstOrNull()?.id
        )
    }
    var editStrength by remember(shot.id) { mutableFloatStateOf(shot.strength.coerceIn(0f, 1f)) }
    var preview by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var previewBaseBitmap by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember(shot.id) { mutableStateOf(false) }
    var exporting by remember(shot.id) { mutableStateOf(false) }
    var showOriginal by remember(shot.id) { mutableStateOf(false) }

    val originalUri = remember(shot.originalPath) {
        shot.originalPath?.let { path ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            }.getOrNull()
        }
    }
    val outputUri = shot.outputUri?.let { Uri.parse(it) }
    val selectedLut = luts.firstOrNull { it.id == editLutId }
    val originalPath = shot.originalPath
    val canEdit = shot.status != "processing" &&
        !originalPath.isNullOrBlank() && File(originalPath).exists()

    LaunchedEffect(luts, editLutId) {
        if (editLutId == null) editLutId = luts.firstOrNull()?.id
    }

    LaunchedEffect(shot.id, canEdit) {
        if (canEdit && previewBaseBitmap == null) {
            previewBaseBitmap = vm.loadShotPreviewBase(shot)
        }
    }

    LaunchedEffect(editing, editLutId, previewBaseBitmap) {
        if (!editing) return@LaunchedEffect
        val id = editLutId ?: return@LaunchedEffect
        val base = previewBaseBitmap ?: return@LaunchedEffect
        val lut = vm.loadLutForPreview(id) ?: return@LaunchedEffect
        if (preview == null) previewLoading = true
        try {
            // snapshotFlow observes the actual mutable slider state. Conflate
            // drops intermediate values if a 720px render is slower than the
            // drag, but the latest value is always rendered.
            snapshotFlow { editStrength }
                .conflate()
                .collect { strength ->
                    preview = withContext(Dispatchers.Default) {
                        PreviewFactory.applyLut(base, lut, strength)
                    }
                    previewLoading = false
                }
        } finally {
            previewLoading = false
        }
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
                .padding(bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editing) "换 LUT 并导出" else shot.fileName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (editing) {
                            "${selectedLut?.name ?: "选择 LUT"} · ${(editStrength * 100).toInt()}%"
                        } else {
                            "${shot.lutName ?: "LUT"} · ${(shot.strength * 100).toInt()}% · ${shot.width}×${shot.height}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Lime,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Muted)
                }
            }

            Spacer(Modifier.height(15.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelHigh)
            ) {
                val bitmap = preview
                when {
                    editing && bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "LUT 预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    editing -> AsyncImage(
                        model = originalUri ?: outputUri,
                        contentDescription = "原片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> {
                        val shown = if (showOriginal) originalUri else outputUri
                        if (shown != null) {
                            AsyncImage(
                                model = shown,
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
                }
                if (previewLoading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Lime,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                if (editing) {
                    Surface(
                        color = Ink.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(7.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    ) {
                        Text(
                            "实时预览",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Lime,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            if (!editing) {
                if (outputUri != null && originalUri != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TogglePill("成片", !showOriginal) { showOriginal = false }
                        TogglePill("原片", showOriginal) { showOriginal = true }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { editing = true },
                    enabled = canEdit && luts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                ) {
                    Text(
                        when {
                            shot.status == "processing" -> "照片处理中，稍后再编辑"
                            canEdit && luts.isNotEmpty() -> "换 LUT / 调强度 / 导出"
                            else -> "缺少原片或 LUT，无法重新导出"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(16.dp))
                Surface(
                    color = Ink,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        InfoLine("拍摄文件", shot.fileName)
                        InfoLine("当前 LUT", shot.lutName ?: "—")
                        InfoLine("LUT 强度", "${(shot.strength * 100).toInt()}%")
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
                    OutlinedDangerButton(
                        text = "从记录移除",
                        modifier = Modifier.weight(1f),
                        onClick = onRemove
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "导出会按当前 LUT 和强度重新处理原片，并另存一张新照片；不会覆盖旧成片。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            } else {
                Spacer(Modifier.height(18.dp))
                Text("选择 LUT", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                if (luts.isEmpty()) {
                    Text(
                        "LUT 库为空，请先到「LUT」页面导入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(luts, key = { it.id }) { info ->
                            LutChoice(
                                info = info,
                                previewBase = previewBase,
                                selected = info.id == editLutId,
                                onClick = { editLutId = info.id }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("强度调整", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(editStrength * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = Lime,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = editStrength,
                    onValueChange = { editStrength = it },
                    colors = SliderDefaults.colors(
                        thumbColor = Lime,
                        activeTrackColor = Lime,
                        inactiveTrackColor = Line
                    )
                )
                Text(
                    "0% 保留原片，100% 完全套用。拖动时使用低分辨率缓存实时预览；导出仍使用原始照片。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val lutId = selectedLut?.id
                            if (lutId != null) {
                                exporting = true
                                vm.exportShot(shot, lutId, editStrength) { ok ->
                                    exporting = false
                                    if (ok) onDismiss()
                                }
                            }
                        },
                        enabled = selectedLut != null && !exporting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(
                                color = Ink,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (exporting) "导出中…" else "导出新成片", fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = { editing = false },
                        enabled = !exporting
                    ) { Text("取消", color = Muted) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "导出会重新读取原片，按原始分辨率处理，JPEG 质量不低于 95；预览图不会用于导出。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
        }
    }
}

@Composable
private fun LutChoice(info: LutInfo, previewBase: Int, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(98.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(55.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) Lime else Line, RoundedCornerShape(9.dp))
        ) {
            AsyncImage(
                model = File(info.previewFor(previewBase)),
                contentDescription = info.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Lime),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            info.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Lime else Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
private fun OutlinedDangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
