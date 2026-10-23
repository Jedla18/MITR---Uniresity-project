package cz.vsb.mitr.domek.testing

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.source.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Na fake se spoléhají testy ViewModelu, proto si hlídá hlavně to, co není na první pohled vidět: odpovědi a bránu příkazů.
@OptIn(ExperimentalCoroutinesApi::class)
class FakeHouseDataSourceTest {
    private val change = SettingKey.TARGET_DAY.change(24.0)
    private val command = HouseCommand.Light(LightAction.TOGGLE)

    @Test
    fun recordsWritesAndCommandsAndAnswersWithTheConfiguredResults() = runTest {
        val fake = FakeHouseDataSource()
        assertEquals(WriteResult.Ok, fake.writeSettings(listOf(change)))
        assertEquals(WriteResult.Ok, fake.sendCommand(command))

        fake.nextWriteResult = WriteResult.Rejected(128)
        fake.nextCommandResult = WriteResult.Timeout
        assertEquals(WriteResult.Rejected(128), fake.writeSettings(listOf(change)))
        assertEquals(WriteResult.Timeout, fake.sendCommand(command))

        assertEquals(listOf(listOf(change), listOf(change)), fake.writtenSettings)
        assertEquals(listOf(command, command), fake.sentCommands)
    }

    @Test
    fun firstCommandWaitsOnTheGateAndThenTheGateIsReset() = runTest {
        val fake = FakeHouseDataSource()
        val gate = CompletableDeferred<Unit>()
        fake.commandGate = gate

        val first = async { fake.sendCommand(command) }
        runCurrent()
        // Příkaz je zaznamenaný hned, odpověď ale čeká na bránu.
        assertEquals(listOf(command), fake.sentCommands)
        assertFalse(first.isCompleted)

        gate.complete(Unit)
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals(WriteResult.Ok, first.await())
        assertNull(fake.commandGate)

        // Další příkaz už bránu nepotřebuje.
        assertEquals(WriteResult.Ok, fake.sendCommand(command))
        assertEquals(listOf(command, command), fake.sentCommands)
    }

    @Test
    fun firstWriteWaitsOnTheGateAndAnswersWithTheResultValidThen() = runTest {
        val fake = FakeHouseDataSource()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate

        val first = async { fake.writeSettings(listOf(change)) }
        runCurrent()
        assertEquals(listOf(listOf(change)), fake.writtenSettings)
        assertFalse(first.isCompleted)

        // Výsledek se čte až po otevření brány, takže test ho může nastavit i během zápisu.
        fake.nextWriteResult = WriteResult.Timeout
        gate.complete(Unit)
        runCurrent()
        assertEquals(WriteResult.Timeout, first.await())
        assertNull(fake.writeGate)

        fake.nextWriteResult = WriteResult.Ok
        assertEquals(WriteResult.Ok, fake.writeSettings(listOf(change)))
    }

    // Uložená barva a jas jdou na Raspberry zápisem Nastavení, proto čekají na stejnou bránu a odpovídají stejným výsledkem.
    @Test
    fun storedLookWritesAreRecordedAndAnsweredLikeSettingsWrites() = runTest {
        val fake = FakeHouseDataSource()
        val gate = CompletableDeferred<Unit>()
        fake.writeGate = gate

        val first = async { fake.writeStoredLook(Rgb(0, 0, 255), 40) }
        runCurrent()
        assertEquals(listOf(StoredLook(Rgb(0, 0, 255), 40)), fake.writtenLooks)
        assertFalse(first.isCompleted)

        fake.nextWriteResult = WriteResult.Rejected(128)
        gate.complete(Unit)
        runCurrent()
        assertEquals(WriteResult.Rejected(128), first.await())
        assertNull(fake.writeGate)

        fake.nextWriteResult = WriteResult.Ok
        assertEquals(WriteResult.Ok, fake.writeStoredLook(null, 60))
        assertEquals(listOf(StoredLook(Rgb(0, 0, 255), 40), StoredLook(brightness = 60)), fake.writtenLooks)
        // Zápis Nastavení po klíčích to není.
        assertTrue(fake.writtenSettings.isEmpty())
    }

    @Test
    fun firstRefreshWaitsOnTheGateAndThenTheGateIsReset() = runTest {
        val fake = FakeHouseDataSource()
        val gate = CompletableDeferred<Unit>()
        fake.refreshGate = gate

        val first = async { fake.refreshSettings() }
        runCurrent()
        assertEquals(1, fake.settingsRefreshes)
        assertFalse(first.isCompleted)

        gate.complete(Unit)
        runCurrent()
        assertTrue(first.isCompleted)
        assertNull(fake.refreshGate)

        fake.refreshSettings()
        assertEquals(2, fake.settingsRefreshes)
    }

    @Test
    fun keepsLastStatusAndSettingsAfterStop() {
        val fake = FakeHouseDataSource(settings = DEFAULTS)
        fake.start()
        fake.stop()

        assertTrue(fake.started)
        assertTrue(fake.stopped)
        assertEquals(DEFAULTS, fake.settings.value)
    }
}
