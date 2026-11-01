package com.tesaauto.mirror

import com.tesaauto.mirror.display.VirtualDisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VirtualDisplayManagerTest {

    @Test
    fun testDisplayConfigDefaults() {
        val config = VirtualDisplayManager.DisplayConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(213, config.densityDpi)
        assertEquals("TeslaVirtualDisplay", config.displayName)
    }

    @Test
    fun testCustomDisplayConfig() {
        val config = VirtualDisplayManager.DisplayConfig(
            width = 1920,
            height = 1080,
            densityDpi = 320,
            displayName = "CustomTeslaDisplay"
        )
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(320, config.densityDpi)
        assertEquals("CustomTeslaDisplay", config.displayName)
    }
}
