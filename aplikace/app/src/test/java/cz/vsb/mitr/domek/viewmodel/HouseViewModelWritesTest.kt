package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.WITHOUT_STORED_LOOK
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseCommand.LightLook
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.data.source.WriteResult
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.dayStartsOf
import cz.vsb.mitr.domek.testing.FakeHouseDataSource
import cz.vsb.mitr.domek.testing.HouseViewModelTestBase
import cz.vsb.mitr.domek.testing.StoredLook
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Zápisy Nastavení, příkazy a hlášky (spec 7). Čísla v komentářích jsou virtuální čas testu v ms.
@OptIn(ExperimentalCoroutinesApi::class)
class HouseViewModelWritesTest : HouseViewModelTestBase() {
    private val fake = FakeHouseDataSource(connection = ConnectionState.Connected("AA", 517, -60), settings = DEFAULTS)

    // Zápis, kterým se vrátí začátky úseků z DEFAULTS.
    private val restoreOfDefaults = listOf(
        SettingKey.DAY_START.change("07:00"),
        SettingKey.EVENING_START.change("19:00"),
        SettingKey.NIGHT_START.change("22:00"),
    )

    private val HouseViewModel.state: HouseUiState get() = uiState.value

    // ViewModel nad připojeným domkem, který už převzal jeho Nastavení. DEFAULTS mají uloženou barvu i jas světla.
    private fun TestScope.connectedViewModel(): HouseViewModel = newViewModel { _, _ -> fake }.also { runCurrent() }

    // Totéž s programem na Raspberry, který uloženou barvu a jas nezná: barvu a jas světla posílá příkaz.
    private fun TestScope.olderProgramViewModel(): HouseViewModel {
        fake.settings.value = WITHOUT_STORED_LOOK
        return connectedViewModel()
    }

    // Hlášky v pořadí, v jakém by je dostal snackbar.
    private fun TestScope.messagesOf(vm: HouseViewModel): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        return messages
    }

    private fun TestScope.statusWithLight(light: LightState) = receivedNow(EXAMPLE.copy(light = light))

    // Kroková tlačítka a drafty

    @Test
    fun stepperChangesAreDebouncedIntoOneWrite() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(300)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0)
        advanceTimeBy(300)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.5)

        advanceTimeBy(599)
        assertTrue(fake.writtenSettings.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(listOf(SettingKey.TARGET_DAY.change(24.5))), fake.writtenSettings)
    }

    @Test
    fun draftShownUntilSettingsConfirm() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        assertEquals(23.5, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)

        advanceTimeBy(601)
        assertEquals(1, fake.writtenSettings.size)
        assertEquals(23.5, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)

        fake.settings.value = DEFAULTS.copy(targetDay = 23.5)
        runCurrent()
        assertTrue(vm.state.drafts.isEmpty())
        assertTrue(vm.state.pendingKeys.isEmpty())
        assertEquals(23.5, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
    }

    @Test
    fun draftDroppedThreeSecondsAfterUnconfirmedWrite() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(601)
        assertEquals(1, fake.writtenSettings.size)

        advanceTimeBy(2_998) // 3599, zápis odešel v 600
        assertEquals(23.5, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        advanceTimeBy(2) // 3601
        assertEquals(23.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertTrue(vm.state.pendingKeys.isEmpty())
    }

    @Test
    fun valuesAreClampedAndRoundedBeforeTheyAreDrafted() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 36.2)
        vm.changeSetting(SettingKey.NOBODY_HOME_SETBACK, 0.1 + 0.2)
        vm.changeSetting(SettingKey.NIGHT_LIGHT_SECONDS, 2.0)
        vm.changeSetting(SettingKey.NOBODY_HOME_AFTER, 30.6)
        assertEquals(
            mapOf(
                SettingKey.TARGET_DAY to JsonPrimitive(35.0),
                SettingKey.NOBODY_HOME_SETBACK to JsonPrimitive(0.3),
                SettingKey.NIGHT_LIGHT_SECONDS to JsonPrimitive(5),
                SettingKey.NOBODY_HOME_AFTER to JsonPrimitive(31),
            ),
            vm.state.drafts,
        )

        advanceTimeBy(601)
        assertEquals(
            setOf(
                listOf(SettingKey.TARGET_DAY.change(35.0)),
                listOf(SettingKey.NOBODY_HOME_SETBACK.change(0.3)),
                listOf(SettingKey.NIGHT_LIGHT_SECONDS.change(5.0)),
                listOf(SettingKey.NOBODY_HOME_AFTER.change(31.0)),
            ),
            fake.writtenSettings.toSet(),
        )
    }

    @Test
    fun notANumberIsIgnored() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, Double.NaN)
        advanceTimeBy(1_000)
        assertTrue(vm.state.drafts.isEmpty())
        assertTrue(fake.writtenSettings.isEmpty())
    }

    // Draft se porovnává s Nastavením podle textu čísla, takže šum dvojkové aritmetiky by potvrzení zablokoval.
    @Test
    fun roundedDraftIsConfirmedByTheValueTheRaspberryEchoes() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.000000000000004)
        advanceTimeBy(601)
        assertEquals(listOf(listOf(SettingKey.TARGET_DAY.change(23.0))), fake.writtenSettings)
        // Raspberry má 23,0, tedy přesně zapsanou hodnotu.
        assertTrue(vm.state.drafts.isEmpty())
    }

    @Test
    fun modeAndSwitchesAreWrittenAtOnce() = vmTest {
        val vm = connectedViewModel()
        vm.setMode(ThermostatMode.MANUAL)
        vm.setLightOnMotion(false)
        assertEquals(JsonPrimitive("rucni"), vm.state.settingValue(SettingKey.MODE))
        assertEquals(JsonPrimitive(false), vm.state.settingValue(SettingKey.LIGHT_ON_MOTION))
        assertEquals(setOf(SettingKey.MODE, SettingKey.LIGHT_ON_MOTION), vm.state.pendingKeys)

        runCurrent() // bez posunu času
        assertEquals(
            listOf(listOf(SettingKey.MODE.change("rucni")), listOf(SettingKey.LIGHT_ON_MOTION.change(false))),
            fake.writtenSettings,
        )
    }

    // Souběh zápisů téhož klíče

    @Test
    fun changesDuringAWriteAreSentRightAfterIt() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(601) // zápis 23,5 odešel v 600 a čeká na odpověď
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.5)
        runCurrent()
        // Na klíč běží nejvýš jeden zápis.
        assertEquals(1, fake.writtenSettings.size)

        gate.complete(Unit)
        runCurrent()
        // Hned po úspěchu, bez dalších 600 ms, a jen s poslední hodnotou.
        assertEquals(
            listOf(listOf(SettingKey.TARGET_DAY.change(23.5)), listOf(SettingKey.TARGET_DAY.change(24.5))),
            fake.writtenSettings,
        )
        advanceTimeBy(1_000)
        assertEquals(2, fake.writtenSettings.size)
        assertEquals(24.5, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
    }

    @Test
    fun changeTakenBackDuringAWriteSendsNothingMore() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(601)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0)
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)

        gate.complete(Unit)
        advanceTimeBy(1_000)
        assertEquals(listOf(listOf(SettingKey.TARGET_DAY.change(23.5))), fake.writtenSettings)
        // Draft čeká na potvrzení zapsané hodnoty.
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)
    }

    @Test
    fun immediateChangeDuringAWriteWaitsForIt() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setMode(ThermostatMode.MANUAL)
        runCurrent()
        vm.setMode(ThermostatMode.OFF)
        runCurrent()
        assertEquals(1, fake.writtenSettings.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(listOf(SettingKey.MODE.change("rucni")), listOf(SettingKey.MODE.change("vypnuto"))),
            fake.writtenSettings,
        )
    }

    @Test
    fun failedWriteDropsAlsoChangesMadeDuringItAndExplainsOnce() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        fake.nextWriteResult = WriteResult.Timeout
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(601)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0)

        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.drafts.isEmpty())
        assertTrue(vm.state.pendingKeys.isEmpty())
        assertEquals(23.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertEquals(listOf("Raspberry neodpovědělo. Změna se možná neprovedla."), messages)

        // Odložený zápis změny z doby zápisu už nic nepošle.
        advanceTimeBy(1_000)
        assertEquals(1, fake.writtenSettings.size)
        assertEquals(1, messages.size)
    }

    // Nová změna ruší čekání na potvrzení předchozího zápisu. Jeho konec by jinak smazal draft, který teprve čeká
    // na odeslání, a s ním i jeho odložený zápis.
    @Test
    fun newChangeOutlivesTheConfirmationTimeoutOfTheEarlierWrite() = vmTest {
        val vm = connectedViewModel()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(3_200) // 23,5 odešlo v 600, Raspberry ho zatím nepotvrdilo
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0)

        advanceTimeBy(401) // 3601: čekání na potvrzení 23,5 by skončilo v 3600
        assertEquals(24.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)
        advanceTimeBy(200) // 3801: 24,0 odešlo v 3800
        assertEquals(
            listOf(listOf(SettingKey.TARGET_DAY.change(23.5)), listOf(SettingKey.TARGET_DAY.change(24.0))),
            fake.writtenSettings,
        )

        advanceTimeBy(3_000) // 6801: čekání na potvrzení 24,0 skončilo v 6800
        assertEquals(23.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
    }

    // Notifikace Nastavení může přijít dřív než odpověď na zápis.
    @Test
    fun settingsArrivingBeforeTheAnswerConfirmTheDraftRightAfterIt() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(601)
        fake.settings.value = DEFAULTS.copy(targetDay = 23.5)
        runCurrent()
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)

        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.drafts.isEmpty())
        assertTrue(vm.state.pendingKeys.isEmpty())
    }

    // Odmítnutí a výpadky

    @Test
    fun rejectedWriteRevertsAndExplains() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(19, 0))
        runCurrent()

        assertEquals(
            listOf("Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo."),
            messages,
        )
        assertEquals(JsonPrimitive("22:00"), vm.state.settingValue(SettingKey.NIGHT_START))
        assertTrue(vm.state.pendingKeys.isEmpty())
    }

    // docs/ble-rozhrani.md (Když zápis selže): po chybě zápisu Nastavení aplikace znovu přečte Nastavení.
    @Test
    fun rejectedWriteRereadsTheSettings() = vmTest {
        val vm = connectedViewModel()
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(19, 0))
        runCurrent()

        assertEquals(1, fake.settingsRefreshes)
    }

    // Čtení po odmítnutí nesmí držet klíč: novou hodnotu téhož nastavení, zadanou během čtení, aplikace hned pošle.
    @Test
    fun aChangeDuringTheRereadIsStillSent() = vmTest {
        val vm = connectedViewModel()
        fake.refreshGate = CompletableDeferred()
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(19, 0))
        runCurrent()
        assertEquals(1, fake.settingsRefreshes)

        fake.nextWriteResult = WriteResult.Ok
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(21, 0))
        runCurrent()

        assertEquals(2, fake.writtenSettings.size)
        assertEquals(JsonPrimitive("21:00"), fake.writtenSettings.last().single().value)
    }

    @Test
    fun acceptedOrUnsentWriteDoesNotRereadTheSettings() = vmTest {
        val vm = connectedViewModel()
        fake.nextWriteResult = WriteResult.NotConnected
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(21, 0))
        runCurrent()
        fake.nextWriteResult = WriteResult.Ok
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(21, 30))
        runCurrent()

        assertEquals(2, fake.writtenSettings.size)
        assertEquals(0, fake.settingsRefreshes)
    }

    @Test
    fun rapidTapsWhileDisconnectedGiveOneMessage() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        fake.nextWriteResult = WriteResult.NotConnected
        listOf(23.5, 24.0, 24.5, 25.0, 25.5).forEachIndexed { index, value ->
            if (index > 0) advanceTimeBy(100)
            vm.changeSetting(SettingKey.TARGET_DAY, value)
        }

        advanceTimeBy(700)
        assertEquals(1, fake.writtenSettings.size)
        assertEquals(listOf("Dům není připojený, změna se neodeslala."), messages)
        assertTrue(vm.state.drafts.isEmpty())
    }

    // Začátky úseků dne

    @Test
    fun dayStartNowUsesRaspberryTime() = vmTest {
        val vm = connectedViewModel()
        // Telefon ukazuje 10:02:15, Raspberry 10:05:42.
        fake.status.value = receivedNow(EXAMPLE.copy(time = "2026-10-20T10:05:42"))
        runCurrent()

        vm.setDayStartNow(DayPart.NIGHT)
        runCurrent()
        assertEquals(listOf(listOf(SettingKey.NIGHT_START.change("10:05"))), fake.writtenSettings)
        assertEquals(dayStartsOf(DEFAULTS), vm.state.savedDayStarts)
    }

    @Test
    fun dayStartNowIgnoredWhenStale() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(vm.state.isStale)

        vm.setDayStartNow(DayPart.NIGHT)
        runCurrent()
        assertTrue(fake.writtenSettings.isEmpty())
        assertTrue(vm.state.drafts.isEmpty())
        assertNull(vm.state.savedDayStarts)
    }

    @Test
    fun dayStartNowIgnoredWithoutStatus() = vmTest {
        val vm = connectedViewModel()
        vm.setDayStartNow(DayPart.NIGHT)
        runCurrent()
        assertTrue(fake.writtenSettings.isEmpty())
        assertNull(vm.state.savedDayStarts)
    }

    @Test
    fun savedDayStartsAreTheTimesFromBeforeTheFirstChange() = vmTest {
        val vm = connectedViewModel()
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(10, 5))
        runCurrent()
        fake.settings.value = DEFAULTS.copy(nightStart = "10:05")
        runCurrent()

        vm.setDayStart(DayPart.DAY, LocalTime.of(8, 0))
        runCurrent()
        assertEquals(dayStartsOf(DEFAULTS), vm.state.savedDayStarts)
        assertEquals(
            listOf(listOf(SettingKey.NIGHT_START.change("10:05")), listOf(SettingKey.DAY_START.change("08:00"))),
            fake.writtenSettings,
        )
    }

    // Draft začátku z doby, kdy Nastavení ještě nebylo načtené, se do původních časů nepočítá.
    @Test
    fun savedDayStartsIgnoreDrafts() = vmTest {
        fake.settings.value = null
        val vm = connectedViewModel()
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(10, 5))
        runCurrent()
        assertNull(vm.state.savedDayStarts)

        fake.settings.value = DEFAULTS
        runCurrent()
        assertEquals(JsonPrimitive("10:05"), vm.state.settingValue(SettingKey.NIGHT_START))
        vm.setDayStart(DayPart.DAY, LocalTime.of(8, 0))
        assertEquals(dayStartsOf(DEFAULTS), vm.state.savedDayStarts)
    }

    @Test
    fun restoreWritesOriginalStartsAtOnce() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow(EXAMPLE.copy(time = "2026-10-20T10:05:42"))
        runCurrent()
        vm.setDayStartNow(DayPart.NIGHT)
        runCurrent()
        fake.settings.value = DEFAULTS.copy(nightStart = "10:05")
        runCurrent()

        vm.restoreDayStarts()
        runCurrent()
        assertEquals(restoreOfDefaults, fake.writtenSettings.last())
        assertNull(vm.state.savedDayStarts)
    }

    // Obnovení počká na běžící zápis začátku a pak pošle všechny tři najednou. Převzatý zápis už nic dalšího nepošle,
    // ani když obnovené časy Raspberry hned nepotvrdí.
    @Test
    fun restoreWaitsForARunningStartWriteAndTakesItsKeyOver() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(10, 5))
        runCurrent()
        // Raspberry noc od 10:05 přijalo a ohlásilo dřív, než odpovědělo na zápis.
        fake.settings.value = DEFAULTS.copy(nightStart = "10:05")
        vm.restoreDayStarts()
        runCurrent()
        assertEquals(1, fake.writtenSettings.size)

        gate.complete(Unit)
        advanceTimeBy(1_000)
        assertEquals(listOf(listOf(SettingKey.NIGHT_START.change("10:05")), restoreOfDefaults), fake.writtenSettings)
        assertEquals(JsonPrimitive("22:00"), vm.state.settingValue(SettingKey.NIGHT_START))
        assertNull(vm.state.savedDayStarts)
    }

    // Začátek změněný během obnovení je nová změna: odejde hned po obnovení a původní časy zůstanou zapamatované.
    @Test
    fun startChangedDuringRestoreIsSentAfterItAndKeepsTheSavedStarts() = vmTest {
        val vm = connectedViewModel()
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(10, 5))
        runCurrent()
        fake.settings.value = DEFAULTS.copy(nightStart = "10:05")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.restoreDayStarts()
        runCurrent()
        vm.setDayStart(DayPart.EVENING, LocalTime.of(18, 0))
        runCurrent()
        assertEquals(2, fake.writtenSettings.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(3, fake.writtenSettings.size)
        assertEquals(listOf(SettingKey.EVENING_START.change("18:00")), fake.writtenSettings.last())
        assertEquals(dayStartsOf(DEFAULTS), vm.state.savedDayStarts)
    }

    // Světlo s uloženou barvou a jasem (spec 3 v 2026-10-23-denik-a-vylepseni-design.md): výběr barvy nebo jasu se jen zapíše
    // do Nastavení, světlo tím nezapne ani nevypne.

    @Test
    fun aChosenColorOrBrightnessIsWrittenToTheSettingsInsteadOfACommand() = vmTest {
        val vm = connectedViewModel()
        vm.setLightColor(Rgb(0, 0, 255))
        runCurrent()
        advanceTimeBy(150)
        vm.setLightBrightness(40)
        runCurrent()

        assertEquals(listOf(StoredLook(color = Rgb(0, 0, 255)), StoredLook(brightness = 40)), fake.writtenLooks)
        assertTrue(fake.sentCommands.isEmpty())
        assertTrue(fake.writtenSettings.isEmpty())
    }

    @Test
    fun anOlderProgramStillGetsTheLightCommand() = vmTest {
        val vm = olderProgramViewModel()
        vm.setLightColor(Rgb(0, 0, 255))
        runCurrent()

        assertEquals(listOf(LightLook(color = Rgb(0, 0, 255))), fake.sentCommands)
        assertTrue(fake.writtenLooks.isEmpty())
    }

    @Test
    fun storedLookDragIsConflated() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.setLightBrightness(30)
        vm.setLightBrightness(40)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10), StoredLook(brightness = 40)), fake.writtenLooks)
    }

    // docs/ble-rozhrani.md (Poznámky pro Android): posuvník zapíše nejvýš jednou za 100 až 200 ms a po puštění poslední hodnotu.
    // Každou změnu Nastavení si Raspberry navíc ukládá do souboru.
    @Test
    fun storedLooksAreWrittenAtLeast150MsApart() = vmTest {
        val vm = connectedViewModel()
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.setLightBrightness(30)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10)), fake.writtenLooks)

        advanceTimeBy(149)
        assertEquals(1, fake.writtenLooks.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10), StoredLook(brightness = 30)), fake.writtenLooks)
    }

    @Test
    fun colorAndBrightnessQueuedTogetherAreWrittenTogether() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightColor(Rgb(0, 0, 255))
        vm.setLightBrightness(40)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10), StoredLook(Rgb(0, 0, 255), 40)), fake.writtenLooks)
    }

    // Výběr barvy u zhasnutého světla ho nerozsvítí: barva se jen uloží a karta Barva a jas ji ukáže hned.
    @Test
    fun aColorPickedWhileTheLightIsOffIsStoredAndTheLightStaysOff() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow() // světlo je zhasnuté
        runCurrent()

        vm.setLightColor(Rgb(255, 138, 61))
        runCurrent()

        assertEquals(listOf(StoredLook(color = Rgb(255, 138, 61))), fake.writtenLooks)
        assertTrue(fake.sentCommands.isEmpty())
        assertEquals(EXAMPLE.light, vm.state.shownLight)
        assertEquals(ChosenLook(Rgb(255, 138, 61), 100), vm.state.chosenLook)
    }

    // Draft potvrdí Nastavení, které vybranou barvu a jas ukazuje. Samotný Stav ne: zhasnuté světlo by výběr neukázalo nikdy.
    @Test
    fun aStoredLookDraftStaysUntilTheSettingsShowIt() = vmTest {
        val vm = connectedViewModel()
        vm.setLightColor(Rgb(0, 0, 255))
        vm.setLightBrightness(40)
        runCurrent()

        fake.status.value = statusWithLight(LightState(on = true, color = listOf(0, 0, 255), brightness = 40, manual = true))
        runCurrent()
        assertNotNull(vm.state.lightDraft)
        fake.settings.value = DEFAULTS.copy(lightColor = listOf(0, 0, 255))
        runCurrent()
        assertNotNull(vm.state.lightDraft)

        fake.settings.value = DEFAULTS.copy(lightColor = listOf(0, 0, 255), lightBrightness = 40)
        runCurrent()
        assertNull(vm.state.lightDraft)
    }

    // Svítící světlo musí novou barvu ukázat i ve Stavu. Kdyby draft zmizel už s Nastavením, náhled by do dalšího Stavu ukázal
    // starou barvu.
    @Test
    fun aShiningLightKeepsTheStoredLookDraftUntilTheStatusShowsItToo() = vmTest {
        val vm = connectedViewModel()
        val red = LightState(on = true, color = listOf(255, 0, 0), brightness = 100, manual = true)
        fake.status.value = statusWithLight(red)
        runCurrent()
        vm.setLightColor(Rgb(0, 0, 255))
        runCurrent()

        fake.settings.value = DEFAULTS.copy(lightColor = listOf(0, 0, 255))
        runCurrent()
        assertNotNull(vm.state.lightDraft)
        assertEquals(red.copy(color = listOf(0, 0, 255)), vm.state.shownLight)

        fake.status.value = statusWithLight(red.copy(color = listOf(0, 0, 255)))
        runCurrent()
        assertNull(vm.state.lightDraft)
    }

    // Zhasnuté světlo výběr neukáže, takže ho potvrdí samotné Nastavení.
    @Test
    fun anOffLightsStoredLookDraftIsConfirmedByTheSettingsAlone() = vmTest {
        val vm = connectedViewModel()
        fake.status.value = receivedNow() // světlo je zhasnuté
        runCurrent()
        vm.setLightColor(Rgb(0, 0, 255))
        runCurrent()

        fake.settings.value = DEFAULTS.copy(lightColor = listOf(0, 0, 255))
        runCurrent()
        assertNull(vm.state.lightDraft)
    }

    // Noční světlo svítí nejvýš na 10 %, vyšší uložený jas ve Stavu neukáže nikdy.
    @Test
    fun theNightLightConfirmsAStoredBrightnessAboveItsCapWithTheSettings() = vmTest {
        val vm = connectedViewModel()
        val nightLight = LightState(on = true, color = listOf(255, 255, 255), brightness = 10, manual = false)
        fake.status.value = receivedNow(EXAMPLE.copy(dayPartText = "noc", light = nightLight))
        runCurrent()
        vm.setLightBrightness(60)
        runCurrent()

        fake.settings.value = DEFAULTS.copy(lightBrightness = 60)
        runCurrent()
        assertNull(vm.state.lightDraft)
    }

    // Po chybě platí zase uložený vzhled: zmizí draft i vzhled, který čekal na zápis, a hláška je jedna.
    @Test
    fun aFailedStoredLookDropsTheDraftAndTheWaitingLookAndExplainsOnce() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        fake.nextWriteResult = WriteResult.Timeout
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(40)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertNull(vm.state.lightDraft)
        assertEquals(listOf(StoredLook(brightness = 10)), fake.writtenLooks)
        assertEquals(listOf("Raspberry neodpovědělo. Změna se možná neprovedla."), messages)
        // Jen odmítnutý zápis se znovu čte, stejně jako u ostatních zápisů Nastavení.
        assertEquals(0, fake.settingsRefreshes)
    }

    // docs/ble-rozhrani.md (Když zápis selže): po chybě zápisu Nastavení aplikace znovu přečte Nastavení.
    @Test
    fun aRejectedStoredLookIsExplainedAndRereadsTheSettings() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setLightColor(Rgb(0, 0, 255))
        vm.setLightBrightness(40)
        runCurrent()

        assertEquals(
            listOf("Raspberry změnu odmítlo: barva světla, jas světla 40${NBSP}% (kód 128). Nic se nezměnilo."),
            messages,
        )
        assertNull(vm.state.lightDraft)
        assertEquals(1, fake.settingsRefreshes)
    }

    // Čtení po odmítnutí běží mimo odesílatele vzhledu, takže další výběr nečeká, až čtení skončí.
    @Test
    fun theRereadAfterARejectedLookDoesNotHoldBackTheNextOne() = vmTest {
        val vm = connectedViewModel()
        fake.refreshGate = CompletableDeferred()
        fake.nextWriteResult = WriteResult.Rejected(128)
        vm.setLightBrightness(10)
        runCurrent()
        assertEquals(1, fake.settingsRefreshes)

        fake.nextWriteResult = WriteResult.Ok
        vm.setLightBrightness(20)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10), StoredLook(brightness = 20)), fake.writtenLooks)
    }

    // Zápis do Nastavení světlo nerozsvítí, takže vzhled, který ještě čeká, po tlačítku světla klidně odejde. Jinak by se
    // poslední hodnota posuvníku ztratila.
    @Test
    fun aLightButtonKeepsTheStoredLookThatIsStillWaiting() = vmTest {
        val vm = connectedViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.lightAction(LightAction.OFF)
        runCurrent()
        assertEquals(listOf(HouseCommand.Light(LightAction.OFF)), fake.sentCommands)
        assertEquals(LightDraft(brightness = 20, setAtMs = 0), vm.state.lightDraft)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10), StoredLook(brightness = 20)), fake.writtenLooks)
    }

    // Světlo u staršího programu: barvu a jas posílá příkaz světla, který světlo zároveň ručně zapne.

    @Test
    fun brightnessDragIsConflated() = vmTest {
        val vm = olderProgramViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.commandGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.setLightBrightness(30)
        vm.setLightBrightness(40)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(LightLook(brightness = 10), LightLook(brightness = 40)), fake.sentCommands)
    }

    // docs/ble-rozhrani.md (Poznámky pro Android): posuvník pošle nejvýš jeden příkaz za 100 až 200 ms a po puštění poslední
    // hodnotu. Každý příkaz s barvou nebo jasem si Raspberry navíc ukládá do souboru.
    @Test
    fun looksAreSentAtLeast150MsApart() = vmTest {
        val vm = olderProgramViewModel()
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.setLightBrightness(30)
        runCurrent()
        assertEquals(listOf(LightLook(brightness = 10)), fake.sentCommands)

        advanceTimeBy(149)
        assertEquals(1, fake.sentCommands.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(LightLook(brightness = 10), LightLook(brightness = 30)), fake.sentCommands)
    }

    @Test
    fun colorAndBrightnessQueuedTogetherAreMerged() = vmTest {
        val vm = olderProgramViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.commandGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightColor(Rgb(0, 0, 255))
        vm.setLightBrightness(40)

        gate.complete(Unit)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(LightLook(brightness = 10), LightLook(Rgb(0, 0, 255), 40)), fake.sentCommands)
    }

    @Test
    fun lightValuesAreClampedToTheirRanges() = vmTest {
        val vm = olderProgramViewModel()
        vm.setLightBrightness(0)
        runCurrent()
        advanceTimeBy(150)
        vm.setLightBrightness(150)
        runCurrent()
        advanceTimeBy(150)
        vm.setLightColor(Rgb(300, -5, 128))
        runCurrent()
        assertEquals(
            listOf(LightLook(brightness = 1), LightLook(brightness = 100), LightLook(color = Rgb(255, 0, 128))),
            fake.sentCommands,
        )
        assertEquals(LightDraft(Rgb(255, 0, 128), 100, setAtMs = 300), vm.state.lightDraft)
    }

    @Test
    fun lightDraftShownUntilTheStatusShowsIt() = vmTest {
        val vm = olderProgramViewModel()
        fake.status.value = receivedNow() // světlo je zhasnuté
        runCurrent()
        vm.setLightColor(Rgb(0, 0, 255))
        vm.setLightBrightness(40)
        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 40, manual = true), vm.state.shownLight)
        runCurrent()

        // Stav, který vybraný vzhled ještě neukazuje, draft nechá. Zhasnuté světlo žádnou barvu neukazuje.
        fake.status.value = statusWithLight(LightState(on = true, color = listOf(0, 0, 255), brightness = 10, manual = true))
        runCurrent()
        assertNotNull(vm.state.lightDraft)
        fake.status.value = statusWithLight(LightState(on = false, color = listOf(0, 0, 255), brightness = 40, manual = true))
        runCurrent()
        assertNotNull(vm.state.lightDraft)

        fake.status.value = statusWithLight(LightState(on = true, color = listOf(0, 0, 255), brightness = 40, manual = true))
        runCurrent()
        assertNull(vm.state.lightDraft)
    }

    @Test
    fun lightDraftDroppedThreeSecondsAfterTheLastChange() = vmTest {
        val vm = connectedViewModel()
        vm.setLightBrightness(40)
        advanceTimeBy(2_000)
        vm.setLightColor(Rgb(0, 0, 255))

        advanceTimeBy(2_999) // 4999
        assertEquals(LightDraft(Rgb(0, 0, 255), 40, setAtMs = 2_000), vm.state.lightDraft)
        advanceTimeBy(2) // 5001
        assertNull(vm.state.lightDraft)
    }

    @Test
    fun lightActionDiscardsALookNotSentYet() = vmTest {
        val vm = olderProgramViewModel()
        val gate = CompletableDeferred<Unit>()
        fake.commandGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)
        vm.lightAction(LightAction.OFF)
        assertNull(vm.state.lightDraft)

        gate.complete(Unit)
        // Po odstupu mezi příkazy světla by odešel i čekající jas, kdyby ho povel nezahodil.
        advanceTimeBy(150)
        runCurrent()
        // Jas 20 neodešel, takže světlo po vypnutí znovu nerozsvítí.
        assertEquals(listOf(LightLook(brightness = 10), HouseCommand.Light(LightAction.OFF)), fake.sentCommands)
    }

    // Po chybě platí zase skutečné světlo: zmizí draft i vzhled, který čekal na odeslání, a hláška je jedna.
    @Test
    fun failedLookDropsTheLightDraftAndTheWaitingLookAndExplainsOnce() = vmTest {
        val vm = olderProgramViewModel()
        val messages = messagesOf(vm)
        val gate = CompletableDeferred<Unit>()
        fake.commandGate = gate
        fake.nextCommandResult = WriteResult.Timeout
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(40)

        gate.complete(Unit)
        // Po odstupu mezi příkazy světla by odešel i čekající jas, kdyby ho chyba nezahodila.
        advanceTimeBy(150)
        runCurrent()
        assertNull(vm.state.lightDraft)
        assertEquals(listOf(LightLook(brightness = 10)), fake.sentCommands)
        assertEquals(listOf("Raspberry neodpovědělo. Změna se možná neprovedla."), messages)
    }

    // Příkazy

    @Test
    fun syncClockSendsPhoneTimeWithSeconds() = vmTest {
        val vm = newViewModel(wallClock = { LocalDateTime.of(2026, 10, 20, 14, 3, 0, 250_000_000) }) { _, _ -> fake }
        runCurrent()
        vm.syncClock()
        runCurrent()
        assertEquals(HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)), fake.sentCommands.last())
    }

    @Test
    fun commandRejectionExplains() = vmTest {
        val vm = connectedViewModel()
        val messages = messagesOf(vm)
        fake.nextCommandResult = WriteResult.Rejected(128)
        vm.lightAction(LightAction.TOGGLE)
        runCurrent()
        assertEquals(listOf(HouseCommand.Light(LightAction.TOGGLE)), fake.sentCommands)
        assertEquals(listOf("Raspberry odmítlo příkaz přepnout světlo (kód 128)."), messages)
    }

    // Přepnutí zdroje. Stav ViewModelu se při přepnutí maže celý, takže časovač, který by přepnutí přežil, je vidět
    // teprve na změně téhož klíče v novém zdroji.

    // Přeživší odložený zápis by poslal draft z nového zdroje předčasně a jeho vlastní odložený zápis pak podruhé.
    @Test
    fun writeDebouncedBeforeASwitchNeverReachesTheNewSource() = vmTest {
        val second = FakeHouseDataSource(connection = ConnectionState.Simulated, settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        runCurrent()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5) // do starého zdroje by odešlo v 600
        advanceTimeBy(300)
        vm.selectSource(SourceKind.SIMULATION)
        advanceTimeBy(100)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0) // 400: do nového zdroje odejde v 1000

        advanceTimeBy(599) // 999
        assertEquals(emptyList<Any>(), second.writtenSettings)
        advanceTimeBy(2) // 1001
        assertEquals(listOf(listOf(SettingKey.TARGET_DAY.change(24.0))), second.writtenSettings)
        advanceTimeBy(5_000)
        assertEquals(1, second.writtenSettings.size)
        assertTrue(fake.writtenSettings.isEmpty())
    }

    // Přeživší čekání na potvrzení zápisu do starého zdroje by smazalo draft téhož klíče z nového zdroje dřív,
    // než uplynou jeho vlastní 3 s.
    @Test
    fun confirmationPendingAtASwitchDoesNotDropANewDraft() = vmTest {
        val second = FakeHouseDataSource(connection = ConnectionState.Simulated, settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        runCurrent()
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        advanceTimeBy(1_000) // 23,5 odešlo v 600 a na potvrzení by čekalo do 3600
        assertEquals(1, fake.writtenSettings.size)
        vm.selectSource(SourceKind.SIMULATION)
        advanceTimeBy(100)
        vm.changeSetting(SettingKey.TARGET_DAY, 24.0) // 1100: odejde v 1700, na potvrzení čeká do 4700

        advanceTimeBy(2_501) // 3601
        assertEquals(listOf(listOf(SettingKey.TARGET_DAY.change(24.0))), second.writtenSettings)
        assertEquals(24.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertEquals(setOf(SettingKey.TARGET_DAY), vm.state.pendingKeys)
        advanceTimeBy(1_100) // 4701
        assertEquals(23.0, vm.state.number(SettingKey.TARGET_DAY)!!, 0.0)
        assertTrue(vm.state.pendingKeys.isEmpty())
    }

    // Totéž pro draft světla: jeho 3 s ze starého zdroje nesmí ukrojit z draftu nastaveného v novém zdroji.
    @Test
    fun lightDraftExpiryPendingAtASwitchDoesNotDropANewLightDraft() = vmTest {
        val second = FakeHouseDataSource(connection = ConnectionState.Simulated, settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        runCurrent()
        vm.setLightBrightness(40) // draft by zmizel v 3000
        advanceTimeBy(1_000)
        vm.selectSource(SourceKind.SIMULATION)
        advanceTimeBy(100)
        vm.setLightBrightness(50) // 1100: draft zmizí v 4100

        advanceTimeBy(1_901) // 3001
        assertEquals(LightDraft(brightness = 50, setAtMs = 1_100), vm.state.lightDraft)
        advanceTimeBy(1_100) // 4101
        assertNull(vm.state.lightDraft)
    }

    @Test
    fun switchClearsWriteStateAndNothingOfTheOldSourceComesBack() = vmTest {
        val second = FakeHouseDataSource(connection = ConnectionState.Simulated, settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        val messages = messagesOf(vm)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        fake.nextWriteResult = WriteResult.NotConnected
        vm.setDayStart(DayPart.NIGHT, LocalTime.of(10, 5)) // zápis zůstane viset na bráně
        vm.changeSetting(SettingKey.TARGET_DAY, 23.5)
        vm.setLightBrightness(40)
        runCurrent()
        val before = vm.state
        assertEquals(setOf(SettingKey.NIGHT_START, SettingKey.TARGET_DAY), before.pendingKeys)
        assertEquals(setOf(SettingKey.NIGHT_START, SettingKey.TARGET_DAY), before.drafts.keys)
        assertNotNull(before.lightDraft)
        assertNotNull(before.savedDayStarts)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()
        // Zápis, který čekal na bráně starého zdroje, přepnutí zrušilo. Otevření brány už nepřinese hlášku ani změnu stavu.
        gate.complete(Unit)
        advanceTimeBy(5_000)

        val after = vm.state
        assertTrue(after.drafts.isEmpty())
        assertTrue(after.pendingKeys.isEmpty())
        assertNull(after.lightDraft)
        assertNull(after.savedDayStarts)
        assertTrue(messages.isEmpty())
        assertTrue(second.writtenSettings.isEmpty())
        assertTrue(second.writtenLooks.isEmpty())
        assertTrue(second.sentCommands.isEmpty())
    }

    @Test
    fun lightRequestPendingAtASwitchNeverReachesTheNewSource() = vmTest {
        val second = FakeHouseDataSource(connection = ConnectionState.Simulated, settings = DEFAULTS)
        val vm = newViewModel { kind, _ -> if (kind == SourceKind.BLUETOOTH) fake else second }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate
        vm.setLightBrightness(10)
        runCurrent()
        vm.setLightBrightness(20)

        vm.selectSource(SourceKind.SIMULATION)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 10)), fake.writtenLooks)
        assertTrue(second.writtenLooks.isEmpty())

        // Nový zdroj má vlastního odesílatele vzhledu.
        vm.setLightBrightness(50)
        runCurrent()
        assertEquals(listOf(StoredLook(brightness = 50)), second.writtenLooks)
    }
}
