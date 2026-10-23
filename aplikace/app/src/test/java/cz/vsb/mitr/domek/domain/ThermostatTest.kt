package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermostatTest {
    @Test
    fun autoUsesDayTargetByDayAndEveningAndNightTargetByNight() {
        assertEquals(TargetSlot.DAY, activeTargetSlot(ThermostatMode.AUTO, DayPart.DAY))
        assertEquals(TargetSlot.DAY, activeTargetSlot(ThermostatMode.AUTO, DayPart.EVENING))
        assertEquals(TargetSlot.NIGHT, activeTargetSlot(ThermostatMode.AUTO, DayPart.NIGHT))
    }

    // Raspberry v Auto drží noční cíl jen v noci, takže neznámý úsek dne znamená denní cíl.
    @Test
    fun autoWithUnknownPartUsesDayTarget() {
        assertEquals(TargetSlot.DAY, activeTargetSlot(ThermostatMode.AUTO, null))
    }

    @Test
    fun manualModeUsesManualTargetInEveryPart() {
        DayPart.entries.forEach { part ->
            assertEquals(part.name, TargetSlot.MANUAL, activeTargetSlot(ThermostatMode.MANUAL, part))
        }
    }

    @Test
    fun offModeHasNoTargetInEveryPart() {
        DayPart.entries.forEach { part ->
            assertNull(part.name, activeTargetSlot(ThermostatMode.OFF, part))
        }
    }

    @Test
    fun slotsPointToTheirSettingsAndLabels() {
        assertEquals(SettingKey.TARGET_DAY, TargetSlot.DAY.key)
        assertEquals(SettingKey.TARGET_NIGHT, TargetSlot.NIGHT.key)
        assertEquals(SettingKey.TARGET_MANUAL, TargetSlot.MANUAL.key)
        assertEquals("Denní cíl", TargetSlot.DAY.label)
        assertEquals("Noční cíl", TargetSlot.NIGHT.label)
        assertEquals("Ruční cíl", TargetSlot.MANUAL.label)
    }

    private fun assertThresholds(target: Double, heatBelow: Double, coolAbove: Double) {
        val thresholds = checkNotNull(thermostatThresholds(target)) { "cíl $target" }
        assertEquals("topení při cíli $target", heatBelow, thresholds.heatBelow, 1e-9)
        assertEquals("chlazení při cíli $target", coolAbove, thresholds.coolAbove, 1e-9)
    }

    // Smlouva Raspberry (docs/ble-rozhrani.md): topení naskočí, když je uvnitř pod cílem o víc než 0,3 °C, chlazení, když je
    // nad cílem o víc než 1,0 °C.
    @Test
    fun heatingStartsThreeTenthsBelowTheTargetAndCoolingOneDegreeAboveIt() {
        assertThresholds(23.0, heatBelow = 22.7, coolAbove = 24.0)
        assertThresholds(20.0, heatBelow = 19.7, coolAbove = 21.0)
    }

    // Cíle jsou po 0,5 °C od 5 do 35 °C.
    @Test
    fun thresholdsFollowEveryTargetOnTheHalfDegreeGrid() {
        assertThresholds(5.0, heatBelow = 4.7, coolAbove = 6.0)
        assertThresholds(20.5, heatBelow = 20.2, coolAbove = 21.5)
        assertThresholds(35.0, heatBelow = 34.7, coolAbove = 36.0)
    }

    // Ve Vypnuto je cíl null: netopí se ani nechladí, takže prahy nejsou.
    @Test
    fun withoutATargetThereAreNoThresholds() {
        assertNull(thermostatThresholds(null))
    }

    // Topení po naskočení topí až do cíle, takže práh říká, kdy naskočí, ne kdy topí.
    @Test
    fun thresholdsTextSaysWhereHeatingAndCoolingSwitchOn() {
        assertEquals(
            "Topení naskočí pod 22,7${NBSP}°C, chlazení nad 24,0${NBSP}°C",
            thresholdsText(checkNotNull(thermostatThresholds(23.0))),
        )
    }
}
