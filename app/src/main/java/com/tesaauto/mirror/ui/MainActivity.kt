package com.tesaauto.mirror.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import com.tesaauto.mirror.vpn.TeslaVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var statusBadge: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var urlText: TextView
    private lateinit var actionButton: Button
    private lateinit var aaStatusText: TextView
    private lateinit var openAaButton: Button
    private lateinit var copyUrlButton: Button

    private var isServiceRunning = false
    private var projectionService: ProjectionService? = null
    private var isBound = false

    private val statusUpdateHandler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusUi()
            refreshNetworkInfo()
            statusUpdateHandler.postDelayed(this, 1500)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            projectionService = (service as? ProjectionService.LocalBinder)?.getService()
            isBound = true
            updateStatusUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            projectionService = null
            isBound = false
            updateStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- Encabezado ---
        val title = TextView(this).apply {
            text = "TesAAuto"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Android Auto Vehicular para Pantalla Tesla"
            textSize = 14f
            setTextColor(Color.parseColor("#8E99A8"))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 32)
        }

        layout.addView(title)
        layout.addView(subtitle)

        // --- Tarjeta: Estado del Servidor Local ---
        val statusCard = createCardLayout()

        statusBadge = TextView(this).apply {
            text = "🔴 SERVICIO DETENIDO"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E74C3C"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        statusDetailText = TextView(this).apply {
            text = "Inicia el servicio para transmitir Waze y Android Auto al auto."
            textSize = 13f
            setTextColor(Color.parseColor("#BDC3C7"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        actionButton = Button(this).apply {
            text = "INICIAR PROYECCIÓN VEHICULAR"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedButtonDrawable(Color.parseColor("#27AE60"))
            setPadding(32, 22, 32, 22)
            setOnClickListener {
                toggleService()
            }
        }

        statusCard.addView(statusBadge)
        statusCard.addView(statusDetailText)
        statusCard.addView(actionButton)
        layout.addView(statusCard)

        // --- Tarjeta: Dirección para Navegador de Tesla ---
        val urlCard = createCardLayout()

        val urlCardTitle = TextView(this).apply {
            text = "🚗 Dirección para el Navegador de Tesla"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3498DB"))
            setPadding(0, 0, 0, 10)
        }

        urlText = TextView(this).apply {
            text = "http://0.0.0.0:8080"
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }

        copyUrlButton = Button(this).apply {
            text = "📋 Copiar Dirección HTTP"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createRoundedButtonDrawable(Color.parseColor("#2C3E50"))
            setPadding(20, 14, 20, 14)
            setOnClickListener {
                copyUrlToClipboard()
            }
        }

        urlCard.addView(urlCardTitle)
        urlCard.addView(urlText)
        urlCard.addView(copyUrlButton)
        layout.addView(urlCard)

        // --- Tarjeta: Android Auto Head Unit ---
        val aaCard = createCardLayout()

        val aaCardTitle = TextView(this).apply {
            text = "⚡ Android Auto Head Unit Receiver"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F39C12"))
            setPadding(0, 0, 0, 8)
        }

        aaStatusText = TextView(this).apply {
            text = "Android Auto: Esperando conexión local (127.0.0.1:5277)"
            textSize = 13f
            setTextColor(Color.parseColor("#BDC3C7"))
            setPadding(0, 0, 0, 16)
        }

        openAaButton = Button(this).apply {
            text = "⚙️ Abrir Ajustes de Android Auto"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createRoundedButtonDrawable(Color.parseColor("#8E44AD"))
            setPadding(20, 14, 20, 14)
            setOnClickListener {
                openAndroidAutoSettings()
            }
        }

        val aaTipText = TextView(this).apply {
            text = "Nota: En Android Auto, pulsa ⋮ (arriba derecha) y elige 'Iniciar servidor de unidad princ.' para activar el enlace nativo."
            textSize = 12f
            setTextColor(Color.parseColor("#7F8C8D"))
            setPadding(0, 10, 0, 0)
        }

        aaCard.addView(aaCardTitle)
        aaCard.addView(aaStatusText)
        aaCard.addView(openAaButton)
        aaCard.addView(aaTipText)
        layout.addView(aaCard)

        // --- Tarjeta: Modo Ahorro Pantalla ---
        val powerCard = createCardLayout()

        val blackScreenButton = Button(this).apply {
            text = "🌙 Atenuar Pantalla del Móvil (Ahorro Batería)"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createRoundedButtonDrawable(Color.parseColor("#34495E"))
            setPadding(20, 16, 20, 16)
        }

        powerCard.addView(blackScreenButton)
        layout.addView(powerCard)

        // --- Instrucciones de uso vehicular ---
        val instructions = TextView(this).apply {
            text = """
                PASOS PARA NAVEGAR EN TU TESLA:
                1. Enciende la Zona Wi-Fi (Hotspot 5 GHz) de tu teléfono.
                2. En Ajustes de Android Auto, pulsa ⋮ y elige 'Iniciar servidor de unidad princ.'.
                3. Toca 'INICIAR PROYECCIÓN VEHICULAR' arriba.
                4. En tu Tesla, conéctate a tu Zona Wi-Fi (marca 'Permanecer conectado en marcha').
                5. Abre el navegador del Tesla e ingresa tu portal oficial:
                   https://johnmantillah-svg.github.io/tesAAuto/
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.parseColor("#7F8C8D"))
            setPadding(8, 24, 8, 24)
            setLineSpacing(10f, 1f)
        }
        layout.addView(instructions)

        root.addView(layout)

        // --- Capa de Pantalla Negra (Ahorro de batería y calor en viajes largos) ---
        val blackOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            val tip = TextView(this@MainActivity).apply {
                text = "Modo Pantalla Atenuada Activo\n(Toca la pantalla para encender)"
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
                Toast.makeText(this@MainActivity, "Pantalla restablecida", Toast.LENGTH_SHORT).show()
            }
        }

        blackScreenButton.setOnClickListener {
            blackOverlay.visibility = View.VISIBLE
            window.attributes = window.attributes.apply {
                screenBrightness = 0.001f
            }
            Toast.makeText(this, "Pantalla atenuada al mínimo para ahorrar batería.", Toast.LENGTH_SHORT).show()
        }

        val container = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(blackOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        setContentView(container)

        refreshNetworkInfo()
        requestNotificationPermission()
        checkBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkInfo()
        updateStatusUi()
        statusUpdateHandler.post(statusUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151B26"))
                cornerRadius = 24f
                setStroke(1, Color.parseColor("#222C3D"))
            }
            setPadding(32, 28, 32, 28)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            layoutParams = params
        }
    }

    private fun createRoundedButtonDrawable(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 18f
        }
    }

    companion object {
        private const val REQUEST_CODE_VPN = 102
    }

    private fun toggleService() {
        try {
            if (isServiceRunning) {
                if (isBound) {
                    unbindService(serviceConnection)
                    isBound = false
                }
                projectionService = null

                val intent = Intent(this, ProjectionService::class.java).apply {
                    action = ProjectionService.ACTION_STOP
                }
                startService(intent)
                stopVpnService()
                isServiceRunning = false
                updateStatusUi()
            } else {
                checkAndStartVpn()
                
                // Iniciar directamente el servicio vehicular enlazado al Servidor de Android Auto (127.0.0.1:5277)
                // Cero diálogos emergentes de captura de pantalla (MediaProjection)
                startProjectionServiceInternal(Activity.RESULT_CANCELED, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error cambiando estado de servicio", e)
            statusBadge.text = "🔴 ERROR: ${e.localizedMessage}"
            statusBadge.setTextColor(Color.parseColor("#E74C3C"))
            isServiceRunning = false
        }
    }

    private fun startProjectionServiceInternal(resultCode: Int, resultData: Intent?) {
        try {
            val intent = Intent(this, ProjectionService::class.java).apply {
                action = ProjectionService.ACTION_START
                putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
                if (resultData != null) {
                    putExtra(ProjectionService.EXTRA_RESULT_DATA, resultData)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            isServiceRunning = true
            updateStatusUi()
            refreshNetworkInfo()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error iniciando ProjectionService", e)
        }
    }

    private fun checkAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQUEST_CODE_VPN)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, TeslaVpnService::class.java).apply {
                action = TeslaVpnService.ACTION_START
            }
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error iniciando TeslaVpnService", e)
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(this, TeslaVpnService::class.java).apply {
                action = TeslaVpnService.ACTION_STOP
            }
            startService(intent)
        } catch (ignored: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_VPN -> {
                if (resultCode == Activity.RESULT_OK) {
                    startVpnService()
                }
            }
        }
    }

    private fun updateStatusUi() {
        runOnUiThread {
            if (isServiceRunning) {
                statusBadge.text = "🟢 TRANSMISIÓN VEHICULAR ACTIVA"
                statusBadge.setTextColor(Color.parseColor("#2ECC71"))
                statusDetailText.text = "Servidor local CIO transmitiendo a 60 FPS por Wi-Fi 5 GHz."
                actionButton.text = "DETENER SERVIDOR"
                actionButton.background = createRoundedButtonDrawable(Color.parseColor("#E74C3C"))
            } else {
                statusBadge.text = "🔴 SERVICIO DETENIDO"
                statusBadge.setTextColor(Color.parseColor("#E74C3C"))
                statusDetailText.text = "Inicia el servicio para transmitir Waze y Android Auto al auto."
                actionButton.text = "INICIAR PROYECCIÓN VEHICULAR"
                actionButton.background = createRoundedButtonDrawable(Color.parseColor("#27AE60"))
            }

            val client = projectionService?.aaClient
            if (client?.isConnected == true) {
                if (client.isVideoActive) {
                    aaStatusText.text = "Android Auto: Transmitiendo H.264 activo a Tesla 🟢 (60 FPS)"
                    aaStatusText.setTextColor(Color.parseColor("#2ECC71"))
                } else {
                    aaStatusText.text = "Android Auto: Conectado vía TLS 1.2 (Esperando video) 🟡"
                    aaStatusText.setTextColor(Color.parseColor("#F1C40F"))
                }
            } else {
                aaStatusText.text = "Android Auto: Esperando servidor en 127.0.0.1:5277"
                aaStatusText.setTextColor(Color.parseColor("#BDC3C7"))
            }
        }
    }

    private fun refreshNetworkInfo() {
        val iface = NetworkInterfaceHelper.getHotspotOrWifiIpAddress()
        val detectedIp = iface?.ipAddress ?: "10.229.231.100"

        val sb = StringBuilder()
        sb.append("🚗 EN EL NAVEGADOR DEL TESLA (HTTPS Let's Encrypt - Sin alertas):\n")
        sb.append("https://taada.top:8443\n")
        sb.append("(o https://app.taada.top)\n\n")
        sb.append("📱 Conexión Directa Local:\n")
        sb.append("http://$detectedIp:8080\n")
        sb.append("https://$detectedIp:8443\n\n")
        sb.append("🌐 O GitHub Pages:\n")
        sb.append("https://johnmantillah-svg.github.io/tesAAuto/\n")

        urlText.text = sb.toString().trim()
    }

    private fun copyUrlToClipboard() {
        val url = "https://taada.top:8443"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TesAAuto URL", url))
        Toast.makeText(this, "Dirección copiada: $url", Toast.LENGTH_SHORT).show()
    }

    private fun openAndroidAutoSettings() {
        try {
            com.tesaauto.mirror.service.TouchAccessibilityService.isAutoStartingHeadUnit = true
            val intent = Intent().apply {
                component = ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "Iniciando Servidor de Android Auto...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir Android Auto: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "No se pudo solicitar exención de batería: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
