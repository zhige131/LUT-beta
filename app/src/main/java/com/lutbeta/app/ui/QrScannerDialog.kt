package com.lutbeta.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lutbeta.app.ui.theme.Danger
import com.lutbeta.app.ui.theme.Ink
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Paper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrScannerDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (hasPermission) {
                    CameraQrPreview(
                        onResult = onResult,
                        onError = { cameraError = it }
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 34.dp, vertical = 130.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(2.dp, Lime, RoundedCornerShape(20.dp))
                        )
                    }
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 56.dp, start = 24.dp, end = 24.dp)
                    ) {
                        Text(
                            "扫描相机热点",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Paper,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "把相机屏幕上的 Wi-Fi Direct QR Code 放进框内。App 会读取 SSID、密码、机型和相机 ID。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                        cameraError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = Paper)
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "需要相机权限才能扫描二维码",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Paper
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "相机权限只用于读取相机屏幕上的二维码；照片和二维码内容不会上传。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                        ) { Text("授予相机权限") }
                        TextButton(onClick = onDismiss) { Text("取消", color = Muted) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraQrPreview(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val provider = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        }
        if (provider == null) {
            onError("相机初始化失败")
            return@LaunchedEffect
        }
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            if (handled.get()) {
                imageProxy.close()
                return@setAnalyzer
            }
            val media = imageProxy.image
            if (media == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (handled.get()) return@addOnSuccessListener
                    val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                    if (!raw.isNullOrBlank() && handled.compareAndSet(false, true)) {
                        mainHandler.post { onResult(raw) }
                    }
                }
                .addOnFailureListener {
                    // A single blurry frame is normal; keep scanning silently.
                }
                .addOnCompleteListener { imageProxy.close() }
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }.onFailure { onError("无法打开相机：${it.message}") }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}
