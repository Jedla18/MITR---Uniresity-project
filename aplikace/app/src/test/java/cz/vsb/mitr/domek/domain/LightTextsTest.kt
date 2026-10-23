package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.DayPart.DAY
import cz.vsb.mitr.domek.data.model.DayPart.EVENING
import cz.vsb.mitr.domek.data.model.DayPart.NIGHT
import cz.vsb.mitr.domek.data.model.LightState
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class LightTextsTest {
    private fun on(brightness: Int, manual: Boolean) = LightState(true, listOf(100, 110, 255), brightness, manual)

    private fun off(manual: Boolean) = LightState(false, listOf(0, 0, 0), 0, manual)

    @Test
    fun manuallyOnShowsBrightnessAndUntil() {
        assertEquals(
            LightTexts("Svítí · ručně", "Jas 60\u00A0% · ruční volba platí do 19:00", "Svítí", "ručně · 60\u00A0%"),
            lightTexts(on(60, manual = true), DAY, LocalTime.of(19, 0)),
        )
    }

    @Test
    fun manuallyOnWithoutUntilShowsOnlyBrightness() {
        assertEquals(
            LightTexts("Svítí · ručně", "Jas 60\u00A0%", "Svítí", "ručně · 60\u00A0%"),
            lightTexts(on(60, manual = true), DAY, null),
        )
    }

    @Test
    fun manuallyOffShowsUntil() {
        assertEquals(
            LightTexts("Zhasnuto · ručně", "Ruční volba platí do 19:00", "Zhasnuto", "ručně"),
            lightTexts(off(manual = true), DAY, LocalTime.of(19, 0)),
        )
    }

    @Test
    fun manuallyOffWithoutUntilHasNoDetail() {
        assertEquals(
            LightTexts("Zhasnuto · ručně", "", "Zhasnuto", "ručně"),
            lightTexts(off(manual = true), DAY, null),
        )
    }

    @Test
    fun manualChoiceDoesNotDependOnThePartOfDay() {
        DayPart.entries.forEach { part ->
            assertEquals(
                part.name,
                LightTexts("Svítí · ručně", "Jas 60\u00A0% · ruční volba platí do 07:05", "Svítí", "ručně · 60\u00A0%"),
                lightTexts(on(60, manual = true), part, LocalTime.of(7, 5)),
            )
        }
    }

    @Test
    fun offLightIgnoresStoredBrightness() {
        // Zhasnuté světlo nikdy nezobrazuje jas, ani kdyby Raspberry nechalo v poli jas starou hodnotu.
        val leftover = LightState(false, listOf(100, 110, 255), 60, true)

        assertEquals(
            LightTexts("Zhasnuto · ručně", "Ruční volba platí do 19:00", "Zhasnuto", "ručně"),
            lightTexts(leftover, DAY, LocalTime.of(19, 0)),
        )
        assertEquals(
            LightTexts("Zhasnuto · venku je dost světla", "", "Zhasnuto", "auto"),
            lightTexts(leftover.copy(manual = false), DAY, null),
        )
    }

    @Test
    fun autoByDay() {
        assertEquals(
            LightTexts("Svítí · venku je šero", "Jas 80\u00A0%", "Svítí", "auto · 80\u00A0%"),
            lightTexts(on(80, manual = false), DAY, null),
        )
        assertEquals(
            LightTexts("Zhasnuto · venku je dost světla", "", "Zhasnuto", "auto"),
            lightTexts(off(manual = false), DAY, null),
        )
    }

    @Test
    fun autoByEvening() {
        assertEquals(
            LightTexts("Svítí · večerní světlo", "Jas 60\u00A0%", "Svítí", "auto · 60\u00A0%"),
            lightTexts(on(60, manual = false), EVENING, null),
        )
        assertEquals(
            LightTexts("Zhasnuto", "", "Zhasnuto", "auto"),
            lightTexts(off(manual = false), EVENING, null),
        )
    }

    @Test
    fun autoByNight() {
        assertEquals(
            LightTexts("Noční světlo · pohyb", "Jas 15\u00A0%", "Svítí", "auto · 15\u00A0%"),
            lightTexts(on(15, manual = false), NIGHT, null),
        )
        assertEquals(
            LightTexts("Zhasnuto · noc", "", "Zhasnuto", "auto"),
            lightTexts(off(manual = false), NIGHT, null),
        )
    }

    // Neznámý nebo chybějící úsek dne (ble-rozhrani.md): texty automatiky bez odkazu na denní dobu.
    @Test
    fun autoWithoutKnownPartOfDay() {
        assertEquals(
            LightTexts("Svítí", "Jas 40${NBSP}%", "Svítí", "auto · 40${NBSP}%"),
            lightTexts(on(40, manual = false), null, null),
        )
        assertEquals(
            LightTexts("Zhasnuto", "", "Zhasnuto", "auto"),
            lightTexts(off(manual = false), null, null),
        )
    }

    @Test
    fun autoIgnoresManualUntil() {
        // Čas „platí do“ patří jen k ruční volbě; u automatiky by byl zavádějící.
        assertEquals(
            LightTexts("Svítí · venku je šero", "Jas 80\u00A0%", "Svítí", "auto · 80\u00A0%"),
            lightTexts(on(80, manual = false), DAY, LocalTime.of(19, 0)),
        )
        assertEquals(
            LightTexts("Zhasnuto · noc", "", "Zhasnuto", "auto"),
            lightTexts(off(manual = false), NIGHT, LocalTime.of(7, 0)),
        )
    }
}
