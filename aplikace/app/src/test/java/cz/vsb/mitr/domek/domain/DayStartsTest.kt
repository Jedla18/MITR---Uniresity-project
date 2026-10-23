package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.DayPart.DAY
import cz.vsb.mitr.domek.data.model.DayPart.EVENING
import cz.vsb.mitr.domek.data.model.DayPart.NIGHT
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayStartsTest {
    private val defaults = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0))

    // Noc začíná dopoledne, takže úseky nejdou po sobě jako den, večer, noc (spec 4.3).
    private val shifted = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(10, 5))

    // Začátky těsně kolem půlnoci, aby se cyklické pravidlo zkoušelo i tam.
    private val aroundMidnight = DayStarts(LocalTime.of(23, 30), LocalTime.of(0, 15), LocalTime.of(12, 0))

    private fun atMinute(minute: Int): LocalTime = LocalTime.of(minute / 60, minute % 60)

    private fun minuteOf(time: LocalTime): Int = time.hour * 60 + time.minute

    @Test
    fun partChangesExactlyAtItsStart() {
        assertEquals(NIGHT, dayPartAt(LocalTime.of(6, 59), defaults))
        assertEquals(DAY, dayPartAt(LocalTime.of(7, 0), defaults))
        assertEquals(EVENING, dayPartAt(LocalTime.of(21, 59), defaults))
        assertEquals(NIGHT, dayPartAt(LocalTime.of(22, 0), defaults))
    }

    @Test
    fun partIsCyclicWhenStartsAreOutOfOrder() {
        // Příklad ze spec 4.3.
        assertEquals(NIGHT, dayPartAt(LocalTime.of(10, 30), shifted))
        assertEquals(EVENING, dayPartAt(LocalTime.of(19, 30), shifted))
        assertEquals(EVENING, dayPartAt(LocalTime.of(3, 0), shifted))
        assertEquals(DAY, dayPartAt(LocalTime.of(8, 0), shifted))
    }

    @Test
    fun partIgnoresSecondsOfTheTime() {
        assertEquals(NIGHT, dayPartAt(LocalTime.of(6, 59, 59), defaults))
        assertEquals(DAY, dayPartAt(LocalTime.of(7, 0, 30), defaults))
    }

    @Test
    fun partMatchesWalkingBackToTheLatestStart() {
        // Nezávislá kontrola pravidla: od každé minuty jdeme zpátky, dokud nenarazíme na začátek úseku.
        listOf(defaults, shifted, aroundMidnight).forEach { starts ->
            for (minute in 0 until 1440) {
                var back = minute
                while (DayPart.entries.none { minuteOf(starts.startOf(it)) == back }) back = (back + 1439) % 1440
                val expected = DayPart.entries.single { minuteOf(starts.startOf(it)) == back }

                assertEquals("minuta $minute", expected, dayPartAt(atMinute(minute), starts))
            }
        }
    }

    @Test
    fun equalStartsStillGiveSomePart() {
        // Raspberry shodu nepřipustí, ale aplikace ji nekontroluje (spec 7), takže nesmí spadnout ani dát nejednoznačný výsledek.
        val same = DayStarts(LocalTime.of(7, 0), LocalTime.of(7, 0), LocalTime.of(7, 0))

        assertEquals(DAY, dayPartAt(LocalTime.of(3, 0), same))
        assertEquals(DAY, dayPartAt(LocalTime.of(7, 0), same))
        assertEquals(DAY, dayPartAt(LocalTime.of(12, 0), same))
    }

    @Test
    fun nextStartIsStrictlyAfterAndCyclic() {
        assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(10, 0), defaults))
        assertEquals(DAY to LocalTime.of(7, 0), nextStart(LocalTime.of(23, 0), defaults))
        assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(10, 5), shifted))
    }

    @Test
    fun nextStartAtExactlyAStartIsTheFollowingOne() {
        assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(7, 0), defaults))
        assertEquals(DAY to LocalTime.of(7, 0), nextStart(LocalTime.of(22, 0), defaults))
        assertEquals(NIGHT to LocalTime.of(10, 5), nextStart(LocalTime.of(7, 0), shifted))
        assertEquals(DAY to LocalTime.of(7, 0), nextStart(LocalTime.of(19, 0), shifted))
    }

    @Test
    fun nextStartIgnoresSecondsOfTheTime() {
        assertEquals(DAY to LocalTime.of(7, 0), nextStart(LocalTime.of(6, 59, 30), defaults))
        assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(7, 0, 30), defaults))
    }

    @Test
    fun nextStartMatchesWalkingForwardToTheFirstStart() {
        // Nezávislá kontrola: od minuty po `now` jdeme dopředu (přes půlnoc), dokud nenarazíme na začátek.
        listOf(defaults, shifted, aroundMidnight).forEach { starts ->
            for (minute in 0 until 1440) {
                var ahead = (minute + 1) % 1440
                while (DayPart.entries.none { minuteOf(starts.startOf(it)) == ahead }) ahead = (ahead + 1) % 1440
                val part = DayPart.entries.single { minuteOf(starts.startOf(it)) == ahead }

                assertEquals("po minutě $minute", part to starts.startOf(part), nextStart(atMinute(minute), starts))
            }
        }
    }

    @Test
    fun timelineSplitsTheDayIntoSegments() {
        assertEquals(
            listOf(
                TimelineSegment(0, 420, NIGHT),
                TimelineSegment(420, 1140, DAY),
                TimelineSegment(1140, 1320, EVENING),
                TimelineSegment(1320, 1440, NIGHT),
            ),
            timeline(defaults),
        )
    }

    @Test
    fun timelineFollowsTheCyclicRule() {
        assertEquals(
            listOf(
                TimelineSegment(0, 420, EVENING),
                TimelineSegment(420, 605, DAY),
                TimelineSegment(605, 1140, NIGHT),
                TimelineSegment(1140, 1440, EVENING),
            ),
            timeline(shifted),
        )
    }

    @Test
    fun timelineHasNoEmptySegmentWhenAPartStartsAtMidnight() {
        val atMidnight = DayStarts(LocalTime.MIDNIGHT, LocalTime.of(19, 0), LocalTime.of(22, 0))

        assertEquals(
            listOf(TimelineSegment(0, 1140, DAY), TimelineSegment(1140, 1320, EVENING), TimelineSegment(1320, 1440, NIGHT)),
            timeline(atMidnight),
        )
    }

    @Test
    fun timelineCoversTheWholeDayWithoutGapsForEveryOrder() {
        // Ve vzorku jsou i shodné začátky, kde se nesmí objevit díra ani prázdný úsek.
        val times = listOf(LocalTime.MIDNIGHT, LocalTime.of(0, 1), LocalTime.of(6, 30), LocalTime.of(12, 0), LocalTime.of(18, 45), LocalTime.of(23, 59))
        times.forEach { day ->
            times.forEach { evening ->
                times.forEach { night ->
                    val starts = DayStarts(day, evening, night)
                    val segments = timeline(starts)

                    assertEquals(starts.toString(), 0, segments.first().fromMinute)
                    assertEquals(starts.toString(), 1440, segments.last().toMinute)
                    segments.zipWithNext().forEach { (a, b) -> assertEquals(starts.toString(), a.toMinute, b.fromMinute) }
                    segments.forEach { segment ->
                        assertTrue("$starts $segment", segment.fromMinute < segment.toMinute)
                        assertEquals("$starts $segment", segment.part, dayPartAt(atMinute(segment.fromMinute), starts))
                        assertEquals("$starts $segment", segment.part, dayPartAt(atMinute(segment.toMinute - 1), starts))
                    }
                }
            }
        }
    }

    @Test
    fun parseHhMmAcceptsOnlyExactHhMm() {
        assertEquals(LocalTime.of(7, 5), parseHhMm("07:05"))
        assertEquals(LocalTime.MIDNIGHT, parseHhMm("00:00"))
        assertEquals(LocalTime.of(23, 59), parseHhMm("23:59"))
        assertNull(parseHhMm("7:5"))
        assertNull(parseHhMm("24:00"))
    }

    @Test
    fun parseHhMmRejectsEverythingElse() {
        listOf(
            "", "7:05", "07:5", "23:60", "25:00", " 07:05", "07:05 ", "07:05\n", "07.05", "0705", "07:05:00",
            "ab:cd", "-1:00", "+7:00", "\u0660\u0667:\u0660\u0665",
        ).forEach { assertNull(it, parseHhMm(it)) }
    }

    @Test
    fun formatHhMmPadsAndDropsSeconds() {
        assertEquals("07:05", formatHhMm(LocalTime.of(7, 5)))
        assertEquals("00:00", formatHhMm(LocalTime.MIDNIGHT))
        assertEquals("23:59", formatHhMm(LocalTime.of(23, 59, 59)))
    }

    @Test
    fun dayStartsOfReadsTheSettings() {
        assertEquals(defaults, dayStartsOf(DEFAULTS))
        assertEquals(shifted, dayStartsOf(DEFAULTS.copy(nightStart = "10:05")))
    }

    @Test
    fun dayStartsOfIsNullWhenAnyTimeCannotBeRead() {
        assertNull(dayStartsOf(DEFAULTS.copy(dayStart = "7:00")))
        assertNull(dayStartsOf(DEFAULTS.copy(eveningStart = "")))
        assertNull(dayStartsOf(DEFAULTS.copy(nightStart = "24:00")))
    }

    @Test
    fun startOfAndWithAddressTheRightPart() {
        assertEquals(LocalTime.of(7, 0), defaults.startOf(DAY))
        assertEquals(LocalTime.of(19, 0), defaults.startOf(EVENING))
        assertEquals(LocalTime.of(22, 0), defaults.startOf(NIGHT))

        assertEquals(DayStarts(LocalTime.of(6, 30), LocalTime.of(19, 0), LocalTime.of(22, 0)), defaults.with(DAY, LocalTime.of(6, 30)))
        assertEquals(DayStarts(LocalTime.of(7, 0), LocalTime.of(18, 15), LocalTime.of(22, 0)), defaults.with(EVENING, LocalTime.of(18, 15)))
        assertEquals(DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(10, 5)), defaults.with(NIGHT, LocalTime.of(10, 5)))
        assertEquals(DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0)), defaults)
    }

    @Test
    fun partTitlesAreCzech() {
        assertEquals("Den", DAY.title)
        assertEquals("Večer", EVENING.title)
        assertEquals("Noc", NIGHT.title)
    }
}
