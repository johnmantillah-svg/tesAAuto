package com.tesaauto.mirror.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import com.tesaauto.mirror.display.ShizukuManager
import com.tesaauto.mirror.display.VirtualDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class TouchInputManager(
    private val context: Context,
    private val virtualDisplayManager: VirtualDisplayManager
) {
    companion object {
        private const val TAG = "TouchInputManager"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val injectInputEventMethod: Method? = try {
        InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo obtener método reflectivo injectInputEvent de InputManager", e)
        null
    }

    private val setDisplayIdMethod: Method? = try {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
    } catch (e: Exception) {
        null
    }

    // Cola secuencial de comandos para evitar saturar el sistema con múltiples subprocesos sh/JVM simultáneos
    private val commandChannel = Channel<String>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val inputScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        inputScope.launch {
            var lastWakeTime = 0L
            for (cmd in commandChannel) {
                try {
                    val now = SystemClock.uptimeMillis()
                    // Si el dispositivo fue bloqueado o durmió, desbloquear ventana para que acepte toques
                    if (now - lastWakeTime > 1500L) {
                        lastWakeTime = now
                        ShizukuManager.executeCommand("cmd window dismiss-keyguard")
                    }
                    ShizukuManager.executeCommand(cmd)
                } catch (e: Exception) {
                    Log.w(TAG, "Error ejecutando comando de entrada: $cmd", e)
                }
            }
        }
    }

    private var downNormX: Double = 0.0
    private var downNormY: Double = 0.0
    private var downAbsX: Float = 0f
    private var downAbsY: Float = 0f
    private var lastDownTime: Long = 0L

    /**
     * Inyecta un toque directo (Tap) en coordenadas normalizadas [0.0, 1.0].
     */
    fun injectTap(normX: Double, normY: Double) {
        val displayId = virtualDisplayManager.currentDisplayId
        val absX = (normX.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_WIDTH).toInt()
        val absY = (normY.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_HEIGHT).toInt()

        Log.d(TAG, "Encolando Tap en ($absX, $absY) - displayId=$displayId")

        if (ShizukuManager.hasPermission()) {
            val cmd = if (displayId != android.view.Display.DEFAULT_DISPLAY) {
                "input -d $displayId tap $absX $absY"
            } else {
                "input tap $absX $absY"
            }
            commandChannel.trySend(cmd)
        } else {
            val now = SystemClock.uptimeMillis()
            lastDownTime = now
            injectDirectMotionEvent(MotionEvent.ACTION_DOWN, absX.toFloat(), absY.toFloat(), now, displayId)
            injectDirectMotionEvent(MotionEvent.ACTION_UP, absX.toFloat(), absY.toFloat(), now + 40, displayId)
        }
    }

    /**
     * Inyecta un deslizamiento / arrastre (Swipe / Drag) en coordenadas normalizadas.
     */
    fun injectSwipe(normX1: Double, normY1: Double, normX2: Double, normY2: Double, durationMs: Int = 200) {
        val displayId = virtualDisplayManager.currentDisplayId
        val x1 = (normX1.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_WIDTH).toInt()
        val y1 = (normY1.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_HEIGHT).toInt()
        val x2 = (normX2.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_WIDTH).toInt()
        val y2 = (normY2.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_HEIGHT).toInt()

        Log.d(TAG, "Encolando Swipe de ($x1, $y1) a ($x2, $y2) en ${durationMs}ms - displayId=$displayId")

        if (ShizukuManager.hasPermission()) {
            val cmd = if (displayId != android.view.Display.DEFAULT_DISPLAY) {
                "input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs"
            } else {
                "input swipe $x1 $y1 $x2 $y2 $durationMs"
            }
            commandChannel.trySend(cmd)
        }
    }

    /**
     * Inyecta un evento de tecla de sistema (ej. Back = 4, Home = 3).
     */
    fun injectKeyEvent(keyCode: Int) {
        val displayId = virtualDisplayManager.currentDisplayId
        Log.d(TAG, "Encolando KeyEvent keycode=$keyCode - displayId=$displayId")

        if (ShizukuManager.hasPermission()) {
            val cmd = if (displayId != android.view.Display.DEFAULT_DISPLAY) {
                "input -d $displayId keyevent $keyCode"
            } else {
                "input keyevent $keyCode"
            }
            commandChannel.trySend(cmd)
        }
    }

    /**
     * Procesa y transforma eventos táctiles continuos recibidos desde el WebSocket.
     */
    fun processTouchEvent(
        actionType: String,
        normalizedX: Double,
        normalizedY: Double,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val displayId = virtualDisplayManager.currentDisplayId
        val clampedX = normalizedX.coerceIn(0.0, 1.0)
        val clampedY = normalizedY.coerceIn(0.0, 1.0)

        val width = VirtualDisplayManager.DEFAULT_WIDTH
        val height = VirtualDisplayManager.DEFAULT_HEIGHT
        val absX = (clampedX * width).toFloat()
        val absY = (clampedY * height).toFloat()
        val eventTime = SystemClock.uptimeMillis()

        when (actionType.lowercase()) {
            "down" -> {
                downNormX = clampedX
                downNormY = clampedY
                downAbsX = absX
                downAbsY = absY
                lastDownTime = eventTime
                injectDirectMotionEvent(MotionEvent.ACTION_DOWN, absX, absY, eventTime, displayId)
            }
            "move" -> {
                injectDirectMotionEvent(MotionEvent.ACTION_MOVE, absX, absY, eventTime, displayId)
            }
            "up" -> {
                injectDirectMotionEvent(MotionEvent.ACTION_UP, absX, absY, eventTime, displayId)
            }
            "cancel" -> {
                injectDirectMotionEvent(MotionEvent.ACTION_CANCEL, absX, absY, eventTime, displayId)
            }
        }
    }

    private fun injectDirectMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        displayId: Int
    ): Boolean {
        return try {
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 1.0f
                    size = 1.0f
                }
            )

            val motionEvent = MotionEvent.obtain(
                lastDownTime,
                eventTime,
                action,
                1,
                properties,
                coords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0
            )

            setDisplayIdMethod?.invoke(motionEvent, displayId)

            var success = false
            if (injectInputEventMethod != null) {
                val res = injectInputEventMethod.invoke(inputManager, motionEvent, INJECT_INPUT_EVENT_MODE_ASYNC)
                success = (res as? Boolean) ?: false
            }

            motionEvent.recycle()
            success
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        inputScope.cancel()
    }
}
