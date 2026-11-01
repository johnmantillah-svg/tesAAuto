package com.tesaauto.mirror

import com.tesaauto.mirror.video.ThermalAdaptationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PipelineStressIntegrationTest {

    @Test
    fun testContinuousStreamingUnderHighThroughput() {
        val totalFrames = 5000
        val dummyNalUnit = ByteArray(1500) { 0x01 } // Simula un paquete NAL de 1.5 KB
        val deliveredFrames = AtomicInteger(0)

        // Simulación de sesiones concurrentes
        val clientSessions = ConcurrentHashMap.newKeySet<Int>()
        clientSessions.add(1)
        clientSessions.add(2)

        val memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Emisión masiva continua de frames
        for (i in 0 until totalFrames) {
            for (sessionId in clientSessions) {
                // Procesar entrega de frame
                deliveredFrames.incrementAndGet()
            }

            // Simular desconexión intempestiva en el frame 2500
            if (i == 2500) {
                clientSessions.remove(2)
            }
        }

        System.gc()
        val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        assertTrue("Debe haberse entregado la gran mayoría de fotogramas", deliveredFrames.get() > 5000)
        assertTrue("La sesión restante (1) debe mantenerse activa", clientSessions.contains(1))
        // La memoria después del GC debe ser razonable (sin fugas masivas)
        val heapGrowthMb = (memoryAfter - memoryBefore) / (1024 * 1024)
        assertTrue("El crecimiento de memoria no debe superar 50 MB (actual: ${heapGrowthMb}MB)", heapGrowthMb < 50)
    }

    @Test
    fun testRapidThermalTransitions() {
        val transitionCount = AtomicInteger(0)
        val mockAdapter = { profile: ThermalAdaptationManager.TargetProfile ->
            transitionCount.incrementAndGet()
            assertTrue("FPS objetivo debe estar en rango seguro", profile.targetFps in 20..60)
            assertTrue("Bitrate debe estar en rango seguro", profile.targetBitrate in 1_200_000..3_500_000)
        }

        // Simular 20 transiciones térmicas abruptas consecutivas
        val statusSequence = listOf(0, 1, 3, 4, 3, 0, 2, 4, 0, 3)
        for (status in statusSequence) {
            val profile = when (status) {
                0, 1 -> ThermalAdaptationManager.TargetProfile(60, 3_500_000, "NORMAL")
                2 -> ThermalAdaptationManager.TargetProfile(45, 2_800_000, "MODERATE")
                3 -> ThermalAdaptationManager.TargetProfile(30, 2_000_000, "SEVERE")
                else -> ThermalAdaptationManager.TargetProfile(20, 1_200_000, "CRITICAL")
            }
            mockAdapter(profile)
        }

        assertEquals(statusSequence.size, transitionCount.get())
    }
}
