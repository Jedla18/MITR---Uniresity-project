package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import cz.vsb.mitr.domek.data.model.storedLookPatch
import cz.vsb.mitr.domek.data.model.toJsonObject
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SimulationControls
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.data.source.WriteResult
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Status, kterým simulace odmítá neplatný zápis nebo příkaz (spec 5.4).
const val SIM_REJECTED_STATUS = 128

// Jeden tik je jedna sekunda modelu, takže simulace běží v reálném čase.
private const val TICK_MS = 1_000L
private const val TICK_SECONDS = 1.0
internal const val DATA_PAUSE_MS = 10_000L

// Nejvíc sekund modelu, které jeden tik dožene po spánku telefonu (10 minut).
private const val MAX_CATCH_UP_STEPS = 600L

// Zdroj dat bez hardwaru (spec 5.4): model HouseSimulation obalený do korutin, aby se navenek choval jako Raspberry.
class SimulatedHouseDataSource(
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val wallClock: () -> LocalDateTime,
    private val writeLatencyMs: Long = 60,
) : HouseDataSource, SimulationControls {
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _status = MutableStateFlow<ReceivedStatus?>(null)
    private val _settings = MutableStateFlow<HouseSettings?>(null)

    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val status: StateFlow<ReceivedStatus?> = _status.asStateFlow()
    override val settings: StateFlow<HouseSettings?> = _settings.asStateFlow()

    // Model není bezpečný pro víc vláken: tik, zápisy, příkazy i ovládání simulace na něj sahají jen přes tenhle zámek.
    private val modelLock = Mutex()

    // Model právě běžícího zdroje, před start() a po stop() null. Volatile, protože ho čtou i zápisy z cizích vláken.
    @Volatile
    private var model: HouseSimulation? = null

    private var tickJob: Job? = null

    // Do tohoto času (TimeSource) se Stav neposílá (Výpadek dat); model mezitím běží dál.
    @Volatile
    private var silentUntilMs = Long.MIN_VALUE

    override fun start() {
        if (tickJob?.isActive == true) return
        val simulation = HouseSimulation(wallClock())
        silentUntilMs = Long.MIN_VALUE
        _connection.value = ConnectionState.Simulated
        _settings.value = simulation.settings
        // Model je zatím jen tady, takže první Stav zámek nepotřebuje. Díky tomu je hotový už po návratu ze start()
        // a po opětovném spuštění nezůstane vidět nic ze starého běhu.
        emitStatus(simulation)
        model = simulation
        val startedAtMs = time.nowMs()
        tickJob = scope.launch {
            // Model se posouvá o skutečně uplynulý čas, ne o počet tiků: ve spánku telefonu delay stojí, ale monotónní čas běží
            // dál. Dožene se po sekundách, aby termostat rozhodoval každou sekundu, a nejvýš o MAX_CATCH_UP_STEPS najednou, aby
            // dlouhý spánek nezdržel UI; zbytek spánku se zahodí.
            var simulatedUntilMs = startedAtMs
            while (true) {
                delay(TICK_MS)
                modelLock.withLock {
                    val nowMs = time.nowMs()
                    val dueSteps = (nowMs - simulatedUntilMs) / TICK_MS
                    val steps = dueSteps.coerceIn(0, MAX_CATCH_UP_STEPS)
                    simulatedUntilMs = if (dueSteps > steps) nowMs else simulatedUntilMs + steps * TICK_MS
                    repeat(steps.toInt()) { simulation.advance(TICK_SECONDS) }
                    emitStatus(simulation)
                }
            }
        }
    }

    override fun stop() {
        tickJob?.cancel()
        model = null
        _connection.value = ConnectionState.Idle
    }

    override suspend fun writeSettings(changes: List<SettingChange>): WriteResult =
        writeToModel { simulation -> simulation.writeSettings(changes.toJsonObject()) }

    override suspend fun writeStoredLook(color: Rgb?, brightness: Int?): WriteResult =
        writeToModel { simulation -> simulation.writeSettings(storedLookPatch(color, brightness)) }

    override suspend fun sendCommand(command: HouseCommand): WriteResult =
        writeToModel { simulation -> simulation.command(command.toJsonObject()) }

    override suspend fun refreshSettings() {
        modelLock.withLock { model?.let { _settings.value = it.settings } }
    }

    override fun perform(action: SimulationAction) {
        when (action) {
            SimulationAction.HEAT_SENSOR -> changeModel { it.heatSensor() }
            SimulationAction.COVER_LIGHT_SENSOR -> changeModel { it.coverLightSensor() }
            SimulationAction.MOTION -> changeModel { it.triggerMotion() }
            SimulationAction.TOGGLE_SENSOR_FAULT -> changeModel { it.sensorFault = !it.sensorFault }
            SimulationAction.PAUSE_DATA -> silentUntilMs = time.nowMs() + DATA_PAUSE_MS
        }
    }

    // Zdroj, který neběží, zápis odmítne hned. Jinak zápis i příkaz dorazí do modelu se zpožděním jako přes Bluetooth:
    // přijatý hned pošle Nastavení a nový Stav, odmítnutý skončí statusem 128. Zdroj, který se mezitím zastavil nebo
    // znovu spustil, cíl zápisu už nemá: zápis patří k běhu, který skončil, a do nového modelu se nesmí dostat.
    private suspend fun writeToModel(write: (HouseSimulation) -> Boolean): WriteResult {
        val target = model ?: return WriteResult.NotConnected
        delay(writeLatencyMs)
        return modelLock.withLock {
            when {
                model !== target -> WriteResult.NotConnected
                !write(target) -> WriteResult.Rejected(SIM_REJECTED_STATUS)
                else -> {
                    // Nastavení jde před Stavem jako na Raspberry. Mění ho i příkaz svetlo s objektem; nezměněné
                    // Nastavení StateFlow znovu nepošle (docs/ble-rozhrani.md, Příkaz → Světlo).
                    _settings.value = target.settings
                    emitStatus(target)
                    WriteResult.Ok
                }
            }
        }
    }

    // perform() není suspend, takže na zámek čeká ve vlastní korutině. Akce zadaná těsně před stop() už model nenajde.
    private fun changeModel(change: (HouseSimulation) -> Unit) {
        scope.launch {
            modelLock.withLock {
                val simulation = model ?: return@withLock
                change(simulation)
                emitStatus(simulation)
            }
        }
    }

    private fun emitStatus(simulation: HouseSimulation) {
        val now = time.nowMs()
        if (now < silentUntilMs) return
        _status.value = ReceivedStatus(simulation.status(), now, wallClock())
    }
}
