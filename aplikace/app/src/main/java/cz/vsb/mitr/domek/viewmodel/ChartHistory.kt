package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.domain.NBSP
import java.time.LocalDateTime

private const val SIX_HOURS_MS = 21_600_000L

// Spec 8: čára se přeruší, kde mezi vzorky uplynulo víc než 5 s.
private const val GAP_MS = 5_000L

private const val RANGE_MARGIN = 0.5
private const val RANGE_MIN_SPAN = 2.0
private val RANGE_WITHOUT_DATA = 18.0..26.0

// atMs je monotónní čas příjmu (řadí vzorky a měří mezery), wallTime čas telefonu pro popisky osy X.
data class ChartSample(
    val atMs: Long,
    val wallTime: LocalDateTime,
    val inside: Double?,
    val outside: Double?,
    val target: Double?,
    val heating: Boolean,
    val cooling: Boolean,
)

enum class ChartWindow(val label: String, val durationMs: Long?) {
    FIVE_MIN("5${NBSP}min", 300_000),
    FIFTEEN_MIN("15${NBSP}min", 900_000),
    ALL("Vše", null),
}

class ChartHistory(private val maxAgeMs: Long = SIX_HOURS_MS) {
    // Při každé změně vznikne nový seznam: ViewModel ho publikuje přes StateFlow, takže ho pozdější add() nesmí měnit.
    var samples: List<ChartSample> = emptyList()
        private set

    fun add(sample: ChartSample) {
        val oldestKeptMs = sample.atMs - maxAgeMs
        samples = samples.filter { it.atMs >= oldestKeptMs } + sample
    }

    fun clear() {
        samples = emptyList()
    }
}

fun ReceivedStatus.toChartSample(): ChartSample = ChartSample(
    atMs = receivedAtMs,
    wallTime = receivedAtWall,
    inside = status.insideTemp,
    outside = status.outsideTemp,
    target = status.target,
    heating = status.heating,
    cooling = status.cooling,
)

fun List<ChartSample>.inWindow(nowMs: Long, window: ChartWindow): List<ChartSample> {
    val durationMs = window.durationMs ?: return this
    val fromMs = nowMs - durationMs
    return filter { it.atMs >= fromMs }
}

// Vzorky jsou seřazené podle atMs. Z každého časového koše zůstane vzorek s nejnižší a nejvyšší hodnotou čáry, kterou řídne
// `value`; každá čára má své výkyvy, takže se řídne zvlášť. Z koše bez hodnot zůstane první vzorek, aby se v čáře zachovala
// mezera. První a poslední vzorek zůstanou vždy, takže košů je (maxPoints - 2) / 2. Rozestupy po ředění mohou přesáhnout
// gapMs, proto segments() a bands() patří na neředěná data.
fun downsample(samples: List<ChartSample>, maxPoints: Int, value: (ChartSample) -> Double?): List<ChartSample> {
    if (samples.size <= maxPoints) return samples
    // Pod dvěma body se první a poslední vzorek nevejdou oba a limit má přednost.
    if (maxPoints < 2) return samples.takeLast(maxPoints.coerceAtLeast(0))

    val bucketCount = (maxPoints - 2) / 2
    if (bucketCount == 0) return listOf(samples.first(), samples.last())

    val startMs = samples.first().atMs
    val spanMs = samples.last().atMs - startMs
    fun bucketOf(index: Int): Int =
        if (spanMs <= 0) 0 else ((samples[index].atMs - startMs) * bucketCount / spanMs).toInt().coerceAtMost(bucketCount - 1)

    val keep = BooleanArray(samples.size)
    keep[0] = true
    keep[samples.lastIndex] = true
    var from = 0
    while (from < samples.size) {
        val bucket = bucketOf(from)
        var to = from + 1
        while (to < samples.size && bucketOf(to) == bucket) to++
        markBucket(samples, from, to, keep, value)
        from = to
    }
    return samples.filterIndexed { index, _ -> keep[index] }
}

// Při shodě hodnot zůstane dřívější vzorek.
private fun markBucket(samples: List<ChartSample>, from: Int, to: Int, keep: BooleanArray, value: (ChartSample) -> Double?) {
    var lowest = -1
    var lowestValue = 0.0
    var highest = -1
    var highestValue = 0.0
    for (index in from until to) {
        val sampleValue = value(samples[index]) ?: continue
        if (lowest < 0 || sampleValue < lowestValue) {
            lowest = index
            lowestValue = sampleValue
        }
        if (highest < 0 || sampleValue > highestValue) {
            highest = index
            highestValue = sampleValue
        }
    }
    if (lowest < 0) {
        keep[from] = true
    } else {
        keep[lowest] = true
        keep[highest] = true
    }
}

// Vzorek bez hodnoty nepatří do žádného úseku a úsek přeruší, takže po něm začíná nový i bez časové mezery.
fun segments(samples: List<ChartSample>, value: (ChartSample) -> Double?, gapMs: Long = GAP_MS): List<List<ChartSample>> {
    val result = mutableListOf<MutableList<ChartSample>>()
    var previous: ChartSample? = null
    for (sample in samples) {
        if (value(sample) == null) {
            previous = null
            continue
        }
        if (previous == null || sample.atMs - previous.atMs > gapMs) result.add(mutableListOf())
        result.last().add(sample)
        previous = sample
    }
    return result
}

// heating = false je pruh chlazení.
data class Band(val fromMs: Long, val toMs: Long, val heating: Boolean)

fun bands(samples: List<ChartSample>, gapMs: Long = GAP_MS): List<Band> =
    (flagBands(samples, gapMs, heating = true) { it.heating } + flagBands(samples, gapMs, heating = false) { it.cooling })
        .sortedBy { it.fromMs }

// Pruh tvoří souvislé vzorky s příznakem a končí časem dalšího vzorku. Když další vzorek chybí nebo je za mezerou,
// končí časem svého posledního vzorku, protože o době mezi nimi nic nevíme.
private fun flagBands(samples: List<ChartSample>, gapMs: Long, heating: Boolean, flag: (ChartSample) -> Boolean): List<Band> {
    val result = mutableListOf<Band>()
    var start = 0
    while (start < samples.size) {
        if (!flag(samples[start])) {
            start++
            continue
        }
        var end = start
        while (end + 1 < samples.size && flag(samples[end + 1]) && samples[end + 1].atMs - samples[end].atMs <= gapMs) end++
        val next = samples.getOrNull(end + 1)
        val toMs = if (next != null && next.atMs - samples[end].atMs <= gapMs) next.atMs else samples[end].atMs
        result.add(Band(samples[start].atMs, toMs, heating))
        start = end + 1
    }
    return result
}

fun temperatureRange(samples: List<ChartSample>): ClosedFloatingPointRange<Double> {
    var lowest = Double.POSITIVE_INFINITY
    var highest = Double.NEGATIVE_INFINITY
    fun include(value: Double?) {
        if (value == null) return
        if (value < lowest) lowest = value
        if (value > highest) highest = value
    }
    for (sample in samples) {
        include(sample.inside)
        include(sample.outside)
        include(sample.target)
    }
    if (lowest > highest) return RANGE_WITHOUT_DATA

    val from = lowest - RANGE_MARGIN
    val to = highest + RANGE_MARGIN
    if (to - from >= RANGE_MIN_SPAN) return from..to
    // Úzká data se roztáhnou na obě strany stejně, aby zůstala uprostřed osy.
    val center = (from + to) / 2
    return (center - RANGE_MIN_SPAN / 2)..(center + RANGE_MIN_SPAN / 2)
}
