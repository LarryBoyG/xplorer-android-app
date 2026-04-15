package com.example.xirolite

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress

data class DroneNetworkInfo(
    val localIp: String?,
    val gatewayIp: String?,
    val guessedDroneIp: String?,
    val wifiSignalText: String,
    val notes: List<String>
)

class DroneNetworkDetector(
    private val context: Context
) {
    fun detect(): DroneNetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return DroneNetworkInfo(null, null, null, "Unknown", listOf("No active network"))

        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return DroneNetworkInfo(null, null, null, "Unknown", listOf("Not on Wi-Fi"))
        }

        val lp = cm.getLinkProperties(network)
            ?: return DroneNetworkInfo(null, null, null, "Unknown", listOf("No LinkProperties"))

        val localIpv4 = lp.linkAddresses
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .firstOrNull()

        val gatewayIpv4 = lp.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway as? Inet4Address

        val localIp = localIpv4?.hostAddress
        val gatewayIp = gatewayIpv4?.hostAddress

        val guessed = gatewayIp ?: guessFromLocal(localIpv4)
        val wifiSignalText = detectWifiSignalText()

        return DroneNetworkInfo(
            localIp = localIp,
            gatewayIp = gatewayIp,
            guessedDroneIp = guessed,
            wifiSignalText = wifiSignalText,
            notes = listOf(
                "Local: $localIp",
                "Gateway: $gatewayIp",
                "Guess: $guessed",
                "Wi-Fi Signal: $wifiSignalText"
            )
        )
    }

    private fun detectWifiSignalText(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "Unknown"
        val rssi = runCatching { wifiManager.connectionInfo.rssi }.getOrNull()
            ?: return "Unknown"
        if (rssi <= -127) return "Unknown"
        return when {
            rssi >= -55 -> "Excellent"
            rssi >= -65 -> "Strong"
            rssi >= -72 -> "Good"
            rssi >= -80 -> "Fair"
            else -> "Weak"
        }
    }

    private fun guessFromLocal(local: Inet4Address?): String? {
        if (local == null) return null
        val bytes = local.address.clone()

        val firstOctet = bytes[0].toInt() and 0xFF
        val candidates = if (firstOctet == 10) {
            listOf(200, 254, 1, 21, 3, 100)
        } else {
            listOf(254, 1, 21, 3, 200, 100)
        }

        for (c in candidates) {
            bytes[3] = c.toByte()
            try {
                return InetAddress.getByAddress(bytes).hostAddress
            } catch (_: Exception) {}
        }
        return null
    }
}
