package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.LIGHT_BRIGHTNESS_KEY
import cz.vsb.mitr.domek.data.model.LIGHT_COLOR_KEY
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.SettingKind
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.parsePiTime
import cz.vsb.mitr.domek.domain.dayStartsOf
import cz.vsb.mitr.domek.domain.parseHhMm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

// Pravidla, podle kterých Raspberry odmítá zápisy a příkazy (docs/ble-rozhrani.md, Nastavení → Pravidla zápisu
// a Příkaz). Simulace je používá, aby odmítla přesně to, co by odmítl skutečný domek.

private val settingKeys: Map<String, SettingKey> = SettingKey.entries.associateBy { it.json }

// Barva a jas světla nemají SettingKey (nejsou to čísla s rozsahem), proto jsou jejich pravidla jen tady.
private val CHANNEL_RANGE = 0..255
private val BRIGHTNESS_RANGE = 0..100

// null = zápis je neplatný a nic se nemění; platí celý zápis, nebo žádná jeho část.
fun applySettingsPatch(current: HouseSettings, patch: JsonObject): HouseSettings? {
    var merged = current
    for ((name, element) in patch) {
        merged = when (name) {
            // Barva a jas mají stejná pravidla jako v příkazu svetlo.
            LIGHT_COLOR_KEY -> {
                val color = parseColor(element) ?: return null
                merged.copy(lightColor = listOf(color.r, color.g, color.b))
            }
            LIGHT_BRIGHTNESS_KEY -> {
                val brightness = element.asWholeNumberIn(BRIGHTNESS_RANGE) ?: return null
                merged.copy(lightBrightness = brightness)
            }
            else -> {
                val key = settingKeys[name] ?: return null
                if (!key.accepts(element)) return null
                merged.with(key, element.jsonPrimitive)
            }
        }
    }
    // Shoda začátků se posuzuje až po sloučení, jinak by jedním zápisem nešly prohodit dva začátky.
    val starts = dayStartsOf(merged) ?: return null
    return merged.takeIf { setOf(starts.day, starts.evening, starts.night).size == 3 }
}

// Druh a rozsah se čtou z SettingKey, aby se pravidla nerozešla s tím, co Nastavení v aplikaci nabízí.
private fun SettingKey.accepts(element: JsonElement): Boolean = when (kind) {
    SettingKind.DECIMAL -> element.asNumber()?.let { it in min..max } == true
    SettingKind.WHOLE -> element.asWholeNumber()?.let { it.toDouble() in min..max } == true
    SettingKind.TIME -> element.asText()?.let { parseHhMm(it) } != null
    SettingKind.MODE -> element.asText()?.let { text -> ThermostatMode.entries.any { it.json == text } } == true
    SettingKind.FLAG -> element.asFlag() != null
}

// Hodnota už prošla accepts(), takže ji jde z JsonPrimitive číst bez obav z chyby.
private fun HouseSettings.with(key: SettingKey, value: JsonPrimitive): HouseSettings = when (key) {
    SettingKey.MODE -> copy(mode = ThermostatMode.entries.first { it.json == value.content })
    SettingKey.TARGET_DAY -> copy(targetDay = value.double)
    SettingKey.TARGET_NIGHT -> copy(targetNight = value.double)
    SettingKey.TARGET_MANUAL -> copy(targetManual = value.double)
    SettingKey.DAY_START -> copy(dayStart = value.content)
    SettingKey.EVENING_START -> copy(eveningStart = value.content)
    SettingKey.NIGHT_START -> copy(nightStart = value.content)
    SettingKey.LUX_THRESHOLD -> copy(luxThreshold = value.double)
    SettingKey.NIGHT_LIGHT_SECONDS -> copy(nightLightSeconds = value.int)
    SettingKey.NOBODY_HOME_AFTER -> copy(nobodyHomeAfterMin = value.int)
    SettingKey.NOBODY_HOME_SETBACK -> copy(nobodyHomeSetback = value.double)
    SettingKey.LIGHT_ON_MOTION -> copy(lightOnMotion = value.boolean)
}

// null = příkaz je neplatný.
fun parseCommand(json: JsonObject): HouseCommand? {
    val (name, value) = json.entries.singleOrNull() ?: return null
    return when (name) {
        "svetlo" -> parseLight(value)
        "cas" -> value.asText()?.let { parsePiTime(it) }?.let { HouseCommand.SetClock(it) }
        else -> null
    }
}

private fun parseLight(value: JsonElement): HouseCommand? {
    if (value is JsonObject) return parseLightLook(value)
    val text = value.asText() ?: return null
    return LightAction.entries.firstOrNull { it.json == text }?.let { HouseCommand.Light(it) }
}

private fun parseLightLook(look: JsonObject): HouseCommand.LightLook? {
    if (look.isEmpty() || look.keys.any { it != "barva" && it != "jas" }) return null
    val color = look["barva"]?.let { parseColor(it) ?: return null }
    val brightness = look["jas"]?.let { it.asWholeNumberIn(BRIGHTNESS_RANGE) ?: return null }
    return HouseCommand.LightLook(color, brightness)
}

private fun parseColor(element: JsonElement): Rgb? {
    if (element !is JsonArray || element.size != 3) return null
    val (r, g, b) = element.map { it.asWholeNumberIn(CHANNEL_RANGE) ?: return null }
    return Rgb(r, g, b)
}

private val wholeNumber = Regex("""-?\d+""")

// Text je jen řetězec v uvozovkách; číslo ani true/false za text nepočítám.
private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// Číslo je holý literál: text se vylučuje přes isString, true a null je Double nepřečte.
private fun JsonElement.asNumber(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

// Celé číslo se pozná podle zápisu, takže 30.0 ani 3e1 Raspberry za celé číslo nebere.
private fun JsonElement.asWholeNumber(): Int? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && wholeNumber.matches(it.content) }?.content?.toIntOrNull()

private fun JsonElement.asWholeNumberIn(range: IntRange): Int? = asWholeNumber()?.takeIf { it in range }

// booleanOrNull čte i text "true", proto se řetězec vylučuje zvlášť.
private fun JsonElement.asFlag(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
