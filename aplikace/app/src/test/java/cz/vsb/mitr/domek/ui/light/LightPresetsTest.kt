package cz.vsb.mitr.domek.ui.light

import cz.vsb.mitr.domek.data.model.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LightPresetsTest {
    @Test
    fun pureHuesGivePureChannels() {
        assertEquals(Rgb(255, 0, 0), hsvToRgb(0f, 1f))
        assertEquals(Rgb(0, 255, 0), hsvToRgb(120f, 1f))
        assertEquals(Rgb(0, 0, 255), hsvToRgb(240f, 1f))
    }

    @Test
    fun secondaryColorsLieBetweenThePureOnes() {
        assertEquals(Rgb(255, 255, 0), hsvToRgb(60f, 1f))
        assertEquals(Rgb(0, 255, 255), hsvToRgb(180f, 1f))
        assertEquals(Rgb(255, 0, 255), hsvToRgb(300f, 1f))
    }

    @Test
    fun noSaturationIsWhiteAndHalfSaturationIsRoundedHalfUp() {
        assertEquals(Rgb(255, 255, 255), hsvToRgb(0f, 0f))
        assertEquals(Rgb(255, 255, 128), hsvToRgb(60f, 0.5f))
    }

    // Dialog barvy posílá jen odstín a sytost, jas řeší jeho vlastní posuvník, proto je výchozí hodnota plná.
    @Test
    fun valueDefaultsToFullAndScalesAllChannels() {
        assertEquals(hsvToRgb(200f, 0.4f, 1f), hsvToRgb(200f, 0.4f))
        assertEquals(Rgb(128, 0, 0), hsvToRgb(0f, 1f, 0.5f))
        assertEquals(Rgb(0, 0, 0), hsvToRgb(90f, 0.7f, 0f))
    }

    // Úplný oběh barevného kola je zase červená a záporný odstín se počítá od konce kola.
    @Test
    fun hueWrapsAroundTheColorWheel() {
        assertEquals(hsvToRgb(0f, 1f), hsvToRgb(360f, 1f))
        assertEquals(hsvToRgb(240f, 1f), hsvToRgb(-120f, 1f))
        assertEquals(hsvToRgb(30f, 1f), hsvToRgb(750f, 1f))
        // Odstín o chlup pod nulou se při přepočtu zaokrouhlí na plný kruh, což je zase červená, ne purpurová.
        assertEquals(Rgb(255, 0, 0), hsvToRgb(-1e-8f, 1f))
    }

    @Test
    fun saturationAndValueOutsideTheirRangeAreClamped() {
        assertEquals(Rgb(255, 255, 255), hsvToRgb(10f, -0.5f))
        assertEquals(hsvToRgb(10f, 1f), hsvToRgb(10f, 1.5f))
        assertEquals(Rgb(255, 0, 0), hsvToRgb(0f, 1f, 2f))
    }

    @Test
    fun presetsAreInTheOrderOfTheSpec() {
        assertEquals(
            listOf(
                "bílá" to Rgb(255, 255, 255),
                "teplá bílá" to Rgb(255, 214, 160),
                "žlutá" to Rgb(255, 200, 60),
                "oranžová" to Rgb(255, 138, 61),
                "červená" to Rgb(244, 67, 54),
                "růžová" to Rgb(255, 95, 162),
                "fialová" to Rgb(155, 92, 255),
                "modrá" to Rgb(100, 110, 255),
                "azurová" to Rgb(46, 197, 232),
                "zelená" to Rgb(61, 214, 140),
                "limetková" to Rgb(181, 229, 80),
            ),
            LIGHT_PRESETS.map { it.name to it.rgb },
        )
    }

    @Test
    fun presetForFindsThePresetOfExactlyThatColor() {
        assertEquals("modrá", presetFor(Rgb(100, 110, 255))?.name)
        LIGHT_PRESETS.forEach { assertEquals(it, presetFor(it.rgb)) }
    }

    @Test
    fun presetForIsNullForAnyOtherColor() {
        assertNull(presetFor(Rgb(1, 2, 3)))
        // O jedničku vedle modré už to modrá není.
        assertNull(presetFor(Rgb(100, 110, 254)))
        assertNotNull(presetFor(Rgb(100, 110, 255)))
    }

    @Test
    fun presetNamesAndColorsAreUnique() {
        assertEquals(LIGHT_PRESETS.size, LIGHT_PRESETS.map { it.name }.toSet().size)
        assertEquals(LIGHT_PRESETS.size, LIGHT_PRESETS.map { it.rgb }.toSet().size)
    }
}
