package cz.vsb.mitr.domek.ui.light

import cz.vsb.mitr.domek.data.model.Rgb
import kotlin.math.floor
import kotlin.math.roundToInt

data class LightPreset(val name: String, val rgb: Rgb)

// Předvolby barev v pořadí, v jakém jsou v mřížce (spec 6.3).
val LIGHT_PRESETS: List<LightPreset> = listOf(
    LightPreset("bílá", Rgb(255, 255, 255)),
    LightPreset("teplá bílá", Rgb(255, 214, 160)),
    LightPreset("žlutá", Rgb(255, 200, 60)),
    LightPreset("oranžová", Rgb(255, 138, 61)),
    LightPreset("červená", Rgb(244, 67, 54)),
    LightPreset("růžová", Rgb(255, 95, 162)),
    LightPreset("fialová", Rgb(155, 92, 255)),
    LightPreset("modrá", Rgb(100, 110, 255)),
    LightPreset("azurová", Rgb(46, 197, 232)),
    LightPreset("zelená", Rgb(61, 214, 140)),
    LightPreset("limetková", Rgb(181, 229, 80)),
)

// Předvolba je jen ta, která má přesně tuto barvu. Barva o jedničku vedle už je vlastní.
fun presetFor(rgb: Rgb): LightPreset? = LIGHT_PRESETS.firstOrNull { it.rgb == rgb }

private const val SECTORS = 6
private const val DEGREES_PER_SECTOR = 60f
private const val FULL_CIRCLE = 360f
private const val MAX_CHANNEL = 255f

// Odstín je ve stupních a bere se cyklicky (360° je zase červená), sytost a jas jsou 0 až 1. Čistý Kotlin, aby šel dialog barvy
// zkoušet v JVM testech. Půlka se zaokrouhluje nahoru, takže sytost 0,5 dává u 255 hodnotu 128.
fun hsvToRgb(hue: Float, saturation: Float, value: Float = 1f): Rgb {
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val sector = hue.mod(FULL_CIRCLE) / DEGREES_PER_SECTOR
    val whole = floor(sector).toInt()
    val fraction = sector - whole
    val low = v * (1f - s)
    val falling = v * (1f - s * fraction)
    val rising = v * (1f - s * (1f - fraction))
    // Odstín těsně pod 0° dá po zbytku po dělení přesně 360°, což je šestý úsek, a ten je zase první.
    return when (whole % SECTORS) {
        0 -> rgbOf(v, rising, low)
        1 -> rgbOf(falling, v, low)
        2 -> rgbOf(low, v, rising)
        3 -> rgbOf(low, falling, v)
        4 -> rgbOf(rising, low, v)
        else -> rgbOf(v, low, falling)
    }
}

private fun rgbOf(r: Float, g: Float, b: Float) =
    Rgb((r * MAX_CHANNEL).roundToInt(), (g * MAX_CHANNEL).roundToInt(), (b * MAX_CHANNEL).roundToInt())
