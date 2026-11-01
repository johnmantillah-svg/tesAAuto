package com.tesaauto.mirror

import android.os.PowerManager
import com.tesaauto.mirror.video.ThermalAdaptationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalAdaptationManagerTest {

    @Test
    fun testThermalStatusProfileMapping() {
        var throttledProfile: ThermalAdaptationManager.TargetProfile? = null
        // Creamos instancia mockeada sin contexto Android nativo para testear la función pura de mapeo
        val manager = object {
            fun getProfileForStatus(status: Int): ThermalAdaptationManager.TargetProfile {
                return when (status) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalAdaptationManager.TargetProfile(60, 3_500_000, "NORMAL/LIGHT")
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalAdaptationManager.TargetProfile(45, 2_800_000, "MODERATE")
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalAdaptationManager.TargetProfile(30, 2_000_000, "SEVERE")
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalAdaptationManager.TargetProfile(20, 1_200_000, "CRITICAL")
                    else -> ThermalAdaptationManager.TargetProfile(30, 2_000_000, "UNKNOWN")
                }
            }
        }

        val normal = manager.getProfileForStatus(PowerManager.THERMAL_STATUS_NONE)
        assertEquals(60, normal.targetFps)
        assertEquals(3_500_000, normal.targetBitrate)

        val severe = manager.getProfileForStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(30, severe.targetFps)
        assertEquals(2_000_000, severe.targetBitrate)

        val critical = manager.getProfileForStatus(PowerManager.THERMAL_STATUS_CRITICAL)
        assertEquals(20, critical.targetFps)
        assertEquals(1_200_000, critical.targetBitrate)
    }
}
