package cz.vsb.mitr.domek.ble

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePermissionsTest {
    @Test
    fun android12AndNewerAsksForScanAndConnect() {
        val expected = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(expected, BlePermissions.required(31))
        assertEquals(expected, BlePermissions.required(37))
    }

    @Test
    fun android11AndOlderAsksForFineLocation() {
        val expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(expected, BlePermissions.required(30))
        assertEquals(expected, BlePermissions.required(26))
    }

    @Test
    fun locationServicesMatterOnlyBeforeAndroid12() {
        assertTrue(BlePermissions.needsLocationServices(26))
        assertTrue(BlePermissions.needsLocationServices(30))
        assertFalse(BlePermissions.needsLocationServices(31))
        assertFalse(BlePermissions.needsLocationServices(37))
    }
}
