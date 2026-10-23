package cz.vsb.mitr.domek.testing

import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import cz.vsb.mitr.domek.data.source.BleControls
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SimulationControls
import cz.vsb.mitr.domek.data.source.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

// Zapsaná uložená barva a jas světla, tak jak je ViewModel předal zdroji.
data class StoredLook(val color: Rgb? = null, val brightness: Int? = null)

// Zdroj dat pro testy ViewModelu: toky nastavuje test a volání si jen zaznamenává, sám od sebe nic nedělá.
// Po stop() si nechává poslední Stav i Nastavení, stejně jako skutečné zdroje.
class FakeHouseDataSource(
    connection: ConnectionState = ConnectionState.Idle,
    status: ReceivedStatus? = null,
    settings: HouseSettings? = null,
) : HouseDataSource, BleControls, SimulationControls {
    override val connection: MutableStateFlow<ConnectionState> = MutableStateFlow(connection)
    override val status: MutableStateFlow<ReceivedStatus?> = MutableStateFlow(status)
    override val settings: MutableStateFlow<HouseSettings?> = MutableStateFlow(settings)

    var started = false
        private set
    var stopped = false
        private set

    val writtenSettings = mutableListOf<List<SettingChange>>()
    val writtenLooks = mutableListOf<StoredLook>()
    val sentCommands = mutableListOf<HouseCommand>()
    var chosen: String? = null
        private set

    // Názvy volaných metod BleControls (kromě chooseDevice, které má vlastní záznam chosen).
    val controlCalls = mutableListOf<String>()
    val simActions = mutableListOf<SimulationAction>()

    var settingsRefreshes = 0
        private set

    var nextWriteResult: WriteResult = WriteResult.Ok
    var nextCommandResult: WriteResult = WriteResult.Ok

    // Zápis, resp. příkaz po nastavení brány na ni počká a pak ji vynuluje, takže další už neblokuje. Zaznamená se
    // hned, ale odpoví až po otevření brány, a to výsledkem, který platí v tu chvíli.
    var writeGate: CompletableDeferred<Unit>? = null
    var commandGate: CompletableDeferred<Unit>? = null

    // Čtení Nastavení po nastavení brány na ni počká stejně jako zápis, jinak skončí hned.
    var refreshGate: CompletableDeferred<Unit>? = null

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
    }

    override suspend fun writeSettings(changes: List<SettingChange>): WriteResult {
        writtenSettings.add(changes)
        writeGate?.let { gate ->
            gate.await()
            writeGate = null
        }
        return nextWriteResult
    }

    // Na Raspberry je to zápis Nastavení, proto platí stejná brána i výsledek jako u writeSettings.
    override suspend fun writeStoredLook(color: Rgb?, brightness: Int?): WriteResult {
        writtenLooks += StoredLook(color, brightness)
        writeGate?.let { gate ->
            gate.await()
            writeGate = null
        }
        return nextWriteResult
    }

    override suspend fun sendCommand(command: HouseCommand): WriteResult {
        sentCommands += command
        commandGate?.let { gate ->
            gate.await()
            commandGate = null
        }
        return nextCommandResult
    }

    override suspend fun refreshSettings() {
        settingsRefreshes++
        refreshGate?.let { gate ->
            gate.await()
            refreshGate = null
        }
    }

    override fun chooseDevice(address: String) {
        chosen = address
    }

    override fun disconnectByUser() {
        controlCalls += "disconnectByUser"
    }

    override fun connectByUser() {
        controlCalls += "connectByUser"
    }

    override fun retry() {
        controlCalls += "retry"
    }

    override fun perform(action: SimulationAction) {
        simActions += action
    }
}
