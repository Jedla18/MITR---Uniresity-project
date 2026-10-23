package cz.vsb.mitr.domek.ui.components

import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

// Jeden test na každý řádek tabulky štítků (spec 6.0).
class StatusPillTest {
    // Stav přijatý v 10 000 ms, takže stáří dat určuje nowMs.
    private val received = ReceivedStatus(EXAMPLE, 10_000, LocalDateTime.of(2026, 10, 20, 10, 2, 15))

    private fun pill(connection: ConnectionState, nowMs: Long = 10_000) =
        pillFor(HouseUiState(connection = connection, received = received, nowMs = nowMs))

    // Data stará 7,4 s: chyby spojení a odpojení mají přednost i před starými daty.
    private val staleNow = 17_400L

    @Test
    fun noBluetooth() {
        assertEquals(PillModel("Bez Bluetooth", PillTone.ERROR), pill(ConnectionState.NoBluetooth, staleNow))
    }

    @Test
    fun permissionsMissing() {
        assertEquals(PillModel("Chybí oprávnění", PillTone.ERROR), pill(ConnectionState.PermissionsMissing, staleNow))
    }

    @Test
    fun bluetoothOff() {
        assertEquals(PillModel("Bluetooth vypnutý", PillTone.ERROR), pill(ConnectionState.BluetoothOff, staleNow))
    }

    @Test
    fun locationOff() {
        assertEquals(PillModel("Poloha vypnutá", PillTone.ERROR), pill(ConnectionState.LocationOff, staleNow))
    }

    @Test
    fun missingService() {
        assertEquals(PillModel("Neznámé zařízení", PillTone.ERROR), pill(ConnectionState.MissingService, staleNow))
    }

    @Test
    fun waitingToReconnectCountsWholeSecondsUpAndNeverBelowZero() {
        fun waiting(retryAtMs: Long) = pill(ConnectionState.WaitingToReconnect(attempt = 2, retryAtMs = retryAtMs), staleNow)

        assertEquals(PillModel("Odpojeno · znovu za 4${NBSP}s", PillTone.ERROR), waiting(21_400))
        assertEquals(PillModel("Odpojeno · znovu za 4${NBSP}s", PillTone.ERROR), waiting(20_500))
        assertEquals(PillModel("Odpojeno · znovu za 5${NBSP}s", PillTone.ERROR), waiting(21_401))
        // Čas pokusu už uplynul, ale stav se ještě nezměnil.
        assertEquals(PillModel("Odpojeno · znovu za 0${NBSP}s", PillTone.ERROR), waiting(16_000))
    }

    @Test
    fun disconnectedByUser() {
        assertEquals(PillModel("Odpojeno", PillTone.ERROR), pill(ConnectionState.DisconnectedByUser, staleNow))
    }

    @Test
    fun idleAndScanningSearch() {
        assertEquals(PillModel("Hledám…", PillTone.NEUTRAL), pill(ConnectionState.Idle, staleNow))
        assertEquals(PillModel("Hledám…", PillTone.NEUTRAL), pill(ConnectionState.Scanning, staleNow))
    }

    @Test
    fun choosingDevice() {
        val devices = listOf(FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61), FoundDevice("B8:27:EB:00:00:01", "Termostat", -75))
        assertEquals(PillModel("Vyber dům", PillTone.NEUTRAL), pill(ConnectionState.ChoosingDevice(devices), staleNow))
    }

    @Test
    fun connectingAndPreparing() {
        assertEquals(PillModel("Připojuji…", PillTone.CONNECTING), pill(ConnectionState.Connecting(attempt = 1), staleNow))
        assertEquals(PillModel("Připojuji…", PillTone.CONNECTING), pill(ConnectionState.Preparing, staleNow))
    }

    @Test
    fun connectedIsConnectedUntilTheDataAreStale() {
        val connected = ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, -61)

        assertEquals(PillModel("Připojeno", PillTone.OK), pill(connected, nowMs = 13_000))
        assertEquals(PillModel("Stará data · 3${NBSP}s", PillTone.STALE), pill(connected, nowMs = 13_001))
        assertEquals(PillModel("Stará data · 7${NBSP}s", PillTone.STALE), pill(connected, staleNow))
        // Před prvním Stavem nejsou žádná data, která by mohla být stará.
        assertEquals(PillModel("Připojeno", PillTone.OK), pillFor(HouseUiState(connection = connected, nowMs = 60_000)))
    }

    @Test
    fun simulatedIsSimulationUntilTheDataAreStale() {
        assertEquals(PillModel("Simulace", PillTone.SIMULATION), pill(ConnectionState.Simulated, nowMs = 13_000))
        assertEquals(PillModel("Stará data · 7${NBSP}s", PillTone.STALE), pill(ConnectionState.Simulated, staleNow))
    }
}
