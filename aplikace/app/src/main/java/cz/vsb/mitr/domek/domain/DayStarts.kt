package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MINUTES_PER_DAY = 24 * 60

// Jen minuty: začátky úseků jsou celé minuty, takže vteřiny času nic nemění.
private val LocalTime.minuteOfDay: Int get() = hour * 60 + minute

data class DayStarts(val day: LocalTime, val evening: LocalTime, val night: LocalTime) {
    fun startOf(part: DayPart): LocalTime = when (part) {
        DayPart.DAY -> day
        DayPart.EVENING -> evening
        DayPart.NIGHT -> night
    }

    fun with(part: DayPart, time: LocalTime): DayStarts = when (part) {
        DayPart.DAY -> copy(day = time)
        DayPart.EVENING -> copy(evening = time)
        DayPart.NIGHT -> copy(night = time)
    }
}

fun dayStartsOf(settings: HouseSettings): DayStarts? {
    val day = parseHhMm(settings.dayStart) ?: return null
    val evening = parseHhMm(settings.eveningStart) ?: return null
    val night = parseHhMm(settings.nightStart) ?: return null
    return DayStarts(day, evening, night)
}

private val hhMmPattern = Regex("""([01]\d|2[0-3]):([0-5]\d)""")

fun parseHhMm(text: String): LocalTime? {
    val (hour, minute) = hhMmPattern.matchEntire(text)?.destructured ?: return null
    return LocalTime.of(hour.toInt(), minute.toInt())
}

private val hhMmFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

fun formatHhMm(time: LocalTime): String = hhMmFormat.format(time)

fun dayPartAt(time: LocalTime, starts: DayStarts): DayPart = dayPartAtMinute(time.minuteOfDay, starts)

// Platí úsek, který začal naposledy, počítáno cyklicky přes půlnoc, tedy ten s nejméně minutami od svého začátku.
// Při shodě začátků (Raspberry ji nepřipustí) vyhraje první v pořadí den, večer, noc.
private fun dayPartAtMinute(minute: Int, starts: DayStarts): DayPart =
    DayPart.entries.minBy { (minute - starts.startOf(it).minuteOfDay).mod(MINUTES_PER_DAY) }

fun nextStart(time: LocalTime, starts: DayStarts): Pair<DayPart, LocalTime> {
    val minute = time.minuteOfDay
    val part = DayPart.entries.minBy { minutesUntilStart(minute, starts.startOf(it)) }
    return part to starts.startOf(part)
}

// Začátek přesně v tuto minutu už nastal a další je až zítra, proto výsledek 1..1440, ne 0..1439.
private fun minutesUntilStart(minute: Int, start: LocalTime): Int =
    (start.minuteOfDay - minute).mod(MINUTES_PER_DAY).let { if (it == 0) MINUTES_PER_DAY else it }

data class TimelineSegment(val fromMinute: Int, val toMinute: Int, val part: DayPart)

fun timeline(starts: DayStarts): List<TimelineSegment> {
    // Den se rozřízne v půlnoci a u každého začátku; kus patří úseku, který platí v jeho první minutě.
    val cuts = (listOf(0, MINUTES_PER_DAY) + DayPart.entries.map { starts.startOf(it).minuteOfDay }).distinct().sorted()
    return cuts.zipWithNext { from, to -> TimelineSegment(from, to, dayPartAtMinute(from, starts)) }
}

val DayPart.title: String
    get() = when (this) {
        DayPart.DAY -> "Den"
        DayPart.EVENING -> "Večer"
        DayPart.NIGHT -> "Noc"
    }

// Název úseku ze Stavu: známý úsek česky, nový tak, jak ho Raspberry poslalo, chybějící null.
val HouseStatus.dayPartTitle: String?
    get() = dayPart?.title ?: dayPartText
