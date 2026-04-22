package com.example.xirolite

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import javax.net.SocketFactory

data class WifiRouteSelection(
    val network: Network,
    val socketFactory: SocketFactory,
    val localIp: String?,
    val gatewayIp: String?,
    val summary: String
)

object WifiRouteSelector {
    fun selectWifiNetwork(context: Context, targetHost: String?): WifiRouteSelection? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks

        return networks
            .mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null

                val linkProperties = cm.getLinkProperties(network)
                val localIpv4 = linkProperties
                    ?.linkAddresses
                    ?.map(LinkAddress::getAddress)
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull()
                    ?.hostAddress

                val gatewayIpv4 = linkProperties
                    ?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway
                    ?.hostAddress

                val score =
                    (if (network == activeNetwork) 40 else 0) +
                        (if (localIpv4 != null) 20 else 0) +
                        (if (targetHost != null && sameIpv4Prefix(localIpv4, targetHost)) 30 else 0) +
                        (if (looksLikeXiroWifi(localIpv4, gatewayIpv4, targetHost)) 10 else 0)

                ScoredWifiRoute(
                    score = score,
                    selection = WifiRouteSelection(
                        network = network,
                        socketFactory = network.socketFactory,
                        localIp = localIpv4,
                        gatewayIp = gatewayIpv4,
                        summary = "Wi-Fi route local=$localIpv4 gateway=$gatewayIpv4 target=$targetHost"
                    )
                )
            }
            .maxByOrNull { it.score }
            ?.selection
    }

    private fun sameIpv4Prefix(localIp: String?, targetHost: String): Boolean {
        val localParts = localIp?.split(".") ?: return false
        val targetParts = targetHost.split(".")
        if (localParts.size != 4 || targetParts.size != 4) return false
        return localParts[0] == targetParts[0] &&
            localParts[1] == targetParts[1] &&
            localParts[2] == targetParts[2]
    }

    private fun looksLikeXiroWifi(localIp: String?, gatewayIp: String?, targetHost: String?): Boolean {
        val knownPrefixes = listOf("192.168.1.", "192.168.42.", "10.0.0.")
        return listOfNotNull(localIp, gatewayIp, targetHost).any { value ->
            knownPrefixes.any { value.startsWith(it) }
        }
    }

    private data class ScoredWifiRoute(
        val score: Int,
        val selection: WifiRouteSelection
    )
}
