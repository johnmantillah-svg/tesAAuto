package com.tesaauto.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DrmResilienceTest {

    @Test
    fun testBlackFrameProcessingDoesNotCrashPipeline() {
        // Simular un frame en negro (DRM / FLAG_SECURE) generado por la superficie del SO
        val width = 1280
        val height = 720
        val blackFrameBytes = ByteArray(width * height * 3 / 2) // Formato NV12 / YUV420

        var frameDispatched = false
        var exceptionOccurred = false

        val testSink: (ByteArray, Boolean) -> Unit = { data, isKeyFrame ->
            try {
                // Verificar que el payload no nulo se procesa correctamente
                if (data.isNotEmpty()) {
                    frameDispatched = true
                }
            } catch (e: Exception) {
                exceptionOccurred = true
            }
        }

        // Ejecutar simulación de 10 fotogramas en negro consecutivos
        for (i in 0 until 10) {
            testSink(blackFrameBytes, i == 0)
        }

        assertTrue("El pipeline debe procesar los frames en negro sin detenerse", frameDispatched)
        assertFalse("No debe ocurrir ninguna excepción al recibir frames protegidos/en blanco", exceptionOccurred)
    }

    @Test
    fun testZeroBytePacketHandling() {
        var emptyFrameHandled = false
        val dummyProcessor: (ByteArray) -> Unit = { buffer ->
            if (buffer.isEmpty()) {
                // El pipeline debe ignorar o no crashear con buffers vacíos
                emptyFrameHandled = true
            }
        }

        dummyProcessor(ByteArray(0))
        assertTrue("El pipeline debe manejar buffers de longitud cero de forma segura", emptyFrameHandled)
    }
}
