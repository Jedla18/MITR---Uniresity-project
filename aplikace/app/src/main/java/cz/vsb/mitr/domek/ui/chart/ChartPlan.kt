package cz.vsb.mitr.domek.ui.chart

import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.viewmodel.Band
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import cz.vsb.mitr.domek.viewmodel.bands
import cz.vsb.mitr.domek.viewmodel.downsample
import cz.vsb.mitr.domek.viewmodel.inWindow
import cz.vsb.mitr.domek.viewmodel.segments
import cz.vsb.mitr.domek.viewmodel.temperatureRange
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor

// Pod dvěma body by downsample úseku zahodil začátek.
private const val MIN_SEGMENT_POINTS = 2

// Mřížka po 1 °C, od rozpětí nad 6 °C po 2 °C (spec 8). Při ještě větším rozpětí se zřídí, aby popisky nesplývaly.
private const val GRID_WIDE_SPAN = 6.0
private const val MAX_GRID_INTERVALS = 8

internal data class ChartPoint(val atMs: Long, val value: Double)

// Všechno, co Canvas potřebuje, spočítané bez Compose, aby se dalo ověřit v testech na JVM. Časy jsou monotónní (atMs),
// popisky osy X už jsou hotové texty.
internal class ChartPlan(
    val fromMs: Long,
    val toMs: Long,
    val range: ClosedFloatingPointRange<Double>,
    val grid: List<Long>,
    val timeLabels: List<String>,
    val bands: List<Band>,
    val inside: List<List<ChartPoint>>,
    val outside: List<List<ChartPoint>>,
    val target: List<List<ChartPoint>>,
    val latest: ChartSample,
)

// Pořadí je důležité: okno, pruhy a rozsah osy se berou z neředěných vzorků a úseky se dělí taky na neředěných. Až potom se
// každý úsek zředí. Zředěné body mají odstup přes 5 s, takže po dělení zředěných dat by se každá čára rozpadla na tečky.
// Bez vzorků s nějakou teplotou vrací null a karta ukáže text místo grafu.
internal fun planChart(samples: List<ChartSample>, window: ChartWindow, nowMs: Long, maxPoints: Int): ChartPlan? {
    val inWindow = samples.inWindow(nowMs, window)
    if (inWindow.none { it.inside != null || it.outside != null || it.target != null }) return null

    val newest = inWindow.last()
    val fromMs = window.durationMs?.let { nowMs - it } ?: inWindow.first().atMs
    // Stav přijatý mezi dvěma tiky UI je novější než nowMs, ale jeho bod nesmí vypadnout z osy.
    val toMs = maxOf(nowMs, newest.atMs)
    val windowSpanMs = (toMs - fromMs).coerceAtLeast(1)
    val range = temperatureRange(inWindow)
    return ChartPlan(
        fromMs = fromMs,
        toMs = toMs,
        range = range,
        grid = gridLines(range),
        timeLabels = timeAxisLabels(newest, fromMs, toMs),
        bands = bands(inWindow),
        inside = series(inWindow, windowSpanMs, maxPoints) { it.inside },
        outside = series(inWindow, windowSpanMs, maxPoints) { it.outside },
        target = series(inWindow, windowSpanMs, maxPoints) { it.target },
        latest = newest,
    )
}

// Úsek dostane tolik bodů, kolik připadá na jeho délku v okně, takže všechny úseky dohromady drží rozpočet maxPoints. Řídne se
// podle hodnot své čáry, aby venkovní a cílová čára neztrácely krátké výkyvy, které ta vnitřní nemá.
private fun series(
    window: List<ChartSample>,
    windowSpanMs: Long,
    maxPoints: Int,
    value: (ChartSample) -> Double?,
): List<List<ChartPoint>> = segments(window, value).map { segment ->
    val segmentSpanMs = segment.last().atMs - segment.first().atMs
    val budget = (maxPoints * segmentSpanMs / windowSpanMs).toInt().coerceAtLeast(MIN_SEGMENT_POINTS)
    downsample(segment, budget, value).mapNotNull { sample -> value(sample)?.let { ChartPoint(sample.atMs, it) } }
}

// Začátek, střed a konec okna jako čas telefonu. Vzorek má monotónní čas i čas telefonu, takže se z posledního vzorku
// odečte, o kolik je daný okamžik starší.
private fun timeAxisLabels(newest: ChartSample, fromMs: Long, toMs: Long): List<String> =
    listOf(fromMs, fromMs + (toMs - fromMs) / 2, toMs).map { atMs ->
        formatHhMm(newest.wallTime.minus(newest.atMs - atMs, ChronoUnit.MILLIS).toLocalTime())
    }

internal fun gridLines(range: ClosedFloatingPointRange<Double>): List<Long> {
    val span = range.endInclusive - range.start
    var step = if (span > GRID_WIDE_SPAN) 2.0 else 1.0
    while (span / step > MAX_GRID_INTERVALS) step *= 2
    val first = ceil(range.start / step).toLong()
    val last = floor(range.endInclusive / step).toLong()
    return (first..last).map { (it * step).toLong() }
}
