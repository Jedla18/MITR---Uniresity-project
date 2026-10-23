package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.source.WriteResult
import cz.vsb.mitr.domek.domain.NBSP
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
    private val rejected = WriteResult.Rejected(128)

    // Popis hodnoty

    @Test
    fun describesValuesInTheirOwnFormat() {
        assertEquals("23,5${NBSP}°C", describeValue(SettingKey.TARGET_DAY, JsonPrimitive(23.5)))
        assertEquals("30${NBSP}min", describeValue(SettingKey.NOBODY_HOME_AFTER, JsonPrimitive(30)))
        assertEquals("19:00", describeValue(SettingKey.EVENING_START, JsonPrimitive("19:00")))
        assertEquals("Ruční", describeValue(SettingKey.MODE, JsonPrimitive("rucni")))
        assertEquals("vypnuto", describeValue(SettingKey.LIGHT_ON_MOTION, JsonPrimitive(false)))
        assertEquals("zapnuto", describeValue(SettingKey.LIGHT_ON_MOTION, JsonPrimitive(true)))
    }

    // Osvětlení je v celé aplikaci bez desetin, teploty s jednou.
    @Test
    fun luxThresholdHasNoDecimals() {
        assertEquals("50${NBSP}lx", describeValue(SettingKey.LUX_THRESHOLD, JsonPrimitive(50.0)))
        assertEquals("3,0${NBSP}°C", describeValue(SettingKey.NOBODY_HOME_SETBACK, JsonPrimitive(3.0)))
    }

    // Odmítnutý zápis Nastavení

    @Test
    fun sameStartsAreExplained() {
        assertEquals(
            "Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.NIGHT_START.change("19:00")), DEFAULTS, rejected),
        )
        assertEquals(
            "Raspberry změnu odmítlo: začátek dne 22:00 je stejný jako začátek noci. Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.DAY_START.change("22:00")), DEFAULTS, rejected),
        )
    }

    // Raspberry začátky posuzuje až po sloučení zápisu s Nastavením: prohození dvou začátků jedním zápisem shodu nemá.
    @Test
    fun startsAreComparedAfterMergingTheChangesIntoTheSettings() {
        val swapped = listOf(SettingKey.EVENING_START.change("22:00"), SettingKey.NIGHT_START.change("19:00"))
        assertEquals(
            "Raspberry změnu odmítlo: začátek večera 22:00, začátek noci 19:00 (kód 128). Nic se nezměnilo.",
            settingsWriteMessage(swapped, DEFAULTS, rejected),
        )
        // Bez známého Nastavení se porovnávají jen začátky ze zápisu.
        assertEquals(
            "Raspberry změnu odmítlo: začátek noci 19:00 (kód 128). Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.NIGHT_START.change("19:00")), null, rejected),
        )
    }

    // Shodují se všechny tři: jmenuje se první změněný začátek a první jiný, se kterým se shoduje.
    @Test
    fun firstChangedStartThatMatchesAnotherIsNamed() {
        val changes = listOf(SettingKey.EVENING_START.change("22:00"), SettingKey.DAY_START.change("22:00"))
        assertEquals(
            "Raspberry změnu odmítlo: začátek večera 22:00 je stejný jako začátek dne. Nic se nezměnilo.",
            settingsWriteMessage(changes, DEFAULTS, rejected),
        )
    }

    @Test
    fun valueOutOfRangeIsExplained() {
        assertEquals(
            "Raspberry změnu odmítlo: denní cíl 36,0${NBSP}°C je mimo rozsah 5–35${NBSP}°C. Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.TARGET_DAY.change(36.0)), DEFAULTS, rejected),
        )
        assertEquals(
            "Raspberry změnu odmítlo: délka nočního světla 700${NBSP}s je mimo rozsah 5–600${NBSP}s. Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.NIGHT_LIGHT_SECONDS.change(700.0)), DEFAULTS, rejected),
        )
    }

    @Test
    fun lightOnMotionUnknownToAnOlderRaspberryIsExplained() {
        assertEquals(
            "Raspberry změnu odmítlo: volbu rozsvěcení při pohybu nezná, nejspíš má starší program. Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.LIGHT_ON_MOTION.change(false)), DEFAULTS, rejected),
        )
    }

    @Test
    fun otherRejectionNamesTheChangeAndTheCode() {
        assertEquals(
            "Raspberry změnu odmítlo: denní cíl 23,5${NBSP}°C (kód 128). Nic se nezměnilo.",
            settingsWriteMessage(listOf(SettingKey.TARGET_DAY.change(23.5)), DEFAULTS, rejected),
        )
    }

    @Test
    fun severalRejectedChangesAreJoinedWithComma() {
        val restore = listOf(
            SettingKey.DAY_START.change("07:00"),
            SettingKey.EVENING_START.change("19:00"),
            SettingKey.NIGHT_START.change("22:00"),
        )
        assertEquals(
            "Raspberry změnu odmítlo: začátek dne 07:00, začátek večera 19:00, začátek noci 22:00 (kód 128). Nic se nezměnilo.",
            settingsWriteMessage(restore, DEFAULTS.copy(nightStart = "10:05"), rejected),
        )
    }

    @Test
    fun unsentAndUnansweredWritesAreExplained() {
        val changes = listOf(SettingKey.TARGET_DAY.change(23.5))
        assertEquals(
            "Dům není připojený, změna se neodeslala.",
            settingsWriteMessage(changes, DEFAULTS, WriteResult.NotConnected),
        )
        assertEquals(
            "Raspberry neodpovědělo. Změna se možná neprovedla.",
            settingsWriteMessage(changes, DEFAULTS, WriteResult.Timeout),
        )
    }

    @Test
    fun successfulWriteHasNoMessage() {
        assertNull(settingsWriteMessage(listOf(SettingKey.TARGET_DAY.change(23.5)), DEFAULTS, WriteResult.Ok))
    }

    // Uložená barva a jas světla. Hodnoty aplikace do rozsahu omezuje sama, takže důvod odmítnutí nezná: hláška jmenuje, co se
    // zapisovalo, a připojí kód.

    @Test
    fun rejectedStoredLookNamesWhatWasWrittenAndTheCode() {
        assertEquals(
            "Raspberry změnu odmítlo: barva světla, jas světla 40${NBSP}% (kód 128). Nic se nezměnilo.",
            storedLookMessage(Rgb(0, 0, 255), 40, rejected),
        )
        assertEquals(
            "Raspberry změnu odmítlo: barva světla (kód 128). Nic se nezměnilo.",
            storedLookMessage(Rgb(255, 147, 41), null, rejected),
        )
        assertEquals(
            "Raspberry změnu odmítlo: jas světla 60${NBSP}% (kód 133). Nic se nezměnilo.",
            storedLookMessage(null, 60, WriteResult.Rejected(133)),
        )
    }

    @Test
    fun unsentUnansweredAndSuccessfulStoredLooks() {
        assertEquals("Dům není připojený, změna se neodeslala.", storedLookMessage(Rgb(0, 0, 255), 40, WriteResult.NotConnected))
        assertEquals("Raspberry neodpovědělo. Změna se možná neprovedla.", storedLookMessage(null, 40, WriteResult.Timeout))
        assertNull(storedLookMessage(null, 40, WriteResult.Ok))
    }

    // Příkazy

    @Test
    fun rejectedCommandNamesTheCommandAndTheCode() {
        assertEquals(
            "Raspberry odmítlo příkaz přepnout světlo (kód 128).",
            commandMessage(HouseCommand.Light(LightAction.TOGGLE), rejected),
        )
        assertEquals(
            "Raspberry odmítlo příkaz nastavit barvu a jas (kód 133).",
            commandMessage(HouseCommand.LightLook(Rgb(0, 0, 255), 40), WriteResult.Rejected(133)),
        )
    }

    @Test
    fun unsentUnansweredAndSuccessfulCommands() {
        val toggle = HouseCommand.Light(LightAction.TOGGLE)
        assertEquals("Dům není připojený, změna se neodeslala.", commandMessage(toggle, WriteResult.NotConnected))
        assertEquals("Raspberry neodpovědělo. Změna se možná neprovedla.", commandMessage(toggle, WriteResult.Timeout))
        assertNull(commandMessage(toggle, WriteResult.Ok))
    }
}
