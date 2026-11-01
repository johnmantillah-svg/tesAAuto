package com.tesaauto.mirror.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkInterfaceHelper {

    data class InterfaceInfo(
        val name: String,
        val ipAddress: String,
        val isHotspotInterface: Boolean
    )

    /**
     * Localiza la dirección IP de la interfaz wlan0 (punto de acceso/hotspot)
     * o, en su defecto, la primera interfaz de red inalámbrica activa con dirección IPv4.
     */
    fun getHotspotOrWifiIpAddress(): InterfaceInfo? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // 1. Prioridad: Interfaz wlan0 específica de hotspot / Wi-Fi local
            val wlanInterface = interfaces.firstOrNull { 
                it.isUp && !it.isLoopback && (it.name.equals("wlan0", ignoreCase = true) || it.name.startsWith("ap0"))
            }

            if (wlanInterface != null) {
                val ip = getIpv4Address(wlanInterface)
                if (ip != null) {
                    return InterfaceInfo(
                        name = wlanInterface.name,
                        ipAddress = ip,
                        isHotspotInterface = true
                    )
                }
            }

            // 2. Fallback: cualquier interfaz no loopback activa con IPv4
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    val ip = getIpv4Address(iface)
                    if (ip != null) {
                        return InterfaceInfo(
                            name = iface.name,
                            ipAddress = ip,
                            isHotspotInterface = iface.name.contains("wlan") || iface.name.contains("ap")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getIpv4Address(networkInterface: NetworkInterface): String? {
        val addresses = Collections.list(networkInterface.inetAddresses)
        for (address in addresses) {
            if (!address.isLoopbackAddress && address is Inet4Address) {
                return address.hostAddress
            }
        }
        return null
    }
}
