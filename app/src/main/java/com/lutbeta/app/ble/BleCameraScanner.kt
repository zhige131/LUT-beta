package com.lutbeta.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Small, honest BLE helper: discover nearby cameras and ask Android to pair.
 * It does not invent a Sony GATT protocol. If the camera requires a Sony-only
 * BLE service after pairing, that is reported as a limitation instead of
 * pretending the pairing succeeded.
 */
class BleCameraScanner(private val context: Context) {

    data class BleCamera(
        val name: String,
        val address: String,
        val rssi: Int,
        val bonded: Boolean
    )

    private val manager =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var scanCallback: ScanCallback? = null
    private var onFound: ((BleCamera) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val seen = linkedMapOf<String, BleCamera>()

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun start(onFound: (BleCamera) -> Unit, onError: (String) -> Unit) {
        stop()
        this.onFound = onFound
        this.onError = onError
        seen.clear()
        val a = adapter
        if (a == null) {
            onError("这台手机没有可用的蓝牙适配器")
            return
        }
        if (!a.isEnabled) {
            onError("请先在系统设置中打开蓝牙")
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            onError("蓝牙 LE 扫描不可用")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: return
                if (name.isBlank()) return
                // Keep the list focused on cameras, but still show the model
                // name Sony advertises (ILCE-7CM2 / A7C II / Sony).
                val interesting = name.contains("ILCE", ignoreCase = true) ||
                    name.contains("A7C", ignoreCase = true) ||
                    name.contains("Sony", ignoreCase = true)
                if (!interesting) return
                val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
                    .getOrDefault(false)
                val item = BleCamera(name, device.address, result.rssi, bonded)
                val previous = seen[device.address]
                if (previous == null || previous.rssi < item.rssi || previous.bonded != item.bonded) {
                    seen[device.address] = item
                    onFound(item)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onError("蓝牙扫描失败（code=$errorCode）")
            }
        }
        scanCallback = callback
        runCatching {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
        }.onFailure {
            scanCallback = null
            onError("无法启动蓝牙扫描：${it.message}")
        }
    }

    fun stop() {
        val cb = scanCallback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanCallback = null
    }

    fun pair(deviceAddress: String, onResult: (Boolean, String) -> Unit) {
        val a = adapter
        if (a == null) {
            onResult(false, "没有蓝牙适配器")
            return
        }
        val device = runCatching { a.getRemoteDevice(deviceAddress) }.getOrNull()
        if (device == null) {
            onResult(false, "找不到这个蓝牙设备")
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            onResult(true, "已经配对")
            return
        }

        var receiver: BroadcastReceiver? = null
        fun finish(ok: Boolean, text: String) {
            runCatching { receiver?.let { context.unregisterReceiver(it) } }
            receiver = null
            onResult(ok, text)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                when (state) {
                    BluetoothDevice.BOND_BONDED -> finish(true, "蓝牙配对已完成")
                    BluetoothDevice.BOND_NONE -> finish(false, "蓝牙配对未完成，请重试")
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure {
            onResult(false, "无法监听蓝牙配对状态：${it.message}")
            return
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        if (!started) {
            finish(false, "系统没有发起配对，请确认相机处于蓝牙配对模式")
        }
    }
}
