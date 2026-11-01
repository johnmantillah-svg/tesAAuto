package com.tesaauto.mirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tesaauto.mirror.display.ShizukuManager
import com.tesaauto.mirror.display.VirtualDisplayManager
import com.tesaauto.mirror.input.TouchInputManager
import com.tesaauto.mirror.network.NetworkInterfaceHelper
import com.tesaauto.mirror.server.TeslaEmbeddedServer
import com.tesaauto.mirror.ui.MainActivity
import com.tesaauto.mirror.video.ThermalAdaptationManager
import com.tesaauto.mirror.video.VideoEncoderPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProjectionService : Service() {

    companion object {
        const val TAG = "ProjectionService"
        const val CHANNEL_ID = "tesaauto_projection_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.tesaauto.mirror.ACTION_START"
        const val ACTION_STOP = "com.tesaauto.mirror.ACTION_STOP"
    }

    private val binder = LocalBinder()
    private var embeddedServer: TeslaEmbeddedServer? = null
    var virtualDisplayManager: VirtualDisplayManager? = null
        private set
    var videoEncoderPipeline: VideoEncoderPipeline? = null
        private set
    private var thermalManager: ThermalAdaptationManager? = null
    private var touchInputManager: TouchInputManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isScreenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.i(TAG, "Pantalla física apagada detectada. Manteniendo VirtualDisplay despierto...")
                if (ShizukuManager.hasPermission()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        ShizukuManager.executeCommand("cmd window dismiss-keyguard")
                    }
                }
            }
        }
    }

    var currentServerIp: String = "0.0.0.0"
        private set

    inner class LocalBinder : Binder() {
        fun getService(): ProjectionService = this@ProjectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        virtualDisplayManager = VirtualDisplayManager(
            context = this,
            onDisplayCreated = { displayId ->
                Log.i(TAG, "VirtualDisplay asignado en servicio con ID=$displayId")
                if (ShizukuManager.hasPermission()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        ShizukuManager.executeCommand("cmd window dismiss-keyguard")
                    }
                }
            },
            onDisplayReleased = {
                Log.i(TAG, "VirtualDisplay liberado en servicio")
            }
        )
        touchInputManager = TouchInputManager(this, virtualDisplayManager!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjectionServer()
            ACTION_STOP -> stopProjectionServer()
        }
        return START_STICKY
    }

    private fun startProjectionServer() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TesAAuto:ProjectionWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock adquirido para mantener proyección con pantalla apagada")

            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TesAAuto:WifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TesAAuto:WifiLock")
                }.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock adquirido para prevenir latencia o desconexión con pantalla apagada")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo adquirir WifiLock: ${e.message}")
            }

            val iface = NetworkInterfaceHelper.getHotspotOrWifiIpAddress()
            currentServerIp = iface?.ipAddress ?: "0.0.0.0"

            if (!isScreenReceiverRegistered) {
                try {
                    registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
                    isScreenReceiverRegistered = true
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo registrar screenReceiver: ${e.message}")
                }
            }

            val notification = createNotification("Activo en http://$currentServerIp:8080 y https://$currentServerIp:8443")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // 1. Configurar optimizaciones globales multi-display vía Shizuku si está disponible
            if (ShizukuManager.hasPermission()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ShizukuManager.configureMultiDisplaySupport()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error configurando multi-display Shizuku", e)
                    }
                }
            }

            // 2. Inicializar el pipeline de codificación por hardware MediaCodec
            try {
                videoEncoderPipeline = VideoEncoderPipeline(
                    onFrameEncoded = { frameData, _ ->
                        embeddedServer?.broadcastVideoFrame(frameData)
                    }
                )
                val encoderInputSurface = videoEncoderPipeline!!.start()

                // 3. Vincular el VirtualDisplay secundario a la superficie de MediaCodec
                virtualDisplayManager?.createVirtualDisplay(surface = encoderInputSurface)

                // 4. Iniciar el gestor térmico adaptativo
                thermalManager = ThermalAdaptationManager(this) { profile ->
                    videoEncoderPipeline?.updateBitrate(profile.targetBitrate)
                }.apply {
                    register()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando pipeline multimedia por hardware", e)
            }

            // 5. Iniciar servidor embebido Ktor CIO con enrutamiento de eventos táctiles
            try {
                embeddedServer = TeslaEmbeddedServer(
                    context = applicationContext,
                    host = "0.0.0.0",
                    port = 8080,
                    onClientConnected = {
                        // Enviar IDR frame de sincronización inmediata al cliente recién conectado
                        videoEncoderPipeline?.requestSyncFrame()
                        if (ShizukuManager.hasPermission()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                ShizukuManager.executeCommand("wm dismiss-keyguard")
                                virtualDisplayManager?.currentDisplayId?.let { id ->
                                    if (id != android.view.Display.DEFAULT_DISPLAY) {
                                        ShizukuManager.executeCommand("cmd activity focus-display $id")
                                    }
                                }
                            }
                        }
                    },
                    onTouchEventReceived = { type, x, y, ts ->
                        touchInputManager?.processTouchEvent(type, x, y, ts)
                    }
                ).apply {
                    getInitialConfigFrame = {
                        videoEncoderPipeline?.cachedSpsPps
                    }
                    onHomeRequest = {
                        virtualDisplayManager?.showPresentation()
                        touchInputManager?.injectKeyEvent(3)
                    }
                    onLaunchAppRequest = { pkg ->
                        virtualDisplayManager?.launchApplicationOnDisplay(pkg) ?: false
                    }
                    onTapRequest = { x, y ->
                        touchInputManager?.injectTap(x, y)
                    }
                    onSwipeRequest = { x1, y1, x2, y2, dur ->
                        touchInputManager?.injectSwipe(x1, y1, x2, y2, dur)
                    }
                    onKeyRequest = { code ->
                        touchInputManager?.injectKeyEvent(code)
                    }
                    onGetStatusRequest = {
                        org.json.JSONObject().apply {
                            put("serverRunning", isRunning)
                            put("displayId", virtualDisplayManager?.currentDisplayId ?: -1)
                            put("displayActive", virtualDisplayManager?.isDisplayActive ?: false)
                            put("shizukuPermission", ShizukuManager.hasPermission())
                            put("shizukuAvailable", ShizukuManager.isServiceRunning())
                            put("isEncoding", videoEncoderPipeline?.isEncoding ?: false)
                            put("cachedSpsPps", videoEncoderPipeline?.cachedSpsPps != null)
                            put("httpPort", embeddedServer?.port ?: 8080)
                            put("httpsPort", embeddedServer?.sslPort ?: 8443)
                            put("tlsActive", embeddedServer?.isTlsActive ?: false)
                        }
                    }
                }
                embeddedServer?.start()
                Log.i(TAG, "TeslaEmbeddedServer iniciado con éxito en http://$currentServerIp:8080")
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando TeslaEmbeddedServer", e)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fallo crítico en startProjectionServer", t)
        }
    }

    private fun stopProjectionServer() {
        try {
            if (isScreenReceiverRegistered) {
                try {
                    unregisterReceiver(screenReceiver)
                } catch (ignored: Exception) {}
                isScreenReceiverRegistered = false
            }

            try {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            } catch (ignored: Exception) {}
            wifiLock = null

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (ignored: Exception) {}
            wakeLock = null

            touchInputManager?.release()
            touchInputManager = null

            thermalManager?.unregister()
            thermalManager = null

            videoEncoderPipeline?.stop()
            videoEncoderPipeline = null

            virtualDisplayManager?.release()

            embeddedServer?.stop()
            embeddedServer = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo servicio", e)
        }
    }

    override fun onDestroy() {
        stopProjectionServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TesAAuto Projection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activo el servidor de streaming para Tesla"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TesAAuto Mirror Activo")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
