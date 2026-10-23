package cz.vsb.mitr.domek.ble

import org.junit.Assert.assertEquals
import org.junit.Test

// Hodnoty jsou ve spec 4.1 pevně dané a překlep by se ukázal až na telefonu, proto je test opisuje znovu jako text.
class BleUuidsTest {
    @Test
    fun houseServiceAndCharacteristicsMatchTheSpec() {
        assertEquals("5bf00001-f758-4c41-a34e-bab58ce87673", BleUuids.HOUSE_SERVICE.toString())
        assertEquals("5bf00010-f758-4c41-a34e-bab58ce87673", BleUuids.STATUS.toString())
        assertEquals("5bf00011-f758-4c41-a34e-bab58ce87673", BleUuids.SETTINGS.toString())
        assertEquals("5bf00012-f758-4c41-a34e-bab58ce87673", BleUuids.COMMAND.toString())
    }

    @Test
    fun cccdIsTheStandardClientConfigurationDescriptor() {
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleUuids.CCCD.toString())
    }

    @Test
    fun deviceNameAndMtuMatchTheSpec() {
        assertEquals("Termostat", BleUuids.DEVICE_NAME)
        assertEquals(517, BleUuids.REQUESTED_MTU)
    }
}
