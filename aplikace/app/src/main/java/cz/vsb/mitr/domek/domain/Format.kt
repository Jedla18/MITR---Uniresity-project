package cz.vsb.mitr.domek.domain

import java.util.Locale
import kotlin.math.abs

const val NBSP = '\u00A0'

// Hodnota, která chybí (null ze Stavu, nebo Nastavení, které ještě nepřišlo).
const val NO_VALUE = "—"
private const val SECONDS_PER_DAY = 86_400L

// Locale.ROOT, aby výstup nezávisel na jazyku telefonu (jinde by vyšly třeba arabské číslice); desetinnou čárku doplníme ručně.
// Záporné číslo zaokrouhlené na nulu si znaménko nenechá: „-0,0“ by vypadalo jako chyba.
fun formatDecimal(value: Double, decimals: Int = 1): String {
    val text = String.format(Locale.ROOT, "%.${decimals}f", value).replace('.', ',')
    return if (text.startsWith('-') && text.drop(1).all { it == '0' || it == ',' }) text.drop(1) else text
}

fun formatTemp(value: Double?): String = formatWithUnit(value, 1, "°C")

fun formatPercent(value: Double?): String = formatWithUnit(value, 0, "%")

fun formatPressure(value: Double?): String = formatWithUnit(value, 0, "hPa")

fun formatLux(value: Double?): String = formatWithUnit(value, 0, "lx")

fun formatMinutes(value: Double?): String = formatWithUnit(value, 0, "min")

private fun formatWithUnit(value: Double?, decimals: Int, unit: String): String =
    if (value == null) NO_VALUE else "${formatDecimal(value, decimals)}$NBSP$unit"

fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    if (total >= SECONDS_PER_DAY) return "více než den"
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val rest = total % 60
    return when {
        hours > 0 -> twoUnits(hours, "h", minutes, "min")
        minutes > 0 -> twoUnits(minutes, "min", rest, "s")
        else -> "$rest${NBSP}s"
    }
}

// Menší jednotka se vynechá, když je nulová („2 min“, ne „2 min 0 s“); zbytek pod ní se zahodí.
private fun twoUnits(large: Long, largeUnit: String, small: Long, smallUnit: String): String =
    if (small == 0L) "$large$NBSP$largeUnit" else "$large$NBSP$largeUnit $small$NBSP$smallUnit"

fun formatAgo(ms: Long): String {
    val seconds = ms / 1000
    if (seconds >= SECONDS_PER_DAY) return "před více než dnem"
    // Od minuty výš se vteřiny neukazují, aby text při každém tiku UI neblikal.
    val shown = if (seconds >= 60) seconds / 60 * 60 else seconds
    return "před ${formatDuration(shown)}"
}

// Rozdíl je Raspberry − telefon. Text se ukazuje jen při rozdílu nad prahem, takže nulu zvlášť neřešíme. Za jednopísmennou
// předložkou je nezlomitelná mezera, aby „o“ nezůstalo na konci řádku.
fun clockDiffText(diffSeconds: Long): String {
    val direction = if (diffSeconds < 0) "pozadu" else "napřed"
    return "Hodiny Raspberry jdou o$NBSP${formatDuration(abs(diffSeconds))} $direction."
}
