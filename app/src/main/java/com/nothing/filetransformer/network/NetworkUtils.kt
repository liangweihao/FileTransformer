package com.nothing.filetransformer.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Returns all site-local IPv4 addresses of this device.
     * These are addresses in the 10.x.x.x, 172.16-31.x.x, or 192.168.x.x ranges.
     */
    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.iterator()?.forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                networkInterface.inetAddresses.asSequence()
                    .filter { !it.isLoopbackAddress && it is Inet4Address }
                    .filter { it.isSiteLocalAddress }
                    .forEach { address ->
                        address.hostAddress?.let { result.add(it) }
                    }
            }
        } catch (_: Exception) {
            // NetworkInterface enumeration can fail in rare edge cases
        }
        return result
    }

    /**
     * Returns the first available site-local IPv4 address, or null if none found.
     */
    fun getPrimaryLocalIp(): String? = getLocalIpAddresses().firstOrNull()
}
