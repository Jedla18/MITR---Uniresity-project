package cz.vsb.mitr.domek.screenshots

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.fontSizeSp
import cz.vsb.mitr.domek.testing.overlappingTexts
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.textsBrokenInsideAWord
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.DomekTab
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Čtyři záložky v rozvržení pro široké obrazovky (boční lišta a dva sloupce): telefon na šířku (náhled „Na šířku“) a tablet
// Galaxy Tab A8, na kterém se aplikace zkouší, na výšku i na šířku. Snímky se prohlížejí, testy hlídají, co oko snadno přehlédne:
// lišta je vlevo, sloupce stojí vedle sebe, žádný text není oříznutý, zalomený uprostřed slova ani na jiném textu a poslední
// ovladač každého sloupce se dá dojet posouváním.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_LANDSCAPE)
class LandscapeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Texty, podle kterých se pozná levý a pravý sloupec záložky, a poslední prvek každého sloupce. Nastavení má demoState se vším,
    // co obrazovka umí ukázat, tedy i s upozorněním na hodiny Raspberry; ostatní záložky jsou bez něj jako na náhledu „Na šířku“.
    private enum class Page(
        val tab: DomekTab,
        val slug: String,
        val left: String,
        val right: String,
        val lastLeft: SemanticsMatcher,
        val lastRight: SemanticsMatcher,
        val state: HouseUiState,
    ) {
        OVERVIEW(
            DomekTab.OVERVIEW, "prehled", "Režim Auto", "Teploty",
            lastLeft = hasText("Svítí") and hasClickAction(), lastRight = hasText("Chladí"),
            state = demoStateWithoutClockWarning(),
        ),
        THERMOSTAT(
            DomekTab.THERMOSTAT, "termostat", "Režim", "Cílové teploty",
            lastLeft = hasText("Vypnuto") and hasClickAction(), lastRight = hasText("Snížit o"),
            state = demoStateWithoutClockWarning(),
        ),
        LIGHT(
            DomekTab.LIGHT, "svetlo", "Svítí · ručně", "Barva",
            lastLeft = hasText("Vrátit automatiku") and hasClickAction(), lastRight = hasText("Práh šera"),
            state = demoStateWithoutClockWarning(),
        ),
        SETTINGS(
            DomekTab.SETTINGS, "nastaveni", "Denní doba", "Čas Raspberry",
            lastLeft = hasText("Vrátit původní časy"), lastRight = hasText("Odpojit") and hasClickAction(),
            state = demoState(),
        ),
    }

    private fun show(page: Page) {
        compose.setContent { DomekTheme(darkTheme = false) { DomekApp(page.state, NoActions, NoSystem, emptyFlow()) } }
        compose.tab(page.tab).performClick()
    }

    private fun capture(prefix: String, page: Page) {
        show(page)
        compose.saveScreenshot("$prefix-${page.slug}")

        val rail = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot()
        assertTrue("boční lišta je vlevo: $rail", rail.right < 90.dp)
        val left = compose.onNodeWithText(page.left).getBoundsInRoot()
        val right = compose.onNodeWithText(page.right).getBoundsInRoot()
        assertTrue("levý sloupec je vpravo od lišty: $rail, $left", left.left > rail.right)
        assertTrue("pravý sloupec je vpravo od levého: $left, $right", right.left > left.right)

        assertEquals("oříznuté texty", emptyList<String>(), compose.cutOffTexts() + compose.verticallyClippedTexts())
        assertEquals("slova zalomená uprostřed", emptyList<String>(), compose.textsBrokenInsideAWord())
        assertEquals("texty přes sebe", emptyList<String>(), compose.overlappingTexts())

        // Na nízké obrazovce je spodek sloupců pod okrajem a posouvá se. Poslední prvek obou sloupců se musí dát dojet celý.
        val screenHeight = compose.onRoot().fetchSemanticsNode().size.height
        listOf(page.lastLeft, page.lastRight).forEach { last ->
            compose.onNode(last).performScrollTo().assertIsDisplayed()
            val node = compose.onNode(last).fetchSemanticsNode()
            val bottom = node.positionInRoot.y + node.size.height
            assertTrue("poslední prvek sloupce se dá celý dojet: $bottom z $screenHeight px", bottom <= screenHeight)
        }
    }

    @Test
    fun phoneOverview() = capture("sirka", Page.OVERVIEW)

    @Test
    fun phoneThermostat() = capture("sirka", Page.THERMOSTAT)

    @Test
    fun phoneLight() = capture("sirka", Page.LIGHT)

    @Test
    fun phoneSettings() = capture("sirka", Page.SETTINGS)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletOverview() = capture("tablet", Page.OVERVIEW)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletThermostat() = capture("tablet", Page.THERMOSTAT)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletLight() = capture("tablet", Page.LIGHT)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletSettings() = capture("tablet", Page.SETTINGS)

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeOverview() = capture("tablet-sirka", Page.OVERVIEW)

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeThermostat() = capture("tablet-sirka", Page.THERMOSTAT)

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeLight() = capture("tablet-sirka", Page.LIGHT)

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeSettings() = capture("tablet-sirka", Page.SETTINGS)

    // Telefon na šířku je nízký, proto má Přehled menší číslo i dlaždice jako na náhledu „Na šířku“ a první řada dlaždic se vejde
    // pod kartu termostatu bez posouvání.
    @Test
    fun theOverviewOfAPhoneInLandscapeIsCompactAndShowsTheFirstRowOfTiles() {
        show(Page.OVERVIEW)

        assertEquals(46f, compose.onNodeWithText("22,4", useUnmergedTree = true).fontSizeSp(), 0.01f)
        val tile = compose.onNode(hasText("Tlak") and hasText("1013${NBSP}hPa")).fetchSemanticsNode()
        val bottom = tile.positionInRoot.y + tile.size.height
        val screenHeight = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue("první řada dlaždic je celá nad dolním okrajem: $bottom z $screenHeight px", bottom <= screenHeight)
    }

    // Tablet má dost místa, takže Přehled zůstává v obvyklých rozměrech.
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun theOverviewOfATabletKeepsTheRegularSizes() {
        show(Page.OVERVIEW)

        assertEquals(60f, compose.onNodeWithText("22,4", useUnmergedTree = true).fontSizeSp(), 0.01f)
    }
}
