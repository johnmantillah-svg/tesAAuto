package com.tesaauto.mirror.video

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

class ThermalAdaptationManager(
    private val context: Context,
    private val onThermalThrottleChanged: (TargetProfile) -> Unit
) {
    companion object {
        private const val TAG = "ThermalAdaptation"
    }

    data class TargetProfile(
        val targetFps: Int,
        val targetBitrate: Int,
        val thermalStatusName: String
    )

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    var currentProfile: TargetProfile = TargetProfile(60, 3_500_000, "NONE")
        private set

    fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                handleThermalStatusChange(status)
            }
            try {
                powerManager.addThermalStatusListener(context.mainExecutor, thermalListener!!)
                val currentStatus = powerManager.currentThermalStatus
                handleThermalStatusChange(currentStatus)
                Log.i(TAG, "ThermalStatusListener registrado. Estado inicial: $currentStatus")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo registrar ThermalStatusListener", e)
            }
        } else {
            Log.i(TAG, "ThermalStatusListener no soportado en esta versión de Android o PowerManager nulo")
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener!!)
                Log.i(TAG, "ThermalStatusListener desregistrado")
            } catch (e: Exception) {
                Log.w(TAG, "Error desregistrando ThermalStatusListener", e)
            }
            thermalListener = null
        }
    }

    /**
     * Mapea el nivel térmico del hardware a perfiles de codificación adaptativos.
     */
    fun handleThermalStatusChange(status: Int) {
        val newProfile = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                TargetProfile(60, 3_500_000, "NORMAL/LIGHT")
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                TargetProfile(45, 2_800_000, "MODERATE")
            }
            PowerManager.THERMAL_STATUS_SEVERE -> {
                TargetProfile(30, 2_000_000, "SEVERE")
            }
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                TargetProfile(20, 1_200_000, "CRITICAL")
            }
            else -> TargetProfile(30, 2_000_000, "UNKNOWN")
        }

        if (newProfile != currentProfile) {
            currentProfile = newProfile
            Log.w(TAG, "Transición térmica detectada -> Perfil: ${newProfile.thermalStatusName} (Target: ${newProfile.targetFps} FPS, ${newProfile.targetBitrate / 1000} kbps)")
            onThermalThrottleChanged(newProfile)
        }
    }
}
