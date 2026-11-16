package com.tesaauto.mirror.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibility"

        @Volatile
        var instance: TouchAccessibilityService? = null
            private set

        @Volatile
        var isAutoStartingHeadUnit: Boolean = false

        val isServiceRunning: Boolean
            get() = instance != null

        fun tap(normX: Double, normY: Double, screenW: Int = 0, screenH: Int = 0): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "TouchAccessibilityService no está conectado aún")
                return false
            }
            return svc.performTap(normX, normY, screenW, screenH)
        }

        fun swipe(normX1: Double, normY1: Double, normX2: Double, normY2: Double, durationMs: Long = 200, screenW: Int = 0, screenH: Int = 0): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "TouchAccessibilityService no está conectado aún")
                return false
            }
            return svc.performSwipe(normX1, normY1, normX2, normY2, durationMs, screenW, screenH)
        }

        fun back(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun home(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "✅ TouchAccessibilityService CONECTADO y listo para inyección táctil vehicular")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoStartingHeadUnit) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.google.android.projection.gearhead") {
            try {
                val root = rootInActiveWindow ?: return
                
                // 1. Si el popup ya está abierto, buscar "Iniciar servidor unidad princ." o equivalente
                val startKeywords = listOf(
                    "Iniciar servidor",
                    "servidor unidad",
                    "Start head unit",
                    "Head unit server"
                )
                for (kw in startKeywords) {
                    val matchingNodes = root.findAccessibilityNodeInfosByText(kw)
                    if (matchingNodes.isNotEmpty()) {
                        for (node in matchingNodes) {
                            val target = if (node.isClickable) node else node.parent
                            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                                Log.i(TAG, "✅ Iniciar Servidor de Unidad Principal pulsado automáticamente por accesibilidad")
                                isAutoStartingHeadUnit = false
                                Handler(Looper.getMainLooper()).postDelayed({
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                }, 600)
                                return
                            }
                        }
                    }
                }

                // 2. Si el menú desplegable aún no está visible, pulsar el botón de opciones ⋮ ("Más opciones")
                val overflowButtons = root.findAccessibilityNodeInfosByText("Más opciones")
                    .ifEmpty { root.findAccessibilityNodeInfosByText("More options") }

                if (overflowButtons.isNotEmpty()) {
                    val btn = overflowButtons[0]
                    val target = if (btn.isClickable) btn else btn.parent
                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Menú ⋮ desplegado automáticamente")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error durante automatización de servidor Android Auto: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ TouchAccessibilityService INTERRUMPIDO")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "TouchAccessibilityService DESTRUIDO")
    }

    fun performTap(normX: Double, normY: Double, screenW: Int = 0, screenH: Int = 0): Boolean {
        val metrics = resources.displayMetrics
        val w = if (screenW > 0) screenW else metrics.widthPixels
        val h = if (screenH > 0) screenH else metrics.heightPixels

        val (x, y) = mapVideoCoordsToScreen(normX, normY, w, h)

        Log.d(TAG, "Inyectando tap en ($x, $y) [norm: $normX, $normY, screen: ${w}x${h}]")

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completado con éxito en ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelado en ($x, $y)")
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun performSwipe(normX1: Double, normY1: Double, normX2: Double, normY2: Double, durationMs: Long = 200, screenW: Int = 0, screenH: Int = 0): Boolean {
        val metrics = resources.displayMetrics
        val w = if (screenW > 0) screenW else metrics.widthPixels
        val h = if (screenH > 0) screenH else metrics.heightPixels

        val (x1, y1) = mapVideoCoordsToScreen(normX1, normY1, w, h)
        val (x2, y2) = mapVideoCoordsToScreen(normX2, normY2, w, h)

        val dur = durationMs.coerceIn(50, 1000)
        Log.d(TAG, "Inyectando swipe de ($x1, $y1) a ($x2, $y2) en ${dur}ms")

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    /**
     * Mapea coordenadas normalizadas del stream de video 16:9 al espacio de píxeles real del dispositivo,
     * considerando pillarboxing o letterboxing automático de MediaProjection.
     */
    private fun mapVideoCoordsToScreen(normX: Double, normY: Double, screenW: Int, screenH: Int): Pair<Float, Float> {
        val streamAspect = 1280.0 / 720.0 // Aspect ratio del stream (16:9)
        val phoneAspect = screenW.toDouble() / screenH.toDouble()

        return if (phoneAspect < streamAspect) {
            // Caso vertical (Portrait) o pantalla más estrecha que 16:9 -> Pillarboxing (barras negras a los lados)
            val usedWidthRatio = phoneAspect / streamAspect
            val marginX = (1.0 - usedWidthRatio) / 2.0
            val mappedNormX = ((normX - marginX) / usedWidthRatio).coerceIn(0.0, 1.0)
            val mappedNormY = normY.coerceIn(0.0, 1.0)
            Pair((mappedNormX * screenW).toFloat(), (mappedNormY * screenH).toFloat())
        } else {
            // Caso horizontal (Landscape) más ancho que 16:9 -> Letterboxing (barras negras arriba/abajo)
            val usedHeightRatio = streamAspect / phoneAspect
            val marginY = (1.0 - usedHeightRatio) / 2.0
            val mappedNormX = normX.coerceIn(0.0, 1.0)
            val mappedNormY = ((normY - marginY) / usedHeightRatio).coerceIn(0.0, 1.0)
            Pair((mappedNormX * screenW).toFloat(), (mappedNormY * screenH).toFloat())
        }
    }
}
