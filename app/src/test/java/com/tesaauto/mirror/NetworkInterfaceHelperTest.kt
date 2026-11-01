package com.tesaauto.mirror

import com.tesaauto.mirror.network.NetworkInterfaceHelper
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfaceHelperTest {

    @Test
    fun testIpAddressDiscoveryDoesNotCrash() {
        val result = NetworkInterfaceHelper.getHotspotOrWifiIpAddress()
        // En entorno de ejecución de prueba en host puede retornar IP o null, pero no debe lanzar excepción
        if (result != null) {
            assertNotNull(result.ipAddress)
            assertTrue(result.ipAddress.isNotEmpty())
        }
    }
}
