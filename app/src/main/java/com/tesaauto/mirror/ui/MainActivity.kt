package com.tesaauto.mirror.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tesaauto.mirror.network.NetworkInterfaceHelper
import com.tesaauto.mirror.service.ProjectionService
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var shizukuText: TextView
    private lateinit var actionButton: Button

    private lateinit var wazeButton: Button
    private lateinit var mapsButton: Button
    private lateinit var dashboardButton: Button

    private var isServiceRunning = false
    private var projectionService: ProjectionService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            projectionService = (service as? ProjectionService.LocalBinder)?.getService()
            isBound = true
            updateAppButtons(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            projectionService = null
            isBound = false
            updateAppButtons(false)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        updateShizukuStatus(grantResult == PackageManager.PERMISSION_GRANTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("TesAAutoCrash", "Excepción no capturada en ${thread.name}", throwable)
            try {
                val crashFile = java.io.File(getExternalFilesDir(null), "crash.log")
                crashFile.writeText("Error: ${throwable.message}\nStackTrace:\n${android.util.Log.getStackTraceString(throwable)}")
            } catch (ignored: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F141C"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "TesAAuto Mirror"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Proyección directa para navegador Tesla"
            textSize = 14f
            setTextColor(Color.parseColor("#7F8C8D"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 36)
        }

        statusText = TextView(this).apply {
            text = "Estado: Detenido"
            textSize = 16f
            setTextColor(Color.parseColor("#E74C3C"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }

        urlText = TextView(this).apply {
            text = "URL: http://0.0.0.0:8080"
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#3498DB"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        shizukuText = TextView(this).apply {
            text = "Shizuku: Verificando..."
            textSize = 14f
            setTextColor(Color.parseColor("#F39C12"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        actionButton = Button(this).apply {
            text = "Iniciar Servidor de Proyección"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2ECC71"))
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                toggleService()
            }
        }

        // --- Panel de Lanzamiento de Aplicaciones a la Pantalla de Tesla ---
        val appSectionTitle = TextView(this).apply {
            text = "Lanzar en Pantalla de Tesla:"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 48, 0, 16)
        }

        val appButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }

        wazeButton = Button(this).apply {
            text = "Lanzar Waze"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener {
                launchApp("com.waze", "Waze")
            }
        }

        mapsButton = Button(this).apply {
            text = "Lanzar Google Maps"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#43A047"))
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener {
                launchApp("com.google.android.apps.maps", "Google Maps")
            }
        }

        dashboardButton = Button(this).apply {
            text = "Ver Reloj / Dashboard Principal"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#546E7A"))
            setTextColor(Color.WHITE)
            isEnabled = false
            setOnClickListener {
                projectionService?.virtualDisplayManager?.let { vm ->
                    if (vm.isDisplayActive) {
                        Toast.makeText(this@MainActivity, "Restableciendo Dashboard en Tesla...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val blackScreenButton = Button(this).apply {
            text = "Modo Pantalla Negra (Ahorro Batería)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#34495E"))
            setTextColor(Color.WHITE)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }

        appButtonsLayout.addView(wazeButton, params)
        appButtonsLayout.addView(mapsButton, params)
        appButtonsLayout.addView(dashboardButton, params)
        appButtonsLayout.addView(blackScreenButton, params)

        val instructions = TextView(this).apply {
            text = """
                Pasos para conectar:
                1. Habilita el punto de acceso (Hotspot) Wi-Fi en 5 GHz.
                2. Conecta el vehículo Tesla al Wi-Fi del smartphone.
                3. Abre el navegador Chromium del vehículo e ingresa la URL indicada arriba.
                4. Autoriza Shizuku si deseas control táctil bidireccional fluido.
                5. Usa 'Modo Pantalla Negra' para apagar los píxeles del móvil y mantener la proyección fluida sin calentamiento.
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.parseColor("#BDC3C7"))
            setPadding(0, 48, 0, 0)
            setLineSpacing(12f, 1f)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(statusText)
        layout.addView(urlText)
        layout.addView(shizukuText)
        layout.addView(actionButton)
        layout.addView(appSectionTitle)
        layout.addView(appButtonsLayout)
        layout.addView(instructions)
        root.addView(layout)

        val blackOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            val tip = TextView(this@MainActivity).apply {
                text = "Modo Pantalla Negra Activo\n(Toca la pantalla para salir)"
                setTextColor(Color.parseColor("#555555"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            val tipParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            addView(tip, tipParams)
            setOnClickListener {
                visibility = View.GONE
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Toast.makeText(this@MainActivity, "Modo pantalla negra desactivado", Toast.LENGTH_SHORT).show()
            }
        }

        blackScreenButton.setOnClickListener {
            blackOverlay.visibility = View.VISIBLE
            window.attributes = window.attributes.apply {
                screenBrightness = 0.001f
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "Pantalla atenuada al mínimo. Toca la pantalla para volver.", Toast.LENGTH_SHORT).show()
        }

        val container = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(blackOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        setContentView(container)

        checkShizuku()
        refreshNetworkInfo()
        requestNotificationPermission()
    }

    private fun toggleService() {
        val intent = Intent(this, ProjectionService::class.java)
        try {
            if (isServiceRunning) {
                if (isBound) {
                    unbindService(serviceConnection)
                    isBound = false
                }
                projectionService = null

                intent.action = ProjectionService.ACTION_STOP
                startService(intent)
                isServiceRunning = false
                statusText.text = "Estado: Detenido"
                statusText.setTextColor(Color.parseColor("#E74C3C"))
                actionButton.text = "Iniciar Servidor de Proyección"
                actionButton.setBackgroundColor(Color.parseColor("#2ECC71"))
                updateAppButtons(false)
            } else {
                intent.action = ProjectionService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

                isServiceRunning = true
                statusText.text = "Estado: Transmitiendo en Vivo"
                statusText.setTextColor(Color.parseColor("#2ECC71"))
                actionButton.text = "Detener Servidor"
                actionButton.setBackgroundColor(Color.parseColor("#E74C3C"))
                refreshNetworkInfo()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cambiando estado de servicio", e)
            statusText.text = "Error: ${e.localizedMessage}"
            statusText.setTextColor(Color.parseColor("#E74C3C"))
            isServiceRunning = false
            updateAppButtons(false)
        }
    }

    private fun launchApp(packageName: String, label: String) {
        val vm = projectionService?.virtualDisplayManager
        if (vm == null || !vm.isDisplayActive) {
            Toast.makeText(this, "El display de Tesla no está activo. Inicia el servidor primero.", Toast.LENGTH_SHORT).show()
            return
        }

        val success = vm.launchApplicationOnDisplay(packageName)
        if (success) {
            Toast.makeText(this, "Lanzando $label en la pantalla de Tesla...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se pudo lanzar $label. Asegúrate de tenerla instalada.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAppButtons(enabled: Boolean) {
        runOnUiThread {
            wazeButton.isEnabled = enabled
            mapsButton.isEnabled = enabled
            dashboardButton.isEnabled = enabled

            wazeButton.alpha = if (enabled) 1.0f else 0.4f
            mapsButton.alpha = if (enabled) 1.0f else 0.4f
            dashboardButton.alpha = if (enabled) 1.0f else 0.4f
        }
    }

    private fun refreshNetworkInfo() {
        val iface = NetworkInterfaceHelper.getHotspotOrWifiIpAddress()
        val ip = iface?.ipAddress ?: "0.0.0.0"
        urlText.text = "HTTP:  http://$ip:8080\nHTTPS: https://$ip:8443 (Seguro / GPU)"
    }

    private fun checkShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    updateShizukuStatus(true)
                } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                    updateShizukuStatus(false, "Permiso requerido")
                    Shizuku.requestPermission(101)
                } else {
                    Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                    Shizuku.requestPermission(101)
                }
            } else {
                updateShizukuStatus(false, "Servicio no iniciado")
            }
        } catch (e: Exception) {
            updateShizukuStatus(false, "No disponible")
        }
    }

    private fun updateShizukuStatus(granted: Boolean, extra: String? = null) {
        runOnUiThread {
            if (granted) {
                shizukuText.text = "Shizuku: Conectado y Autorizado (ADB Activo)"
                shizukuText.setTextColor(Color.parseColor("#2ECC71"))
            } else {
                shizukuText.text = "Shizuku: ${extra ?: "Sin Autorizar"}"
                shizukuText.setTextColor(Color.parseColor("#F39C12"))
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
