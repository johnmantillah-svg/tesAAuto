package com.tesaauto.mirror.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import com.tesaauto.mirror.display.VirtualDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private val inputScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        val now = SystemClock.uptimeMillis()
        lastDownTime = now
        injectDirectMotionEvent(MotionEvent.ACTION_DOWN, absX.toFloat(), absY.toFloat(), now, displayId)
        injectDirectMotionEvent(MotionEvent.ACTION_UP, absX.toFloat(), absY.toFloat(), now + 40, displayId)
    }

    /**
     * Inyecta un deslizamiento / arrastre (Swipe / Drag) en coordenadas normalizadas.
     */
    fun injectSwipe(normX1: Double, normY1: Double, normX2: Double, normY2: Double, durationMs: Int = 200) {
        val displayId = virtualDisplayManager.currentDisplayId
        val x1 = (normX1.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_WIDTH).toFloat()
        val y1 = (normY1.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_HEIGHT).toFloat()
        val x2 = (normX2.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_WIDTH).toFloat()
        val y2 = (normY2.coerceIn(0.0, 1.0) * VirtualDisplayManager.DEFAULT_HEIGHT).toFloat()

        val now = SystemClock.uptimeMillis()
        lastDownTime = now
        injectDirectMotionEvent(MotionEvent.ACTION_DOWN, x1, y1, now, displayId)
        injectDirectMotionEvent(MotionEvent.ACTION_MOVE, x2, y2, now + durationMs, displayId)
        injectDirectMotionEvent(MotionEvent.ACTION_UP, x2, y2, now + durationMs + 20, displayId)
    }

    /**
     * Inyecta un evento de tecla de sistema (ej. Back = 4, Home = 3).
     */
    fun injectKeyEvent(keyCode: Int) {
        // En Android Auto el control es directo por OAA
        Log.d(TAG, "injectKeyEvent keycode=$keyCode")
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
