package cz.vsb.mitr.domek.ui.log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.screenshots.TABLET_LANDSCAPE
import cz.vsb.mitr.domek.screenshots.renderScreenshot
import cz.vsb.mitr.domek.testing.colorAt
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.isNear
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.LogCategory
import cz.vsb.mitr.domek.viewmodel.LogEntry
import cz.vsb.mitr.domek.viewmodel.LogEvent
import cz.vsb.mitr.domek.viewmodel.LogKind
import java.time.LocalDateTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Obrazovka Deníku (spec 2.1): řádky, filtry, prázdné stavy a tečky v barvě světla.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf(emptyList<LogEntry>())

    private fun show(entries: List<LogEntry>) {
        shown = entries
        compose.setContent { DomekTheme(darkTheme = false) { LogScreen(shown) } }
    }

    private fun update(entries: List<LogEntry>) {
        shown = entries
        compose.waitForIdle()
    }

    private val wall = LocalDateTime.of(2026, 10, 20, 14, 3, 12)
    private var nextId = 0L

    private fun entry(event: LogEvent, at: LocalDateTime = wall) = LogEntry(nextId++, at, event)

    private val cooling = LogEvent(LogCategory.THERMOSTAT, LogKind.COOLING, "Začalo chladit", "Uvnitř 24,1${NBSP}°C, cíl 23,0${NBSP}°C")
    private val lightOn =
        LogEvent(LogCategory.LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "auto · venku je šero · 100${NBSP}%", listOf(Rgb(255, 0, 255)))
    private val motion = LogEvent(LogCategory.MOTION, LogKind.MOTION, "Pohyb", "2× od 14:02:40")
    private val lost = LogEvent(LogCategory.CONNECTION, LogKind.CONNECTION_LOST, "Spojení přerušeno", "znovu se připojuji…")
    private val simulation =
        LogEvent(LogCategory.OTHER, LogKind.SIMULATION, "Simulace: zahřáté čidlo", "venku o${NBSP}8${NBSP}°C víc na 30${NBSP}s")

    private fun chip(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun selectFilter(label: String) {
        chip(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertShown(vararg titles: String) {
        listOf(cooling, lightOn, motion, lost, simulation).forEach { event ->
            val row = compose.onNodeWithText(event.detail, useUnmergedTree = true)
            if (event.title in titles) row.assertExists("řádek „${event.title}“") else row.assertDoesNotExist()
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    // Řádek

    @Test
    fun rowShowsThePhoneTimeTitleAndDetail() {
        show(listOf(entry(cooling)))

        compose.onNodeWithText("14:03:12", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Začalo chladit", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Uvnitř 24,1${NBSP}°C, cíl 23,0${NBSP}°C", useUnmergedTree = true).assertIsDisplayed()
    }

    // Čtečka obrazovky přečte řádek jako celek: čas, nadpis i podrobnost.
    @Test
    fun rowIsReadAsOneItem() {
        show(listOf(entry(cooling)))

        compose.onNode(hasText("14:03:12") and hasText("Začalo chladit") and hasText("Uvnitř 24,1${NBSP}°C, cíl 23,0${NBSP}°C"))
            .assertIsDisplayed()
    }

    // Pořadí dělá ViewModel (nejnovější nahoře), obrazovka ho jen zachová.
    @Test
    fun entriesKeepTheirOrderFromTopToBottom() {
        show(listOf(entry(lost, wall.plusMinutes(2)), entry(cooling)))

        val newer = compose.onNodeWithText("Spojení přerušeno", useUnmergedTree = true).getBoundsInRoot()
        val older = compose.onNodeWithText("Začalo chladit", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("novější nahoře: $newer, $older", newer.bottom <= older.top)
    }

    @Test
    fun longTitlesWrapInsteadOfBeingCut() {
        val message = "Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo."
        show(listOf(entry(LogEvent(LogCategory.CONNECTION, LogKind.MESSAGE, message))))

        assertTrue("nadpis má víc řádků", textLayout(message).lineCount > 1)
        assertEquals(emptyList<String>(), compose.cutOffTexts() + compose.verticallyClippedTexts())
    }

    // Tečky v barvě světla

    // Purpurovou nic jiného na obrazovce nemá, takže ji může nakreslit jen tečka.
    @Test
    fun lightDotHasTheColorOfTheLightAndStandsBeforeTheDetail() {
        show(listOf(entry(lightOn)))

        val found = colorBeforeDetail(lightOn.detail, Color(0xFFFF00FF))
        assertTrue("purpurová tečka před podrobností", found.isNotEmpty())
    }

    @Test
    fun colorChangeShowsTheOldColorThenTheNewOne() {
        val change = LogEvent(
            LogCategory.LIGHT,
            LogKind.SETTING,
            "Barva světla změněna",
            "změna z${NBSP}aplikace",
            listOf(Rgb(255, 0, 255), Rgb(0, 200, 0)),
        )
        show(listOf(entry(change)))

        val old = colorBeforeDetail(change.detail, Color(0xFFFF00FF))
        val new = colorBeforeDetail(change.detail, Color(0xFF00C800))
        assertTrue("obě tečky: stará ${old.size} px, nová ${new.size} px", old.isNotEmpty() && new.isNotEmpty())
        assertTrue("stará barva vlevo od nové", old.max() < new.min())
    }

    @Test
    fun entryWithoutLightColorHasNoDot() {
        show(listOf(entry(lightOn.copy(lightColors = emptyList()))))

        assertTrue(colorBeforeDetail(lightOn.detail, Color(0xFFFF00FF)).isEmpty())
    }

    // Vodorovné souřadnice (px) bodů v barvě `color` mezi začátkem nadpisu a začátkem podrobnosti, ve výšce podrobnosti.
    private fun colorBeforeDetail(detail: String, color: Color): List<Int> {
        val density = compose.density.density
        val title = compose.onNodeWithText(shown.single().event.title, useUnmergedTree = true).getBoundsInRoot()
        val text = compose.onNodeWithText(detail, useUnmergedTree = true).getBoundsInRoot()
        val image = compose.renderScreenshot("tecka")
        val xs = (title.left.value * density).toInt() until (text.left.value * density).toInt()
        val ys = (text.top.value * density).toInt()..(text.bottom.value * density).toInt()
        return xs.filter { x -> ys.any { y -> isNear(color, image.colorAt(x.toFloat(), y.toFloat())) } }
    }

    // Filtry

    @Test
    fun allIsSelectedFirstAndShowsEverything() {
        show(listOf(entry(cooling), entry(lightOn), entry(motion), entry(lost), entry(simulation)))

        chip("Vše").assertIsSelected()
        listOf("Termostat", "Světlo", "Pohyb", "Spojení a chyby").forEach { chip(it).assertIsNotSelected() }
        assertShown("Začalo chladit", "Světlo se rozsvítilo", "Pohyb", "Spojení přerušeno", "Simulace: zahřáté čidlo")
    }

    // Ostatní události (simulace, hodiny, začátky úseků) nemají vlastní filtr, jsou jen ve „Vše“.
    @Test
    fun eachFilterShowsItsGroupOnly() {
        show(listOf(entry(cooling), entry(lightOn), entry(motion), entry(lost), entry(simulation)))

        selectFilter("Termostat")
        assertShown("Začalo chladit")
        selectFilter("Světlo")
        assertShown("Světlo se rozsvítilo")
        selectFilter("Pohyb")
        assertShown("Pohyb")
        selectFilter("Spojení a chyby")
        assertShown("Spojení přerušeno")
        selectFilter("Vše")
        assertShown("Začalo chladit", "Světlo se rozsvítilo", "Pohyb", "Spojení přerušeno", "Simulace: zahřáté čidlo")
    }

    @Test
    fun exactlyOneFilterIsSelected() {
        show(listOf(entry(cooling)))

        selectFilter("Světlo")

        chip("Světlo").assertIsSelected()
        listOf("Vše", "Termostat", "Pohyb", "Spojení a chyby").forEach { chip(it).assertIsNotSelected() }
    }

    // Prázdné stavy

    @Test
    fun emptyLogSaysNothingHappenedYetAndWhenItStartsWriting() {
        show(emptyList())

        compose.onNodeWithText("Zatím se nic nestalo.").assertIsDisplayed()
        compose.onNodeWithText("Deník zapisuje změny od chvíle, kdy aplikace dostane první data.").assertIsDisplayed()

        // Prázdný je celý Deník, ne jen skupina.
        selectFilter("Pohyb")
        compose.onNodeWithText("Zatím se nic nestalo.").assertIsDisplayed()
        compose.onNodeWithText("V${NBSP}této skupině zatím nic není.").assertDoesNotExist()
    }

    @Test
    fun emptyGroupSaysSo() {
        show(listOf(entry(simulation)))

        selectFilter("Termostat")

        compose.onNodeWithText("V${NBSP}této skupině zatím nic není.").assertIsDisplayed()
        compose.onNodeWithText("Zatím se nic nestalo.").assertDoesNotExist()
    }

    // Nové události

    private fun manyEntries(): List<LogEntry> =
        (0 until 30).map { index ->
            entry(LogEvent(LogCategory.OTHER, LogKind.SIMULATION, "Událost $index", "podrobnost $index"), wall.minusSeconds(index.toLong()))
        }

    // Kdo je na začátku seznamu, vidí i události, které právě přibyly nad ním.
    @Test
    fun aNewEntryIsShownWhenTheListIsAtTheTop() {
        val old = manyEntries()
        show(old)

        update(listOf(entry(lost, wall.plusSeconds(5))) + old)

        compose.onNodeWithText("Spojení přerušeno", useUnmergedTree = true).assertIsDisplayed()
    }

    // Kdo si posunul seznam níž, tomu nová událost nahoře řádky pod rukou neposune.
    @Test
    fun aScrolledListStaysWhereItIsWhenANewEntryArrives() {
        val old = manyEntries()
        show(old)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        val before = compose.onNodeWithText("Událost 15", useUnmergedTree = true).getBoundsInRoot()

        update(listOf(entry(lost, wall.plusSeconds(5))) + old)

        assertEquals(before, compose.onNodeWithText("Událost 15", useUnmergedTree = true).getBoundsInRoot())
    }

    // Široká obrazovka

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun onAWideScreenTheListIsCenteredAndAtMost720dpWide() {
        show(listOf(entry(cooling)))

        val root = compose.onRoot().getBoundsInRoot()
        // Ve sloučeném stromu je text jen v uzlu celého řádku.
        val row = compose.onNodeWithText("Začalo chladit").getBoundsInRoot()
        val width = row.right - row.left
        assertTrue("řádek je nejvýš 720 dp široký: $width", width <= 720.dp)
        assertTrue("řádek je široký skoro 720 dp: $width", width >= 680.dp)
        val center = (row.left + row.right) / 2
        assertTrue("řádek je uprostřed: $row v $root", abs((center - (root.left + root.right) / 2).value) < 1f)
    }
}
