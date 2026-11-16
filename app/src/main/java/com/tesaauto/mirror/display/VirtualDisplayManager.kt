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

    /**
     * Lanza una aplicación (ej. Waze, Google Maps) en el VirtualDisplay secundario o en pantalla principal.
     */
    fun launchApplicationOnDisplay(packageName: String, activityClass: String? = null, displayId: Int = currentDisplayId): Boolean {
        // Ocultar la presentación de reloj/dashboard para dejar libre el display a la app
        dismissPresentation()

        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            } ?: if (packageName == "com.waze") {
                Intent().apply {
                    component = ComponentName("com.waze", "com.waze.FreeMapAppActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            } else if (activityClass != null) {
                Intent().apply {
                    component = ComponentName(packageName, activityClass)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            } else {
                null
            }

            if (intent == null) {
                Log.w(TAG, "No se encontró intent de lanzamiento para $packageName")
                return false
            }

            if (displayId != Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(displayId)
                }
                context.startActivity(intent, options.toBundle())
                Log.i(TAG, "Aplicación $packageName lanzada mediante ActivityOptions en DisplayID=$displayId")
            } else {
                context.startActivity(intent)
                Log.i(TAG, "Aplicación $packageName lanzada directamente en pantalla principal")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al lanzar app $packageName", e)
        }

        return false
    }

    /**
     * Libera de forma segura la superficie del VirtualDisplay secundario.
     */
    fun release() {
        try {
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
