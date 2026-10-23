package cz.vsb.mitr.domek.data.model

import kotlinx.serialization.json.Json

// Raspberry může časem přidat klíče; stará aplikace je má přeskočit, ne selhat.
val HouseJson: Json = Json { ignoreUnknownKeys = true }

fun decodeStatus(bytes: ByteArray): HouseStatus? = decodeOrNull(bytes)

fun decodeSettings(bytes: ByteArray): HouseSettings? = decodeOrNull(bytes)

// Data z BLE můžou být zkrácená nebo rozbitá; jakákoli chyba znamená „nepoužitelná data“, ne pád aplikace.
private inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
    try {
        HouseJson.decodeFromString<T>(bytes.decodeToString())
    } catch (e: Exception) {
        null
    }
