package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartHistoryTest {
    private val tenAm = LocalDateTime.of(2026, 10, 20, 10, 0)

    private fun s(
        sec: Int,
        inside: Double? = 22.0,
        heating: Boolean = false,
        cooling: Boolean = false,
        outside: Double? = 21.0,
        target: Double? = 23.0,
    ) = ChartSample(sec * 1000L, tenAm.plusSeconds(sec.toLong()), inside, outside, target, heating, cooling)

    private fun List<ChartSample>.times() = map { it.atMs }

    // Většina testů ředění je o vnitřní teplotě.
    private fun thinInside(samples: List<ChartSample>, maxPoints: Int) = downsample(samples, maxPoints) { it.inside }

    // Historie

    @Test
    fun dropsSamplesOlderThanSixHours() {
        val history = ChartHistory()
        history.add(s(0))
        history.add(s(6 * 3600 + 1))
        assertEquals(listOf(21_601_000L), history.samples.times())
    }

    @Test
    fun keepsSampleExactlySixHoursOld() {
        val history = ChartHistory()
        history.add(s(0))
        history.add(s(6 * 3600))
        assertEquals(listOf(0L, 21_600_000L), history.samples.times())
    }

    @Test
    fun dropsOnlySamplesTooOldForTheNewOne() {
        val history = ChartHistory(maxAgeMs = 10_000)
        (0..5).forEach { history.add(s(it * 5)) }
        // Poslední vzorek je v 25 s, takže zůstane to, co není starší než 10 s.
        assertEquals(listOf(15_000L, 20_000L, 25_000L), history.samples.times())
    }

    @Test
    fun snapshotIsNotChangedByLaterAddOrClear() {
        val history = ChartHistory()
        history.add(s(0))
        val afterFirstAdd = history.samples
        history.add(s(1))
        val afterSecondAdd = history.samples
        history.clear()
        assertEquals(listOf(0L), afterFirstAdd.times())
        assertEquals(listOf(0L, 1000L), afterSecondAdd.times())
        assertTrue(history.samples.isEmpty())
    }

    @Test
    fun clearEmptiesHistoryAndItKeepsWorking() {
        val history = ChartHistory()
        history.add(s(0))
        history.clear()
        assertTrue(history.samples.isEmpty())
        history.add(s(1))
        assertEquals(listOf(1000L), history.samples.times())
    }

    // Převod Stavu na vzorek

    @Test
    fun receivedStatusBecomesChartSample() {
        val wall = tenAm.plusSeconds(42)
        val status = EXAMPLE.copy(insideTemp = 21.5, outsideTemp = 12.0, target = 24.0, heating = false, cooling = true)
        val sample = ReceivedStatus(status, receivedAtMs = 123_456, receivedAtWall = wall).toChartSample()
        assertEquals(ChartSample(123_456, wall, 21.5, 12.0, 24.0, heating = false, cooling = true), sample)
    }

    @Test
    fun missingTemperaturesStayNullInChartSample() {
        val status = EXAMPLE.copy(insideTemp = null, outsideTemp = null, target = null)
        val sample = ReceivedStatus(status, receivedAtMs = 1_000, receivedAtWall = tenAm).toChartSample()
        assertNull(sample.inside)
        assertNull(sample.outside)
        assertNull(sample.target)
    }

    // Okno

    @Test
    fun windowKeepsRecentSamples() {
        val samples = (0..600).map { s(it) }
        assertEquals(300_000L, samples.inWindow(600_000, ChartWindow.FIVE_MIN).first().atMs)
        assertEquals(samples, samples.inWindow(600_000, ChartWindow.ALL))
    }

    @Test
    fun windowStartsExactlyAtItsDuration() {
        val justTooOld = s(0).copy(atMs = 299_999)
        val atTheEdge = s(300)
        assertEquals(listOf(atTheEdge), listOf(justTooOld, atTheEdge).inWindow(600_000, ChartWindow.FIVE_MIN))
        val samples = (0..1000).map { s(it) }
        val fifteen = samples.inWindow(1_000_000, ChartWindow.FIFTEEN_MIN)
        assertEquals(100_000L, fifteen.first().atMs)
        assertEquals(901, fifteen.size)
    }

    @Test
    fun windowsKeepTheirLabelsAndDurations() {
        assertEquals(listOf("5\u00A0min", "15\u00A0min", "Vše"), ChartWindow.entries.map { it.label })
        assertEquals(listOf<Long?>(300_000, 900_000, null), ChartWindow.entries.map { it.durationMs })
    }

    // Ředění

    @Test
    fun downsampleKeepsBoundsAndExtremes() {
        val samples = (0 until 10_000).map { s(it, inside = if (it == 5000) 30.0 else 22.0) }
        val result = thinInside(samples, 400)
        assertTrue(result.size <= 400)
        assertEquals(samples.first(), result.first())
        assertEquals(samples.last(), result.last())
        assertTrue(result.any { it.inside == 30.0 })
    }

    @Test
    fun downsampleReturnsInputWhenItFits() {
        val samples = (0 until 400).map { s(it) }
        assertSame(samples, thinInside(samples, 400))
        assertSame(samples, thinInside(samples, 1000))
        assertEquals(emptyList<ChartSample>(), thinInside(emptyList(), 10))
    }

    // 100 vzorků po 1 s a 12 bodů dávají 5 košů po 20 vzorcích (0..19, 20..39, ..., 80..99).
    @Test
    fun downsampleKeepsLowestAndHighestInsideOfEachBucketInTimeOrder() {
        val special = mapOf(
            7 to 25.0, 13 to 19.0, // koš 0: maximum před minimem
            25 to 18.0, 34 to 26.0, // koš 1: minimum před maximem
            90 to 30.0, 95 to 10.0, // koš 4
        )
        val samples = (0 until 100).map { i ->
            s(i, inside = if (i in 60..79) null else special[i] ?: 22.0)
        }
        val result = thinInside(samples, 12)
        // Koš 2 je rovný, nechá jen první vzorek. Koš 3 nemá žádnou hodnotu, nechá taky první. K tomu první a poslední vzorek.
        assertEquals(listOf(0, 7, 13, 25, 34, 40, 60, 90, 95, 99).map { it * 1000L }, result.times())
    }

    // Stejné vzorky, jiná čára: ředí se podle hodnot té čáry, kterou volající řídne. Vnitřní teplota je rovná, venkovní má výkyvy
    // v koších jako v předchozím testu (100 vzorků po 1 s a 12 bodů dávají 5 košů po 20 vzorcích).
    @Test
    fun downsampleKeepsLowestAndHighestValueOfTheLineBeingThinned() {
        val special = mapOf(
            7 to 25.0, 13 to 19.0, // koš 0: maximum před minimem
            34 to 26.0, // koš 1: jen maximum, minimum je první vzorek
            90 to 30.0, 95 to 10.0, // koš 4
        )
        val samples = (0 until 100).map { i ->
            s(i, inside = 22.0, outside = if (i in 60..79) null else special[i] ?: 21.0)
        }

        // Rovná vnitřní čára nechá z každého koše první vzorek.
        assertEquals(listOf(0, 20, 40, 60, 80, 99).map { it * 1000L }, downsample(samples, 12) { it.inside }.times())
        // Venkovní čára nechá své výkyvy. Koš 2 je rovný, nechá první vzorek. Koš 3 nemá žádnou hodnotu, nechá taky první.
        assertEquals(listOf(0, 7, 13, 20, 34, 40, 60, 90, 95, 99).map { it * 1000L }, downsample(samples, 12) { it.outside }.times())
    }

    @Test
    fun downsampleKeepsStretchOfMissingValuesAsGapInTheLine() {
        val samples = (0 until 10_000).map { s(it, inside = if (it in 4_000..4_999) null else 22.0) }
        val result = thinInside(samples, 400)
        // Mezeru nesmí vyrobit čas (gapMs je nekonečné), jen vzorky s null; bez nich by čára vedla přes celý výpadek.
        assertEquals(2, segments(result, { it.inside }, gapMs = Long.MAX_VALUE).size)
    }

    @Test
    fun downsampleNeverExceedsMaxPointsAndKeepsOrderAndBounds() {
        val random = Random(2026)
        for (size in listOf(3, 10, 99, 1_000, 5_001)) {
            var atMs = 0L
            val samples = (0 until size).map {
                atMs += random.nextLong(100, 3_000)
                val inside = if (random.nextInt(8) == 0) null else random.nextDouble(15.0, 30.0)
                ChartSample(atMs, tenAm, inside, 21.0, 23.0, heating = false, cooling = false)
            }
            for (maxPoints in listOf(2, 3, 4, 5, 6, 7, 10, 50, 399, 400).filter { it < size }) {
                val label = "size=$size maxPoints=$maxPoints"
                val result = thinInside(samples, maxPoints)
                assertTrue(label, result.size <= maxPoints)
                assertEquals(label, samples.first(), result.first())
                assertEquals(label, samples.last(), result.last())
                assertTrue(label, result.times().zipWithNext().all { (before, after) -> before < after })
            }
        }
    }

    @Test
    fun downsampleToTwoOrThreePointsKeepsOnlyFirstAndLast() {
        val samples = (0 until 50).map { s(it, inside = it.toDouble()) }
        val firstAndLast = listOf(samples.first(), samples.last())
        assertEquals(firstAndLast, thinInside(samples, 2))
        assertEquals(firstAndLast, thinInside(samples, 3))
    }

    @Test
    fun downsampleBelowTwoPointsKeepsAtMostTheNewestSample() {
        val samples = (0 until 10).map { s(it) }
        assertEquals(listOf(samples.last()), thinInside(samples, 1))
        assertEquals(emptyList<ChartSample>(), thinInside(samples, 0))
        assertEquals(emptyList<ChartSample>(), thinInside(samples, -5))
    }

    @Test
    fun downsampleHandlesSamplesWithTheSameTimestamp() {
        val samples = (0 until 100).map { s(5, inside = it.toDouble()) }
        val result = thinInside(samples, 10)
        assertTrue(result.size <= 10)
        assertEquals(samples.first(), result.first())
        assertEquals(samples.last(), result.last())
    }

    // Mezery v čarách

    @Test
    fun segmentsSplitAtGapsAndNulls() {
        val samples = listOf(s(0), s(1), s(10), s(11, inside = null), s(12))
        val result = segments(samples, { it.inside })
        assertEquals(listOf(listOf(0L, 1000L), listOf(10_000L), listOf(12_000L)), result.map { it.times() })
    }

    @Test
    fun segmentGapMustBeLongerThanGapMs() {
        assertEquals(1, segments(listOf(s(0), s(5)), { it.inside }).size)
        assertEquals(2, segments(listOf(s(0), s(6)), { it.inside }).size)
        assertEquals(2, segments(listOf(s(0), s(3)), { it.inside }, gapMs = 2_000).size)
    }

    @Test
    fun segmentsFollowTheGivenValue() {
        val samples = listOf(s(0, outside = null), s(1), s(2))
        assertEquals(listOf(listOf(0L, 1000L, 2000L)), segments(samples, { it.inside }).map { it.times() })
        assertEquals(listOf(listOf(1000L, 2000L)), segments(samples, { it.outside }).map { it.times() })
    }

    @Test
    fun segmentsOfNoValuesAreEmpty() {
        assertEquals(emptyList<List<ChartSample>>(), segments(emptyList(), { it.inside }))
        assertEquals(emptyList<List<ChartSample>>(), segments(listOf(s(0, inside = null), s(1, inside = null)), { it.inside }))
    }

    // Pruhy topení a chlazení

    @Test
    fun bandsMergeConsecutiveSamples() {
        val samples = listOf(s(0, heating = true), s(1, heating = true), s(2), s(3, cooling = true), s(4, cooling = true))
        assertEquals(listOf(Band(0, 2000, true), Band(3000, 4000, false)), bands(samples))
    }

    @Test
    fun bandEndsAtItsLastSampleWhenGapFollows() {
        val samples = listOf(s(0, heating = true), s(1, heating = true), s(20, heating = true), s(21, heating = true))
        assertEquals(listOf(Band(0, 1000, true), Band(20_000, 21_000, true)), bands(samples))
        assertEquals(listOf(Band(0, 21_000, true)), bands(samples, gapMs = 30_000))
    }

    @Test
    fun bandBeforeGapEndsAtItsLastFlaggedSampleNotAtTheNextOne() {
        val samples = listOf(s(0, heating = true), s(1, heating = true), s(20))
        assertEquals(listOf(Band(0, 1000, true)), bands(samples))
    }

    @Test
    fun bandAtTheLastSampleEndsAtItsOwnTime() {
        assertEquals(listOf(Band(1000, 1000, true)), bands(listOf(s(0), s(1, heating = true))))
    }

    @Test
    fun heatingFollowedDirectlyByCoolingTouchesAtTheSwitch() {
        val samples = listOf(s(0, heating = true), s(1, heating = true), s(2, cooling = true), s(3, cooling = true), s(4))
        assertEquals(listOf(Band(0, 2000, true), Band(2000, 4000, false)), bands(samples))
    }

    @Test
    fun bandsWithoutFlagsAreEmpty() {
        assertEquals(emptyList<Band>(), bands(emptyList()))
        assertEquals(emptyList<Band>(), bands(listOf(s(0), s(1))))
    }

    // Rozsah osy Y

    @Test
    fun temperatureRangeHasMarginAndMinimumSpan() {
        val range = temperatureRange(listOf(s(0, inside = 22.0, outside = 22.2, target = 22.1)))
        assertEquals(21.1, range.start, 1e-9)
        assertEquals(23.1, range.endInclusive, 1e-9)
    }

    @Test
    fun temperatureRangeKeepsWideDataUnexpanded() {
        val samples = listOf(
            s(0, inside = 20.0, outside = 12.0, target = 25.0),
            s(1, inside = 24.0, outside = 12.0, target = 25.0),
        )
        assertEquals(11.5..25.5, temperatureRange(samples))
    }

    @Test
    fun temperatureRangeUsesAllThreeSeriesAndIgnoresNulls() {
        val samples = listOf(
            s(0, inside = null, outside = 5.0, target = null),
            s(1, inside = null, outside = null, target = 30.0),
        )
        assertEquals(4.5..30.5, temperatureRange(samples))
    }

    @Test
    fun temperatureRangeExpandsAroundTheCenter() {
        assertEquals(21.0..23.0, temperatureRange(listOf(s(0, inside = 22.0, outside = 22.0, target = 22.0))))
    }

    @Test
    fun temperatureRangeOfExactlyMinimumSpanIsNotExpanded() {
        assertEquals(20.5..22.5, temperatureRange(listOf(s(0, inside = 21.0, outside = 22.0, target = 21.5))))
    }

    @Test
    fun temperatureRangeWithoutData() {
        assertEquals(18.0..26.0, temperatureRange(emptyList()))
        val allNull = (0 until 5).map { s(it, inside = null, outside = null, target = null) }
        assertEquals(18.0..26.0, temperatureRange(allNull))
    }
}
