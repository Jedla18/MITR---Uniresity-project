package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.label
import cz.vsb.mitr.domek.data.model.rgbOf
import cz.vsb.mitr.domek.data.model.valueOf
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.NO_VALUE
import cz.vsb.mitr.domek.domain.activeTargetSlot
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.formatDuration
import cz.vsb.mitr.domek.domain.formatPercent
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.domain.piErrorDetail
import cz.vsb.mitr.domek.domain.piErrorText
import cz.vsb.mitr.domek.sim.COVERED_LUX_LIMIT
import cz.vsb.mitr.domek.sim.DATA_PAUSE_MS
import cz.vsb.mitr.domek.sim.HEAT_SENSOR_BOOST
import cz.vsb.mitr.domek.sim.SENSOR_EFFECT_SECONDS
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.doubleOrNull

// Události Deníku a pravidla, podle kterých vznikají ze Stavu, z Nastavení a z akcí aplikace (spec 2.3 v
// docs/navrh/2026-10-23-denik-a-vylepseni-design.md). Čas a slučování jim dává až HouseLog.

// Skupina, podle které Deník filtruje. OTHER vlastní filtr nemá, takže je vidět jen ve „Vše“.
enum class LogCategory { THERMOSTAT, LIGHT, MOTION, CONNECTION, OTHER }

// Podle druhu kreslí Deník ikonu a její barvu.
enum class LogKind {
    HEATING,
    COOLING,
    DAY,
    EVENING,
    NIGHT,
    OTHER_DAY_PART,
    LIGHT,
    MOTION,
    NOBODY_HOME,
    SOMEBODY_HOME,
    SETTING,
    CONNECTED,
    CONNECTION_LOST,
    DISCONNECTED,
    SIMULATION_STARTED,
    DATA_STALE,
    DATA_FRESH,
    FAULT,
    FAULT_ENDED,
    MESSAGE,
    SIMULATION,
    CLOCK,
}

// lightColors jsou tečky v barvě světla před podrobností: barva rozsvíceného světla, nebo stará a nová barva.
data class LogEvent(
    val category: LogCategory,
    val kind: LogKind,
    val title: String,
    val detail: String = "",
    val lightColors: List<Rgb> = emptyList(),
)

// Položka Nastavení, jejíž změny Deník zapisuje: klíč, který aplikace zapisuje, nebo barva či jas světla, které mění příkaz světla.
sealed interface SettingsField {
    data class Key(val key: SettingKey) : SettingsField

    data object LightColor : SettingsField

    data object LightBrightness : SettingsField
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

fun formatLogTime(time: LocalDateTime): String = timeFormat.format(time)

// ----- Stav -----

// Příčiny jdou před následky (úsek dne, pohyb a „nikdo doma“, světlo, nakonec topení a chlazení). Deník ukazuje nejnovější
// nahoře, takže následek pak stojí nad svou příčinou. Změnu cíle a režimu Stav nepíše: cíl vysvětlí jiná událost a režim
// zapíše Nastavení (spec 2.3, Pravidla). Poruchy z pole `chyby` řeší HouseLog, protože k nim patří i jejich délka.
fun statusEvents(previous: HouseStatus, current: HouseStatus, settings: HouseSettings?): List<LogEvent> = buildList {
    if (current.dayPartText != null && current.dayPartText != previous.dayPartText) add(dayPartEvent(current, settings))
    if (current.motion && !previous.motion) add(LogEvent(LogCategory.MOTION, LogKind.MOTION, "Pohyb"))
    if (current.nobodyHome != previous.nobodyHome) add(nobodyHomeEvent(current, settings))
    lightEvent(previous.light, current)?.let(::add)
    addAll(thermostatEvents(previous, current))
}

private fun dayPartEvent(status: HouseStatus, settings: HouseSettings?): LogEvent {
    val (kind, title) = when (status.dayPart) {
        DayPart.DAY -> LogKind.DAY to "Začal den"
        DayPart.EVENING -> LogKind.EVENING to "Začal večer"
        DayPart.NIGHT -> LogKind.NIGHT to "Začala noc"
        null -> LogKind.OTHER_DAY_PART to "Úsek dne: ${status.dayPartText}"
    }
    return LogEvent(LogCategory.THERMOSTAT, kind, title, appliedTarget(status, settings))
}

// Jen v Auto mění úsek dne cíl. Hodnota je z Nastavení, protože cíl ve Stavu může být snížený o „nikdo doma“.
private fun appliedTarget(status: HouseStatus, settings: HouseSettings?): String {
    if (status.mode != ThermostatMode.AUTO) return ""
    val slot = activeTargetSlot(status.mode, status.dayPart) ?: return ""
    val value = settings?.valueOf(slot.key)?.doubleOrNull ?: status.target
    return "platí ${slot.label.lowercase()} ${formatTemp(value)}"
}

// Snížení se bere z Nastavení a jen v Auto s platným cílem, stejně jako štítek „nikdo doma“ na Přehledu. V ručním režimu
// se cíl nesnižuje (docs/ble-rozhrani.md, Jak domek hodnoty určuje).
private fun nobodyHomeEvent(status: HouseStatus, settings: HouseSettings?): LogEvent {
    if (!status.nobodyHome) return LogEvent(LogCategory.MOTION, LogKind.SOMEBODY_HOME, "Někdo je zase doma")
    if (status.mode == ThermostatMode.MANUAL) {
        return LogEvent(LogCategory.MOTION, LogKind.NOBODY_HOME, "Nikdo doma", "v${NBSP}ručním režimu se cíl nesnižuje")
    }
    val setback = settings?.nobodyHomeSetback?.takeIf { status.target != null && status.mode == ThermostatMode.AUTO }
    return LogEvent(LogCategory.MOTION, LogKind.NOBODY_HOME, "Nikdo doma", setback?.let { "cíl snížen o$NBSP${formatTemp(it)}" } ?: "")
}

// Zapnutí rukou mění `zap` i `rucne` najednou a zapíše se jen jako rozsvícení. Tečka je jen u svítícího světla.
private fun lightEvent(previous: LightState, status: HouseStatus): LogEvent? {
    val light = status.light
    val shining = if (light.on) listOf(light.rgb) else emptyList()
    return when {
        light.on && !previous.on -> lightEvent("Světlo se rozsvítilo", switchedOnDetail(light, status.dayPart), shining)
        !light.on && previous.on -> lightEvent("Světlo zhaslo", if (light.manual) "ručně" else "auto")
        light.manual != previous.manual ->
            lightEvent(if (light.manual) "Ruční volba světla" else "Světlo zpět v${NBSP}automatice", "", shining)
        else -> null
    }
}

private fun lightEvent(title: String, detail: String, colors: List<Rgb> = emptyList()) =
    LogEvent(LogCategory.LIGHT, LogKind.LIGHT, title, detail, colors)

// Důvod automatiky podle úseku dne, stejně jako na obrazovce Světlo. Bez známého úseku se důvod neví.
private fun switchedOnDetail(light: LightState, dayPart: DayPart?): String {
    val brightness = formatPercent(light.brightness.toDouble())
    if (light.manual) return "ručně · $brightness"
    val reason = when (dayPart) {
        DayPart.DAY -> "venku je šero"
        DayPart.EVENING -> "večerní světlo"
        DayPart.NIGHT -> "noční světlo"
        null -> null
    }
    return listOfNotNull("auto", reason, brightness).joinToString(" · ")
}

// Konce jdou před začátky, aby nový začátek stál v Deníku nahoře.
private fun thermostatEvents(previous: HouseStatus, current: HouseStatus): List<LogEvent> {
    val detail = "Uvnitř ${formatTemp(current.insideTemp)}, cíl ${formatTemp(current.target)}"
    fun event(kind: LogKind, title: String) = LogEvent(LogCategory.THERMOSTAT, kind, title, detail)
    return buildList {
        if (previous.heating && !current.heating) add(event(LogKind.HEATING, "Přestalo topit"))
        if (previous.cooling && !current.cooling) add(event(LogKind.COOLING, "Přestalo chladit"))
        if (current.heating && !previous.heating) add(event(LogKind.HEATING, "Začalo topit"))
        if (current.cooling && !previous.cooling) add(event(LogKind.COOLING, "Začalo chladit"))
    }
}

// ----- Poruchy z pole `chyby` -----

// Texty z karty chyby na Přehledu (docs/ble-rozhrani.md, Hodnoty v poli chyby). Text karty pokračuje za dvojtečkou malým
// písmenem, jen vlastní jméno Raspberry zůstane velké. Neznámý kód karta vypisuje celou větou, Deník ho jen pojmenuje.
fun faultStarted(code: String): LogEvent {
    if (code !in faultEndTitles) return LogEvent(LogCategory.CONNECTION, LogKind.FAULT, "Neznámá porucha: $code")
    val text = piErrorText(code).let { if (it.startsWith("Raspberry")) it else it.replaceFirstChar(Char::lowercase) }
    return LogEvent(LogCategory.CONNECTION, LogKind.FAULT, "Porucha: $text", piErrorDetail(code))
}

// Známé kódy z pole `chyby` a co znovu funguje, když kód zmizí.
private val faultEndTitles = mapOf(
    METEO_FAULT to "Čidlo BME280 zase funguje",
    "osvetleni" to "Čidlo osvětlení zase funguje",
    "displej" to "Displej zase funguje",
    "svetlo" to "Světlo (RGB LED) zase funguje",
    "nastaveni" to "Ukládání nastavení zase funguje",
)

// lastedSeconds je null, když se začátek poruchy neví (Raspberry ji hlásilo už v prvním Stavu).
fun faultEnded(code: String, lastedSeconds: Long?): LogEvent = LogEvent(
    LogCategory.CONNECTION,
    LogKind.FAULT_ENDED,
    faultEndTitles[code] ?: "Chyba $code skončila",
    lastedSeconds?.let { "porucha trvala ${formatDuration(it)}" } ?: "",
)

// ----- Nastavení -----

// Klíče v pořadí SettingKey, pak barva a jas světla. Položku, kterou jedno z Nastavení nemá (starší program na Raspberry),
// není s čím srovnat.
fun changedFields(previous: HouseSettings, current: HouseSettings): List<SettingsField> = buildList {
    SettingKey.entries.forEach { key ->
        if (differs(previous.valueOf(key), current.valueOf(key))) add(SettingsField.Key(key))
    }
    if (differs(previous.lightColor, current.lightColor)) add(SettingsField.LightColor)
    if (differs(previous.lightBrightness, current.lightBrightness)) add(SettingsField.LightBrightness)
}

private fun <T : Any> differs(old: T?, new: T?): Boolean = old != null && new != null && old != new

// Nadpis je popisek s velkým písmenem a hodnoty ve tvaru hlášek (describeValue), jednotka jen jednou na konci:
// „Denní cíl: 26,0 → 23,0 °C“. Barva má místo hodnot tečky (spec 2.3, Pravidla).
fun settingsEvent(field: SettingsField, old: HouseSettings, new: HouseSettings, fromApp: Boolean): LogEvent {
    val detail = if (fromApp) "změna z${NBSP}aplikace" else "změna na Raspberry"
    return when (field) {
        is SettingsField.Key -> {
            val key = field.key
            val title = "${key.label.replaceFirstChar { it.uppercase() }}: ${fromTo(describe(old, key), describe(new, key), key.unit)}"
            LogEvent(key.logCategory, LogKind.SETTING, title, detail)
        }
        SettingsField.LightColor -> {
            val colors = listOfNotNull(old.lightColor?.let(::rgbOf), new.lightColor?.let(::rgbOf))
            LogEvent(LogCategory.LIGHT, LogKind.SETTING, "Barva světla změněna", detail, colors)
        }
        SettingsField.LightBrightness -> {
            val brightness = { settings: HouseSettings -> formatPercent(settings.lightBrightness?.toDouble()) }
            LogEvent(LogCategory.LIGHT, LogKind.SETTING, "Jas světla: ${fromTo(brightness(old), brightness(new), "%")}", detail)
        }
    }
}

private fun describe(settings: HouseSettings, key: SettingKey): String = settings.valueOf(key)?.let { describeValue(key, it) } ?: NO_VALUE

private fun fromTo(old: String, new: String, unit: String): String =
    "${if (unit.isEmpty()) old else old.removeSuffix("$NBSP$unit")} → $new"

private val SettingKey.logCategory: LogCategory
    get() = when (this) {
        SettingKey.MODE,
        SettingKey.TARGET_DAY,
        SettingKey.TARGET_NIGHT,
        SettingKey.TARGET_MANUAL,
        SettingKey.NOBODY_HOME_SETBACK,
        -> LogCategory.THERMOSTAT
        SettingKey.DAY_START, SettingKey.EVENING_START, SettingKey.NIGHT_START -> LogCategory.OTHER
        SettingKey.LUX_THRESHOLD, SettingKey.NIGHT_LIGHT_SECONDS, SettingKey.LIGHT_ON_MOTION -> LogCategory.LIGHT
        SettingKey.NOBODY_HOME_AFTER -> LogCategory.MOTION
    }

// ----- Akce aplikace -----

// Tlačítka karty „Ovládání simulace“ s čísly přímo ze simulace, aby řádek říkal, co tlačítko opravdu udělá. Porucha BME280 se
// přepíná: tlačítko ji zapne, když ji Stav zatím nehlásí, jinak ji vypne.
fun simulationEvent(action: SimulationAction, faultReported: Boolean): LogEvent {
    val effect = "${formatDecimal(SENSOR_EFFECT_SECONDS, 0)}${NBSP}s"
    val (what, detail) = when (action) {
        SimulationAction.HEAT_SENSOR ->
            "zahřáté čidlo" to "venku o$NBSP${formatDecimal(HEAT_SENSOR_BOOST, 0)}$NBSP°C víc na $effect"
        SimulationAction.COVER_LIGHT_SENSOR ->
            "zakryté čidlo světla" to "osvětlení ${formatDecimal(COVERED_LUX_LIMIT, 0)}${NBSP}lx na $effect"
        SimulationAction.MOTION -> "pohyb" to ""
        SimulationAction.TOGGLE_SENSOR_FAULT -> (if (faultReported) "porucha BME280 vypnutá" else "porucha BME280 zapnutá") to ""
        SimulationAction.PAUSE_DATA -> "výpadek dat" to "${DATA_PAUSE_MS / 1_000}${NBSP}s bez Stavu"
    }
    return LogEvent(LogCategory.OTHER, LogKind.SIMULATION, "Simulace: $what", detail)
}

// Akce, ne výsledek: zapíše se před odesláním, výsledek ukážou následné události a chybu její hláška.
fun clockSyncEvent(phoneTime: LocalDateTime): LogEvent =
    LogEvent(LogCategory.OTHER, LogKind.CLOCK, "Srovnání hodin", "podle telefonu ${formatLogTime(phoneTime)}")

// Hláška o zápisu nebo příkazu, který se nepovedl, tak, jak ji ukázal snackbar.
fun messageEvent(text: String): LogEvent = LogEvent(LogCategory.CONNECTION, LogKind.MESSAGE, text)
