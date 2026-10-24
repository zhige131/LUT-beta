package com.lutbeta.app.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Holds the one camera Wi-Fi network currently requested by the app. */
object CameraWifiSession {
    @Volatile
    var network: Network? = null
        private set

    @Volatile
    var ssid: String? = null
        private set

    @Volatile
    var cameraHost: String? = null
        private set

    fun update(network: Network?, ssid: String?, host: String?) {
        this.network = network
        this.ssid = ssid
        this.cameraHost = host
    }

    fun clear() {
        network = null
        ssid = null
        cameraHost = null
    }
}

/**
 * Android will show its own Wi-Fi approval dialog for WifiNetworkSpecifier.
 * We never bypass that dialog and never use the deprecated addNetwork() path.
 */
object CameraWifiConnector {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var manager: ConnectivityManager? = null

    data class Result(val ok: Boolean, val message: String, val host: String? = null)

    fun connect(
        context: Context,
        ssid: String,
        password: String,
        onResult: (Result) -> Unit
    ) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        disconnect(app, notify = false)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid.trim())
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            private var delivered = false

            private fun deliver(result: Result) {
                if (delivered) return
                delivered = true
                mainHandler.post { onResult(result) }
            }

            override fun onAvailable(network: Network) {
                val host = findCameraHost(cm, network)
                CameraWifiSession.update(network, ssid.trim(), host)
                deliver(Result(true, "已连接 $ssid", host))
            }

            override fun onUnavailable() {
                CameraWifiSession.clear()
                deliver(Result(false, "系统未批准这次 Wi-Fi 连接，或相机热点不可用"))
            }

            override fun onLost(network: Network) {
                if (CameraWifiSession.network == network) {
                    CameraWifiSession.clear()
                    deliver(Result(false, "相机 Wi-Fi 已断开"))
                }
            }
        }

        callback = cb
        manager = cm
        runCatching {
            cm.requestNetwork(request, cb, 30_000)
        }.onFailure {
            callback = null
            manager = null
            CameraWifiSession.clear()
            mainHandler.post { onResult(Result(false, it.message ?: "无法发起 Wi-Fi 连接")) }
        }
    }

    fun disconnect(context: Context, notify: Boolean = true) {
        val cm = manager ?: (context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        manager = null
        CameraWifiSession.clear()
        if (notify) {
            // No UI callback here; callers update their own status.
        }
    }

    private fun findCameraHost(cm: ConnectivityManager, network: Network): String? {
        val link: LinkProperties? = cm.getLinkProperties(network)
        if (link != null) {
            val gateway = link.routes
                .firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
            if (!gateway.isNullOrBlank()) return gateway
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dhcp = link.dhcpServerAddress?.hostAddress
                if (!dhcp.isNullOrBlank()) return dhcp
            }
            // Wi-Fi Direct often has no default route, but the camera is the
            // DHCP server and normally .1 in the phone's /24.
            val local = link.linkAddresses.firstOrNull()?.address?.hostAddress
            if (!local.isNullOrBlank()) {
                val prefix = local.substringBeforeLast('.', "")
                if (prefix.isNotBlank()) return "$prefix.1"
            }
        }
        // Sony Wi-Fi Direct commonly uses this address. It is only a fallback;
        // the PTP client will also try the gateway and the other candidates.
        return "192.168.122.1"
    }

    fun hostCandidates(): List<String> {
        val result = linkedSetOf<String>()
        CameraWifiSession.cameraHost?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        result.add("192.168.122.1")
        result.add("192.168.49.1")
        result.add("192.168.0.1")
        result.add("10.0.0.1")
        return result.toList()
    }
}
