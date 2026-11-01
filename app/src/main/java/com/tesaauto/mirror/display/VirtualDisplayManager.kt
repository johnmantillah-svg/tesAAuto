package com.tesaauto.mirror.display

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VirtualDisplayManager(
    private val context: Context,
    private val onDisplayCreated: ((Int) -> Unit)? = null,
    private val onDisplayReleased: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VirtualDisplayManager"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DPI = 213 // tvdpi para escala óptima en pantallas vehiculares
    }

    data class DisplayConfig(
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
        val densityDpi: Int = DEFAULT_DPI,
        val displayName: String = "TeslaVirtualDisplay"
    )

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: SecondaryDisplayPresentation? = null
    var currentDisplayId: Int = Display.DEFAULT_DISPLAY
        private set

    val isDisplayActive: Boolean
        get() = virtualDisplay != null && currentDisplayId != Display.DEFAULT_DISPLAY

    private fun showDefaultPresentation(display: Display) {
        Handler(Looper.getMainLooper()).post {
            try {
                presentation?.dismiss()
                presentation = SecondaryDisplayPresentation(context, display).apply {
                    show()
                }
                Log.i(TAG, "SecondaryDisplayPresentation mostrada en DisplayID=${display.displayId}")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo mostrar SecondaryDisplayPresentation: ${e.message}")
            }
        }
    }

    fun showPresentation() {
        virtualDisplay?.let { vd ->
            showDefaultPresentation(vd.display)
        }
    }

    fun dismissPresentation() {
        Handler(Looper.getMainLooper()).post {
            try {
                presentation?.dismiss()
                presentation = null
                Log.i(TAG, "SecondaryDisplayPresentation ocultada")
            } catch (e: Exception) {
                Log.w(TAG, "Error descartando SecondaryDisplayPresentation: ${e.message}")
            }
        }
    }

    /**
     * Crea un VirtualDisplay secundario e independiente vinculado a la Surface proporcionada
     * (típicamente la entrada del codec de video MediaCodec).
     */
    fun createVirtualDisplay(
        config: DisplayConfig = DisplayConfig(),
        surface: Surface
    ): VirtualDisplay? {
        release() // Asegurar liberación de display previo si existiese

        return try {
            // Se utiliza únicamente VIRTUAL_DISPLAY_FLAG_PRESENTATION para permitir que
            // aplicaciones externas (Waze, Maps, Spotify) proyecten sus ventanas sin restricción de proceso.
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

            virtualDisplay = displayManager.createVirtualDisplay(
                config.displayName,
                config.width,
                config.height,
                config.densityDpi,
                surface,
                flags,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Log.d(TAG, "VirtualDisplay pausado")
                    }

                    override fun onResumed() {
                        Log.d(TAG, "VirtualDisplay reanudado")
                    }

                    override fun onStopped() {
                        Log.d(TAG, "VirtualDisplay detenido")
                        release()
                    }
                },
                null
            )

            virtualDisplay?.let { vd ->
                currentDisplayId = vd.display.displayId
                Log.i(TAG, "VirtualDisplay secundario creado con éxito. DisplayID=$currentDisplayId (${config.width}x${config.height} @ ${config.densityDpi}dpi)")
                if (ShizukuManager.hasPermission()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        ShizukuManager.executeCommand("cmd activity focus-display $currentDisplayId")
                        ShizukuManager.executeCommand("wm dismiss-keyguard")
                    }
                }
                showDefaultPresentation(vd.display)
                onDisplayCreated?.invoke(currentDisplayId)
            }
            virtualDisplay
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico creando VirtualDisplay con flags de presentación, intentando modo privado...", e)
            try {
                // Intento de rescate con display privado básico
                virtualDisplay = displayManager.createVirtualDisplay(
                    config.displayName,
                    config.width,
                    config.height,
                    config.densityDpi,
                    surface,
                    0,
                    null,
                    null
                )
                currentDisplayId = virtualDisplay?.display?.displayId ?: Display.DEFAULT_DISPLAY
                virtualDisplay?.let { showDefaultPresentation(it.display) }
                onDisplayCreated?.invoke(currentDisplayId)
                virtualDisplay
            } catch (e2: Exception) {
                Log.e(TAG, "Fallo total creando VirtualDisplay de respaldo", e2)
                release()
                null
            }
        }
    }

    private var activeTaskEnforcerJob: Job? = null

    /**
     * Monitorea continuamente las tareas de la aplicación activa para garantizar que si
     * Android o la propia aplicación intentan devolverla a la pantalla física (Display 0),
     * sea trasladada de inmediato al VirtualDisplay secundario (ej. al iniciar ruta en Waze).
     */
    fun startAppTaskEnforcer(packageName: String, targetDisplayId: Int) {
        activeTaskEnforcerJob?.cancel()
        activeTaskEnforcerJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Iniciando TaskEnforcer para $packageName hacia DisplayID=$targetDisplayId")
            var checks = 0
            while (isActive && isDisplayActive) {
                // Chequear cada 800ms al inicio, luego cada 2000ms para no saturar CPU ni generar lag
                val interval = if (checks < 6) 800L else 2000L
                delay(interval)
                checks++

                try {
                    val output = ShizukuManager.executeCommand("dumpsys window displays | grep -E 'DefaultTaskDisplayArea_d[0-9]+|\\* Task\\{.*$packageName'").output
                    if (output.isNotBlank()) {
                        var currentDisplay = -1
                        for (line in output.lines()) {
                            val dispMatch = Regex("""DefaultTaskDisplayArea_d(\d+)""").find(line)
                            if (dispMatch != null) {
                                currentDisplay = dispMatch.groupValues[1].toIntOrNull() ?: -1
                                continue
                            }
                            val taskMatch = Regex("""\*\s+Task\{[0-9a-fA-F]+\s+#(\d+)""").find(line)
                            if (taskMatch != null) {
                                val taskId = taskMatch.groupValues[1]
                                if (currentDisplay == Display.DEFAULT_DISPLAY && currentDisplay != targetDisplayId) {
                                    Log.i(TAG, "TaskEnforcer: Tarea #$taskId ($packageName) detectada en pantalla física (Display 0). Moviendo a Display #$targetDisplayId...")
                                    ShizukuManager.executeCommand("cmd activity display move-stack $taskId $targetDisplayId")
                                    ShizukuManager.executeCommand("cmd activity focus-display $targetDisplayId")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Errores transitorios de grep/adb ignorados
                }
            }
        }
    }

    /**
     * Lanza una aplicación (ej. Waze, Google Maps) directamente en el VirtualDisplay secundario.
     * Utiliza ActivityOptions de la API estándar y recurre a Shizuku (am start --display) como respaldo.
     */
    fun launchApplicationOnDisplay(packageName: String, activityClass: String? = null, displayId: Int = currentDisplayId): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "No se puede lanzar en display secundario: DisplayID no válido")
            return false
        }

        // Ocultar la presentación de reloj/dashboard para dejar libre el display a la app
        dismissPresentation()

        // 1. Prioridad: Shizuku ADB Shell (UID 2000) para forzar la ventana al VirtualDisplay
        if (ShizukuManager.hasPermission()) {
            CoroutineScope(Dispatchers.IO).launch {
                val cmd = when (packageName) {
                    "com.waze" -> {
                        // FreeMapAppActivity es la actividad principal de mapa
                        "am start -n com.waze/com.waze.FreeMapAppActivity --display $displayId -f 0x10000000"
                    }
                    "com.google.android.apps.maps" -> {
                        "am start -n com.google.android.apps.maps/com.google.android.maps.MapsActivity --display $displayId -f 0x10000000"
                    }
                    "com.spotify.music" -> {
                        "am start -n com.spotify.music/.MainActivity --display $displayId -f 0x10000000"
                    }
                    else -> {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        val targetComponent = activityClass?.let { "$packageName/$it" }
                            ?: launchIntent?.component?.flattenToShortString()
                        if (targetComponent != null) {
                            "am start -n $targetComponent --display $displayId -f 0x10000000"
                        } else {
                            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --display $displayId -p $packageName -f 0x10000000"
                        }
                    }
                }

                val res = ShizukuManager.executeCommand(cmd)
                ShizukuManager.executeCommand("cmd window dismiss-keyguard")
                ShizukuManager.executeCommand("cmd activity focus-display $displayId")
                Log.i(TAG, "Lanzamiento Shizuku ($cmd): exitCode=${res.exitCode}, out=${res.output}, err=${res.error}")

                // Iniciar el guardián de tareas para mantener la app y sus rutas en la pantalla secundaria
                startAppTaskEnforcer(packageName, displayId)
            }
            return true
        }

        // 2. Respaldo: ActivityOptions estándar
        try {
            val intent = if (packageName == "com.waze") {
                Intent().apply {
                    component = ComponentName("com.waze", "com.waze.FreeMapAppActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else if (activityClass != null) {
                Intent().apply {
                    component = ComponentName(packageName, activityClass)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(displayId)
                }
                context.startActivity(intent, options.toBundle())
                Log.i(TAG, "Aplicación $packageName lanzada mediante ActivityOptions en DisplayID=$displayId")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al lanzar vía ActivityOptions", e)
        }

        return false
    }

    /**
     * Libera de forma segura la superficie del VirtualDisplay secundario.
     */
    fun release() {
        try {
            activeTaskEnforcerJob?.cancel()
            activeTaskEnforcerJob = null
            Handler(Looper.getMainLooper()).post {
                try {
                    presentation?.dismiss()
                } catch (ignored: Exception) {}
                presentation = null
            }
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando VirtualDisplay", e)
        } finally {
            virtualDisplay = null
            currentDisplayId = Display.DEFAULT_DISPLAY
            onDisplayReleased?.invoke()
            Log.i(TAG, "VirtualDisplay liberado y recursos reseteados")
        }
    }
}
