package com.tesaauto.mirror.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

class TeslaVpnService : VpnService() {

    companion object {
        const val TAG = "TeslaVpnService"
        const val VPN_IP = "51.75.29.16" // taada.top IP
        const val VPN_IP_FALLBACK = "7.7.7.7"
        const val VPN_PREFIX = 32
        const val ACTION_START = "com.tesaauto.mirror.vpn.START"
        const val ACTION_STOP = "com.tesaauto.mirror.vpn.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                startVpn()
                return START_STICKY
            }
        }
    }

    private fun startVpn() {
        if (isRunning) return
        try {
            val builder = Builder()
                .setSession("TesAAuto")
                .addAddress(VPN_IP, VPN_PREFIX)
                .addRoute(VPN_IP, 32)
                .addAddress(VPN_IP_FALLBACK, 32)
                .addRoute(VPN_IP_FALLBACK, 32)
                .addDisallowedApplication(packageName)
                .setMtu(1500)
                .setBlocking(false)

            vpnInterface = builder.establish()
            isRunning = true
            Log.i(TAG, "✅ TeslaVpnService establecido con IP virtual $VPN_IP (taada.top) y $VPN_IP_FALLBACK")

            // Hilo ligero para drenar el buffer de entrada del túnel y evitar acumulación
            vpnThread = Thread({
                val pfd = vpnInterface ?: return@Thread
                val fis = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32768)
                try {
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        val read = fis.read(buffer)
                        if (read <= 0) {
                            Thread.sleep(50)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Hebra de lectura VPN terminada: ${e.message}")
                }
            }, "TeslaVpn-Thread").apply {
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando TeslaVpnService: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {}
        vpnInterface = null
        stopSelf()
        Log.i(TAG, "TeslaVpnService detenido")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
