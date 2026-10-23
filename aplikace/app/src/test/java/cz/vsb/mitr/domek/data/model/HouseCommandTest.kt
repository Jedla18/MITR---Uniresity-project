package cz.vsb.mitr.domek.data.model

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HouseCommandTest {
    @Test
    fun lightActionsUseJsonNames() {
        assertEquals("""{"svetlo":"zapnout"}""", HouseCommand.Light(LightAction.ON).toJsonObject().toString())
        assertEquals("""{"svetlo":"vypnout"}""", HouseCommand.Light(LightAction.OFF).toJsonObject().toString())
        assertEquals("""{"svetlo":"prepnout"}""", HouseCommand.Light(LightAction.TOGGLE).toJsonObject().toString())
        assertEquals("""{"svetlo":"auto"}""", HouseCommand.Light(LightAction.AUTO).toJsonObject().toString())
    }

    @Test
    fun lightLookWritesColorAndBrightness() {
        assertEquals(
            """{"svetlo":{"barva":[0,0,255],"jas":50}}""",
            HouseCommand.LightLook(Rgb(0, 0, 255), 50).toJsonObject().toString(),
        )
    }

    @Test
    fun lightLookWritesOnlyWhatIsSet() {
        assertEquals("""{"svetlo":{"jas":50}}""", HouseCommand.LightLook(brightness = 50).toJsonObject().toString())
        assertEquals("""{"svetlo":{"barva":[255,128,0]}}""", HouseCommand.LightLook(color = Rgb(255, 128, 0)).toJsonObject().toString())
    }

    @Test
    fun lightLookNeedsColorOrBrightness() {
        assertThrows(IllegalArgumentException::class.java) { HouseCommand.LightLook() }
    }

    @Test
    fun setClockWritesPiTime() {
        assertEquals(
            """{"cas":"2026-10-20T14:03:00"}""",
            HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)).toJsonObject().toString(),
        )
    }

    @Test
    fun formatPiTimeAlwaysWritesSecondsAndDropsFraction() {
        assertEquals("2026-01-02T00:00:00", formatPiTime(LocalDateTime.of(2026, 1, 2, 0, 0)))
        assertEquals("2026-10-20T14:03:07", formatPiTime(LocalDateTime.of(2026, 10, 20, 14, 3, 7, 123_000_000)))
    }

    @Test
    fun parsePiTimeReadsRaspberryFormat() {
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0, 0), parsePiTime("2026-10-20T10:00:00"))
    }

    @Test
    fun parsePiTimeRejectsOtherTexts() {
        assertNull(parsePiTime("20.10.2026 10:00"))
        assertNull(parsePiTime("2026-10-20T10:00"))
        assertNull(parsePiTime("2026-02-30T10:00:00"))
        assertNull(parsePiTime(""))
    }

    @Test
    fun labelsDescribeTheCommand() {
        assertEquals("přepnout světlo", HouseCommand.Light(LightAction.TOGGLE).label())
        assertEquals("zapnout světlo", HouseCommand.Light(LightAction.ON).label())
        assertEquals("vypnout světlo", HouseCommand.Light(LightAction.OFF).label())
        assertEquals("vrátit světlo do automatiky", HouseCommand.Light(LightAction.AUTO).label())
        assertEquals("nastavit barvu a jas", HouseCommand.LightLook(Rgb(0, 0, 255), 50).label())
        assertEquals("nastavit čas", HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)).label())
    }
}
