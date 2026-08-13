package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun identityIsStableLowercaseHexForSameAndroidID() {
        val first = deriveAtrustDeviceID("android-device-id")
        val second = deriveAtrustDeviceID("android-device-id")

        assertEquals(first, second)
        assertEquals("ee65e2b497250e60de8b1be481604506", first)
        assertEquals(32, first?.length)
        assertTrue(first.orEmpty().matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun differentDevicesProduceDifferentIdentities() {
        assertFalse(deriveAtrustDeviceID("device-a") == deriveAtrustDeviceID("device-b"))
    }

    @Test
    fun missingAndroidIDDoesNotFallBackToRandomIdentity() {
        assertNull(deriveAtrustDeviceID(null))
        assertNull(deriveAtrustDeviceID("   "))
    }
}
