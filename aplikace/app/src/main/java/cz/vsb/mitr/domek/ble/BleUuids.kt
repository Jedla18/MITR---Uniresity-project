package cz.vsb.mitr.domek.ble

import java.util.UUID

// Pevně dané rozhraní Raspberry (spec 4.1), hodnoty se nesmí měnit.
object BleUuids {
    val HOUSE_SERVICE: UUID = UUID.fromString("5bf00001-f758-4c41-a34e-bab58ce87673")
    val STATUS: UUID = UUID.fromString("5bf00010-f758-4c41-a34e-bab58ce87673")
    val SETTINGS: UUID = UUID.fromString("5bf00011-f758-4c41-a34e-bab58ce87673")
    val COMMAND: UUID = UUID.fromString("5bf00012-f758-4c41-a34e-bab58ce87673")

    // Client Characteristic Configuration Descriptor: zápisem do něj se zapínají notifikace.
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME = "Termostat"
    const val REQUESTED_MTU = 517
}
