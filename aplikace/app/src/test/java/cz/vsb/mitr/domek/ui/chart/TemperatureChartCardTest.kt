package cz.vsb.mitr.domek.ui.chart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.screenshots.DEMO_WALL
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.screenshots.demoChart
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val EMPTY_TEXT = "Graf se začne kreslit s${NBSP}prvními daty."

// Skutečná písma a hustota telefonu: s nimi má přepínač rozměry z náhledu, bez nich by se dotyková plocha nedala ověřit.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TemperatureChartCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val reported = mutableListOf<ChartWindow>()
    private val demo = demoChart(endMs = 300_000, endWall = DEMO_WALL)

    private fun show(samples: List<ChartSample>, window: ChartWindow = ChartWindow.ALL, nowMs: Long = 300_400) {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                TemperatureChartCard(samples, window, nowMs, onWindowChange = { reported += it })
            }
        }
    }

    @Test
    fun showsTitleWindowSelectorAndLegend() {
        show(demo)

        compose.onNodeWithText("Teploty").assertIsDisplayed()
        ChartWindow.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        listOf("Uvnitř", "Venku", "Cíl", "Topí", "Chladí").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun marksTheCurrentWindowAsSelected() {
        show(demo, ChartWindow.FIFTEEN_MIN)

        compose.onNodeWithText(ChartWindow.FIFTEEN_MIN.label).assertIsSelected()
        compose.onNodeWithText(ChartWindow.FIVE_MIN.label).assertIsNotSelected()
        compose.onNodeWithText(ChartWindow.ALL.label).assertIsNotSelected()
    }

    @Test
    fun clickingAWindowReportsIt() {
        show(demo)

        compose.onNodeWithText(ChartWindow.FIVE_MIN.label).performClick()
        compose.onNodeWithText(ChartWindow.FIFTEEN_MIN.label).performClick()
        compose.onNodeWithText(ChartWindow.ALL.label).performClick()

        assertEquals(listOf(ChartWindow.FIVE_MIN, ChartWindow.FIFTEEN_MIN, ChartWindow.ALL), reported)
    }

    // Okno drží volající (ViewModel). Po jeho změně se musí přepočítat, co se kreslí: data jsou 10 minut stará, takže ve
    // Vše je graf a v 5 min už ne.
    @Test
    fun changingTheWindowRecomputesWhatIsDrawn() {
        compose.setContent {
            var window by remember { mutableStateOf(ChartWindow.ALL) }
            DomekTheme(darkTheme = false) {
                TemperatureChartCard(demo, window, nowMs = 900_000, onWindowChange = { window = it })
            }
        }
        compose.onNodeWithText(EMPTY_TEXT).assertDoesNotExist()

        compose.onNodeWithText(ChartWindow.FIVE_MIN.label).performClick()
        compose.onNodeWithText(ChartWindow.FIVE_MIN.label).assertIsSelected()
        compose.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()

        compose.onNodeWithText(ChartWindow.ALL.label).performClick()
        compose.onNodeWithText(EMPTY_TEXT).assertDoesNotExist()
    }

    // Přepínač je nízký jako na náhledu (26 dp), ale prst ho musí trefit i kousek pod ním: dotyková plocha má 48 dp.
    @Test
    fun windowSegmentsAreEasyToHitJustOutsideTheirVisibleBounds() {
        show(demo)
        val fiveMin = compose.onNodeWithText(ChartWindow.FIVE_MIN.label).assertHeightIsEqualTo(26.dp)

        fiveMin.performTouchInput { click(Offset(center.x, height + 6.dp.toPx())) }
        assertEquals(listOf(ChartWindow.FIVE_MIN), reported)

        fiveMin.performTouchInput { click(Offset(center.x, height + 30.dp.toPx())) }
        assertEquals("dotyk daleko pod segmentem nic nepřepíná", listOf(ChartWindow.FIVE_MIN), reported)
    }

    @Test
    fun withoutSamplesTheEmptyTextReplacesTheChart() {
        show(emptyList())

        compose.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()
        compose.onNodeWithText("Teploty").assertIsDisplayed()
        compose.onNodeWithText(ChartWindow.ALL.label).assertIsDisplayed()
    }

    @Test
    fun onlyNullValuesShowTheEmptyTextToo() {
        val allNull = demo.map { it.copy(inside = null, outside = null, target = null) }
        show(allNull)

        compose.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()
    }

    @Test
    fun windowWithoutSamplesShowsTheEmptyText() {
        show(demo, ChartWindow.FIVE_MIN, nowMs = 900_000)

        compose.onNodeWithText(EMPTY_TEXT).assertIsDisplayed()
    }

    @Test
    fun withDataThereIsAChartDescribedByItsLatestValues() {
        show(demo)

        compose.onNodeWithText(EMPTY_TEXT).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Graf teplot: uvnitř ${formatTemp(22.4)}, venku ${formatTemp(21.8)}, cíl ${formatTemp(23.0)}",
        ).assertIsDisplayed()
    }
}
