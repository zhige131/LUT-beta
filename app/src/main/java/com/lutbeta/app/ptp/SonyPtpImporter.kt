package com.lutbeta.app.ptp

import android.content.Context
import android.net.Network
import com.lutbeta.app.wifi.CameraWifiConnector
import com.lutbeta.app.wifi.CameraWifiSession
import java.io.File
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Downloads photos from the camera using PTP/IP.
 *
 * The camera is in Remote Shoot / Wi-Fi Direct mode. GetStorageIDs may return
 * 0x2013 StoreNotAvailable while the camera has no exposed store yet. That is
 * handled as a wait state, not as a fake success: the importer listens for the
 * standard ObjectAdded event (0x4002), and also retries GetObjectHandles with
 * the PTP "all stores" id 0xFFFFFFFF. If neither works, the real response code
 * is reported and no placeholder photo is created.
 */
class SonyPtpImporter(
    context: Context,
    private val incomingDir: File,
    private val cameraId: String,
    private val model: String?,
    private val onFile: (File) -> Unit,
    private val onConnected: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("lut_beta_ptp", Context.MODE_PRIVATE)
    private val seenKey = "seen_${cameraId.ifBlank { "unknown" }}"
    private val seen = Collections.synchronizedSet(
        prefs.getStringSet(seenKey, emptySet())?.toMutableSet() ?: mutableSetOf()
    )
    private val queued = Collections.synchronizedSet(mutableSetOf<Long>())
    private val pending = Channel<Long>(Channel.UNLIMITED)

    @Volatile
    private var client: PtpIpClient? = null

    @Volatile
    private var stopped = false

    private var job: Job? = null
    private var storageIds: LongArray = LongArray(0)
    private var reportedStoreNotAvailable = false

    fun start() {
        if (job != null) return
        stopped = false
        job = scope.launch { runImporter() }
    }

    fun stop() {
        stopped = true
        runCatching { client?.close() }
        client = null
        runCatching { pending.close() }
        job?.cancel()
        job = null
        scope.cancel()
    }

    private suspend fun runImporter() {
        try {
            onStatus("正在连接相机 PTP/IP…")
            val connected = connect()
            if (connected == null) {
                val ssid = CameraWifiSession.ssid ?: "相机 Wi-Fi"
                onError(
                    "没有连上相机 PTP/IP。请确认相机已开启“遥控拍摄”，手机已加入 $ssid，且相机 IP 可访问。"
                )
                return
            }
            client = connected
            // Standard OpenSession is Remote Control Mode. For downloading
            // stills, Sony requires SDIO_OpenSession(FunctionMode=1) +
            // SDIO authentication + SetContentsTransferMode. This is the
            // documented fix for 0x1004/0x2013 on content transfer.
            connected.sdioOpenSession()
            connected.sdioAuthenticate()
            connected.setContentsTransferMode(true)
            delay(250)
            onConnected()
            onStatus("PTP/IP Content Transfer Mode 已开启，正在读取相机存储…")

            // Some cameras still need a moment after StoreAdded; GetStorageIDs
            // is retried by the poll loop if the first response is 0x2013.
            refreshStorageIds(connected, initial = true)

            try {
                coroutineScope {
                    launch { downloadLoop(connected) }
                    launch { pollLoop(connected) }
                }
            } finally {
                runCatching { connected.setContentsTransferMode(false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PtpIpClient.PtpException) {
            val code = e.responseCode?.let { "0x${it.toString(16).uppercase()}" } ?: ""
            onError(e.message ?: "PTP/IP 错误 $code")
        } catch (e: Exception) {
            if (!stopped) onError("PTP/IP 导入失败：${e.message ?: "未知错误"}")
        } finally {
            runCatching { client?.close() }
            client = null
        }
    }

    private suspend fun connect(): PtpIpClient? {
        val network = CameraWifiSession.network
        val hosts = CameraWifiConnector.hostCandidates()
        var lastError: String? = null
        for (host in hosts) {
            if (stopped) return null
            val candidate = PtpIpClient(
                host = host,
                network = network,
                guidSeed = "lutbeta-android:$cameraId:${model ?: "ILCE-7CM2"}",
                onEvent = { event ->
                    if (event.code == PtpIpClient.EVENT_OBJECT_ADDED && event.params.isNotEmpty()) {
                        val handle = event.params[0]
                        if (handle != 0L) {
                            if (queued.add(handle)) {
                                pending.trySend(handle)
                                onStatus("相机有新照片（PTP 对象 $handle），正在下载…")
                            }
                        }
                    }
                },
                onLog = { /* protocol detail is not shown as fake UI state */ }
            )
            val ok = withTimeoutOrNull(6_000) {
                runCatching {
                    candidate.open(5_000)
                    candidate
                }.getOrElse {
                    lastError = it.message
                    runCatching { candidate.close() }
                    null
                }
            }
            if (ok != null) return ok
        }
        if (lastError != null) onStatus("PTP/IP 连接失败：$lastError")
        return null
    }

    private suspend fun refreshStorageIds(active: PtpIpClient, initial: Boolean) {
        var last: PtpIpClient.PtpException? = null
        for (attempt in 0 until 4) {
            try {
                val ids = active.getStorageIds()
                if (ids.isNotEmpty()) {
                    storageIds = ids
                    reportedStoreNotAvailable = false
                    onStatus("相机存储已就绪，正在读取照片列表…")
                    enqueueHandles(active, ids)
                    return
                }
                onStatus("相机返回空存储列表，等待拍照事件…")
                tryAllStores(active)
                return
            } catch (e: PtpIpClient.PtpException) {
                last = e
                if (e.responseCode != PtpIpClient.RC_STORE_NOT_AVAILABLE) break
                if (attempt < 3) delay(300)
            }
        }
        val e = last
        if (e?.responseCode == PtpIpClient.RC_STORE_NOT_AVAILABLE) {
            if (!reportedStoreNotAvailable) {
                reportedStoreNotAvailable = true
                onStatus(
                    "GetStorageIDs 返回 0x2013 StoreNotAvailable：" +
                        "Content Transfer 已开启，但相机暂未暴露存储；等待 StoreAdded/ObjectAdded 事件。"
                )
            }
            tryAllStores(active)
        } else if (e != null) {
            onStatus("GetStorageIDs 失败：${e.message}")
            if (initial) {
                // Do not stop the whole import: ObjectAdded may still work.
                onStatus("仍会尝试监听相机的新照片事件…")
            }
        }
    }

    private fun tryAllStores(active: PtpIpClient) {
        try {
            val handles = active.getObjectHandles(PtpIpClient.ALL_STORAGE_ID)
            if (handles.isNotEmpty()) {
                onStatus("通过全存储 ID 找到 ${handles.size} 张照片")
                enqueueHandles(active, handles)
            }
        } catch (e: PtpIpClient.PtpException) {
            if (e.responseCode != PtpIpClient.RC_STORE_NOT_AVAILABLE) {
                onStatus("全存储查询失败：${e.message}")
            }
        }
    }

    private fun enqueueHandles(active: PtpIpClient, handles: LongArray) {
        for (handle in handles) {
            if (stopped) return
            if (seen.contains(handle.toString())) continue
            if (queued.add(handle)) {
                pending.trySend(handle)
            }
        }
    }

    private suspend fun downloadLoop(active: PtpIpClient) {
        for (handle in pending) {
            if (stopped) return
            try {
                downloadOne(active, handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PtpIpClient.PtpException) {
                queued.remove(handle)
                onStatus("对象 $handle 下载失败：${e.message}")
            } catch (e: Exception) {
                queued.remove(handle)
                onStatus("对象 $handle 下载失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun pollLoop(active: PtpIpClient) {
        while (scope.isActive && !stopped && active.isOpen) {
            delay(2_000)
            if (stopped) return
            try {
                val ids = if (storageIds.isNotEmpty()) storageIds else longArrayOf(PtpIpClient.ALL_STORAGE_ID)
                for (id in ids) {
                    val handles = active.getObjectHandles(id)
                    enqueueHandles(active, handles)
                }
            } catch (e: PtpIpClient.PtpException) {
                // 0x2013 is the expected "not exposed yet" state; do not spam
                // the UI with it on every poll.
                if (e.responseCode != PtpIpClient.RC_STORE_NOT_AVAILABLE) {
                    onStatus("轮询相机照片失败：${e.message}")
                }
            } catch (e: Exception) {
                if (!stopped) onStatus("PTP/IP 连接中断：${e.message ?: "未知错误"}")
                return
            }
        }
    }

    private suspend fun downloadOne(active: PtpIpClient, handle: Long) {
        val info = active.getObjectInfo(handle)
        val name = info.fileName.ifBlank { "PTP_$handle.jpg" }
        if (!isSupportedImage(name, info.format)) {
            queued.remove(handle)
            seen.add(handle.toString())
            persistSeen()
            onStatus("跳过非 JPEG/HEIF 对象：$name")
            return
        }
        if (info.size <= 0L) {
            queued.remove(handle)
            return
        }
        val data = active.getObject(handle)
        if (data.isEmpty()) {
            queued.remove(handle)
            onStatus("对象 $handle 下载为空，已跳过")
            return
        }
        incomingDir.mkdirs()
        val extension = name.substringAfterLast('.', "jpg").lowercase(Locale.US)
        val safeBase = name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(80)
            .ifBlank { "PTP_$handle" }
        val target = uniqueFile(incomingDir, "${safeBase}_$handle.$extension")
        val temp = File(incomingDir, ".${target.name}.part")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        seen.add(handle.toString())
        queued.remove(handle)
        persistSeen()
        onStatus("已下载 $name（${data.size / 1024} KB）")
        onFile(target)
    }

    private fun isSupportedImage(name: String, format: Int): Boolean {
        val lower = name.lowercase(Locale.US)
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".hif")
        ) return true
        // PTP JPEG object format. HEIF formats vary by camera, so the extension
        // check above is the safer signal for those.
        return format == 0x3801
    }

    private fun persistSeen() {
        val snapshot = synchronized(seen) {
            if (seen.size <= 2000) seen.toSet() else seen.toList().takeLast(2000).toSet()
        }
        prefs.edit().putStringSet(seenKey, snapshot).apply()
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(
                dir,
                if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext"
            )
            index++
        }
        return candidate
    }
}
