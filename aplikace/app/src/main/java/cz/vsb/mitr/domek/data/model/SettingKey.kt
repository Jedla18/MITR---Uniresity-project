package cz.vsb.mitr.domek.data.model

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SettingKind { DECIMAL, WHOLE, TIME, MODE, FLAG }

enum class SettingKey(
    val json: String,
    val kind: SettingKind,
    val label: String,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val unit: String = "",
) {
    MODE("rezim_termostatu", SettingKind.MODE, "režim termostatu"),
    TARGET_DAY("cil_den", SettingKind.DECIMAL, "denní cíl", 5.0, 35.0, "°C"),
    TARGET_NIGHT("cil_noc", SettingKind.DECIMAL, "noční cíl", 5.0, 35.0, "°C"),
    TARGET_MANUAL("cil_rucni", SettingKind.DECIMAL, "ruční cíl", 5.0, 35.0, "°C"),
    DAY_START("zacatek_den", SettingKind.TIME, "začátek dne"),
    EVENING_START("zacatek_vecer", SettingKind.TIME, "začátek večera"),
    NIGHT_START("zacatek_noc", SettingKind.TIME, "začátek noci"),
    LUX_THRESHOLD("prah_lux", SettingKind.DECIMAL, "práh šera", 1.0, 10000.0, "lx"),
    NIGHT_LIGHT_SECONDS("nocni_svetlo_s", SettingKind.WHOLE, "délka nočního světla", 5.0, 600.0, "s"),
    NOBODY_HOME_AFTER("nikdo_doma_po_min", SettingKind.WHOLE, "snížení cíle po", 1.0, 240.0, "min"),
    NOBODY_HOME_SETBACK("utlum_nikdo_doma", SettingKind.DECIMAL, "snížení cíle o", 0.0, 10.0, "°C"),
    LIGHT_ON_MOTION("nocni_svetlo_pri_pohybu", SettingKind.FLAG, "rozsvěcení při pohybu"),
}

data class SettingChange(val key: SettingKey, val value: JsonPrimitive)

fun SettingKey.change(value: Double): SettingChange {
    require(kind == SettingKind.DECIMAL || kind == SettingKind.WHOLE) { "Nastavení $json nemá číselnou hodnotu" }
    // Spec 5.2: celá čísla se posílají jako celá čísla, ostatní jako desetinná.
    val primitive = if (kind == SettingKind.WHOLE) JsonPrimitive(value.roundToInt()) else JsonPrimitive(value)
    return SettingChange(this, primitive)
}

fun SettingKey.change(value: String): SettingChange {
    require(kind == SettingKind.TIME || kind == SettingKind.MODE) { "Nastavení $json nemá textovou hodnotu" }
    return SettingChange(this, JsonPrimitive(value))
}

fun SettingKey.change(value: Boolean): SettingChange {
    require(kind == SettingKind.FLAG) { "Nastavení $json není přepínač" }
    return SettingChange(this, JsonPrimitive(value))
}

fun List<SettingChange>.toJsonObject(): JsonObject = JsonObject(associate { it.key.json to it.value })

// Stejný tvar hodnoty jako u change(), aby šel zapsaný návrh porovnat s Nastavením přečteným z Raspberry.
fun HouseSettings.valueOf(key: SettingKey): JsonPrimitive? = when (key) {
    SettingKey.MODE -> JsonPrimitive(mode.json)
    SettingKey.TARGET_DAY -> JsonPrimitive(targetDay)
    SettingKey.TARGET_NIGHT -> JsonPrimitive(targetNight)
    SettingKey.TARGET_MANUAL -> JsonPrimitive(targetManual)
    SettingKey.DAY_START -> JsonPrimitive(dayStart)
    SettingKey.EVENING_START -> JsonPrimitive(eveningStart)
    SettingKey.NIGHT_START -> JsonPrimitive(nightStart)
    SettingKey.LUX_THRESHOLD -> JsonPrimitive(luxThreshold)
    SettingKey.NIGHT_LIGHT_SECONDS -> JsonPrimitive(nightLightSeconds)
    SettingKey.NOBODY_HOME_AFTER -> JsonPrimitive(nobodyHomeAfterMin)
    SettingKey.NOBODY_HOME_SETBACK -> JsonPrimitive(nobodyHomeSetback)
    SettingKey.LIGHT_ON_MOTION -> lightOnMotion?.let { JsonPrimitive(it) }
}
