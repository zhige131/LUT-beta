package com.lutbeta.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.lutbeta.app.MainActivity
import com.lutbeta.app.R
import com.lutbeta.app.core.ImageProcessor
import com.lutbeta.app.core.LutStore
import com.lutbeta.app.core.MediaStoreSaver
import com.lutbeta.app.core.SettingsStore
import com.lutbeta.app.core.Shot
import com.lutbeta.app.core.ShotStore
import com.lutbeta.app.ftp.FtpServerManager
import com.lutbeta.app.ptp.SonyPtpImporter
import com.lutbeta.app.util.NetworkUtils
import com.lutbeta.app.wifi.CameraWifiSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<File>(Channel.UNLIMITED)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private lateinit var settingsStore: SettingsStore
    private lateinit var lutStore: LutStore
    private lateinit var shotStore: ShotStore

    private var ftpServer: FtpServerManager? = null
    private var ptpImporter: SonyPtpImporter? = null
    private var galleryWatcher: GalleryWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var consumerStarted = false

    @Volatile
    private var started = false

    private val incomingDir: File by lazy { File(filesDir, "incoming").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        lutStore = LutStore(this)
        shotStore = ShotStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settingsStore.update { it.copy(serviceEnabled = false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETRY -> {
                ensureStarted()
                scope.launch { scanIncoming() }
                return START_STICKY
            }
            ACTION_START_PTP -> {
                ensureStarted(startFtp = false)
                startPtp(
                    cameraId = intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty(),
                    model = intent.getStringExtra(EXTRA_CAMERA_MODEL)
                )
                return START_STICKY
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun ensureStarted(startFtp: Boolean = true) {
        if (started) return
        started = true
        val settings = settingsStore.get()
        RuntimeState.processed.value = 0
        RuntimeState.queued.value = 0
        RuntimeState.lastError.value = null
        RuntimeState.port.value = settings.ftpPort
        RuntimeState.running.value = true
        RuntimeState.ftpReady.value = false
        RuntimeState.cameraConnected.value = false
        RuntimeState.cameraClients.value = 0
        RuntimeState.lastCameraSeen.value = 0L
        RuntimeState.ptpRunning.value = !startFtp
        RuntimeState.ptpConnected.value = false
        RuntimeState.statusText.value =
            if (startFtp) "正在启动相机接收服务…" else "正在连接相机 PTP/IP…"

        startForegroundCompat(
            buildNotification(if (startFtp) "正在启动相机接收服务…" else "正在连接相机 PTP/IP…")
        )
        acquireLocks()
        startConsumer()
        if (startFtp) {
            startFtp(settings.ftpPort, settings.ftpUser, settings.ftpPassword)
        }
        scope.launch { scanIncoming() }
        if (settings.watchGallery) startGalleryWatcher(settings.sonyOnly)
    }

    private fun startPtp(cameraId: String, model: String?) {
        if (started.not()) ensureStarted(startFtp = false)
        runCatching { ftpServer?.stop() }
        ftpServer = null
        RuntimeState.ftpReady.value = false
        ptpImporter?.stop()
        ptpImporter = null

        RuntimeState.ptpRunning.value = true
        RuntimeState.ptpConnected.value = false
        RuntimeState.ptpCameraId.value = cameraId.ifBlank { null }
        RuntimeState.ptpSsid.value = CameraWifiSession.ssid
        RuntimeState.ptpStatus.value = "正在连接相机 PTP/IP…"
        RuntimeState.statusText.value = "正在连接相机 PTP/IP…"
        RuntimeState.lastError.value = null
        updateNotification("正在连接相机 PTP/IP…")

        val importer = SonyPtpImporter(
            context = this,
            incomingDir = incomingDir,
            cameraId = cameraId,
            model = model,
            onFile = { file ->
                RuntimeState.ptpDownloaded.update { it + 1 }
                enqueue(file)
            },
            onConnected = {
                RuntimeState.ptpConnected.value = true
                RuntimeState.cameraConnected.value = true
                RuntimeState.noteCameraSeen()
                RuntimeState.statusText.value = "PTP/IP 已连接，等待相机照片"
                updateNotification("PTP/IP 已连接，等待相机照片")
            },
            onStatus = { text ->
                RuntimeState.ptpStatus.value = text
                RuntimeState.statusText.value = text
                updateNotification(text)
            },
            onError = { text ->
                RuntimeState.ptpRunning.value = false
                RuntimeState.ptpConnected.value = false
                RuntimeState.lastError.value = text
                RuntimeState.ptpStatus.value = text
                RuntimeState.statusText.value = text
                updateNotification(text)
            }
        )
        ptpImporter = importer
        importer.start()
    }

    private fun startConsumer() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (file in queue) {
                try {
                    runCatching { waitUntilStable(file) }
                    processFile(file)
                } catch (e: Exception) {
                    RuntimeState.lastError.value = e.message ?: "处理失败"
                } finally {
                    inFlight.remove(file.absolutePath)
                    RuntimeState.queued.update { (it - 1).coerceAtLeast(0) }
                }
            }
        }
    }

    private fun startFtp(port: Int, user: String, password: String) {
        ptpImporter?.stop()
        ptpImporter = null
        RuntimeState.ptpRunning.value = false
        RuntimeState.ptpConnected.value = false
        RuntimeState.ptpStatus.value = "FTP 备用模式"
        scope.launch {
            try {
                val ip = NetworkUtils.findLocalIpv4()
                val manager = FtpServerManager(
                    root = incomingDir,
                    userName = user,
                    password = password,
                    port = port,
                    passiveAddress = ip,
                    onUploadFinished = { file ->
                        RuntimeState.noteCameraSeen()
                        scope.launch {
                            delay(700)
                            enqueue(file)
                        }
                    },
                    onConnectionChanged = { count ->
                        RuntimeState.cameraClients.value = count
                        RuntimeState.cameraConnected.value = count > 0
                        if (count > 0) RuntimeState.noteCameraSeen()
                    }
                )
                manager.start()
                ftpServer = manager
                RuntimeState.running.value = true
                RuntimeState.ftpReady.value = true
                RuntimeState.address.value = ip
                RuntimeState.port.value = port
                RuntimeState.lastError.value = null
                RuntimeState.statusText.value = "等待 A7C II 上传照片"
                updateNotification(
                    if (ip != null) "$ip:$port · 等待相机上传" else "已启动，但未找到局域网 IP"
                )
            } catch (e: Exception) {
                RuntimeState.running.value = true
                RuntimeState.ftpReady.value = false
                RuntimeState.cameraConnected.value = false
                RuntimeState.cameraClients.value = 0
                RuntimeState.lastError.value = "相机接收服务启动失败，请重试"
                RuntimeState.statusText.value = "相机接收服务暂不可用"
                updateNotification("相机接收服务启动失败，请重试")
            }
        }
    }

    private fun startGalleryWatcher(sonyOnly: Boolean) {
        if (galleryWatcher != null) return
        val watcher = GalleryWatcher(
            context = this,
            incomingDir = incomingDir,
            sonyOnly = sonyOnly,
            scope = scope,
            onNewPhoto = { file -> enqueue(file) }
        )
        watcher.start()
        galleryWatcher = watcher
    }

    private suspend fun scanIncoming() {
        val files = incomingDir.walkTopDown()
            .filter { it.isFile && FtpServerManager.isImage(it.name) && it.length() > 0 }
            .sortedBy { it.lastModified() }
            .toList()
        for (file in files) enqueue(file)
    }

    private fun enqueue(file: File) {
        if (!file.exists() || !FtpServerManager.isImage(file.name)) return
        if (!inFlight.add(file.absolutePath)) return
        RuntimeState.queued.update { it + 1 }
        val sent = queue.trySend(file).isSuccess
        if (!sent) {
            inFlight.remove(file.absolutePath)
            RuntimeState.queued.update { (it - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun waitUntilStable(file: File) {
        if (!file.exists()) return
        var previous = -1L
        repeat(30) {
            val length = file.length()
            if (length > 0 && length == previous) return
            previous = length
            delay(200)
        }
    }

    private fun processFile(file: File) {
        if (!file.exists()) return
        val settings = settingsStore.get()
        val lutInfo = lutStore.get(settings.selectedLutId)
        val lut = lutInfo?.let { lutStore.load(it.id) }
        if (lutInfo == null || lut == null) {
            RuntimeState.statusText.value = "已收到 ${file.name}，请先选择 LUT"
            RuntimeState.lastError.value = "尚未选择 LUT，照片已保留在收件箱"
            updateNotification("已收到 ${file.name} · 请先在 App 内选择 LUT")
            return
        }

        var shot = Shot(
            fileName = file.name,
            originalPath = file.absolutePath,
            lutName = lutInfo.name,
            lutId = lutInfo.id,
            strength = settings.lutStrength,
            status = "processing",
            source = if (file.name.startsWith("gallery_")) "gallery" else "ftp"
        )
        shotStore.add(shot)
        RuntimeState.emitShot(shot)
        RuntimeState.bumpHistory()
        RuntimeState.statusText.value = "正在套用 ${lutInfo.name}"
        updateNotification("正在套用 ${lutInfo.name} · ${file.name}")

        val temp = File(cacheDir, "lutbeta_${System.currentTimeMillis()}.jpg")
        try {
            val result = ImageProcessor.process(
                input = file,
                output = temp,
                lut = lut,
                strength = settings.lutStrength,
                // Auto import is also a final export: never downscale, never
                // use the 720px editor preview. The preview is only for UI.
                maxDimension = 0,
                quality = maxOf(settings.quality, 95)
            )
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val displayName = "${file.nameWithoutExtension}_LUT_$stamp.jpg"
            val takenAt = readCaptureTime(file)
            val uri = MediaStoreSaver.saveJpeg(this, result.file, displayName, dateTakenMillis = takenAt)
                ?: throw IllegalStateException("写入系统相册失败")

            if (settings.keepOriginal) {
                MediaStoreSaver.saveJpeg(
                    this, file, file.name, MediaStoreSaver.ORIGINAL_ALBUM, takenAt
                )
            }

            val originalsDir = File(filesDir, "originals").apply { mkdirs() }
            val original = uniqueFile(originalsDir, file.name)
            if (!file.renameTo(original)) {
                file.copyTo(original, overwrite = true)
                file.delete()
            }

            shot = shot.copy(
                outputUri = uri.toString(),
                originalPath = original.absolutePath,
                width = result.width,
                height = result.height,
                status = "done",
                error = null
            )
            shotStore.update(shot)
            RuntimeState.emitShot(shot)
            RuntimeState.bumpHistory()
            RuntimeState.processed.update { it + 1 }
            RuntimeState.lastError.value = null
            RuntimeState.statusText.value = "已保存 ${file.nameWithoutExtension}（${lutInfo.name}）"
            updateNotification("已保存 $displayName · ${result.width}×${result.height}")
        } catch (e: Exception) {
            val failedDir = File(filesDir, "failed").apply { mkdirs() }
            val failed = uniqueFile(failedDir, file.name)
            runCatching {
                if (!file.renameTo(failed)) {
                    file.copyTo(failed, overwrite = true)
                    file.delete()
                }
            }
            val failedShot = shot.copy(
                status = "error",
                error = e.message ?: "处理失败",
                originalPath = failed.absolutePath
            )
            shotStore.update(failedShot)
            RuntimeState.emitShot(failedShot)
            RuntimeState.bumpHistory()
            RuntimeState.lastError.value = e.message ?: "处理失败"
            RuntimeState.statusText.value = "处理失败：${e.message ?: "未知错误"}"
            updateNotification("处理失败：${e.message ?: "未知错误"}")
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun readCaptureTime(file: File): Long? {
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: return null
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "_$index" else "_$index.$ext"
            candidate = File(dir, "$base$suffix")
            index++
        }
        return candidate
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LUTBeta::import").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LUTBeta::wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun updateNotification(text: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ImportService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lut)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFFD7FF63.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        started = false
        ptpImporter?.stop()
        ptpImporter = null
        runCatching { ftpServer?.stop() }
        ftpServer = null
        galleryWatcher?.stop()
        galleryWatcher = null
        scope.cancel()
        queue.close()
        releaseLocks()
        RuntimeState.running.value = false
        RuntimeState.ftpReady.value = false
        RuntimeState.cameraConnected.value = false
        RuntimeState.cameraClients.value = 0
        RuntimeState.lastCameraSeen.value = 0L
        RuntimeState.ptpRunning.value = false
        RuntimeState.ptpConnected.value = false
        RuntimeState.ptpStatus.value = "已停止"
        RuntimeState.statusText.value = "已停止"
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "camera_import"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.lutbeta.app.action.START"
        const val ACTION_STOP = "com.lutbeta.app.action.STOP"
        const val ACTION_RETRY = "com.lutbeta.app.action.RETRY"
        const val ACTION_START_PTP = "com.lutbeta.app.action.START_PTP"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_CAMERA_MODEL = "camera_model"

        fun start(context: Context) {
            val intent = Intent(context, ImportService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startPtp(context: Context, cameraId: String, model: String?) {
            val intent = Intent(context, ImportService::class.java).apply {
                action = ACTION_START_PTP
                putExtra(EXTRA_CAMERA_ID, cameraId)
                putExtra(EXTRA_CAMERA_MODEL, model)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImportService::class.java))
        }

        fun retryPending(context: Context) {
            val intent = Intent(context, ImportService::class.java).setAction(ACTION_RETRY)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
