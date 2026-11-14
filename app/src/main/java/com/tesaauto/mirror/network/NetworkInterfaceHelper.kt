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
     * Localiza la dirección IP de la interfaz de Hotspot (swlan0, ap0, wlan0) o Wi-Fi local.
     * Excluye estrictamente interfaces celulares (rmnet, pdp) para evitar exponer la IP pública de datos móviles.
     */
    fun getHotspotOrWifiIpAddress(): InterfaceInfo? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // 1. Máxima Prioridad: Interfaz con rango clásico de Hotspot Android (192.168.43.x) o ap/softap
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    val ip = getIpv4Address(iface)
                    if (ip != null && ip.startsWith("192.168.43.")) {
                        return InterfaceInfo(
                            name = iface.name,
                            ipAddress = ip,
                            isHotspotInterface = true
                        )
                    }
                }
            }

            // 2. Prioridad: Interfaces específicas de punto de acceso (ap0, softap)
            val dedicatedAp = interfaces.firstOrNull { 
                it.isUp && !it.isLoopback && (it.name.startsWith("ap", ignoreCase = true) || it.name.startsWith("softap", ignoreCase = true))
            }
            if (dedicatedAp != null) {
                val ip = getIpv4Address(dedicatedAp)
                if (ip != null && isPrivateIp(ip)) {
                    return InterfaceInfo(
                        name = dedicatedAp.name,
                        ipAddress = ip,
                        isHotspotInterface = true
                    )
                }
            }

            // 3. Prioridad: Interfaz cliente Wi-Fi estándar (wlan0, wlan1) para pruebas locales en red doméstica
            val wlanCandidate = interfaces.firstOrNull { 
                it.isUp && !it.isLoopback && it.name.startsWith("wlan", ignoreCase = true)
            }
            if (wlanCandidate != null) {
                val ip = getIpv4Address(wlanCandidate)
                if (ip != null && isPrivateIp(ip)) {
                    return InterfaceInfo(
                        name = wlanCandidate.name,
                        ipAddress = ip,
                        isHotspotInterface = false
                    )
                }
            }

            // 4. Prioridad: Otras interfaces Wi-Fi P2P / Hotspot Samsung (swlan)
            val swlanCandidate = interfaces.firstOrNull {
                it.isUp && !it.isLoopback && it.name.startsWith("swlan", ignoreCase = true)
            }
            if (swlanCandidate != null) {
                val ip = getIpv4Address(swlanCandidate)
                if (ip != null && isPrivateIp(ip)) {
                    return InterfaceInfo(
                        name = swlanCandidate.name,
                        ipAddress = ip,
                        isHotspotInterface = true
                    )
                }
            }
                    )
                }
            }

            // 4. Fallback: Cualquier interfaz privada que NO sea celular ni loopback
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback && !isCellularOrVirtual(iface.name)) {
                    val ip = getIpv4Address(iface)
                    if (ip != null && isPrivateIp(ip)) {
                        return InterfaceInfo(
                            name = iface.name,
                            ipAddress = ip,
                            isHotspotInterface = false
                        )
                    }
                }
            }

            // 5. Fallback final si no se detectó IP
            return InterfaceInfo(
                name = "hotspot_default",
                ipAddress = "192.168.43.1",
                isHotspotInterface = true
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return InterfaceInfo(name = "fallback", ipAddress = "192.168.43.1", isHotspotInterface = true)
    }

    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
    }

    private fun isCellularOrVirtual(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") || lower.startsWith("ccmni") || 
               lower.startsWith("pdp") || lower.startsWith("dummy") || 
               lower.startsWith("tun") || lower.startsWith("sit") || 
               lower.startsWith("gre") || lower.startsWith("epdg")
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
