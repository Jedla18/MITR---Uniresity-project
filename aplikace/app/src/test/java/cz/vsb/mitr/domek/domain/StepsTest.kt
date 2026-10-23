package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.SettingKey.DAY_START
import cz.vsb.mitr.domek.data.model.SettingKey.EVENING_START
import cz.vsb.mitr.domek.data.model.SettingKey.LIGHT_ON_MOTION
import cz.vsb.mitr.domek.data.model.SettingKey.LUX_THRESHOLD
import cz.vsb.mitr.domek.data.model.SettingKey.MODE
import cz.vsb.mitr.domek.data.model.SettingKey.NIGHT_LIGHT_SECONDS
import cz.vsb.mitr.domek.data.model.SettingKey.NIGHT_START
import cz.vsb.mitr.domek.data.model.SettingKey.NOBODY_HOME_AFTER
import cz.vsb.mitr.domek.data.model.SettingKey.NOBODY_HOME_SETBACK
import cz.vsb.mitr.domek.data.model.SettingKey.TARGET_DAY
import cz.vsb.mitr.domek.data.model.SettingKey.TARGET_MANUAL
import cz.vsb.mitr.domek.data.model.SettingKey.TARGET_NIGHT
import cz.vsb.mitr.domek.data.model.SettingKind
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {
    // Tolerance 0: výsledek kroku musí být přesně to desetinné číslo, které zná Raspberry, ne jeho okolí.
    private fun assertStepUp(expected: Double, key: SettingKey, current: Double) =
        assertEquals("${key.name} z $current nahoru", expected, stepValue(key, current, up = true), 0.0)

    private fun assertStepDown(expected: Double, key: SettingKey, current: Double) =
        assertEquals("${key.name} z $current dolů", expected, stepValue(key, current, up = false), 0.0)

    private val numericKeys = SettingKey.entries.filter { it.kind == SettingKind.DECIMAL || it.kind == SettingKind.WHOLE }

    @Test
    fun targetsStepByHalfDegree() {
        assertStepUp(23.5, TARGET_DAY, 23.0)
        assertStepDown(19.5, TARGET_NIGHT, 20.0)
        assertStepUp(22.5, TARGET_MANUAL, 22.0)
    }

    @Test
    fun targetsStopAtTheLimits() {
        assertStepUp(35.0, TARGET_DAY, 35.0)
        assertStepDown(5.0, TARGET_DAY, 5.0)
        assertStepUp(35.0, TARGET_DAY, 34.8)
        assertStepDown(5.0, TARGET_DAY, 5.3)
    }

    @Test
    fun nobodyHomeStepsByMinuteAndHalfDegree() {
        assertStepDown(29.0, NOBODY_HOME_AFTER, 30.0)
        assertStepUp(31.0, NOBODY_HOME_AFTER, 30.0)
        assertStepDown(1.0, NOBODY_HOME_AFTER, 1.0)
        assertStepUp(240.0, NOBODY_HOME_AFTER, 240.0)
        assertStepDown(2.5, NOBODY_HOME_SETBACK, 3.0)
        assertStepUp(3.5, NOBODY_HOME_SETBACK, 3.0)
        assertStepDown(0.0, NOBODY_HOME_SETBACK, 0.0)
        assertStepUp(10.0, NOBODY_HOME_SETBACK, 10.0)
    }

    @Test
    fun nightLightStepsByFiveSeconds() {
        assertStepUp(35.0, NIGHT_LIGHT_SECONDS, 30.0)
        assertStepDown(25.0, NIGHT_LIGHT_SECONDS, 30.0)
        assertStepDown(5.0, NIGHT_LIGHT_SECONDS, 5.0)
        assertStepUp(600.0, NIGHT_LIGHT_SECONDS, 600.0)
    }

    @Test
    fun luxThresholdStepGrowsWithMagnitude() {
        assertStepUp(55.0, LUX_THRESHOLD, 50.0)
        assertStepDown(95.0, LUX_THRESHOLD, 100.0)
        assertStepUp(10.0, LUX_THRESHOLD, 9.0)
        assertStepDown(950.0, LUX_THRESHOLD, 1000.0)
        assertStepUp(1500.0, LUX_THRESHOLD, 1000.0)
        assertStepDown(1.0, LUX_THRESHOLD, 1.0)
        assertStepUp(10000.0, LUX_THRESHOLD, 10000.0)
    }

    @Test
    fun luxThresholdStepInsideAndBetweenTheBands() {
        assertStepUp(2.0, LUX_THRESHOLD, 1.0)
        assertStepUp(15.0, LUX_THRESHOLD, 10.0)
        assertStepDown(9.0, LUX_THRESHOLD, 10.0)
        assertStepUp(150.0, LUX_THRESHOLD, 100.0)
        assertStepDown(1500.0, LUX_THRESHOLD, 2000.0)
        assertStepUp(5500.0, LUX_THRESHOLD, 5000.0)
        assertStepDown(9500.0, LUX_THRESHOLD, 10000.0)
    }

    @Test
    fun luxLadderIsTheSameGoingUpAndDown() {
        val expected = (1..10).map { it.toDouble() } +
            (15..100 step 5).map { it.toDouble() } +
            (150..1000 step 50).map { it.toDouble() } +
            (1500..10000 step 500).map { it.toDouble() }

        val upwards = mutableListOf(LUX_THRESHOLD.min)
        while (upwards.last() < LUX_THRESHOLD.max) upwards += stepValue(LUX_THRESHOLD, upwards.last(), up = true)
        val downwards = mutableListOf(LUX_THRESHOLD.max)
        while (downwards.last() > LUX_THRESHOLD.min) downwards += stepValue(LUX_THRESHOLD, downwards.last(), up = false)

        assertEquals(expected, upwards)
        assertEquals(expected.reversed(), downwards)
    }

    @Test
    fun stepsDoNotLeaveBinaryNoise() {
        assertStepUp(23.7, TARGET_DAY, 23.2)
        assertEquals("23.7", stepValue(TARGET_DAY, 23.2, up = true).toString())
        // Odečtení 0,5 dává bez zaokrouhlení třeba 7.699999999999999 nebo 15.899999999999999.
        assertStepDown(7.7, TARGET_DAY, 8.2)
        assertStepDown(7.8, TARGET_DAY, 8.3)
        assertStepDown(15.6, TARGET_DAY, 16.1)
        assertStepDown(15.9, TARGET_DAY, 16.4)
        assertStepDown(31.7, TARGET_DAY, 32.2)
        assertStepDown(31.8, TARGET_DAY, 32.3)
        assertEquals("15.9", stepValue(TARGET_DAY, 16.4, up = false).toString())
    }

    @Test
    fun walkingAcrossTheWholeRangeKeepsEveryValueOnTheTenthGrid() {
        var value = 5.1
        for (i in 1..59) {
            value = stepValue(TARGET_DAY, value, up = true)
            assertEquals("nahoru $i", (51 + 5 * i) / 10.0, value, 0.0)
        }
        for (i in 1..59) {
            value = stepValue(TARGET_DAY, value, up = false)
            assertEquals("dolů $i", (346 - 5 * i) / 10.0, value, 0.0)
        }
    }

    @Test
    fun wholeKeysAlwaysGiveWholeNumbers() {
        assertStepUp(35.0, NIGHT_LIGHT_SECONDS, 30.4)
        assertStepDown(29.0, NOBODY_HOME_AFTER, 30.4)
        assertStepUp(31.0, NOBODY_HOME_AFTER, 29.6)
    }

    @Test
    fun everyNumericKeyHasAStepAndStaysInItsRange() {
        numericKeys.forEach { key ->
            val up = stepValue(key, key.min, up = true)
            val down = stepValue(key, key.max, up = false)

            assertTrue("${key.name} nahoru z minima", up > key.min && up <= key.max)
            assertTrue("${key.name} dolů z maxima", down < key.max && down >= key.min)
            assertEquals("${key.name} dolů z minima", key.min, stepValue(key, key.min, up = false), 0.0)
            assertEquals("${key.name} nahoru z maxima", key.max, stepValue(key, key.max, up = true), 0.0)
            if (key.kind == SettingKind.WHOLE) {
                assertEquals("${key.name} nahoru je celé číslo", floor(up), up, 0.0)
                assertEquals("${key.name} dolů je celé číslo", floor(down), down, 0.0)
            }
        }
    }

    @Test
    fun rejectsKeysWithoutNumericValue() {
        listOf(MODE, DAY_START, EVENING_START, NIGHT_START, LIGHT_ON_MOTION).forEach { key ->
            assertThrows(key.name, IllegalArgumentException::class.java) { stepValue(key, 1.0, up = true) }
        }
    }
}
