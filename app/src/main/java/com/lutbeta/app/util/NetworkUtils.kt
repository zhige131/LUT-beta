package com.lutbeta.app.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Returns the most likely LAN/hotspot IPv4 address for the phone. */
    fun findLocalIpv4(): String? = allLocalIpv4().firstOrNull()

    fun allLocalIpv4(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name.lowercase()
                val nameScore = when {
                    name.startsWith("ap") || name.contains("swlan") || name.contains("softap") -> 0
                    name.startsWith("wlan") -> 1
                    name.startsWith("eth") -> 2
                    else -> 3
                }
                for (address in networkInterface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (!address.isSiteLocalAddress) continue
                        val ipScore = when {
                            host == "192.168.43.1" -> 0
                            host.startsWith("192.168.") -> 1
                            host.startsWith("10.") -> 2
                            else -> 3
                        }
                        candidates.add((nameScore * 10 + ipScore) to host)
                    }
                }
            }
        }
        return candidates.sortedBy { it.first }.map { it.second }.distinct()
    }
}
