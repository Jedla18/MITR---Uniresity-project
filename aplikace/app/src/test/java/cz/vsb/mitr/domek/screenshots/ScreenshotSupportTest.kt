package cz.vsb.mitr.domek.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()

// Pomocník pro screenshoty musí poznat okna nad oknem aplikace a nesmí tiše uložit prázdný obrázek.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ScreenshotSupportTest {
    @get:Rule
    val compose = createComposeRule()

    private fun BufferedImage.count(argb: Int): Int =
        (0 until height).sumOf { y -> (0 until width).count { x -> getRGB(x, y) == argb } }

    // Dialog i spodní panel (ModalBottomSheet) mají vlastní okno, tedy vlastní kořen Compose. Obě okna musí být na snímku.
    @Test
    fun drawsTheAppWindowAndTheWindowsAboveItIntoOneImage() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White))
            Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.size(100.dp).background(Color.Red))
            }
        }

        val image = compose.renderScreenshot("okno-nad-oknem")

        assertEquals("obrázek má velikost okna aplikace (384 × 832 dp při 3 px na dp)", 1152 to 2496, image.width to image.height)
        assertTrue("okno aplikace (bílé) je na snímku", image.count(WHITE) > 0)
        assertTrue("dialog (červený čtverec) je na snímku", image.count(RED) > 0)
    }

    // Prázdná obrazovka nemá projít jako „screenshot“, na který se pak nikdo nepodívá.
    @Test
    fun failsLoudlyAndSavesNothingWhenTheRenderIsOneColour() {
        compose.setContent { Box(Modifier.fillMaxSize()) }
        val file = File("build/screenshots/test-prazdny.png").apply { delete() }

        val error = assertThrows(IllegalStateException::class.java) { compose.saveScreenshot("test-prazdny") }

        assertTrue("zpráva ${error.message}", error.message.orEmpty().contains("jednobarevný"))
        assertTrue("zpráva ${error.message}", error.message.orEmpty().contains("test-prazdny"))
        assertFalse("jednobarevný snímek se nesmí uložit", file.exists())
    }

    @Test
    fun failsWithAClearMessageWhenThereIsNoComposeContent() {
        val error = assertThrows(IllegalStateException::class.java) { compose.saveScreenshot("test-bez-obsahu") }

        assertTrue("zpráva ${error.message}", error.message.orEmpty().contains("žádný kořen"))
        assertTrue("zpráva ${error.message}", error.message.orEmpty().contains("test-bez-obsahu"))
    }
}
