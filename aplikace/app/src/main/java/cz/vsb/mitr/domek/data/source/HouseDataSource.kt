package cz.vsb.mitr.domek.data.source

import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import java.time.LocalDateTime
import kotlinx.coroutines.flow.StateFlow

enum class SourceKind { BLUETOOTH, SIMULATION }

data class FoundDevice(val address: String, val name: String, val rssi: Int)

// receivedAtMs je monotónní čas pro stáří dat, receivedAtWall čas telefonu pro porovnání s hodinami Raspberry.
data class ReceivedStatus(val status: HouseStatus, val receivedAtMs: Long, val receivedAtWall: LocalDateTime)

sealed interface WriteResult {
    data object Ok : WriteResult

    // Raspberry důvod odmítnutí neřekne, aplikace ho odvozuje z toho, co poslala (spec 4.3).
    data class Rejected(val gattStatus: Int) : WriteResult

    data object NotConnected : WriteResult
    data object Timeout : WriteResult
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object NoBluetooth : ConnectionState
    data object PermissionsMissing : ConnectionState
    data object BluetoothOff : ConnectionState
    data object LocationOff : ConnectionState
    data object Scanning : ConnectionState
    data class ChoosingDevice(val devices: List<FoundDevice>) : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Preparing : ConnectionState

    // rssi je null, dokud se signál nepřečetl.
    data class Connected(val address: String, val mtu: Int, val rssi: Int?) : ConnectionState

    // retryAtMs je na stejných hodinách jako TimeSource.nowMs().
    data class WaitingToReconnect(val attempt: Int, val retryAtMs: Long) : ConnectionState

    data object DisconnectedByUser : ConnectionState
    data object MissingService : ConnectionState
    data object Simulated : ConnectionState
}

interface HouseDataSource {
    val connection: StateFlow<ConnectionState>
    val status: StateFlow<ReceivedStatus?>
    val settings: StateFlow<HouseSettings?>

    fun start()
    fun stop()
    suspend fun writeSettings(changes: List<SettingChange>): WriteResult

    // Zapíše do Nastavení uloženou barvu a jas světla, jen části, které nejsou null. Světlo tím nezapne ani nevypne
    // (docs/ble-rozhrani.md, Nastavení).
    suspend fun writeStoredLook(color: Rgb?, brightness: Int?): WriteResult
    suspend fun sendCommand(command: HouseCommand): WriteResult

    // Přečte Nastavení znovu, výsledek přijde tokem settings. Bez spojení nic neudělá.
    suspend fun refreshSettings()
}

interface BleControls {
    fun chooseDevice(address: String)
    fun disconnectByUser()
    fun connectByUser()
    fun retry()
}

enum class SimulationAction(val label: String) {
    HEAT_SENSOR("Zahřát čidlo"),
    COVER_LIGHT_SENSOR("Zakrýt čidlo světla"),
    MOTION("Pohyb"),
    TOGGLE_SENSOR_FAULT("Porucha BME280"),
    PAUSE_DATA("Výpadek dat"),
}

interface SimulationControls {
    fun perform(action: SimulationAction)
}
