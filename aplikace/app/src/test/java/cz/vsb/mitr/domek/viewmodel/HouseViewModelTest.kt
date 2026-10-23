package cz.vsb.mitr.domek.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.sim.SIM_DEFAULT_SETTINGS
import cz.vsb.mitr.domek.sim.SimulatedHouseDataSource
import cz.vsb.mitr.domek.testing.FakeHouseDataSource
import cz.vsb.mitr.domek.testing.HouseViewModelTestBase
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HouseViewModelTest : HouseViewModelTestBase() {
    // Start a stav ze zdroje

    @Test
    fun startsBluetoothSourceOnCreate() = vmTest {
        val requested = mutableListOf<Pair<SourceKind, CoroutineScope>>()
        val fake = FakeHouseDataSource()
        val vm = newViewModel { kind, scope -> fake.also { requested += kind to scope } }

        assertTrue(fake.started)
        assertEquals(SourceKind.BLUETOOTH, vm.uiState.value.sourceKind)
        assertEquals(listOf(SourceKind.BLUETOOTH), requested.map { it.first })
        // BLE zdroj počítá s hlavním vláknem a SupervisorJob, proto vždy dostává viewModelScope.
        assertSame(vm.viewModelScope, requested.single().second)
    }

    @Test
    fun mirrorsConnectionStatusAndSettingsOfTheSource() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        runCurrent()
        assertEquals(ConnectionState.Idle, vm.uiState.value.connection)
        assertNull(vm.uiState.value.received)
        assertNull(vm.uiState.value.settings)

        val received = receivedNow()
        fake.connection.value = ConnectionState.Scanning
        fake.status.value = received
        fake.settings.value = DEFAULTS
        runCurrent()

        val state = vm.uiState.value
        assertEquals(ConnectionState.Scanning, state.connection)
        assertEquals(received, state.received)
        assertEquals(EXAMPLE, state.status)
        assertEquals(DEFAULTS, state.settings)
    }

    // Tik a stará data

    @Test
    fun tickRefreshesNowAndPhoneTimeEveryHalfSecond() = vmTest {
        val vm = newViewModel { _, _ -> FakeHouseDataSource() }
        runCurrent()
        assertEquals(0L, vm.uiState.value.nowMs)
        assertEquals(startedAt, vm.uiState.value.phoneNow)

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0L, vm.uiState.value.nowMs)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(500L, vm.uiState.value.nowMs)
        assertEquals(startedAt.plusNanos(500_000_000), vm.uiState.value.phoneNow)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2_000L, vm.uiState.value.nowMs)
        assertEquals(startedAt.plusSeconds(2), vm.uiState.value.phoneNow)
    }

    @Test
    fun tickStopsWhenViewModelScopeIsCancelled() = vmTest {
        val vm = newViewModel { _, _ -> FakeHouseDataSource() }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(500L, vm.uiState.value.nowMs)

        vm.viewModelScope.cancel()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(500L, vm.uiState.value.nowMs)
    }

    // Tik je po 500 ms, takže stáří se přepočítá v 3000 a 3500 ms; stará jsou data až nad 3000 ms.
    @Test
    fun staleAfterThreeSeconds() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        fake.status.value = receivedNow()
        runCurrent()
        assertFalse(vm.uiState.value.isStale)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3_000L, vm.uiState.value.statusAgeMs)
        assertFalse(vm.uiState.value.isStale)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(3_500L, vm.uiState.value.statusAgeMs)
        assertTrue(vm.uiState.value.isStale)
    }

    @Test
    fun newStatusEndsStaleDataAtOnce() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        fake.status.value = receivedNow()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.uiState.value.isStale)

        fake.status.value = receivedNow()
        runCurrent()
        assertFalse(vm.uiState.value.isStale)
        assertEquals(0L, vm.uiState.value.statusAgeMs)
    }

    // Stav přijatý mezi tiky je novější než poslední tik. Bez okamžitého přepočtu hodin by vyšlo záporné stáří a „před X“ by se
    // počítalo ze starého času.
    @Test
    fun statusReceivedBetweenTicksRefreshesClockAtOnce() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        runCurrent()
        advanceTimeBy(700)
        runCurrent()
        assertEquals(500L, vm.uiState.value.nowMs)

        fake.status.value = receivedNow()
        runCurrent()
        val state = vm.uiState.value
        assertEquals(700L, state.nowMs)
        assertEquals(startedAt.plusNanos(700_000_000), state.phoneNow)
        assertEquals(0L, state.statusAgeMs)
    }

    // Graf

    @Test
    fun everyStatusGoesToChart() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        repeat(3) { index ->
            advanceTimeBy(1_000)
            fake.status.value = receivedNow(EXAMPLE.copy(insideTemp = 21.0 + index))
            runCurrent()
        }

        val chart = vm.uiState.value.chart
        assertEquals(3, chart.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), chart.map { it.atMs })
        assertEquals(listOf(21.0, 22.0, 23.0), chart.map { it.inside })
    }

    @Test
    fun chartWindowOnlyChangesStateAndSurvivesSourceSwitch() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        runCurrent()
        assertEquals(ChartWindow.ALL, vm.uiState.value.chartWindow)

        vm.setChartWindow(ChartWindow.FIVE_MIN)
        assertEquals(ChartWindow.FIVE_MIN, vm.uiState.value.chartWindow)
        assertFalse(fake.stopped)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()
        assertEquals(ChartWindow.FIVE_MIN, vm.uiState.value.chartWindow)
    }

    // Přepínání zdroje

    @Test
    fun switchingSourceStopsOldClearsChartStartsNew() = vmTest {
        val first = FakeHouseDataSource(connection = ConnectionState.Scanning, settings = DEFAULTS)
        val second = FakeHouseDataSource()
        val vm = newViewModel { kind, _ ->
            if (kind == SourceKind.BLUETOOTH) {
                first
            } else {
                // Starý zdroj musí být zastavený dřív, než se vytvoří nový.
                assertTrue(first.stopped)
                second
            }
        }
        first.status.value = receivedNow()
        runCurrent()
        assertEquals(1, vm.uiState.value.chart.size)
        assertNotNull(vm.uiState.value.received)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()

        assertTrue(first.stopped)
        assertTrue(second.started)
        assertFalse(second.stopped)
        val state = vm.uiState.value
        assertEquals(SourceKind.SIMULATION, state.sourceKind)
        assertTrue(state.chart.isEmpty())
        assertNull(state.received)
        assertNull(state.settings)
        // Spojení patří novému zdroji, ne tomu, který se právě zastavil.
        assertEquals(ConnectionState.Idle, state.connection)
    }

    @Test
    fun switchedOutSourceNoLongerReachesTheState() = vmTest {
        val first = FakeHouseDataSource()
        val second = FakeHouseDataSource()
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) first else second }
        first.status.value = receivedNow()
        runCurrent()
        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()

        first.connection.value = ConnectionState.Scanning
        first.status.value = receivedNow(EXAMPLE.copy(motion = true))
        first.settings.value = DEFAULTS
        runCurrent()

        val state = vm.uiState.value
        assertEquals(ConnectionState.Idle, state.connection)
        assertNull(state.received)
        assertNull(state.settings)
        assertTrue(state.chart.isEmpty())
        assertNull(state.lastMotionAt)
    }

    // Nový zdroj může mít Stav hned po spuštění (simulace ho má vždy). Ten je prvním vzorkem grafu a nic ze starého zdroje
    // v grafu nezůstane.
    @Test
    fun newSourceIsCollectedFromItsFirstValuesAndStartsAFreshChart() = vmTest {
        val first = FakeHouseDataSource(status = receivedNow(EXAMPLE.copy(insideTemp = 22.0)))
        val second = FakeHouseDataSource(
            connection = ConnectionState.Simulated,
            status = receivedNow(EXAMPLE.copy(insideTemp = 25.0)),
            settings = DEFAULTS,
        )
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) first else second }
        runCurrent()
        assertEquals(listOf(22.0), vm.uiState.value.chart.map { it.inside })

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()

        val state = vm.uiState.value
        assertEquals(ConnectionState.Simulated, state.connection)
        assertEquals(DEFAULTS, state.settings)
        assertEquals(second.status.value, state.received)
        assertEquals(listOf(25.0), state.chart.map { it.inside })
    }

    @Test
    fun selectingTheCurrentSourceChangesNothing() = vmTest {
        var created = 0
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ ->
            created++
            fake
        }
        fake.status.value = receivedNow()
        runCurrent()

        vm.selectSource(SourceKind.BLUETOOTH)
        runCurrent()

        assertEquals(1, created)
        assertFalse(fake.stopped)
        assertEquals(1, vm.uiState.value.chart.size)
        assertNotNull(vm.uiState.value.received)
    }

    @Test
    fun switchingBackCreatesAFreshSource() = vmTest {
        val sources = mutableListOf<FakeHouseDataSource>()
        val vm = newViewModel { _, _ -> FakeHouseDataSource().also { sources += it } }
        runCurrent()

        vm.selectSource(SourceKind.SIMULATION)
        vm.selectSource(SourceKind.BLUETOOTH)
        runCurrent()

        assertEquals(3, sources.size)
        assertEquals(listOf(true, true, false), sources.map { it.stopped })
        assertTrue(sources.all { it.started })
        assertEquals(SourceKind.BLUETOOTH, vm.uiState.value.sourceKind)
    }

    // Poslední pohyb

    @Test
    fun lastMotionOnRisingEdge() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        val firstMotion = startedAt.plusSeconds(10)
        val secondStatusWithMotion = startedAt.plusSeconds(20)

        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = false), 0, startedAt)
        runCurrent()
        assertNull(vm.uiState.value.lastMotionAt)

        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 10_000, firstMotion)
        runCurrent()
        assertEquals(firstMotion, vm.uiState.value.lastMotionAt)

        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 20_000, secondStatusWithMotion)
        runCurrent()
        assertEquals(firstMotion, vm.uiState.value.lastMotionAt)
    }

    @Test
    fun lastMotionFollowsEveryRisingEdge() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        val secondMotion = startedAt.plusSeconds(30)

        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 10_000, startedAt.plusSeconds(10))
        runCurrent()
        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = false), 20_000, startedAt.plusSeconds(20))
        runCurrent()
        assertEquals(startedAt.plusSeconds(10), vm.uiState.value.lastMotionAt)

        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 30_000, secondMotion)
        runCurrent()
        assertEquals(secondMotion, vm.uiState.value.lastMotionAt)
    }

    @Test
    fun lastMotionRecordedWhenFirstStatusAlreadyShowsMotion() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        fake.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 0, startedAt)
        runCurrent()

        assertEquals(startedAt, vm.uiState.value.lastMotionAt)
    }

    // Po přepnutí zdroje je první Stav zase „první“, takže i ten s pohybem se zaznamená.
    @Test
    fun sourceSwitchClearsLastMotionAndRearmsTheFirstStatusRule() = vmTest {
        val first = FakeHouseDataSource(status = ReceivedStatus(EXAMPLE.copy(motion = true), 0, startedAt))
        val second = FakeHouseDataSource()
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) first else second }
        runCurrent()
        assertEquals(startedAt, vm.uiState.value.lastMotionAt)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()
        assertNull(vm.uiState.value.lastMotionAt)

        val later = startedAt.plusSeconds(5)
        second.status.value = ReceivedStatus(EXAMPLE.copy(motion = true), 5_000, later)
        runCurrent()
        assertEquals(later, vm.uiState.value.lastMotionAt)
    }

    // Rozdíl hodin a ovládání

    @Test
    fun clockDifferenceFromCasAndPhoneTime() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }
        // EXAMPLE má cas 10:00:00 a telefon ho v čase 0 přijal v 10:02:15.
        fake.status.value = receivedNow()
        runCurrent()

        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0, 0), vm.uiState.value.piTime)
        assertEquals(-135L, vm.uiState.value.clockDiffSeconds)
    }

    @Test
    fun controlsEnabledOnlyWithSettingsAndConnection() = vmTest {
        val fake = FakeHouseDataSource(connection = ConnectionState.Connected("AA", 517, -60), settings = DEFAULTS)
        val vm = newViewModel { _, _ -> fake }
        runCurrent()
        assertTrue(vm.uiState.value.controlsEnabled)

        fake.settings.value = null
        runCurrent()
        assertFalse(vm.uiState.value.controlsEnabled)

        fake.settings.value = DEFAULTS
        fake.connection.value = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 1_000)
        runCurrent()
        assertFalse(vm.uiState.value.controlsEnabled)

        fake.connection.value = ConnectionState.Simulated
        runCurrent()
        assertTrue(vm.uiState.value.controlsEnabled)
    }

    // Předávání akcí zdroji

    @Test
    fun bleAndSimulationActionsAreForwarded() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { _, _ -> fake }

        vm.chooseDevice("AA")
        vm.disconnect()
        vm.connect()
        vm.retryConnection()
        vm.simulation(SimulationAction.MOTION)

        assertEquals("AA", fake.chosen)
        assertEquals(listOf("disconnectByUser", "connectByUser", "retry"), fake.controlCalls)
        assertEquals(listOf(SimulationAction.MOTION), fake.simActions)
    }

    // Návrat do aplikace (onResume) jen znovu ověří předpoklady připojení. Zařízení bez služby Domek zkusí znovu jen tlačítko
    // „Zkusit znovu“, jinak by se aplikace po každém návratu připojila ke stejnému zařízení bez služby.
    @Test
    fun returningToTheAppRechecksThePrerequisitesButLeavesAMissingServiceAlone() = vmTest {
        val fake = FakeHouseDataSource(connection = ConnectionState.PermissionsMissing)
        val vm = newViewModel { _, _ -> fake }
        runCurrent()

        vm.recheckPrerequisites()
        assertEquals(listOf("retry"), fake.controlCalls)

        fake.connection.value = ConnectionState.MissingService
        runCurrent()
        vm.recheckPrerequisites()
        assertEquals("návrat do aplikace zařízení bez služby znovu nezkouší", listOf("retry"), fake.controlCalls)

        vm.retryConnection()
        assertEquals("„Zkusit znovu“ ano", listOf("retry", "retry"), fake.controlCalls)
    }

    @Test
    fun controlsAreIgnoredWhenTheSourceLacksThem() = vmTest {
        val fake = FakeHouseDataSource()
        // Zdroj, který jde jen přes HouseDataSource: ovládání BLE ani simulace nemá.
        val plain = object : HouseDataSource by fake {}
        val vm = newViewModel { _, _ -> plain }

        vm.chooseDevice("AA")
        vm.disconnect()
        vm.connect()
        vm.retryConnection()
        vm.recheckPrerequisites()
        vm.simulation(SimulationAction.MOTION)

        assertNull(fake.chosen)
        assertTrue(fake.controlCalls.isEmpty())
        assertTrue(fake.simActions.isEmpty())
    }

    // Se skutečnou simulací a zastavením

    @Test
    fun simulationSourceFeedsTheViewModelAndTakesItsControls() = vmTest {
        val fake = FakeHouseDataSource()
        val vm = newViewModel { kind, scope ->
            when (kind) {
                SourceKind.BLUETOOTH -> fake
                SourceKind.SIMULATION -> SimulatedHouseDataSource(scope = scope, time = timeSource(), wallClock = { phoneTime() })
            }
        }
        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()

        val before = vm.uiState.value
        assertTrue(fake.stopped)
        assertEquals(ConnectionState.Simulated, before.connection)
        assertEquals(SIM_DEFAULT_SETTINGS, before.settings)
        assertTrue(before.controlsEnabled)
        assertEquals("2026-10-20T10:02:15", before.status!!.time)
        assertEquals(0L, before.clockDiffSeconds)
        assertEquals(1, before.chart.size)
        assertNull(before.lastMotionAt)

        // Simulace provede akci ve vlastní korutině, proto se projeví až po runCurrent().
        vm.simulation(SimulationAction.MOTION)
        runCurrent()
        val after = vm.uiState.value
        assertTrue(after.status!!.motion)
        assertEquals(after.received!!.receivedAtWall, after.lastMotionAt)
    }

    // ViewModelStore.clear() dělá to, co Android při definitivním zániku aktivity: zavolá onCleared().
    // ViewModel vzniká přes newViewModel, aby ho vmTest zastavil, i kdyby test selhal dřív než store.clear().
    @Test
    fun stopsSourceWhenViewModelIsCleared() = vmTest {
        val fake = FakeHouseDataSource()
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer { newViewModel { _, _ -> fake } }
        }
        ViewModelProvider.create(store, factory)[HouseViewModel::class]
        runCurrent()
        assertTrue(fake.started)
        assertFalse(fake.stopped)

        store.clear()
        assertTrue(fake.stopped)
    }
}
