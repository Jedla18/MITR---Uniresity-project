package cz.vsb.mitr.domek.ui.chart

import cz.vsb.mitr.domek.viewmodel.Band
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import cz.vsb.mitr.domek.viewmodel.downsample
import cz.vsb.mitr.domek.viewmodel.segments
import java.time.LocalDateTime
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Co se do grafu nakreslí, se rozhoduje v planChart, takže jde ověřit bez Compose. Kreslení pak jen prochází plán.
class TemperatureChartPlanTest {
    private val tenAm = LocalDateTime.of(2026, 10, 20, 10, 0)

    // Canvas telefonu: 328 dp po 3 px, nejvýš dva body na pixel.
    private val phoneMaxPoints = 2 * 984

    private fun s(
        sec: Int,
        inside: Double? = 22.0,
        outside: Double? = 21.0,
        target: Double? = 23.0,
        heating: Boolean = false,
        cooling: Boolean = false,
    ) = ChartSample(sec * 1000L, tenAm.plusSeconds(sec.toLong()), inside, outside, target, heating, cooling)

    // Dvě hodiny po 1 s: po zředění na dva body na pixel je koš široký 7,3 s, takže mezi body jsou mezery přes 5 s. Kdyby se
    // zředilo dřív, než se čára rozdělí na úseky, rozpadla by se na samé tečky.
    @Test
    fun twoHoursAtOneHertzAreOneContinuousSegmentPerSeries() {
        val history = (0 until 7_200).map { s(it, inside = 22.0 + sin(it / 600.0)) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 7_200_000, maxPoints = phoneMaxPoints)!!

        assertEquals(1, plan.inside.size)
        assertEquals(1, plan.outside.size)
        assertEquals(1, plan.target.size)
        val inside = plan.inside.single()
        assertTrue("bodů uvnitř: ${inside.size}", inside.size in 2..phoneMaxPoints)
        assertEquals(0L, inside.first().atMs)
        assertEquals(7_199_000L, inside.last().atMs)
        // Pojistka, že test hlídá správnou věc: na zředěných bodech by segments() čáru rozdělilo na desítky kusů.
        val thinned = downsample(history, phoneMaxPoints) { it.inside }
        assertTrue(segments(thinned, { it.inside }).size > 1)
    }

    @Test
    fun segmentsAreMadeOnTheUnthinnedSamplesSoOnlyRealGapsBreakTheLine() {
        // Výpadek 31 s uprostřed hodiny dat.
        val history = (0 until 3_600).filter { it !in 1_800..1_830 }.map { s(it) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 3_600_000, maxPoints = phoneMaxPoints)!!

        assertEquals(2, plan.inside.size)
        assertEquals(1_799_000L, plan.inside[0].last().atMs)
        assertEquals(1_831_000L, plan.inside[1].first().atMs)
    }

    @Test
    fun missingValueBreaksOnlyTheLineOfThatSeries() {
        val history = (0 until 60).map { s(it, inside = if (it in 20..29) null else 22.0) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 60_000, maxPoints = 1_000)!!

        assertEquals(listOf(20, 30), plan.inside.map { it.size })
        assertEquals(listOf(60), plan.outside.map { it.size })
        assertEquals(listOf(60), plan.target.map { it.size })
    }

    @Test
    fun seriesCarryTheValuesOfTheirOwnField() {
        val plan = planChart(listOf(s(0, inside = 20.0, outside = 10.0, target = 25.5)), ChartWindow.ALL, 0, 100)!!

        assertEquals(listOf(listOf(ChartPoint(0, 20.0))), plan.inside)
        assertEquals(listOf(listOf(ChartPoint(0, 10.0))), plan.outside)
        assertEquals(listOf(listOf(ChartPoint(0, 25.5))), plan.target)
    }

    // Úsek, který pokrývá desetinu okna, dostane desetinu bodů. Hodnoty kolísají, aby mělo ředění co ubírat.
    @Test
    fun pointBudgetIsProportionalToTheTimeSpanOfEachSegment() {
        val long = (0 until 3_600).map { s(it, inside = 20.0 + it % 7) }
        val short = (3_630 until 3_990).map { s(it, inside = 20.0 + it % 7) }

        val plan = planChart(long + short, ChartWindow.ALL, nowMs = 4_000_000, maxPoints = 1_000)!!

        assertEquals(2, plan.inside.size)
        val (longPoints, shortPoints) = plan.inside.map { it.size }
        assertTrue("dlouhý úsek: $longPoints", longPoints in 700..899)
        assertTrue("krátký úsek: $shortPoints", shortPoints in 20..89)
        assertTrue("dohromady: ${longPoints + shortPoints}", longPoints + shortPoints <= 1_000)
    }

    // Každá čára se řídne podle svých hodnot. Venkovní teplota má v jediném vzorku špičku, vnitřní je rovná: špička venku zůstane
    // a rovná vnitřní čára se řídne jako dřív, tedy na jeden vzorek z koše.
    @Test
    fun aShortSpikeOfTheOutsideLineSurvivesThinningWhileTheFlatInsideLineStaysThin() {
        val history = (0 until 7_200).map { s(it, outside = if (it == 3_601) 35.0 else 21.0) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 7_200_000, maxPoints = phoneMaxPoints)!!

        val inside = plan.inside.single()
        val outside = plan.outside.single()
        assertTrue("špička venku zůstala mezi ${outside.size} body", ChartPoint(3_601_000, 35.0) in outside)
        assertTrue("vnitřní čára špičku venku nenese", inside.none { it.atMs == 3_601_000L })
        // Rovná čára nechá z každého koše jediný vzorek, venkovní o jeden víc kvůli špičce.
        assertEquals(inside.size + 1, outside.size)
    }

    // Špička každé čáry zůstane jen té čáře, ostatní ji v tom koši nenesou.
    @Test
    fun eachLineKeepsItsOwnSpikeAndNoOneElsesSpike() {
        val spikes = mapOf("inside" to 1_801, "outside" to 3_601, "target" to 5_401)
        val history = (0 until 7_200).map {
            s(
                it,
                inside = if (it == spikes.getValue("inside")) 29.0 else 22.0,
                outside = if (it == spikes.getValue("outside")) 35.0 else 21.0,
                target = if (it == spikes.getValue("target")) 15.0 else 23.0,
            )
        }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 7_200_000, maxPoints = phoneMaxPoints)!!

        val lines = mapOf("inside" to plan.inside.single(), "outside" to plan.outside.single(), "target" to plan.target.single())
        assertTrue("špička vnitřní čáry", ChartPoint(1_801_000, 29.0) in lines.getValue("inside"))
        assertTrue("špička venkovní čáry", ChartPoint(3_601_000, 35.0) in lines.getValue("outside"))
        assertTrue("propad čáry cíle", ChartPoint(5_401_000, 15.0) in lines.getValue("target"))
        for ((line, points) in lines) {
            for ((owner, second) in spikes) {
                assertEquals("čára $line, špička čáry $owner", line == owner, points.any { it.atMs == second * 1_000L })
            }
        }
    }

    @Test
    fun windowEndsAtNowAndStartsOneDurationBefore() {
        val history = (0..900).map { s(it) }

        val plan = planChart(history, ChartWindow.FIVE_MIN, nowMs = 900_400, maxPoints = 2_000)!!

        assertEquals(600_400L, plan.fromMs)
        assertEquals(900_400L, plan.toMs)
        assertEquals(601_000L, plan.inside.single().first().atMs)
        assertEquals(900_000L, plan.inside.single().last().atMs)
    }

    @Test
    fun allWindowStartsAtTheFirstSample() {
        val plan = planChart((10..20).map { s(it) }, ChartWindow.ALL, nowMs = 21_000, maxPoints = 2_000)!!

        assertEquals(10_000L, plan.fromMs)
        assertEquals(21_000L, plan.toMs)
    }

    // Stav přijatý mezi dvěma tiky UI je novější než nowMs, ale jeho bod nesmí vypadnout z osy.
    @Test
    fun windowEndsAtTheNewestSampleWhenItIsNewerThanNow() {
        val plan = planChart(listOf(s(0), s(10)), ChartWindow.FIVE_MIN, nowMs = 9_600, maxPoints = 100)!!

        assertEquals(10_000L, plan.toMs)
    }

    @Test
    fun aSingleSampleIsOnePoint() {
        val plan = planChart(listOf(s(5)), ChartWindow.ALL, nowMs = 5_000, maxPoints = 100)!!

        assertEquals(listOf(listOf(ChartPoint(5_000, 22.0))), plan.inside)
        assertEquals(5_000L, plan.fromMs)
    }

    // Pruhy a rozsah osy se berou z neředěného okna. Zředěním by pruh topení kratší než rozestup bodů zmizel.
    @Test
    fun bandsComeFromTheUnthinnedWindow() {
        val history = (0 until 7_200).map { s(it, heating = it in 3_600..3_602, cooling = it in 5_000..5_001) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 7_200_000, maxPoints = phoneMaxPoints)!!

        assertEquals(listOf(Band(3_600_000, 3_603_000, heating = true), Band(5_000_000, 5_002_000, heating = false)), plan.bands)
    }

    // Špička venkovní teploty v jediném vzorku: při ředění na pár bodů zůstane z čáry jen první a poslední vzorek a špička vypadne,
    // ale osa musí vyjít z celého okna.
    @Test
    fun temperatureRangeIsMeasuredOnTheUnthinnedWindow() {
        val history = (0 until 7_200).map { s(it, outside = if (it == 3_601) 35.0 else 21.0) }

        val plan = planChart(history, ChartWindow.ALL, nowMs = 7_200_000, maxPoints = 3)!!

        assertTrue("předpoklad testu: špička se při ředění ztratí", plan.outside.flatten().none { it.value == 35.0 })
        assertEquals(35.5, plan.range.endInclusive, 1e-9)
        assertEquals(20.5, plan.range.start, 1e-9)
    }

    @Test
    fun rangeIgnoresSamplesOutsideTheWindow() {
        val history = listOf(s(0, inside = 40.0)) + (600..700).map { s(it, inside = 22.0, outside = 22.0, target = 22.0) }

        val plan = planChart(history, ChartWindow.FIVE_MIN, nowMs = 700_000, maxPoints = 100)!!

        assertEquals(21.0..23.0, plan.range)
    }

    @Test
    fun nothingToDrawGivesNoPlan() {
        assertNull(planChart(emptyList(), ChartWindow.ALL, 0, 100))
        assertNull(planChart((0 until 60).map { s(it, inside = null, outside = null, target = null) }, ChartWindow.ALL, 60_000, 100))
        // Topí a chladí bez jediné teploty nemá co nakreslit.
        val onlyFlags = (0 until 5).map { s(it, inside = null, outside = null, target = null, heating = true) }
        assertNull(planChart(onlyFlags, ChartWindow.ALL, 5_000, 100))
        // Poslední vzorek je starý deset minut, takže okno 5 min je prázdné.
        assertNull(planChart(listOf(s(0)), ChartWindow.FIVE_MIN, 600_000, 100))
    }

    @Test
    fun sampleWithOnlyOneTemperatureIsEnoughToDraw() {
        val plan = planChart(listOf(s(0, inside = null, outside = null, target = 24.0)), ChartWindow.ALL, 0, 100)!!

        assertTrue(plan.inside.isEmpty())
        assertTrue(plan.outside.isEmpty())
        assertEquals(1, plan.target.size)
    }

    // Čas osy se počítá od posledního vzorku: wallTime − (atMs poslední − t).
    @Test
    fun timeLabelsAreWallClockTimesCountedBackFromTheNewestSample() {
        val newest = ChartSample(1_000_000, LocalDateTime.of(2026, 10, 20, 10, 2, 15), 22.0, 21.0, 23.0, false, false)
        val older = newest.copy(atMs = 990_000, wallTime = newest.wallTime.minusSeconds(10))

        val plan = planChart(listOf(older, newest), ChartWindow.FIVE_MIN, nowMs = 1_000_400, maxPoints = 100)!!

        // 09:57:15,4, 09:59:45,4 a 10:02:15,4
        assertEquals(listOf("09:57", "09:59", "10:02"), plan.timeLabels)
    }

    // Poslední vzorek je 5 s starý, takže konec osy („teď“) je o 5 s dál než jeho čas: 10:02:59 + 5 s = 10:03:04.
    @Test
    fun endLabelIsTheTimeOfNowEvenWhenTheNewestSampleIsOlder() {
        val newest = ChartSample(1_000_000, LocalDateTime.of(2026, 10, 20, 10, 2, 59), 22.0, 21.0, 23.0, false, false)

        val plan = planChart(listOf(newest), ChartWindow.FIVE_MIN, nowMs = 1_005_000, maxPoints = 100)!!

        assertEquals(listOf("09:58", "10:00", "10:03"), plan.timeLabels)
    }

    @Test
    fun timeLabelsCrossMidnight() {
        val newest = ChartSample(1_000_000, LocalDateTime.of(2026, 10, 21, 0, 1, 0), 22.0, 21.0, 23.0, false, false)

        val plan = planChart(listOf(newest), ChartWindow.FIFTEEN_MIN, nowMs = 1_000_000, maxPoints = 100)!!

        assertEquals(listOf("23:46", "23:53", "00:01"), plan.timeLabels)
    }

    @Test
    fun allWindowLabelsStartAtTheFirstSample() {
        val plan = planChart((0..120).map { s(it) }, ChartWindow.ALL, nowMs = 120_000, maxPoints = 100)!!

        assertEquals(listOf("10:00", "10:01", "10:02"), plan.timeLabels)
    }

    @Test
    fun latestIsTheNewestSampleOfTheWindow() {
        val plan = planChart((0..10).map { s(it, inside = 20.0 + it) }, ChartWindow.ALL, nowMs = 10_000, maxPoints = 100)!!

        assertEquals(30.0, plan.latest.inside!!, 0.0)
    }

    // Mřížka

    @Test
    fun gridIsOneDegreeUpToSixDegreesOfSpan() {
        assertEquals(listOf(21L, 22L, 23L, 24L, 25L), gridLines(20.5..25.5))
        assertEquals((20L..26L).toList(), gridLines(20.0..26.0))
    }

    @Test
    fun gridIsTwoDegreesAboveSixDegreesOfSpan() {
        assertEquals(listOf(20L, 22L, 24L, 26L), gridLines(20.0..26.5))
        assertEquals(listOf(12L, 14L, 16L, 18L, 20L, 22L, 24L), gridLines(11.5..25.5))
        assertEquals(listOf(-4L, -2L, 0L, 2L, 4L, 6L, 8L), gridLines(-4.5..8.5))
    }

    // Čidlo, které hlásí nesmysl, nesmí z grafu udělat změť popisků ani smyčku bez konce.
    @Test
    fun gridStaysBoundedForVeryWideRanges() {
        for (range in listOf(-30.0..60.0, 0.0..1e6, -1e300..1e300)) {
            val lines = gridLines(range)
            assertTrue("$range: ${lines.size} čar", lines.size in 1..9)
        }
        assertEquals(listOf(-16L, 0L, 16L, 32L, 48L), gridLines(-30.0..60.0))
    }

    @Test
    fun planCarriesTheGridOfItsRange() {
        val plan = planChart(listOf(s(0, inside = 21.0, outside = 25.0, target = 22.0)), ChartWindow.ALL, 0, 100)!!

        assertEquals(20.5..25.5, plan.range)
        assertEquals(listOf(21L, 22L, 23L, 24L, 25L), plan.grid)
    }
}
