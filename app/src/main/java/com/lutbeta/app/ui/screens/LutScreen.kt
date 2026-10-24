package com.lutbeta.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lutbeta.app.core.LutInfo
import com.lutbeta.app.core.PreviewFactory
import com.lutbeta.app.ui.AppViewModel
import com.lutbeta.app.ui.UiState
import com.lutbeta.app.ui.components.EmptyState
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

private fun previewBaseName(index: Int): String = if (index == 1) "灰片1" else "灰片"

@Composable
fun LutScreen(state: UiState, vm: AppViewModel) {
    var pendingDelete by remember { mutableStateOf<LutInfo?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLut(uri)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PageHeader(
                    eyebrow = "Color Looks",
                    title = "LUT 库",
                    trailing = {
                        Text(
                            "${state.luts.size}".padStart(2, '0'),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Muted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "导入 .cube 文件，选中的 LUT 会自动套用到新照片。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Muted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "示例图：灰片 / 灰片1（真实 S-Log3 灰片）。在预览左上角点「切换」可在两张之间切换；预览会显示完整画面，不裁切。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted.copy(alpha = 0.78f)
                )
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Lime.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导入 .cube LUT", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(22.dp))
            }
        }

        val selected = state.selectedLut
        if (selected != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SelectedLutCard(
                    info = selected,
                    strength = state.settings.lutStrength,
                    previewBase = state.settings.previewBaseIndex,
                    onToggleBase = { vm.togglePreviewBase() },
                    vm = vm
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("全部 LUT", trailing = "点击切换")
            }
        }

        if (state.luts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "LUT 库是空的",
                    subtitle = "支持 3D .cube（LUT_3D_SIZE 2–129）。导入后会在本机生成一张效果预览图。",
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        TextButton(onClick = { vm.importDemoLut() }) {
                            Text("先导入演示 LUT", color = Lime)
                        }
                    }
                )
            }
        } else {
            items(state.luts, key = { it.id }) { info ->
                LutCard(
                    info = info,
                    previewBase = state.settings.previewBaseIndex,
                    selected = info.id == selected?.id,
                    onClick = { vm.selectLut(info.id) },
                    onDelete = { pendingDelete = info }
                )
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Panel,
            titleContentColor = Paper,
            textContentColor = Muted,
            title = { Text("删除「${toDelete.name}」？") },
            text = { Text("只会删除 App 内的 LUT 文件，不影响已经保存到相册的照片。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteLut(toDelete)
                    pendingDelete = null
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = Muted) }
            }
        )
    }
}

@Composable
private fun SelectedLutCard(
    info: LutInfo,
    strength: Float,
    previewBase: Int,
    onToggleBase: () -> Unit,
    vm: AppViewModel
) {
    val context = LocalContext.current
    var localStrength by remember(info.id) { mutableFloatStateOf(strength) }
    var strengthPreview by remember(info.id, previewBase) { mutableStateOf<Bitmap?>(null) }

    // The old slider only wrote the value when the finger was released, and the
    // card kept showing the pre-generated 100% JPEG. Re-render the whole 16:9
    // reference frame from the cached base at the current strength instead.
    LaunchedEffect(info.id, previewBase) {
        strengthPreview = null
        val base = withContext(Dispatchers.IO) {
            PreviewFactory.loadBase(context.assets, previewBase)
        } ?: return@LaunchedEffect
        val lut = vm.loadLutForPreview(info.id) ?: return@LaunchedEffect
        snapshotFlow { localStrength }
            .conflate()
            .collect { value ->
                strengthPreview = withContext(Dispatchers.Default) {
                    PreviewFactory.applyLut(base, lut, value)
                }
            }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Lime.copy(alpha = 0.35f))
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(PanelHigh)
            ) {
                val rendered = strengthPreview
                if (rendered != null) {
                    Image(
                        bitmap = rendered.asImageBitmap(),
                        contentDescription = info.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = File(info.previewFor(previewBase)),
                        contentDescription = info.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f)
                            )
                        )
                )
                Surface(
                    color = Ink.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clickable { onToggleBase() }
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lime,
                            fontSize = 9.sp
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            previewBaseName(previewBase),
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper,
                            fontSize = 9.sp
                        )
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Lime)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Ink))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "自动套用",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink,
                        fontSize = 9.sp
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                ) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${info.gridSize}³ CUBE · ${info.originalFileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lime,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("强度调整", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(localStrength * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = Lime,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = localStrength,
                    onValueChange = { localStrength = it },
                    onValueChangeFinished = { vm.setStrength(localStrength) },
                    colors = SliderDefaults.colors(
                        thumbColor = Lime,
                        activeTrackColor = Lime,
                        inactiveTrackColor = Line
                    )
                )
                Text(
                    "0% 保留原片，100% 完全套用。拖动时预览会实时重绘；这个强度也会用于之后拍摄的新照片。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
        }
    }
}

@Composable
private fun LutCard(
    info: LutInfo,
    previewBase: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = Panel,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Lime else Line)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(PanelHigh)
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
                            .padding(8.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Lime),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Ink,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${info.gridSize}³ · ${info.originalFileName.substringAfterLast('.', "").uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = Muted.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
