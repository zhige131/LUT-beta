package com.lutbeta.app.service

import com.lutbeta.app.core.Shot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object RuntimeState {
    val running = MutableStateFlow(false)
    val ftpReady = MutableStateFlow(false)
    val address = MutableStateFlow<String?>(null)
    val port = MutableStateFlow(2121)
    val processed = MutableStateFlow(0)
    val queued = MutableStateFlow(0)
    val statusText = MutableStateFlow("未启动")
    val lastError = MutableStateFlow<String?>(null)

    /** Number of live FTP control connections. Cameras usually keep this at 1 while connected. */
    val cameraClients = MutableStateFlow(0)
    val cameraConnected = MutableStateFlow(false)
    val lastCameraSeen = MutableStateFlow(0L)

    /** PTP/IP mode (camera Wi-Fi Direct) is a separate transport from FTP. */
    val ptpRunning = MutableStateFlow(false)
    val ptpConnected = MutableStateFlow(false)
    val ptpStatus = MutableStateFlow("未启动 PTP/IP")
    val ptpCameraId = MutableStateFlow<String?>(null)
    val ptpSsid = MutableStateFlow<String?>(null)
    val ptpDownloaded = MutableStateFlow(0)

    /** Bumped by the service whenever the shot history changes, so the UI can refresh immediately. */
    val historyVersion = MutableStateFlow(0)

    /** Direct add/update events: the UI upserts the card immediately, without re-reading storage. */
    val shotEvents = MutableSharedFlow<Shot>(extraBufferCapacity = 32)

    fun bumpHistory() {
        historyVersion.update { it + 1 }
    }

    fun emitShot(shot: Shot) {
        shotEvents.tryEmit(shot)
    }

    fun noteCameraSeen() {
        lastCameraSeen.value = System.currentTimeMillis()
    }
}
