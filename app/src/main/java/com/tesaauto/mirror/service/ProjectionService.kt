package com.tesaauto.mirror.service

import android.app.Activity
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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tesaauto.mirror.aa.AndroidAutoHeadUnitClient
import com.tesaauto.mirror.display.VirtualDisplayManager
import com.tesaauto.mirror.input.TouchInputManager
import com.tesaauto.mirror.network.NetworkInterfaceHelper
import com.tesaauto.mirror.server.TeslaEmbeddedServer
import com.tesaauto.mirror.ui.MainActivity
import com.tesaauto.mirror.video.ThermalAdaptationManager
import com.tesaauto.mirror.video.VideoEncoderPipeline

class ProjectionService : Service() {

    companion object {
        const val TAG = "ProjectionService"
        const val CHANNEL_ID = "tesaauto_projection_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.tesaauto.mirror.ACTION_START"
        const val ACTION_STOP = "com.tesaauto.mirror.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val binder = LocalBinder()
    var embeddedServer: TeslaEmbeddedServer? = null
        private set
    var aaClient: AndroidAutoHeadUnitClient? = null
        private set
    var virtualDisplayManager: VirtualDisplayManager? = null
        private set
    var videoEncoderPipeline: VideoEncoderPipeline? = null
        private set
    private var thermalManager: ThermalAdaptationManager? = null
    private var touchInputManager: TouchInputManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isScreenReceiverRegistered = false

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var screenVirtualDisplay: VirtualDisplay? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.i(TAG, "Pantalla física apagada detectada. Manteniendo proyección activa...")
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
        val initialNotification = createNotification("Iniciando servicio de proyección vehicular...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        virtualDisplayManager = VirtualDisplayManager(
            context = this,
            onDisplayCreated = { displayId ->
                Log.i(TAG, "VirtualDisplay asignado en servicio con ID=$displayId")
            },
            onDisplayReleased = {
                Log.i(TAG, "VirtualDisplay liberado en servicio")
            }
        )
        touchInputManager = TouchInputManager(this, virtualDisplayManager!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                startProjectionServer(resultCode, resultData)
            }
            ACTION_STOP -> stopProjectionServer()
        }
        return START_STICKY
    }

    private fun startProjectionServer(resultCode: Int = Activity.RESULT_CANCELED, resultData: Intent? = null) {
        val hasMediaProjection = (resultCode == Activity.RESULT_OK && resultData != null)

        if (embeddedServer?.isRunning == true) {
            Log.i(TAG, "startProjectionServer: El servidor ya está en ejecución activa")
            if (hasMediaProjection && mediaProjection == null) {
                initMediaProjection(resultCode, resultData!!)
            }
            return
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TesAAuto:ProjectionWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WakeLock adquirido para mantener proyección con pantalla apagada")
            }

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

            val notification = createNotification("Servidor vehicular activo en http://$currentServerIp:8080")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasMediaProjection) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasMediaProjection) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            if (hasMediaProjection && mediaProjection == null) {
                initMediaProjection(resultCode, resultData!!)
            }

            // 1. Iniciar servidor embebido Ktor CIO con enrutamiento de eventos táctiles si no está corriendo
            if (embeddedServer == null || embeddedServer?.isRunning == false) {
                try {
                    embeddedServer = TeslaEmbeddedServer(
                        context = applicationContext,
                        host = "0.0.0.0",
                        port = 8080,
                        onClientConnected = {
                            Log.i(TAG, "Tesla browser conectado vía WebSocket. Asertando VideoFocus y SyncFrame...")
                            videoEncoderPipeline?.requestSyncFrame()
                            aaClient?.requestVideoFocus(projected = true, force = true)
                        },
                        onTouchEventReceived = { type, x, y, _ ->
                            if (aaClient?.isConnected == true) {
                                aaClient?.sendTouchEvent(type, x, y)
                            } else {
                                Log.w(TAG, "Touch vehicular ignorado: Android Auto no está conectado aún")
                            }
                        }
                    ).apply {
                        getInitialConfigFrame = {
                            aaClient?.cachedSpsPps ?: videoEncoderPipeline?.cachedSpsPps
                        }
                        onHomeRequest = {
                            if (aaClient?.isConnected == true) {
                                Log.i(TAG, "Home vehicular en Android Auto")
                            }
                        }
                        onLaunchAppRequest = { pkg ->
                            if (aaClient?.isConnected == true) {
                                Log.i(TAG, "Android Auto conectado: la selección de aplicaciones ($pkg) se realiza dentro de la barra vehicular Coolwalk")
                                true
                            } else {
                                Log.w(TAG, "Lanzamiento de $pkg denegado: Servidor Android Auto no conectado")
                                false
                            }
                        }
                        onTapRequest = { x, y ->
                            if (aaClient?.isConnected == true) {
                                // MODO ANDROID AUTO: El toque va DIRECTO al canal vehicular de Gearhead sin tocar la pantalla física del teléfono
                                aaClient?.sendTouchEvent("down", x, y)
                                aaClient?.sendTouchEvent("up", x, y)
                            } else {
                                Log.w(TAG, "Tap vehicular ignorado: Android Auto no conectado")
                            }
                        }
                        onSwipeRequest = { x1, y1, x2, y2, _ ->
                            if (aaClient?.isConnected == true) {
                                // MODO ANDROID AUTO: El swipe va DIRECTO al canal vehicular
                                aaClient?.sendTouchEvent("down", x1, y1)
                                aaClient?.sendTouchEvent("move", x2, y2)
                                aaClient?.sendTouchEvent("up", x2, y2)
                            } else {
                                Log.w(TAG, "Swipe vehicular ignorado: Android Auto no conectado")
                            }
                        }
                        onKeyRequest = { code ->
                            if (code == 4 && TouchAccessibilityService.isServiceRunning) {
                                TouchAccessibilityService.back()
                            } else if (code == 3 && TouchAccessibilityService.isServiceRunning) {
                                TouchAccessibilityService.home()
                            } else {
                                touchInputManager?.injectKeyEvent(code)
                            }
                        }
                        onGetStatusRequest = {
                            org.json.JSONObject().apply {
                                put("serverRunning", isRunning)
                                put("aaConnected", aaClient?.isConnected ?: false)
                                put("aaVideoActive", aaClient?.isVideoActive ?: false)
                                put("displayId", virtualDisplayManager?.currentDisplayId ?: -1)
                                put("displayActive", virtualDisplayManager?.isDisplayActive ?: false)
                                put("isEncoding", videoEncoderPipeline?.isEncoding ?: false)
                                put("cachedSpsPps", (aaClient?.cachedSpsPps != null || videoEncoderPipeline?.cachedSpsPps != null))
                                put("httpPort", embeddedServer?.port ?: 8080)
                                put("httpsPort", embeddedServer?.sslPort ?: 8443)
                                put("tlsActive", embeddedServer?.isTlsActive ?: false)
                                put("ip", currentServerIp)
                            }
                        }
                    }
                    embeddedServer?.start()
                    Log.i(TAG, "TeslaEmbeddedServer iniciado con éxito en http://$currentServerIp:8080")
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando TeslaEmbeddedServer", e)
                }
            }

            // 2. Iniciar cliente receptor de Android Auto Head Unit (puerto 5277 local)
            if (aaClient == null) {
                try {
                    aaClient = AndroidAutoHeadUnitClient(
                        context = applicationContext,
                        host = "127.0.0.1",
                        port = 5277,
                        onVideoFrame = { nalBytes ->
                            embeddedServer?.broadcastVideoFrame(nalBytes)
                        },
                        onConnected = {
                            Log.i(TAG, "Android Auto Head Unit conectado y listo para streaming")
                        },
                        onDisconnected = { reason ->
                            Log.i(TAG, "Android Auto desconectado: $reason")
                        }
                    ).apply {
                        start()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando AndroidAutoHeadUnitClient", e)
                }
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

            stopScreenCapture()

            touchInputManager?.release()
            touchInputManager = null

            thermalManager?.unregister()
            thermalManager = null

            videoEncoderPipeline?.stop()
            videoEncoderPipeline = null

            virtualDisplayManager?.release()

            embeddedServer?.stop()
            embeddedServer = null

            aaClient?.stop()
            aaClient = null

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

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection detenido por el sistema")
                    stopScreenCapture()
                }
            }
            mediaProjection?.registerCallback(mediaProjectionCallback!!, Handler(Looper.getMainLooper()))
            startScreenCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando MediaProjection: ${e.message}", e)
        }
    }

    private fun startScreenCapture() {
        val mp = mediaProjection ?: return
        try {
            val metrics = resources.displayMetrics
            val width = 1280
            val height = 720
            val density = metrics.densityDpi

            if (videoEncoderPipeline == null) {
                videoEncoderPipeline = VideoEncoderPipeline(
                    config = VideoEncoderPipeline.EncoderConfig(
                        width = width,
                        height = height,
                        bitrate = 3_500_000,
                        frameRate = 60,
                        mimeType = VideoEncoderPipeline.MIME_H264
                    ),
                    onFrameEncoded = { nalBytes, isKeyFrame ->
                        embeddedServer?.broadcastVideoFrame(nalBytes)
                    }
                )
            }
            val surface = videoEncoderPipeline?.start()
            if (surface != null) {
                screenVirtualDisplay = mp.createVirtualDisplay(
                    "TesAAutoScreenMirror",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                Log.i(TAG, "✅ Screen Mirroring activo vía MediaProjection: ${width}x${height} @ 60 FPS")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando captura de pantalla con MediaProjection", e)
        }
    }

    private fun stopScreenCapture() {
        try {
            screenVirtualDisplay?.release()
            screenVirtualDisplay = null
            videoEncoderPipeline?.stop()
            videoEncoderPipeline = null
            mediaProjection?.stop()
            mediaProjection = null
            Log.i(TAG, "Screen Mirroring detenido y recursos liberados")
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando captura de pantalla: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TesAAuto Projection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activo el servidor de streaming vehicular para Tesla"
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
            .setContentTitle("TesAAuto Mirror (Vehicular)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
