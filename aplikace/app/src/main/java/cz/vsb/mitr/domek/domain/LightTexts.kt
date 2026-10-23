package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightState
import java.time.LocalTime

data class LightTexts(val title: String, val detail: String, val tileValue: String, val tileDetail: String)

// Šero začne pod prahem a skončí až nad tímto násobkem prahu (docs/ble-rozhrani.md, Světlo v automatice).
const val DUSK_END_FACTOR = 1.3

// Noční světlo svítí uloženým jasem, ale nejvýš na 10 % (docs/ble-rozhrani.md, Světlo v automatice).
const val NIGHT_LIGHT_MAX_BRIGHTNESS = 10

// Texty podle spec 6.3. `manualUntil` je začátek dalšího úseku a týká se jen ruční volby. Bez známého úseku dne
// (chybí, nebo je nový) se automatika popíše bez denní doby.
fun lightTexts(light: LightState, dayPart: DayPart?, manualUntil: LocalTime?): LightTexts {
    val brightness = formatPercent(light.brightness.toDouble())
    val until = manualUntil?.let(::formatHhMm)
    return when {
        light.manual && light.on -> LightTexts(
            title = "Svítí · ručně",
            detail = if (until != null) "Jas $brightness · ruční volba platí do $until" else "Jas $brightness",
            tileValue = "Svítí",
            tileDetail = "ručně · $brightness",
        )
        light.manual -> LightTexts(
            title = "Zhasnuto · ručně",
            detail = if (until != null) "Ruční volba platí do $until" else "",
            tileValue = "Zhasnuto",
            tileDetail = "ručně",
        )
        light.on -> LightTexts(
            title = when (dayPart) {
                DayPart.DAY -> "Svítí · venku je šero"
                DayPart.EVENING -> "Svítí · večerní světlo"
                DayPart.NIGHT -> "Noční světlo · pohyb"
                null -> "Svítí"
            },
            detail = "Jas $brightness",
            tileValue = "Svítí",
            tileDetail = "auto · $brightness",
        )
        else -> LightTexts(
            title = when (dayPart) {
                DayPart.DAY -> "Zhasnuto · venku je dost světla"
                DayPart.EVENING -> "Zhasnuto"
                DayPart.NIGHT -> "Zhasnuto · noc"
                null -> "Zhasnuto"
            },
            detail = "",
            tileValue = "Zhasnuto",
            tileDetail = "auto",
        )
    }
}
