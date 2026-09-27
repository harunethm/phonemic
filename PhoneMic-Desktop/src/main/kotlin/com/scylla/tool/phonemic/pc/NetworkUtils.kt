package com.scylla.tool.phonemic.pc

import java.net.Inet4Address
import java.net.NetworkInterface

/** Best-effort local WiFi/LAN IPv4 address, for display in the pairing QR/code. */
object NetworkUtils {
    fun detectLocalIPv4(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .toList()
            // Prefer a private/LAN address (192.168.x, 10.x, 172.16-31.x) over anything
            // else that happens to be up (e.g. a Docker/VPN virtual adapter).
            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
