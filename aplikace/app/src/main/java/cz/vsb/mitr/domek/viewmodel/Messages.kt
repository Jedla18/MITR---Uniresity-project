package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.SettingKind
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.label
import cz.vsb.mitr.domek.data.model.valueOf
import cz.vsb.mitr.domek.data.source.WriteResult
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.formatPercent
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// Hlášky o zápisech a příkazech, které se nepovedly (spec 7). Raspberry důvod odmítnutí neříká, proto se odhaduje
// z toho, co aplikace poslala.

private const val NOT_CONNECTED = "Dům není připojený, změna se neodeslala."
private const val NO_ANSWER = "Raspberry neodpovědělo. Změna se možná neprovedla."

private val startKeys = SettingKey.entries.filter { it.kind == SettingKind.TIME }

fun describeValue(key: SettingKey, value: JsonPrimitive): String {
    val text = when (key.kind) {
        // Osvětlení je v celé aplikaci bez desetin (spec 6.0).
        SettingKind.DECIMAL -> value.doubleOrNull?.let {
            withUnit(formatDecimal(it, if (key == SettingKey.LUX_THRESHOLD) 0 else 1), key)
        }
        SettingKind.WHOLE -> value.doubleOrNull?.let { withUnit(it.roundToInt().toString(), key) }
        SettingKind.TIME -> value.contentOrNull
        SettingKind.MODE -> ThermostatMode.entries.firstOrNull { it.json == value.contentOrNull }?.label
        SettingKind.FLAG -> value.booleanOrNull?.let { if (it) "zapnuto" else "vypnuto" }
    }
    // Hodnota jiného tvaru, než klíč čeká, se ukáže tak, jak je.
    return text ?: value.content
}

fun settingsWriteMessage(changes: List<SettingChange>, current: HouseSettings?, result: WriteResult): String? = when (result) {
    WriteResult.Ok -> null
    WriteResult.NotConnected -> NOT_CONNECTED
    WriteResult.Timeout -> NO_ANSWER
    is WriteResult.Rejected -> {
        val reason = sameStartsReason(changes, current)
            ?: outOfRangeReason(changes)
            ?: lightOnMotionReason(changes)
            ?: "${changes.joinToString(", ") { "${it.key.label} ${describeValue(it.key, it.value)}" }} (kód ${result.gattStatus})"
        rejectedChange(reason)
    }
}

// Odmítnutý zápis Nastavení nic nezmění, ani částečně (docs/ble-rozhrani.md, Když zápis selže).
private fun rejectedChange(reason: String): String = "Raspberry změnu odmítlo: $reason. Nic se nezměnilo."

// Zápis uložené barvy a jasu světla do Nastavení. Hodnoty aplikace do rozsahu omezuje sama, takže důvod odmítnutí neodhadne:
// hláška jmenuje, co se zapisovalo, a připojí kód. Složky barvy by člověku nic neřekly, proto jen „barva světla“.
fun storedLookMessage(color: Rgb?, brightness: Int?, result: WriteResult): String? = when (result) {
    WriteResult.Ok -> null
    WriteResult.NotConnected -> NOT_CONNECTED
    WriteResult.Timeout -> NO_ANSWER
    is WriteResult.Rejected -> {
        val parts = listOfNotNull(
            color?.let { "barva světla" },
            brightness?.let { "jas světla ${formatPercent(it.toDouble())}" },
        )
        rejectedChange("${parts.joinToString(", ")} (kód ${result.gattStatus})")
    }
}

fun commandMessage(command: HouseCommand, result: WriteResult): String? = when (result) {
    WriteResult.Ok -> null
    WriteResult.NotConnected -> NOT_CONNECTED
    WriteResult.Timeout -> NO_ANSWER
    is WriteResult.Rejected -> "Raspberry odmítlo příkaz ${command.label()} (kód ${result.gattStatus})."
}

private fun withUnit(number: String, key: SettingKey): String = "$number$NBSP${key.unit}"

// Raspberry začátky posuzuje až po sloučení zápisu s dosavadním Nastavením (spec 4.1), takže stejně se hledá i shoda.
private fun sameStartsReason(changes: List<SettingChange>, current: HouseSettings?): String? {
    val starts = startKeys.associateWith { current?.valueOf(it)?.contentOrNull }.toMutableMap()
    val changedStarts = changes.filter { it.key in starts }
    changedStarts.forEach { starts[it.key] = it.value.contentOrNull }
    for (change in changedStarts) {
        val time = starts.getValue(change.key) ?: continue
        val other = startKeys.firstOrNull { it != change.key && starts[it] == time } ?: continue
        return "${change.key.label} $time je stejný jako ${other.label}"
    }
    return null
}

// Aplikace čísla do rozsahu omezuje sama (spec 7). Tohle je pojistka pro hodnotu, která by mimo rozsah přesto odešla.
private fun outOfRangeReason(changes: List<SettingChange>): String? {
    val change = changes.firstOrNull { change ->
        val key = change.key
        val number = change.value.doubleOrNull
        (key.kind == SettingKind.DECIMAL || key.kind == SettingKind.WHOLE) && number != null && number !in key.min..key.max
    } ?: return null
    val key = change.key
    val range = "${formatBound(key.min)}–${withUnit(formatBound(key.max), key)}"
    return "${key.label} ${describeValue(key, change.value)} je mimo rozsah $range"
}

// nocni_svetlo_pri_pohybu je novější klíč rozhraní: Raspberry se starším programem ho nezná a zápis s ním odmítne.
private fun lightOnMotionReason(changes: List<SettingChange>): String? =
    if (changes.any { it.key == SettingKey.LIGHT_ON_MOTION }) {
        "volbu ${SettingKey.LIGHT_ON_MOTION.label} nezná, nejspíš má starší program"
    } else {
        null
    }

// Celé meze se píšou bez desetin („5–35 °C“).
private fun formatBound(bound: Double): String = formatDecimal(bound, if (bound % 1.0 == 0.0) 0 else 1)
