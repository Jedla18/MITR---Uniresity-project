package cz.vsb.mitr.domek.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Pomocníci pro snímky na šířku musí poznat, co mají hlídat, a nesmí hlásit zdravé rozvržení. Texty jsou skutečné (NATIVE), protože
// v běžném režimu Robolectricu je text jen zlomkem skutečné šířky.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TextPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    // Bez motivu aplikace je velikost písma neurčená a Robolectric pak hlásí jiný počet řádků, než kolik jich text zabírá.
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { DomekTheme(darkTheme = false) { content() } }
    }

    @Test
    fun aWordBrokenInTheMiddleIsFound() {
        show { Box(Modifier.width(40.dp)) { Text("Přepnout") } }

        assertEquals(listOf("Přepnout"), compose.textsBrokenInsideAWord())
    }

    @Test
    fun aLineBrokenAtASpaceIsNotAWordBrokenInTheMiddle() {
        show { Box(Modifier.width(90.dp)) { Text("Ve dne a večer drží denní cíl") } }

        assertEquals(emptyList<String>(), compose.textsBrokenInsideAWord())
    }

    @Test
    fun aTextThatFitsOnOneLineIsNotAWordBrokenInTheMiddle() {
        show { Text("Přepnout") }

        assertEquals(emptyList<String>(), compose.textsBrokenInsideAWord())
    }

    @Test
    fun textsOnTopOfEachOtherAreFound() {
        show {
            Box {
                Text("První text")
                Text("Druhý text")
            }
        }

        assertEquals(listOf("„První text“ a „Druhý text“"), compose.overlappingTexts())
    }

    @Test
    fun textsBesideEachOtherAndBelowEachOtherAreNotFound() {
        show {
            Column {
                Row {
                    Text("Vlevo")
                    Text("Vpravo")
                }
                Text("Dole")
            }
        }

        assertEquals(emptyList<String>(), compose.overlappingTexts())
    }

    // Rámeček textu je o pár dp vyšší než písmena, takže drobné překrytí rámečků o nic nejde.
    @Test
    fun aSmallOverlapOfTheBoxesIsTolerated() {
        show {
            Column {
                Text("Nahoře")
                Text("Dole", Modifier.offset(y = (-4).dp))
            }
        }

        assertEquals(emptyList<String>(), compose.overlappingTexts())
    }

    @Test
    fun aDeepOverlapOfTheBoxesIsFound() {
        show {
            Column {
                Text("Nahoře")
                Text("Dole", Modifier.offset(y = (-12).dp))
            }
        }

        assertEquals(listOf("„Nahoře“ a „Dole“"), compose.overlappingTexts())
    }

    @Test
    fun theFontSizeIsTheOneTheTextIsDrawnWith() {
        show { Text("Teplota", fontSize = 46.sp) }

        assertEquals(46f, compose.onNodeWithText("Teplota", useUnmergedTree = true).fontSizeSp(), 0.01f)
    }
}
