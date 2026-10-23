package cz.vsb.mitr.domek.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.domain.DayStarts
import cz.vsb.mitr.domek.screenshots.PHONE_TALL
import cz.vsb.mitr.domek.screenshots.renderScreenshot
import cz.vsb.mitr.domek.testing.assertNear
import cz.vsb.mitr.domek.testing.colorAt
import cz.vsb.mitr.domek.testing.isNear
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LightColorScheme
import cz.vsb.mitr.domek.ui.theme.LightDomekColors
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TAG = "timeline"

// Šířka pruhu jako na telefonu: obrazovka 384 dp, odsazení stránky 12 dp a karty 16 dp.
private val TIMELINE_WIDTH = 328.dp

// Svislý střed pruhu od horního okraje DayTimeline: řádek popisku značky (10,5 sp × 1,35 = 14,2 dp), mezera 4 dp a půl pruhu
// (24 dp). Vzorek barvy se bere tady, tedy daleko od okrajů pruhu i od popisku.
private val BAR_CENTER = 30.dp

// Pruh z náhledu 4: noc do 7:00, den do 19:00, večer do 22:00 a znovu noc.
private val DEFAULT_STARTS = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0))

// Začátek noci posunutý „Teď“ na 10:05: den trvá od 7:00 do 10:05 a noc od 10:05 do 19:00.
private val AFTER_NOW_STARTS = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(10, 5))

// Pruh přes 24 h s úseky dne a značkou času Raspberry. Barvy se čtou ze snímku, protože pruh je kresba bez uzlů ve stromu.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class DayTimelineTest {
    @get:Rule
    val compose = createComposeRule()

    private var starts by mutableStateOf<DayStarts?>(DEFAULT_STARTS)
    private var now by mutableStateOf<LocalTime?>(LocalTime.of(10, 0, 3))
    private var stale by mutableStateOf(false)

    @Before
    fun showTimeline() {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(LightColorScheme.surface)) {
                    Box(Modifier.padding(16.dp).width(TIMELINE_WIDTH)) {
                        DayTimeline(starts, now, Modifier.testTag(TAG), stale)
                    }
                }
            }
        }
    }

    private fun update(change: () -> Unit) {
        change()
        compose.waitForIdle()
    }

    private val timeline: Rect get() = compose.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot

    private val barY: Float get() = timeline.top + BAR_CENTER.value * compose.density.density

    // Vodorovná poloha času na pruhu, který je široký přes celý den.
    private fun xAt(time: LocalTime): Float = timeline.left + timeline.width * time.toSecondOfDay() / 86_400f

    private fun assertBarColours(vararg expected: Pair<LocalTime, Color>) {
        val image = compose.renderScreenshot("pruh")
        expected.forEach { (time, colour) -> assertNear("pruh v $time", colour, image.colorAt(xAt(time), barY)) }
    }

    private val night = LightDomekColors.night
    private val day = LightDomekColors.day
    private val evening = LightDomekColors.evening

    // ----- Pruh -----

    @Test
    fun barIsColouredByThePartOfTheDayInTheMiddleOfEachSegment() {
        assertBarColours(
            LocalTime.of(3, 30) to night,
            LocalTime.of(13, 0) to day,
            LocalTime.of(20, 30) to evening,
            LocalTime.of(23, 0) to night,
        )
    }

    @Test
    fun segmentsChangeAtTheStarts() {
        assertBarColours(
            LocalTime.of(6, 40) to night,
            LocalTime.of(7, 20) to day,
            LocalTime.of(18, 40) to day,
            LocalTime.of(19, 20) to evening,
            LocalTime.of(21, 40) to evening,
            LocalTime.of(22, 20) to night,
        )
    }

    // Po „Teď“ u začátku noci v 10:05: noc trvá od 10:05 do 19:00, před ní je od 7:00 den a od půlnoci do 7:00 večer.
    @Test
    fun barShowsTheNightFromTheMomentTheStartWasMovedTo() {
        update { starts = AFTER_NOW_STARTS }

        assertBarColours(
            LocalTime.of(3, 30) to evening,
            LocalTime.of(8, 30) to day,
            LocalTime.of(9, 50) to day,
            LocalTime.of(10, 20) to night,
            LocalTime.of(14, 0) to night,
            LocalTime.of(18, 40) to night,
            LocalTime.of(19, 20) to evening,
            LocalTime.of(23, 30) to evening,
        )
    }

    @Test
    fun withoutStartsTheBarIsNeutral() {
        update { starts = null }

        assertBarColours(
            LocalTime.of(3, 30) to LightDomekColors.neutralContainer,
            LocalTime.of(13, 0) to LightDomekColors.neutralContainer,
            LocalTime.of(20, 30) to LightDomekColors.neutralContainer,
        )
    }

    @Test
    fun barIsDrawnAcrossTheWholeWidth() {
        val image = compose.renderScreenshot("pruh")

        // Hrany pruhu jsou zaoblené, proto se bere vzorek 3 dp od okraje, kde je už plná barva noci.
        val inset = 3 * compose.density.density
        assertNear("levý okraj", night, image.colorAt(timeline.left + inset, barY))
        assertNear("pravý okraj", night, image.colorAt(timeline.right - inset, barY))
    }

    // ----- Značka -----

    @Test
    fun markerStandsAtTheTimeOfTheRaspberryWithItsLabel() {
        val image = compose.renderScreenshot("pruh")
        val markerX = xAt(LocalTime.of(10, 0, 3))
        val side = 6 * compose.density.density

        assertNear("značka", LightColorScheme.onSurface, image.colorAt(markerX, barY))
        assertNear("pruh vlevo od značky", day, image.colorAt(markerX - side, barY))
        assertNear("pruh vpravo od značky", day, image.colorAt(markerX + side, barY))

        val label = compose.onNodeWithText("10:00").fetchSemanticsNode().boundsInRoot
        assertEquals("popisek je nad značkou", markerX, label.center.x, 1f)
        assertTrue("popisek je nad pruhem: $label", label.bottom <= barY - 12 * compose.density.density)
    }

    @Test
    fun markerFollowsTheTime() {
        update { now = LocalTime.of(22, 30) }

        val image = compose.renderScreenshot("pruh")
        assertNear("značka", LightColorScheme.onSurface, image.colorAt(xAt(LocalTime.of(22, 30)), barY))
        assertNear("v 10:00 už značka není", day, image.colorAt(xAt(LocalTime.of(10, 0, 3)), barY))
        compose.onNodeWithText("22:30").assertExists()
        compose.onNodeWithText("10:00").assertDoesNotExist()
    }

    // Popisek se nesmí dostat za okraj pruhu, ani když je značka úplně na kraji dne.
    @Test
    fun theLabelStaysAboveTheBarAtTheEdgesOfTheDay() {
        listOf(LocalTime.of(0, 0) to "00:00", LocalTime.of(23, 59, 59) to "23:59").forEach { (time, text) ->
            update { now = time }

            val label = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertTrue("popisek $text je v šířce pruhu: $label, pruh $timeline", label.left >= timeline.left - 0.5f && label.right <= timeline.right + 0.5f)
        }
    }

    @Test
    fun withoutTheTimeOfTheRaspberryThereIsNoMarkerAndNoLabel() {
        update { now = null }

        val image = compose.renderScreenshot("pruh")
        assertNear("pruh bez značky", day, image.colorAt(xAt(LocalTime.of(10, 0, 3)), barY))
        val timeTexts = compose.onAllNodes(SemanticsMatcher("čas HH:MM") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("""\d\d:\d\d""")) } == true
        })
        timeTexts.assertCountEquals(0)
    }

    @Test
    fun markerWithoutStartsStillShowsTheTimeOfTheRaspberry() {
        update { starts = null }

        val image = compose.renderScreenshot("pruh")
        assertNear("značka na neutrálním pruhu", LightColorScheme.onSurface, image.colorAt(xAt(LocalTime.of(10, 0, 3)), barY))
        compose.onNodeWithText("10:00").assertExists()
    }

    // Stará data (spec 6.5): značka zešedne, pruh ne, protože vychází z Nastavení.
    @Test
    fun staleMarkerIsDimmedButTheBarIsNot() {
        update { stale = true }

        val image = compose.renderScreenshot("pruh")
        val markerX = xAt(LocalTime.of(10, 0, 3))
        assertTrue("značka zešedla", !isNear(LightColorScheme.onSurface, image.colorAt(markerX, barY)))
        assertNear("pruh vedle značky zůstává", day, image.colorAt(markerX - 6 * compose.density.density, barY))
        assertNear("večer zůstává", evening, image.colorAt(xAt(LocalTime.of(20, 30)), barY))
    }

    // ----- Hodiny pod pruhem -----

    @Test
    fun hourLabelsStandUnderTheirHours() {
        val labels = listOf(0, 6, 12, 18, 24).associateWith { compose.onNodeWithText(it.toString()).fetchSemanticsNode().boundsInRoot }

        assertEquals("0 je u levého okraje", timeline.left, labels.getValue(0).left, 1f)
        assertEquals("24 je u pravého okraje", timeline.right, labels.getValue(24).right, 1f)
        listOf(6, 12, 18).forEach { hour ->
            assertEquals("$hour je pod svou hodinou", timeline.left + timeline.width * hour / 24f, labels.getValue(hour).center.x, 1f)
        }
        val barBottom = barY + 12 * compose.density.density
        labels.forEach { (hour, bounds) -> assertTrue("$hour je pod pruhem: $bounds", bounds.top >= barBottom) }
    }

    @Test
    fun hourLabelsDoNotDependOnTheData() {
        update {
            starts = null
            now = null
        }

        listOf(0, 6, 12, 18, 24).forEach { compose.onAllNodesWithText(it.toString()).assertCountEquals(1) }
    }
}
