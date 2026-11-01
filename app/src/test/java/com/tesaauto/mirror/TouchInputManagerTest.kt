package com.tesaauto.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchInputManagerTest {

    @Test
    fun testCoordinateNormalizationAndClamping() {
        val width = 1280
        val height = 720

        fun toAbsoluteCoords(normX: Double, normY: Double): Pair<Int, Int> {
            val clampedX = normX.coerceIn(0.0, 1.0)
            val clampedY = normY.coerceIn(0.0, 1.0)
            return Pair((clampedX * width).toInt(), (clampedY * height).toInt())
        }

        // Centro exacto
        val center = toAbsoluteCoords(0.5, 0.5)
        assertEquals(640, center.first)
        assertEquals(360, center.second)

        // Esquina superior izquierda
        val topLeft = toAbsoluteCoords(0.0, 0.0)
        assertEquals(0, topLeft.first)
        assertEquals(0, topLeft.second)

        // Esquina inferior derecha
        val bottomRight = toAbsoluteCoords(1.0, 1.0)
        assertEquals(1280, bottomRight.first)
        assertEquals(720, bottomRight.second)

        // Valores fuera de límites (deben ser clampeados)
        val clampedOut = toAbsoluteCoords(-0.2, 1.5)
        assertEquals(0, clampedOut.first)
        assertEquals(720, clampedOut.second)
    }
}
