package cz.vsb.mitr.domek.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Klíče Nastavení s uloženou barvou a jasem světla. Na rozdíl od SettingKey to nejsou čísla s rozsahem, proto mají vlastní zápis.
const val LIGHT_COLOR_KEY = "barva_svetla"
const val LIGHT_BRIGHTNESS_KEY = "jas_svetla"

@Serializable
data class HouseSettings(
    @SerialName("rezim_termostatu") val mode: ThermostatMode,
    @SerialName("cil_den") val targetDay: Double,
    @SerialName("cil_noc") val targetNight: Double,
    @SerialName("cil_rucni") val targetManual: Double,
    @SerialName("zacatek_den") val dayStart: String,
    @SerialName("zacatek_vecer") val eveningStart: String,
    @SerialName("zacatek_noc") val nightStart: String,
    @SerialName("prah_lux") val luxThreshold: Double,
    @SerialName("nocni_svetlo_s") @Serializable(with = FlexibleIntSerializer::class) val nightLightSeconds: Int,
    @SerialName("nikdo_doma_po_min") @Serializable(with = FlexibleIntSerializer::class) val nobodyHomeAfterMin: Int,
    @SerialName("utlum_nikdo_doma") val nobodyHomeSetback: Double,
    // Poslední tři klíče z docs/ble-rozhrani.md starší program na Raspberry neposílá, proto můžou chybět.
    @SerialName("nocni_svetlo_pri_pohybu") val lightOnMotion: Boolean? = null,
    // Barva a jas, kterými světlo svítí v automatice i po ručním zapnutí.
    @SerialName("barva_svetla") val lightColor: List<@Serializable(with = FlexibleIntSerializer::class) Int>? = null,
    @SerialName("jas_svetla") @Serializable(with = FlexibleIntSerializer::class) val lightBrightness: Int? = null,
)

// Program na Raspberry, který v Nastavení posílá uloženou barvu i jas světla, je umí i zapsat (docs/ble-rozhrani.md, Nastavení).
val HouseSettings.storesLightLook: Boolean
    get() = lightColor != null && lightBrightness != null

// Zápis uložené barvy a jasu světla do Nastavení (docs/ble-rozhrani.md, Nastavení): jen části, které se mění, a složky barvy i jas
// jako celá čísla bez desetinné tečky, jinak by je Raspberry odmítlo. Klíče jsou stejné jako u polí lightColor a lightBrightness.
fun storedLookPatch(color: Rgb?, brightness: Int?): JsonObject = buildJsonObject {
    color?.let { rgb -> put(LIGHT_COLOR_KEY, buildJsonArray { add(rgb.r); add(rgb.g); add(rgb.b) }) }
    brightness?.let { put(LIGHT_BRIGHTNESS_KEY, it) }
}
