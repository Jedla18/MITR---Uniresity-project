package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.data.source.WriteResult
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedHouseDataSourceTest {
    private val tenAm = LocalDateTime.of(2026, 10, 20, 10, 0)

    // Monotónní čas i hodiny telefonu běží s virtuálním časem testu, takže tik po 1000 ms jde odměřit přesně.
    private fun TestScope.timeSource() = TimeSource { testScheduler.currentTime }

    private fun TestScope.wallClock(): () -> LocalDateTime = { tenAm.plusNanos(testScheduler.currentTime * 1_000_000) }

    private fun TestScope.newSource() = SimulatedHouseDataSource(
        scope = backgroundScope,
        time = timeSource(),
        wallClock = wallClock(),
    )

    // Zdroj běží a první Stav je venku.
    private fun TestScope.startedSource() = newSource().also {
        it.start()
        runCurrent()
    }

    private val SimulatedHouseDataSource.receivedAtMs: Long get() = status.value!!.receivedAtMs

    private val SimulatedHouseDataSource.latest: HouseStatus get() = status.value!!.status

    // Spuštění a tik

    @Test
    fun isIdleBeforeStart() = runTest {
        val source = newSource()
        assertEquals(ConnectionState.Idle, source.connection.value)
        assertNull(source.status.value)
        assertNull(source.settings.value)
    }

    // Vše je hotové už po návratu ze start(), takže po opětovném spuštění nezůstane vidět nic ze starého běhu.
    @Test
    fun startPublishesConnectionSettingsAndFirstStatusAtOnce() = runTest {
        val source = newSource()
        source.start()
        assertEquals(ConnectionState.Simulated, source.connection.value)
        assertEquals(SIM_DEFAULT_SETTINGS, source.settings.value)
        assertEquals(0L, source.receivedAtMs)
        assertEquals("2026-10-20T10:00:00", source.latest.time)
    }

    // Model začíná na hodinách telefonu, ne na pevném čase.
    @Test
    fun modelStartsAtPhoneTime() = runTest {
        val source = SimulatedHouseDataSource(
            scope = backgroundScope,
            time = timeSource(),
            wallClock = { LocalDateTime.of(2026, 10, 20, 23, 30, 15) },
        )
        source.start()
        assertEquals("2026-10-20T23:30:15", source.latest.time)
        assertEquals(DayPart.NIGHT, source.latest.dayPart)
    }

    @Test
    fun emitsStatusEverySecond() = runTest {
        val source = newSource()
        source.start()
        runCurrent()
        assertEquals(ConnectionState.Simulated, source.connection.value)
        val first = source.receivedAtMs
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(source.receivedAtMs >= first + 1_000)
    }

    // Tik nečeká na událost modelu: za klidného dne se nic neděje a Stav přesto chodí každou sekundu.
    @Test
    fun statusArrivesOnEachTickAndOnlyThen() = runTest {
        val source = startedSource()
        repeat(5) { second ->
            advanceTimeBy(999)
            runCurrent()
            assertEquals(second * 1_000L, source.receivedAtMs)
            advanceTimeBy(1)
            runCurrent()
            assertEquals((second + 1) * 1_000L, source.receivedAtMs)
        }
    }

    @Test
    fun statusCarriesModelTimeAndPhoneTime() = runTest {
        val source = startedSource()
        assertEquals("2026-10-20T10:00:00", source.latest.time)
        assertEquals(tenAm, source.status.value!!.receivedAtWall)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:00:01", source.latest.time)
        assertEquals(tenAm.plusSeconds(1), source.status.value!!.receivedAtWall)
        assertEquals(1_000L, source.receivedAtMs)
    }

    // Zdroj, jehož monotónní čas uteče o `sleptMs` dál než virtuální čas testu: tak vypadá spánek telefonu, kdy delay stojí,
    // ale elapsedRealtime běží dál.
    private fun TestScope.sleepingSource(sleptMs: () -> Long) = SimulatedHouseDataSource(
        scope = backgroundScope,
        time = TimeSource { testScheduler.currentTime + sleptMs() },
        wallClock = wallClock(),
    )

    // Tik po probuzení posune model o celý skutečně uplynulý čas, a to po sekundách: termostat přitom rozhoduje každou sekundu,
    // takže topení skončí u cíle, místo aby topilo celé dvě minuty v kuse.
    @Test
    fun theTickAfterTheSleepCatchesUpTheRealElapsedTimeSecondBySecond() = runTest {
        var sleptMs = 0L
        val source = sleepingSource { sleptMs }
        source.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:00:01", source.latest.time)
        assertTrue(source.latest.heating)

        // Mezi tímto a příštím tikem uběhne 120 s, delay z nich naměří jen 1 s.
        sleptMs = 119_000
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:02:01", source.latest.time)
        val inside = source.latest.insideTemp!!
        assertTrue("uvnitř je $inside, termostat drží cíl 23 °C", inside in 22.6..23.1)

        // Další tiky jsou zase po sekundě.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:02:02", source.latest.time)
    }

    // Po dlouhém spánku model doběhne nejvýš 10 minut najednou, aby jeden tik nezdržel UI. Zbytek už nedohání.
    @Test
    fun oneTickCatchesUpAtMostTenMinutes() = runTest {
        var sleptMs = 0L
        val source = sleepingSource { sleptMs }
        source.start()
        runCurrent()

        sleptMs = 3_600_000
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:10:00", source.latest.time)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("2026-10-20T10:10:01", source.latest.time)
    }

    @Test
    fun startingTwiceKeepsOneRunningSimulation() = runTest {
        val source = startedSource()
        advanceTimeBy(2_500)
        runCurrent()
        source.start()
        advanceTimeBy(1_100)
        runCurrent()
        // Druhé start() nic nespustilo: druhý běh by tikal v 3500 a poslední Stav by byl z toho času, ne z 3000.
        // Model přitom ušel právě tři kroky.
        assertEquals(3_000L, source.receivedAtMs)
        assertEquals("2026-10-20T10:00:03", source.latest.time)
    }

    @Test
    fun restartAfterStopStartsAFreshSimulation() = runTest {
        val source = startedSource()
        source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0)))
        source.sendCommand(HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 23, 30)))
        source.sendCommand(HouseCommand.Light(LightAction.ON))
        assertEquals(24.0, source.settings.value!!.targetDay, 0.0)
        assertEquals(DayPart.NIGHT, source.latest.dayPart)
        assertTrue(source.latest.light.manual)

        source.stop()
        source.start()
        runCurrent()
        assertEquals(ConnectionState.Simulated, source.connection.value)
        assertEquals(SIM_DEFAULT_SETTINGS, source.settings.value)
        assertEquals(DayPart.DAY, source.latest.dayPart)
        assertEquals("2026-10-20T10:00:00", source.latest.time)
        assertFalse(source.latest.light.manual)
    }

    // Zápisy a příkazy

    @Test
    fun validWriteUpdatesSettings() = runTest {
        val source = startedSource()
        assertEquals(WriteResult.Ok, source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))))
        assertEquals(24.0, source.settings.value!!.targetDay, 0.0)
        // Nový Stav přišel hned se zápisem, ne až s příštím tikem.
        assertEquals(60L, source.receivedAtMs)
        assertEquals(24.0, source.latest.target!!, 0.0)
    }

    @Test
    fun invalidWriteIsRejectedWith128() = runTest {
        val source = startedSource()
        val settingsBefore = source.settings.value
        val statusBefore = source.status.value
        // Začátek noci shodný se začátkem večera Raspberry odmítne.
        assertEquals(WriteResult.Rejected(128), source.writeSettings(listOf(SettingKey.NIGHT_START.change("19:00"))))
        assertEquals(settingsBefore, source.settings.value)
        assertSame(statusBefore, source.status.value)
        assertEquals(128, SIM_REJECTED_STATUS)
    }

    @Test
    fun commandEmitsStatusImmediately() = runTest {
        val source = startedSource()
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.Light(LightAction.ON)))
        assertTrue(source.latest.light.on)
        assertTrue(source.latest.light.manual)
        assertEquals(60L, source.receivedAtMs)
    }

    @Test
    fun invalidCommandIsRejectedWith128AndChangesNothing() = runTest {
        val source = startedSource()
        val statusBefore = source.status.value
        val settingsBefore = source.settings.value
        // Jas nad 100 % je mimo rozsah, takový příkaz Raspberry odmítne.
        assertEquals(WriteResult.Rejected(128), source.sendCommand(HouseCommand.LightLook(brightness = 101)))
        assertSame(statusBefore, source.status.value)
        assertSame(settingsBefore, source.settings.value)
    }

    // Příkaz svetlo s objektem uloží barvu a jas do Nastavení a Raspberry po něm pošle i Nastavení
    // (docs/ble-rozhrani.md, Příkaz → Světlo).
    @Test
    fun lookCommandPublishesTheChangedSettings() = runTest {
        val source = startedSource()
        val warm = listOf(255, 147, 41)
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.LightLook(Rgb(255, 147, 41), 40)))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightColor = warm, lightBrightness = 40), source.settings.value)
        assertEquals(LightState(true, warm, 40, true), source.latest.light)

        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.LightLook(brightness = 70)))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightColor = warm, lightBrightness = 70), source.settings.value)
    }

    // Příkaz, který uloženou barvu a jas nezmění, nové Nastavení neposílá.
    @Test
    fun commandKeepingTheStoredLookPublishesNoNewSettings() = runTest {
        val source = startedSource()
        val before = source.settings.value
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.LightLook(Rgb(255, 255, 255), 100)))
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.Light(LightAction.OFF)))
        assertSame(before, source.settings.value)
    }

    // Zápis uložené barvy a jasu do Nastavení je jen uloží (docs/ble-rozhrani.md, Nastavení): večerní světlo, které si domek
    // rozsvítil sám, se hned přebarví a zůstane v automatice.
    @Test
    fun storedLookWriteRecolorsALitLightWithoutMakingItManual() = runTest {
        val source = startedSource()
        source.sendCommand(HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 19, 30)))
        assertEquals(LightState(on = true, color = listOf(255, 255, 255), brightness = 100, manual = false), source.latest.light)

        assertEquals(WriteResult.Ok, source.writeStoredLook(Rgb(0, 0, 255), 40))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightColor = listOf(0, 0, 255), lightBrightness = 40), source.settings.value)
        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 40, manual = false), source.latest.light)
    }

    // Ručně zapnuté světlo zůstane ruční a změní se jen to, co zápis nese.
    @Test
    fun storedLookWriteKeepsAManualLightManual() = runTest {
        val source = startedSource()
        source.sendCommand(HouseCommand.Light(LightAction.ON))

        assertEquals(WriteResult.Ok, source.writeStoredLook(Rgb(255, 138, 61), null))
        assertEquals(LightState(on = true, color = listOf(255, 138, 61), brightness = 100, manual = true), source.latest.light)
    }

    // Zhasnuté světlo zůstane zhasnuté, jen se barva a jas uloží pro příští rozsvícení.
    @Test
    fun storedLookWriteLeavesAnOffLightOff() = runTest {
        val source = startedSource()
        val off = source.latest.light
        assertFalse(off.on)

        assertEquals(WriteResult.Ok, source.writeStoredLook(Rgb(255, 138, 61), 30))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightColor = listOf(255, 138, 61), lightBrightness = 30), source.settings.value)
        assertEquals(off, source.latest.light)
    }

    @Test
    fun invalidStoredLookIsRejectedWith128AndChangesNothing() = runTest {
        val source = startedSource()
        val settingsBefore = source.settings.value

        assertEquals(WriteResult.Rejected(128), source.writeStoredLook(null, 101))
        assertSame(settingsBefore, source.settings.value)
    }

    @Test
    fun clockCommandMovesTheModelClockAtOnce() = runTest {
        val source = startedSource()
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 23, 30))))
        assertEquals("2026-10-20T23:30:00", source.latest.time)
        assertEquals(DayPart.NIGHT, source.latest.dayPart)
    }

    @Test
    fun writesAndCommandsReachTheModelOnlyAfterTheLatency() = runTest {
        val source = startedSource()
        val validWrite = async { source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))) }
        val invalidWrite = async { source.writeSettings(listOf(SettingKey.NIGHT_START.change("19:00"))) }
        val validCommand = async { source.sendCommand(HouseCommand.Light(LightAction.ON)) }
        val invalidCommand = async { source.sendCommand(HouseCommand.LightLook(brightness = 101)) }
        runCurrent()
        advanceTimeBy(59)
        runCurrent()
        assertFalse(listOf(validWrite, invalidWrite, validCommand, invalidCommand).any { it.isCompleted })
        assertEquals(23.0, source.settings.value!!.targetDay, 0.0)
        assertFalse(source.latest.light.manual)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(WriteResult.Ok, validWrite.await())
        assertEquals(WriteResult.Rejected(128), invalidWrite.await())
        assertEquals(WriteResult.Ok, validCommand.await())
        assertEquals(WriteResult.Rejected(128), invalidCommand.await())
        assertEquals(24.0, source.settings.value!!.targetDay, 0.0)
        assertTrue(source.latest.light.manual)
    }

    @Test
    fun writeLatencyIsConfigurable() = runTest {
        val source = SimulatedHouseDataSource(
            scope = backgroundScope,
            time = timeSource(),
            wallClock = wallClock(),
            writeLatencyMs = 250,
        )
        source.start()
        runCurrent()
        val command = async { source.sendCommand(HouseCommand.Light(LightAction.ON)) }
        runCurrent()
        advanceTimeBy(249)
        runCurrent()
        assertFalse(command.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(command.isCompleted)
    }

    @Test
    fun writeIsNotConnectedBeforeStartAndAfterStop() = runTest {
        val source = newSource()
        assertEquals(WriteResult.NotConnected, source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))))
        assertEquals(WriteResult.NotConnected, source.sendCommand(HouseCommand.Light(LightAction.ON)))
        // Zdroj, který neběží, odmítne hned, bez čekání na zpoždění zápisu.
        assertEquals(0L, testScheduler.currentTime)

        source.start()
        runCurrent()
        source.stop()
        assertEquals(WriteResult.NotConnected, source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))))
        assertEquals(WriteResult.NotConnected, source.sendCommand(HouseCommand.Light(LightAction.ON)))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun writeInFlightWhenStoppedIsNotConnected() = runTest {
        val source = startedSource()
        val write = async { source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))) }
        runCurrent()
        source.stop()
        val statusAtStop = source.status.value
        advanceTimeBy(100)
        runCurrent()
        assertEquals(WriteResult.NotConnected, write.await())
        assertEquals(23.0, source.settings.value!!.targetDay, 0.0)
        assertSame(statusAtStop, source.status.value)
    }

    @Test
    fun writeInFlightAcrossRestartDoesNotReachTheNewRun() = runTest {
        val source = startedSource()
        val write = async { source.writeSettings(listOf(SettingKey.TARGET_DAY.change(24.0))) }
        runCurrent()
        source.stop()
        source.start()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(WriteResult.NotConnected, write.await())
        assertEquals(SIM_DEFAULT_SETTINGS, source.settings.value)
    }

    // Ovládání simulace

    @Test
    fun motionActionShowsMotionAtOnce() = runTest {
        val source = startedSource()
        assertFalse(source.latest.motion)
        source.perform(SimulationAction.MOTION)
        runCurrent()
        assertTrue(source.latest.motion)
    }

    @Test
    fun heatSensorActionRaisesOutsideTemperatureAtOnce() = runTest {
        val source = startedSource()
        assertEquals(21.5, source.latest.outsideTemp!!, 0.001)
        source.perform(SimulationAction.HEAT_SENSOR)
        runCurrent()
        assertEquals(29.5, source.latest.outsideTemp!!, 0.001)
    }

    @Test
    fun coverLightSensorActionDarkensLuxAtOnce() = runTest {
        val source = startedSource()
        assertEquals(300.0, source.latest.lux!!, 0.001)
        source.perform(SimulationAction.COVER_LIGHT_SENSOR)
        runCurrent()
        assertEquals(5.0, source.latest.lux!!, 0.001)
    }

    @Test
    fun sensorFaultActionTogglesBme280Error() = runTest {
        val source = startedSource()
        assertEquals(emptyList<String>(), source.latest.errors)
        assertTrue(source.latest.heating)

        source.perform(SimulationAction.TOGGLE_SENSOR_FAULT)
        runCurrent()
        assertEquals(listOf("meteo"), source.latest.errors)
        assertNull(source.latest.outsideTemp)
        assertNull(source.latest.humidity)
        assertNull(source.latest.pressure)
        // Už první Stav s poruchou ukazuje stojící termostat.
        assertFalse(source.latest.heating)
        assertFalse(source.latest.cooling)

        source.perform(SimulationAction.TOGGLE_SENSOR_FAULT)
        runCurrent()
        assertEquals(emptyList<String>(), source.latest.errors)
        assertNotNull(source.latest.outsideTemp)
    }

    @Test
    fun controlsDoNothingBeforeStart() = runTest {
        val source = newSource()
        SimulationAction.entries.forEach { source.perform(it) }
        runCurrent()
        assertNull(source.status.value)
    }

    // Akce zadaná těsně před stop() se do zastaveného zdroje nedostane.
    @Test
    fun controlQueuedBeforeStopDoesNothing() = runTest {
        val source = startedSource()
        source.perform(SimulationAction.MOTION)
        source.stop()
        runCurrent()
        assertFalse(source.latest.motion)
    }

    // Výpadek dat

    @Test
    fun pauseDataStopsStatusForTenSeconds() = runTest {
        val source = startedSource()
        advanceTimeBy(1_500)
        runCurrent()
        val before = source.receivedAtMs
        source.perform(SimulationAction.PAUSE_DATA)
        advanceTimeBy(9_500)
        runCurrent()
        assertEquals(before, source.receivedAtMs)
        advanceTimeBy(1_600)
        runCurrent()
        assertTrue(source.receivedAtMs > before)
    }

    // Deset sekund se měří od akce, ne od tiku: první Stav přijde s prvním tikem v čase akce + 10 000 ms.
    @Test
    fun pauseEndsExactlyTenSecondsAfterTheAction() = runTest {
        val source = startedSource()
        source.perform(SimulationAction.PAUSE_DATA)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0L, source.receivedAtMs)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(10_000L, source.receivedAtMs)
    }

    @Test
    fun pauseSuppressesImmediateStatusesWhileTheModelRunsOn() = runTest {
        val source = startedSource()
        source.perform(SimulationAction.PAUSE_DATA)
        val silent = source.status.value
        source.perform(SimulationAction.MOTION)
        runCurrent()
        assertSame(silent, source.status.value)
        assertEquals(WriteResult.Ok, source.sendCommand(HouseCommand.Light(LightAction.ON)))
        assertSame(silent, source.status.value)

        advanceTimeBy(9_500)
        runCurrent()
        assertSame(silent, source.status.value)
        advanceTimeBy(500)
        runCurrent()
        // Model za těch deset sekund běžel dál a příkaz v něm platí; první Stav po výpadku to ukáže.
        assertEquals(10_000L, source.receivedAtMs)
        assertEquals("2026-10-20T10:00:10", source.latest.time)
        assertTrue(source.latest.light.manual)
    }

    @Test
    fun pauseDoesNotCarryOverToTheNextRun() = runTest {
        val source = startedSource()
        source.perform(SimulationAction.PAUSE_DATA)
        source.stop()
        source.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1_000L, source.receivedAtMs)
    }

    // Zastavení

    @Test
    fun stopEndsEmission() = runTest {
        val source = startedSource()
        advanceTimeBy(1_000)
        runCurrent()
        val last = source.status.value
        source.stop()
        assertEquals(ConnectionState.Idle, source.connection.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertSame(last, source.status.value)
    }
}
