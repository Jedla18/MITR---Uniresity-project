package cz.vsb.mitr.domek.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DayPart(val json: String) {
    @SerialName("den") DAY("den"),
    @SerialName("vecer") EVENING("vecer"),
    @SerialName("noc") NIGHT("noc"),
}

@Serializable
enum class ThermostatMode(val json: String, val label: String) {
    @SerialName("auto") AUTO("auto", "Auto"),
    @SerialName("rucni") MANUAL("rucni", "Ruční"),
    @SerialName("vypnuto") OFF("vypnuto", "Vypnuto"),
}

@Serializable
data class LightState(
    @SerialName("zap") val on: Boolean,
    @SerialName("barva") val color: List<@Serializable(with = FlexibleIntSerializer::class) Int>,
    @SerialName("jas") @Serializable(with = FlexibleIntSerializer::class) val brightness: Int,
    @SerialName("rucne") val manual: Boolean,
) {
    val rgb: Rgb
        get() = rgbOf(color)
}

@Serializable
data class HouseStatus(
    // Před prvním krokem domku jsou čas i úsek null (docs/ble-rozhrani.md, Stav).
    @SerialName("cas") val time: String? = null,
    // Úsek, jak ho Raspberry poslalo. Nový úsek se má ukázat, jak přišel, takže se kvůli němu Stav nezahazuje.
    @SerialName("usek") val dayPartText: String? = null,
    @SerialName("t_venku") val outsideTemp: Double? = null,
    @SerialName("vlhkost") val humidity: Double? = null,
    @SerialName("tlak") val pressure: Double? = null,
    @SerialName("lux") val lux: Double? = null,
    @SerialName("t_uvnitr") val insideTemp: Double? = null,
    @SerialName("cil") val target: Double? = null,
    @SerialName("topi") val heating: Boolean,
    @SerialName("chladi") val cooling: Boolean,
    @SerialName("rezim") val mode: ThermostatMode,
    @SerialName("nikdo_doma") val nobodyHome: Boolean,
    @SerialName("pohyb") val motion: Boolean,
    @SerialName("svetlo") val light: LightState,
    @SerialName("chyby") val errors: List<String> = emptyList(),
) {
    // Známý úsek dne, nebo null, když chybí nebo je nový.
    val dayPart: DayPart?
        get() = DayPart.entries.firstOrNull { it.json == dayPartText }
}
