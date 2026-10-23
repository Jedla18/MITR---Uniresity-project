package cz.vsb.mitr.domek.testing

import androidx.compose.ui.graphics.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue

// Kontroly barev na snímku obrazovky (renderScreenshot) pro testy Compose, které jinak vidí jen rozměry a texty.

fun BufferedImage.colorAt(x: Float, y: Float): Color = Color(getRGB(x.roundToInt(), y.roundToInt()))

fun isNear(expected: Color, actual: Color, tolerance: Float = 0.04f): Boolean =
    abs(expected.red - actual.red) <= tolerance && abs(expected.green - actual.green) <= tolerance &&
        abs(expected.blue - actual.blue) <= tolerance && abs(expected.alpha - actual.alpha) <= tolerance

// Půl odstínu z 255: barvy se mají shodovat, ne jen být podobné.
const val EXACT = 0.002f

fun assertNear(what: String, expected: Color, actual: Color, tolerance: Float = 0.04f) =
    assertTrue("$what: čekala se barva $expected, na snímku je $actual", isNear(expected, actual, tolerance))
