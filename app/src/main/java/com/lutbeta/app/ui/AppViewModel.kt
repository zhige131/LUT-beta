package com.lutbeta.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lutbeta.app.BuildConfig
import com.lutbeta.app.core.AppSettings
import com.lutbeta.app.core.CameraQrInfo
import com.lutbeta.app.core.CubeLut
import com.lutbeta.app.core.ImageProcessor
import com.lutbeta.app.core.LutInfo
import com.lutbeta.app.core.LutStore
import com.lutbeta.app.core.MediaStoreSaver
import com.lutbeta.app.core.SettingsStore
import com.lutbeta.app.core.Shot
import com.lutbeta.app.core.ShotStore
import com.lutbeta.app.service.ImportService
import com.lutbeta.app.service.RuntimeState
import com.lutbeta.app.util.NetworkUtils
import com.lutbeta.app.wifi.CameraWifiConnector
import com.lutbeta.app.wifi.CameraWifiSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val luts: List<LutInfo> = emptyList(),
    val shots: List<Shot> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val running: Boolean = false,
    val ftpReady: Boolean = false,
    val address: String? = null,
    val port: Int = 2121,
    val processed: Int = 0,
    val queued: Int = 0,
    val statusText: String = "未启动",
    val lastError: String? = null,
    val localIps: List<String> = emptyList(),
    val cameraConnected: Boolean = false,
    val cameraClients: Int = 0,
    val lastCameraSeen: Long = 0L,
    val ptpRunning: Boolean = false,
    val ptpConnected: Boolean = false,
    val ptpStatus: String = "未启动 PTP/IP",
    val ptpCameraId: String? = null,
    val ptpSsid: String? = null,
    val ptpDownloaded: Int = 0,
    val message: String? = null
) {
    val selectedLut: LutInfo? get() = luts.firstOrNull { it.id == settings.selectedLutId }
    val displayIp: String? get() = address ?: localIps.firstOrNull()
    val effectivePort: Int get() = if (running) port else settings.ftpPort
    val cameraRecentlySeen: Boolean
        get() = cameraConnected || (lastCameraSeen > 0 && System.currentTimeMillis() - lastCameraSeen < 90_000)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val lutStore = LutStore(app)
    private val shotStore = ShotStore(app)
    private val settingsStore = SettingsStore(app)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                lutStore.regeneratePreviewsIfNeeded(BuildConfig.VERSION_CODE)
            }
            refresh()
        }
        viewModelScope.launch {
            RuntimeState.running.collect { v -> _state.update { it.copy(running = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ftpReady.collect { v -> _state.update { it.copy(ftpReady = v) } }
        }
        viewModelScope.launch {
            RuntimeState.address.collect { v -> _state.update { it.copy(address = v) } }
        }
        viewModelScope.launch {
            RuntimeState.port.collect { v -> _state.update { it.copy(port = v) } }
        }
        viewModelScope.launch {
            RuntimeState.processed.collect { v -> _state.update { it.copy(processed = v) } }
        }
        viewModelScope.launch {
            RuntimeState.queued.collect { v -> _state.update { it.copy(queued = v) } }
        }
        viewModelScope.launch {
            RuntimeState.statusText.collect { v -> _state.update { it.copy(statusText = v) } }
        }
        viewModelScope.launch {
            RuntimeState.lastError.collect { v -> _state.update { it.copy(lastError = v) } }
        }
        viewModelScope.launch {
            RuntimeState.cameraConnected.collect { v -> _state.update { it.copy(cameraConnected = v) } }
        }
        viewModelScope.launch {
            RuntimeState.cameraClients.collect { v -> _state.update { it.copy(cameraClients = v) } }
        }
        viewModelScope.launch {
            RuntimeState.lastCameraSeen.collect { v -> _state.update { it.copy(lastCameraSeen = v) } }
        }
        viewModelScope.launch {
            RuntimeState.shotEvents.collect { shot -> upsertShot(shot) }
        }
        viewModelScope.launch {
            RuntimeState.ptpRunning.collect { v -> _state.update { it.copy(ptpRunning = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ptpConnected.collect { v -> _state.update { it.copy(ptpConnected = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ptpStatus.collect { v -> _state.update { it.copy(ptpStatus = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ptpCameraId.collect { v -> _state.update { it.copy(ptpCameraId = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ptpSsid.collect { v -> _state.update { it.copy(ptpSsid = v) } }
        }
        viewModelScope.launch {
            RuntimeState.ptpDownloaded.collect { v -> _state.update { it.copy(ptpDownloaded = v) } }
        }
    }

    private fun upsertShot(shot: Shot) {
        _state.update { state ->
            val items = state.shots.toMutableList()
            val index = items.indexOfFirst { it.id == shot.id }
            if (index >= 0) items[index] = shot else items.add(0, shot)
            state.copy(shots = items.sortedByDescending { it.createdAt }.take(120))
        }
    }

    fun refresh(forceReplaceShots: Boolean = false) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val luts = lutStore.list()
                var settings = settingsStore.get()
                if (settings.selectedLutId != null && luts.none { it.id == settings.selectedLutId }) {
                    settings = settingsStore.update { it.copy(selectedLutId = luts.firstOrNull()?.id) }
                }
                if (settings.selectedLutId == null && luts.isNotEmpty()) {
                    settings = settingsStore.update { it.copy(selectedLutId = luts.first().id) }
                }
                Triple(luts, shotStore.list(), settings)
            }
            _state.update { current ->
                val loadedShots = loaded.second
                val shots = if (forceReplaceShots) {
                    loadedShots
                } else {
                    // Preserve a card that arrived by event while this read was in flight.
                    val loadedIds = loadedShots.mapTo(HashSet()) { it.id }
                    (loadedShots + current.shots.filter { it.id !in loadedIds })
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt }
                        .take(120)
                }
                current.copy(
                    luts = loaded.first,
                    shots = shots,
                    settings = loaded.third,
                    localIps = NetworkUtils.allLocalIpv4()
                )
            }
        }
    }

    fun onResume() {
        refresh()
        viewModelScope.launch {
            delay(150)
            val settings = settingsStore.get()
            if (!settings.serviceEnabled || RuntimeState.running.value) return@launch
            if (settings.cameraMode == 2) {
                if (CameraWifiSession.network != null && settings.ptpSsid.isNotBlank()) {
                    ImportService.startPtp(app, settings.ptpCameraId, settings.ptpModel)
                } else {
                    postMessage("相机 Wi-Fi 未连接，请重新扫描相机热点")
                }
            } else {
                ImportService.start(app)
            }
        }
    }

    fun importLut(uri: Uri) {
        viewModelScope.launch {
            postMessage("正在解析 LUT…")
            try {
                val info = withContext(Dispatchers.IO) { lutStore.import(app.contentResolver, uri) }
                settingsStore.update { it.copy(selectedLutId = info.id) }
                refresh()
                postMessage("已导入「${info.name}」 ${info.gridSize}³")
                if (settingsStore.get().serviceEnabled) ImportService.retryPending(app)
            } catch (e: Exception) {
                postMessage("导入失败：${e.message ?: "文件无法解析"}")
            }
        }
    }

    fun importDemoLut() {
        viewModelScope.launch {
            postMessage("正在导入演示 LUT…")
            try {
                val info = withContext(Dispatchers.IO) {
                    lutStore.importAsset("LUTBeta_SLog3_Warm_17.cube", "LUT beta S-Log3 Warm Film.cube")
                }
                settingsStore.update { it.copy(selectedLutId = info.id) }
                refresh()
                postMessage("已导入演示 LUT「${info.name}」")
                if (settingsStore.get().serviceEnabled) ImportService.retryPending(app)
            } catch (e: Exception) {
                postMessage("演示 LUT 导入失败：${e.message}")
            }
        }
    }

    fun selectLut(id: String) {
        settingsStore.update { it.copy(selectedLutId = id) }
        _state.update { it.copy(settings = it.settings.copy(selectedLutId = id)) }
        if (settingsStore.get().serviceEnabled) ImportService.retryPending(app)
        postMessage("已选择「${_state.value.luts.firstOrNull { it.id == id }?.name ?: "LUT"}」")
    }

    fun togglePreviewBase() {
        val next = if (_state.value.settings.previewBaseIndex == 0) 1 else 0
        val s = settingsStore.update { it.copy(previewBaseIndex = next) }
        _state.update { it.copy(settings = s) }
    }

    fun setStrength(value: Float) {
        val v = value.coerceIn(0f, 1f)
        settingsStore.update { it.copy(lutStrength = v) }
        _state.update { it.copy(settings = it.settings.copy(lutStrength = v)) }
    }

    fun deleteLut(info: LutInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { lutStore.delete(info.id) }
            var settings = settingsStore.get()
            if (settings.selectedLutId == info.id) {
                val next = lutStore.list().firstOrNull()?.id
                settings = settingsStore.update { it.copy(selectedLutId = next) }
            }
            refresh()
            postMessage("已删除「${info.name}」")
        }
    }

    fun updateProcessing(maxDimension: Int, quality: Int, keepOriginal: Boolean) {
        // Final output is always full-resolution. The 720px preview is never
        // used as an export source.
        val s = settingsStore.update {
            it.copy(
                maxDimension = 0,
                quality = quality.coerceIn(95, 100),
                keepOriginal = keepOriginal
            )
        }
        _state.update { it.copy(settings = s) }
        postMessage("输出设置已保存")
    }

    fun updateFtp(port: Int, user: String, password: String) {
        if (port !in 1024..65535) {
            postMessage("端口需在 1024–65535 之间")
            return
        }
        if (user.isBlank() || password.isBlank()) {
            postMessage("用户名和密码不能为空")
            return
        }
        val wasRunning = _state.value.running || settingsStore.get().serviceEnabled
        val s = settingsStore.update {
            it.copy(ftpPort = port, ftpUser = user.trim(), ftpPassword = password)
        }
        _state.update { it.copy(settings = s, port = port) }
        if (wasRunning) {
            ImportService.stop(app)
            viewModelScope.launch {
                delay(700)
                ImportService.start(app)
            }
            postMessage("接收设置已保存，服务重启中")
        } else {
            postMessage("接收设置已保存")
        }
    }

    fun setGalleryWatch(enabled: Boolean) {
        val wasRunning = _state.value.running || settingsStore.get().serviceEnabled
        val s = settingsStore.update { it.copy(watchGallery = enabled) }
        _state.update { it.copy(settings = s) }
        if (wasRunning) restartServiceInternal()
        postMessage(if (enabled) "已开启相册监听" else "已关闭相册监听")
    }

    fun setSonyOnly(enabled: Boolean) {
        val s = settingsStore.update { it.copy(sonyOnly = enabled) }
        _state.update { it.copy(settings = s) }
        if (_state.value.running) restartServiceInternal()
    }

    fun setService(enabled: Boolean) {
        val settings = settingsStore.get()
        val s = settingsStore.update { it.copy(serviceEnabled = enabled) }
        _state.update { it.copy(settings = s) }
        if (enabled) {
            if (settings.cameraMode == 2) {
                if (CameraWifiSession.network != null && settings.ptpSsid.isNotBlank()) {
                    ImportService.startPtp(app, settings.ptpCameraId, settings.ptpModel)
                    RuntimeState.statusText.value = "正在连接相机 PTP/IP…"
                } else {
                    postMessage("请先扫描相机热点，或切回 FTP 备用模式")
                }
            } else {
                ImportService.start(app)
                RuntimeState.statusText.value = "正在启动…"
            }
        } else {
            ImportService.stop(app)
            RuntimeState.statusText.value = "已停止"
        }
    }

    fun selectCameraMode(mode: Int) {
        val safe = mode.coerceIn(0, 2)
        ImportService.stop(app)
        CameraWifiConnector.disconnect(app, notify = false)
        RuntimeState.ptpRunning.value = false
        RuntimeState.ptpConnected.value = false
        val s = settingsStore.update { it.copy(cameraMode = safe, serviceEnabled = false) }
        _state.update { it.copy(settings = s, ptpRunning = false, ptpConnected = false) }
        postMessage(
            when (safe) {
                0 -> "已选择手机热点 + FTP 备用方式"
                1 -> "已选择同一 Wi-Fi + FTP 备用方式"
                else -> "已选择相机 Wi-Fi Direct + PTP/IP"
            }
        )
    }

    fun connectCameraWifi(info: CameraQrInfo, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        RuntimeState.ptpStatus.value = "正在请求 Android 连接 ${info.ssid}…"
        CameraWifiConnector.connect(app, info.ssid, info.password) { result ->
            if (!result.ok) {
                RuntimeState.ptpConnected.value = false
                RuntimeState.ptpRunning.value = false
                RuntimeState.ptpStatus.value = result.message
                postMessage(result.message)
                onResult(false, result.message)
            } else {
                val updated = settingsStore.update {
                    it.copy(
                        cameraMode = 2,
                        serviceEnabled = true,
                        ptpSsid = info.ssid,
                        ptpCameraId = info.cameraId.orEmpty(),
                        ptpModel = info.model.orEmpty()
                    )
                }
                _state.update { it.copy(settings = updated, ptpSsid = info.ssid) }
                RuntimeState.ptpSsid.value = info.ssid
                RuntimeState.ptpStatus.value = "相机 Wi-Fi 已连接，启动 PTP/IP…"
                RuntimeState.ptpDownloaded.value = 0
                ImportService.startPtp(app, info.cameraId.orEmpty(), info.model)
                postMessage("已连接 ${info.ssid}，正在启动 PTP/IP")
                onResult(true, result.message)
            }
        }
    }

    fun connectCameraWifiManual(
        ssid: String,
        password: String,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val trimmedSsid = ssid.trim()
        if (!trimmedSsid.startsWith("DIRECT-", ignoreCase = true)) {
            val message = "SSID 应以 DIRECT- 开头；建议直接扫描相机二维码"
            RuntimeState.ptpStatus.value = message
            postMessage(message)
            onResult(false, message)
            return
        }
        if (password.isBlank()) {
            val message = "请输入相机 Wi-Fi 密码"
            postMessage(message)
            onResult(false, message)
            return
        }
        connectCameraWifi(
            CameraQrInfo(
                ssid = trimmedSsid,
                password = password,
                model = null,
                cameraId = null,
                raw = "manual"
            ),
            onResult
        )
    }

    fun stopCameraWifi() {
        ImportService.stop(app)
        CameraWifiConnector.disconnect(app, notify = false)
        settingsStore.update { it.copy(serviceEnabled = false) }
        _state.update {
            it.copy(
                settings = it.settings.copy(serviceEnabled = false),
                ptpRunning = false,
                ptpConnected = false,
                ptpSsid = null
            )
        }
        RuntimeState.ptpRunning.value = false
        RuntimeState.ptpConnected.value = false
        RuntimeState.ptpSsid.value = null
        RuntimeState.ptpStatus.value = "已断开相机 Wi-Fi"
        RuntimeState.statusText.value = "已断开相机 Wi-Fi"
        postMessage("已断开相机 Wi-Fi")
    }

    fun clearHistory() {
        shotStore.clear()
        _state.update { it.copy(shots = emptyList()) }
        refresh(forceReplaceShots = true)
        postMessage("历史记录已清空")
    }

    fun removeShot(id: String) {
        shotStore.remove(id)
        _state.update { state -> state.copy(shots = state.shots.filterNot { it.id == id }) }
        refresh(forceReplaceShots = true)
    }

    fun restartService() {
        if (!settingsStore.get().serviceEnabled) {
            postMessage("请先开启自动导入服务")
            return
        }
        restartServiceInternal()
        postMessage("服务重启中…")
    }

    fun sendTestShot() {
        if (settingsStore.get().selectedLutId == null) {
            postMessage("请先导入并选择一个 LUT，再发送测试照片")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = File(app.filesDir, "incoming").apply { mkdirs() }
                    val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
                    val target = File(dir, "TEST_$stamp.jpg")
                    app.assets.open("test_shot.jpg").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (!settingsStore.get().serviceEnabled) {
                    settingsStore.update { it.copy(serviceEnabled = true) }
                    ImportService.start(app)
                    delay(1200)
                }
                ImportService.retryPending(app)
                postMessage("测试照片已放入收件箱，几秒后查看「最近作品」")
            } catch (e: Exception) {
                postMessage("测试照片失败：${e.message}")
            }
        }
    }

    suspend fun loadShotPreviewBase(shot: Shot): Bitmap? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val original = shot.originalPath?.let(::File)
                    ?: throw IllegalStateException("找不到原片")
                if (!original.exists()) throw IllegalStateException("原片文件不存在")
                ImageProcessor.decodePreviewBase(original, 720)
            }.onFailure {
                if (it is CancellationException) throw it
                postMessage("预览失败：${it.message}")
            }.getOrNull()
        }
    }

    suspend fun loadLutForPreview(lutId: String): CubeLut? {
        return withContext(Dispatchers.IO) { lutStore.load(lutId) }
    }

    suspend fun renderShotPreview(shot: Shot, lutId: String, strength: Float): Bitmap? {
        return withContext(Dispatchers.IO) {
            val result = runCatching {
                val original = shot.originalPath?.let(::File)
                    ?: throw IllegalStateException("找不到原片")
                if (!original.exists()) throw IllegalStateException("原片文件不存在")
                val lut = lutStore.load(lutId) ?: throw IllegalStateException("LUT 已不存在")
                ImageProcessor.renderPreview(original, lut, strength)
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error != null) postMessage("预览失败：${error.message}")
            result.getOrNull()
        }
    }

    fun exportShot(
        shot: Shot,
        lutId: String,
        strength: Float,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val original = shot.originalPath?.let(::File)
            if (original == null || !original.exists()) {
                postMessage("导出失败：找不到原片文件")
                onComplete(false)
                return@launch
            }
            postMessage("正在按新的 LUT 导出…")
            try {
                withContext(Dispatchers.IO) {
                    val lut = lutStore.load(lutId) ?: throw IllegalStateException("LUT 已不存在")
                    val info = lutStore.get(lutId) ?: throw IllegalStateException("LUT 已不存在")
                    val settings = settingsStore.get()
                    val temp = File(app.cacheDir, "lutbeta_export_${System.currentTimeMillis()}.jpg")
                    try {
                        val result = ImageProcessor.process(
                            input = original,
                            output = temp,
                            lut = lut,
                            strength = strength.coerceIn(0f, 1f),
                            // Per-photo export always goes back to the original pixels.
                            maxDimension = 0,
                            quality = maxOf(settings.quality, 95)
                        )
                        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
                        val safeLut = info.name
                            .replace(Regex("[^A-Za-z0-9]+"), "_")
                            .trim('_')
                            .take(24)
                            .ifBlank { "LUT" }
                        val base = shot.fileName.substringBeforeLast('.')
                        val displayName = "${base}_${safeLut}_$stamp.jpg"
                        val uri = MediaStoreSaver.saveJpeg(
                            app,
                            temp,
                            displayName,
                            dateTakenMillis = shot.createdAt
                        ) ?: throw IllegalStateException("写入系统相册失败")
                        val updated = shot.copy(
                            outputUri = uri.toString(),
                            lutName = info.name,
                            lutId = info.id,
                            strength = strength.coerceIn(0f, 1f),
                            width = result.width,
                            height = result.height,
                            status = "done",
                            error = null
                        )
                        shotStore.update(updated)
                        RuntimeState.emitShot(updated)
                        RuntimeState.bumpHistory()
                    } finally {
                        temp.delete()
                    }
                }
                postMessage("已导出新成片，可在相册「Pictures/LUT Beta」查看")
                onComplete(true)
            } catch (e: Exception) {
                postMessage("导出失败：${e.message ?: "未知错误"}")
                onComplete(false)
            }
        }
    }

    fun refreshNetwork() {
        _state.update { it.copy(localIps = NetworkUtils.allLocalIpv4()) }
        if (_state.value.running || settingsStore.get().serviceEnabled) {
            restartServiceInternal()
            postMessage("正在刷新网络并重启接收服务…")
        } else {
            postMessage("已刷新本机地址")
        }
    }

    fun retryPending() {
        if (settingsStore.get().serviceEnabled) {
            ImportService.retryPending(app)
            postMessage("正在重新处理收件箱中的照片")
        } else {
            postMessage("请先开启自动导入服务")
        }
    }

    fun postMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun restartServiceInternal() {
        ImportService.stop(app)
        viewModelScope.launch {
            delay(700)
            ImportService.start(app)
        }
    }
}
