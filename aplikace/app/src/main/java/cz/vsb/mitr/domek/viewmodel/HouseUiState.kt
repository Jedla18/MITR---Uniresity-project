package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.parsePiTime
import cz.vsb.mitr.domek.data.model.rgbOf
import cz.vsb.mitr.domek.data.model.storesLightLook
import cz.vsb.mitr.domek.data.model.valueOf
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.DayStarts
import cz.vsb.mitr.domek.domain.NIGHT_LIGHT_MAX_BRIGHTNESS
import cz.vsb.mitr.domek.domain.parseHhMm
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// Data jsou stará, když stáří přesáhne 3 s (spec 5.5).
internal const val STALE_AFTER_MS = 3_000L

// Barva a jas, které uživatel právě nastavil a Stav je zatím nepotvrdil. setAtMs je monotónní čas poslední změny.
data class LightDraft(val color: Rgb? = null, val brightness: Int? = null, val setAtMs: Long)

// Jediný stav, ze kterého se kreslí všechny obrazovky. nowMs a phoneNow obnovuje tik ViewModelu, aby se stáří dat
// a časy typu „před 40 s“ měnily i bez nového Stavu.
data class HouseUiState(
    val sourceKind: SourceKind = SourceKind.BLUETOOTH,
    val connection: ConnectionState = ConnectionState.Idle,
    val received: ReceivedStatus? = null,
    val settings: HouseSettings? = null,
    val drafts: Map<SettingKey, JsonPrimitive> = emptyMap(),
    val pendingKeys: Set<SettingKey> = emptySet(),
    val lightDraft: LightDraft? = null,
    val chart: List<ChartSample> = emptyList(),
    val chartWindow: ChartWindow = ChartWindow.ALL,
    val lastMotionAt: LocalDateTime? = null,
    val savedDayStarts: DayStarts? = null,
    // Deník událostí, nejnovější nahoře (spec 2 v 2026-10-23-denik-a-vylepseni-design.md).
    val log: List<LogEntry> = emptyList(),
    val nowMs: Long = 0,
    val phoneNow: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0),
)

val HouseUiState.status: HouseStatus?
    get() = received?.status

// Stav přijatý mezi dvěma tiky je novější než nowMs, takže stáří se nikdy nepočítá pod nulu.
val HouseUiState.statusAgeMs: Long?
    get() = received?.let { (nowMs - it.receivedAtMs).coerceAtLeast(0) }

val HouseUiState.isStale: Boolean
    get() = (statusAgeMs ?: 0L) > STALE_AFTER_MS

val HouseUiState.controlsEnabled: Boolean
    get() = settings != null && (connection is ConnectionState.Connected || connection == ConnectionState.Simulated)

// Rozpracovaná hodnota má před hodnotou z Raspberry přednost, aby uživatel hned viděl, co nastavil.
fun HouseUiState.settingValue(key: SettingKey): JsonPrimitive? = drafts[key] ?: settings?.valueOf(key)

fun HouseUiState.number(key: SettingKey): Double? = settingValue(key)?.doubleOrNull

val HouseUiState.dayStarts: DayStarts?
    get() {
        val day = timeValue(SettingKey.DAY_START) ?: return null
        val evening = timeValue(SettingKey.EVENING_START) ?: return null
        val night = timeValue(SettingKey.NIGHT_START) ?: return null
        return DayStarts(day, evening, night)
    }

private fun HouseUiState.timeValue(key: SettingKey): LocalTime? = settingValue(key)?.contentOrNull?.let(::parseHhMm)

val HouseUiState.piTime: LocalDateTime?
    get() = status?.time?.let(::parsePiTime)

// Záporný rozdíl znamená, že hodiny Raspberry jdou pozadu za telefonem.
val HouseUiState.clockDiffSeconds: Long?
    get() {
        val piTime = piTime ?: return null
        val phoneTime = received?.receivedAtWall ?: return null
        return ChronoUnit.SECONDS.between(phoneTime, piTime)
    }

// Raspberry s uloženou barvou a jasem světla (spec 3 v 2026-10-23-denik-a-vylepseni-design.md) výběr barvy a jasu jen uloží.
// Bez nich (starší program) mění barvu a jas příkaz světla, který světlo zároveň ručně zapne.
val HouseUiState.storesLightLook: Boolean
    get() = settings?.storesLightLook == true

// Barva a jas, které uživatel pro světlo zvolil: karta Barva a jas je ukazuje i u zhasnutého světla a dialog vlastní barvy z nich
// začíná. Barva null znamená, že zvolená žádná není (zhasnuté světlo bez uložené barvy).
data class ChosenLook(val color: Rgb?, val brightness: Int)

// Rozpracovaná volba, jinak uložená v Nastavení, jinak ta, kterou světlo i s rozpracovanou volbou svítí. Uložená má přednost
// před svítící: světlo jí svítí vždy (noční světlo jen slabě), nejpozději po dalším kroku domku.
val HouseUiState.chosenLook: ChosenLook?
    get() {
        val light = shownLight ?: return null
        return ChosenLook(
            color = lightDraft?.color ?: settings?.lightColor?.let(::rgbOf) ?: light.rgb.takeIf { light.on },
            brightness = lightDraft?.brightness ?: settings?.lightBrightness ?: light.brightness,
        )
    }

// Zhasnuté světlo hlásí Raspberry s černou barvou a jasem 0 %. Když Nastavení uloženou barvu nebo jas nemá (starší program),
// doplní se bílá a plný jas, nikdy černá a nula.
private val WHITE_LIGHT = listOf(255, 255, 255)
private const val FULL_BRIGHTNESS = 100

// Světlo, jak ho Raspberry ukáže po výběru barvy nebo jasu, který zatím nepotvrdilo.
val HouseUiState.shownLight: LightState?
    get() {
        val light = status?.light ?: return null
        val draft = lightDraft ?: return light
        return if (storesLightLook) recoloredBy(light, draft) else switchedOnBy(light, draft)
    }

// Výběr se jen uloží: zhasnuté světlo zůstane zhasnuté a svítící se hned přebarví a zůstane v automatice, nebo v ruční volbě.
// Noční světlo (automatika v noci) svítí nejvýš na 10 % (docs/ble-rozhrani.md, Světlo v automatice).
internal fun HouseUiState.recoloredBy(light: LightState, draft: LightDraft): LightState {
    if (!light.on) return light
    val nightLight = !light.manual && status?.dayPart == DayPart.NIGHT
    val brightness = draft.brightness?.let { if (nightLight) minOf(it, NIGHT_LIGHT_MAX_BRIGHTNESS) else it }
    return light.copy(color = draft.color?.channels ?: light.color, brightness = brightness ?: light.brightness)
}

// Starší program: výběr barvy nebo jasu světlo rovnou ručně zapne (spec 6.3), proto draft světlo nejen přebarví, ale i zapne. Co
// draft nemění, ukáže tak, jak to Raspberry opravdu použije: uloženou barvu a jas z Nastavení (docs/ble-rozhrani.md, Příkaz),
// bez nich svítící světlo svou barvu a jas, zhasnuté bílou a plný jas.
private fun HouseUiState.switchedOnBy(light: LightState, draft: LightDraft): LightState = light.copy(
    on = true,
    color = draft.color?.channels ?: settings?.lightColor ?: if (light.on) light.color else WHITE_LIGHT,
    brightness = draft.brightness ?: settings?.lightBrightness ?: if (light.on) light.brightness else FULL_BRIGHTNESS,
    manual = true,
)

private val Rgb.channels: List<Int>
    get() = listOf(r, g, b)
