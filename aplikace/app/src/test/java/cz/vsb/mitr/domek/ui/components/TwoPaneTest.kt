package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.screenshots.PHONE_LANDSCAPE
import cz.vsb.mitr.domek.screenshots.TABLET
import cz.vsb.mitr.domek.screenshots.TABLET_LANDSCAPE
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Rozložení dvou sloupců: poměr šířek, mezery a největší šířka obsahu na velké obrazovce. Sloupce jsou prázdné obdélníky,
// takže se měří jen rozvržení, ne obsah obrazovek.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE_LANDSCAPE)
class TwoPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(wide: Boolean = true) {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                TwoPane(
                    wide = wide,
                    left = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("left")) },
                    right = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("right")) },
                )
            }
        }
    }

    private fun left() = compose.onNodeWithTag("left").getBoundsInRoot()

    private fun right() = compose.onNodeWithTag("right").getBoundsInRoot()

    private fun assertDpEquals(message: String, expected: Float, actual: Float) = assertEquals(message, expected, actual, 0.5f)

    // Telefon na šířku (832 dp) má sloupce přes celou šířku s okraji i mezerou 12 dp, v poměru 1 : 1,15 jako na náhledu.
    @Test
    fun columnsFillAPhoneInLandscapeInTheRatioOfTheMockup() {
        show()

        assertDpEquals("levý okraj", 12f, left().left.value)
        assertDpEquals("pravý okraj", 832f - 12f, right().right.value)
        assertDpEquals("mezera mezi sloupci", 12f, (right().left - left().right).value)
        assertEquals("poměr šířek", 1.15f, right().width.value / left().width.value, 0.01f)
    }

    // Tablet na šířku (1280 dp) má obsah uprostřed a nejvýš tak široký, aby karty nebyly natažené přes celou obrazovku: širší než
    // telefon na šířku, ale užší než 1100 dp.
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun onATabletInLandscapeTheColumnsStayInTheMiddleAtALimitedWidth() {
        show()

        val width = (right().right - left().left).value
        assertTrue("obsah je širší než na telefonu na šířku ($width dp)", width > 832f - 24f)
        assertTrue("obsah není natažený přes celý tablet ($width dp)", width < 1100f)
        assertDpEquals("obsah je uprostřed", left().left.value, 1280f - right().right.value)
        assertEquals("poměr šířek", 1.15f, right().width.value / left().width.value, 0.01f)
    }

    // Tablet na výšku (800 dp) je užší než omezení, takže sloupce vyplní celou šířku jako na telefonu.
    @Test
    @Config(qualifiers = TABLET)
    fun onATabletInPortraitTheColumnsFillTheWholeWidth() {
        show()

        assertDpEquals("levý okraj", 12f, left().left.value)
        assertDpEquals("pravý okraj", 800f - 12f, right().right.value)
    }

    // Úzká obrazovka má jeden sloupec: levý obsah nad pravým, oba přes celou šířku.
    @Test
    @Config(qualifiers = PHONE)
    fun aNarrowScreenHasOneColumnWithTheLeftContentAboveTheRight() {
        show(wide = false)

        assertDpEquals("šířka levého", 384f - 24f, left().width.value)
        assertDpEquals("šířka pravého", 384f - 24f, right().width.value)
        assertTrue("pravý obsah je pod levým: ${left()}, ${right()}", right().top >= left().bottom)
    }
}
