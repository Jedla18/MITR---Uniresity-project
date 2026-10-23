package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.data.source.WriteResult
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.FakeHouseDataSource
import cz.vsb.mitr.domek.testing.HouseViewModelTestBase
import cz.vsb.mitr.domek.viewmodel.LogCategory.CONNECTION
import cz.vsb.mitr.domek.viewmodel.LogCategory.LIGHT
import cz.vsb.mitr.domek.viewmodel.LogCategory.OTHER
import cz.vsb.mitr.domek.viewmodel.LogCategory.THERMOSTAT
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

// Deník ve ViewModelu: co mu ViewModel předává ze zdroje dat a ze svých akcí. Pravidla událostí testuje HouseLogTest.
// Čísla v komentářích jsou virtuální čas testu v ms.
@OptIn(ExperimentalCoroutinesApi::class)
class HouseViewModelLogTest : HouseViewModelTestBase() {
    private val connected = ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, -60)
    private val fake = FakeHouseDataSource(connection = connected, settings = DEFAULTS)
    private val calm = EXAMPLE.copy(heating = false)
    private val connectedTitle = "Připojeno k${NBSP}domu Termostat"

    private val HouseViewModel.log: List<LogEntry> get() = uiState.value.log

    private fun HouseViewModel.titles() = log.map { it.event.title }

    private fun TestScope.connectedViewModel(): HouseViewModel = newViewModel { _, _ -> fake }.also { runCurrent() }

    // Spojení

    @Test
    fun theConnectionToTheHouseIsLoggedWithThePhoneTime() = vmTest {
        val vm = connectedViewModel()

        assertEquals(
            listOf(LogEvent(CONNECTION, LogKind.CONNECTED, connectedTitle, "B8:27:EB:4A:19:C2 · MTU${NBSP}517")),
            vm.log.map { it.event },
        )
        assertEquals(startedAt, vm.log.single().at)
    }

    @Test
    fun aLostConnectionAndItsReturnAreLoggedWithTheOutage() = vmTest {
        val vm = connectedViewModel()
        advanceTimeBy(1_000)
        fake.connection.value = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 3_000)
        runCurrent()
        advanceTimeBy(9_000)
        fake.connection.value = connected
        runCurrent()

        assertEquals(listOf("Znovu připojeno", "Spojení přerušeno", connectedTitle), vm.titles())
        assertEquals("výpadek 9${NBSP}s", vm.log.first().event.detail)
        assertEquals(startedAt.plusSeconds(10), vm.log.first().at)
    }

    @Test
    fun startingTheSimulationIsLogged() = vmTest {
        val simulation = FakeHouseDataSource(connection = ConnectionState.Simulated, status = receivedNow(calm), settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else simulation }
        runCurrent()

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()

        assertEquals(listOf(LogEvent(CONNECTION, LogKind.SIMULATION_STARTED, "Simulace spuštěna")), vm.log.map { it.event })
    }

    // Stav

    // První Stav nedá nic, ani když ukazuje pohyb; až rozdíl proti němu.
    @Test
    fun statusChangesAreLoggedAtTheirReceiveTime() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow(calm.copy(motion = true))
        runCurrent()
        advanceTimeBy(2_000)
        fake.status.value = receivedNow(calm.copy(motion = true, heating = true))
        runCurrent()

        assertEquals(listOf("Začalo topit", connectedTitle), vm.titles())
        assertEquals(startedAt.plusSeconds(2), vm.log.first().at)
    }

    // Tik po 500 ms: stáří 3000 ms ještě nevadí, 3500 ms už ano. Výpadek je doba mezi Stavy.
    @Test
    fun staleDataAndTheirReturnAreLogged() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow(calm)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(connectedTitle), vm.titles())

        advanceTimeBy(500)
        runCurrent()
        assertEquals(LogEvent(CONNECTION, LogKind.DATA_STALE, "Data jsou stará", "Raspberry přestalo posílat stav"), vm.log.first().event)
        assertEquals(startedAt.plusNanos(3_500_000_000), vm.log.first().at)

        advanceTimeBy(6_500)
        fake.status.value = receivedNow(calm)
        runCurrent()
        assertEquals(listOf("Data zase chodí", "Data jsou stará", connectedTitle), vm.titles())
        assertEquals("výpadek 10${NBSP}s", vm.log.first().event.detail)
    }

    // Nastavení

    @Test
    fun settingsChangedOnTheRaspberryAreLogged() = vmTest {
        val vm = connectedViewModel()
        fake.settings.value = DEFAULTS.copy(targetDay = 26.0)
        runCurrent()

        assertEquals(
            LogEvent(THERMOSTAT, LogKind.SETTING, "Denní cíl: 23,0 → 26,0${NBSP}°C", "změna na Raspberry"),
            vm.log.first().event,
        )
        assertEquals(2, vm.log.size)
    }

    @Test
    fun aSettingTheAppWroteIsLoggedAsAChangeFromTheApp() = vmTest {
        val vm = connectedViewModel()
        vm.setMode(ThermostatMode.MANUAL)
        runCurrent()
        advanceTimeBy(9_000)
        fake.settings.value = DEFAULTS.copy(mode = ThermostatMode.MANUAL)
        runCurrent()

        assertEquals(
            LogEvent(THERMOSTAT, LogKind.SETTING, "Režim termostatu: Auto → Ruční", "změna z${NBSP}aplikace"),
            vm.log.first().event,
        )
    }

    // Výběr jasu aplikace zapíše do Nastavení (starší program ho uloží z příkazu světla), změna jasu je tedy z aplikace.
    @Test
    fun brightnessSentByTheAppIsLoggedAsAChangeFromTheApp() = vmTest {
        val vm = connectedViewModel()
        vm.setLightBrightness(60)
        runCurrent()
        fake.settings.value = DEFAULTS.copy(lightBrightness = 60)
        runCurrent()

        assertEquals(LogEvent(LIGHT, LogKind.SETTING, "Jas světla: 100 → 60${NBSP}%", "změna z${NBSP}aplikace"), vm.log.first().event)
    }

    // Hlášky, simulace a hodiny

    @Test
    fun aRejectedWriteMessageIsLogged() = vmTest {
        val vm = connectedViewModel()
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(19, 0))
        runCurrent()

        assertEquals(
            LogEvent(
                CONNECTION,
                LogKind.MESSAGE,
                "Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo.",
            ),
            vm.log.first().event,
        )
    }

    @Test
    fun simulationButtonsAreLogged() = vmTest {
        val vm = connectedViewModel()
        vm.simulation(SimulationAction.HEAT_SENSOR)
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: zahřáté čidlo", "venku o${NBSP}8${NBSP}°C víc na 30${NBSP}s"),
            vm.log.first().event,
        )

        // Porucha, kterou Stav hlásí, se tlačítkem vypíná.
        fake.status.value = receivedNow(calm.copy(errors = listOf(METEO_FAULT)))
        runCurrent()
        vm.simulation(SimulationAction.TOGGLE_SENSOR_FAULT)
        assertEquals("Simulace: porucha BME280 vypnutá", vm.log.first().event.title)
        assertEquals(listOf(SimulationAction.HEAT_SENSOR, SimulationAction.TOGGLE_SENSOR_FAULT), fake.simActions)
    }

    @Test
    fun simulationButtonsOfASourceWithoutSimulationAreNotLogged() = vmTest {
        val plain = object : HouseDataSource by fake {}
        val vm = newViewModel { _, _ -> plain }
        runCurrent()

        vm.simulation(SimulationAction.MOTION)

        assertEquals(listOf(connectedTitle), vm.titles())
    }

    // Srovnání hodin je akce: zapíše se hned, ještě než Raspberry odpoví, aby stálo pod tím, co způsobí.
    @Test
    fun clockSyncIsLoggedBeforeItIsSent() = vmTest {
        val vm = newViewModel(wallClock = { LocalDateTime.of(2026, 10, 20, 14, 3, 0, 250_000_000) }) { _, _ -> fake }
        runCurrent()
        fake.commandGate = CompletableDeferred()
        vm.syncClock()
        runCurrent()

        assertEquals(1, fake.sentCommands.size)
        assertEquals(LogEvent(OTHER, LogKind.CLOCK, "Srovnání hodin", "podle telefonu 14:03:00"), vm.log.first().event)
    }

    @Test
    fun failedClockSyncAddsItsMessageAboveTheAction() = vmTest {
        val vm = connectedViewModel()
        fake.nextCommandResult = WriteResult.Timeout
        vm.syncClock()
        runCurrent()

        assertEquals(listOf("Raspberry neodpovědělo. Změna se možná neprovedla.", "Srovnání hodin", connectedTitle), vm.titles())
    }

    // Spánek telefonu: tik stojí (delay počítá jen dobu, kdy telefon nespí), monotónní čas běží dál. Po probuzení jsou data
    // stará jen proto, že telefon spal, takže mají zase 3 s na nový Stav.
    @Test
    fun staleDataAreNotLoggedRightAfterThePhoneSlept() = vmTest {
        var slept = 0L
        val time = TimeSource { testScheduler.currentTime + slept }
        val vm = newViewModel(wallClock = { phoneTime().plusNanos(slept * 1_000_000) }, time = time) { _, _ -> fake }
        runCurrent()
        fake.status.value = ReceivedStatus(calm, time.nowMs(), phoneTime())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        slept = 120_000
        advanceTimeBy(500) // 121 500: první tik po probuzení
        runCurrent()
        advanceTimeBy(3_000) // 124 500: 3 s po probuzení ještě ne
        runCurrent()
        assertEquals(listOf(connectedTitle), vm.titles())

        advanceTimeBy(500)
        runCurrent()
        assertEquals("Data jsou stará", vm.log.first().event.title)
    }

    // Přepnutí zdroje

    // Nový zdroj začíná s prázdným Deníkem a jeho první Stav i Nastavení zase nejsou s čím srovnat.
    @Test
    fun switchingTheSourceClearsTheLog() = vmTest {
        val second = FakeHouseDataSource(settings = DEFAULTS.copy(targetDay = 30.0))
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        runCurrent()
        fake.status.value = receivedNow(calm)
        runCurrent()
        fake.status.value = receivedNow(calm.copy(heating = true))
        runCurrent()
        assertEquals(2, vm.log.size)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()
        assertEquals(emptyList<LogEntry>(), vm.log)

        second.status.value = receivedNow(calm.copy(motion = true))
        runCurrent()
        assertEquals(emptyList<LogEntry>(), vm.log)
    }
}
