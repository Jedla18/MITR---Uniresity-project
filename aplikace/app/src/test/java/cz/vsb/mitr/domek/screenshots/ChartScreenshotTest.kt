package cz.vsb.mitr.domek.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.chart.TemperatureChartCard
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LightColorScheme
import cz.vsb.mitr.domek.ui.theme.LightDomekColors
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Poslední vzorek příběhu z náhledu a o 0,4 s pozdější tik UI (jako v demoState).
private const val DEMO_END_MS = 300_000L
private const val DEMO_NOW_MS = 300_400L

private const val STORY_MS = 300_000L
private const val TWENTY_MIN_NOW_MS = 1_200_400L
private const val TWO_HOURS_NOW_MS = 7_200_400L

// Graf na Přehledu. Karta stojí na pozadí stránky s okrajem 12 dp, jako na náhledu, aby šla porovnat s grafem z něj.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ChartScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        samples: List<ChartSample>,
        window: ChartWindow = ChartWindow.ALL,
        nowMs: Long = DEMO_NOW_MS,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        TemperatureChartCard(samples, window, nowMs, onWindowChange = {})
                    }
                }
            }
        }
        compose.saveScreenshot(name)
    }

    // Náhled 1: pět minut příběhu, okno Vše. Topení, chlazení, schody cíle a šedá venkovní čára.
    @Test
    fun chartDemo() {
        capture("graf-demo", demoChart(DEMO_END_MS, DEMO_WALL))

        // Snímky se s ničím nesrovnávají sami, proto se aspoň hlídá, že je v grafu všechno z náhledu. Jen v grafu, protože
        // legenda má stejné barvy.
        val image = compose.chartPixels("graf-demo")
        mapOf(
            "vnitřní čára nebo tečka" to LightColorScheme.primary,
            "venkovní čára" to LightDomekColors.chartOutside,
            "cíl" to LightDomekColors.chartTarget,
            "pruh topení" to LightDomekColors.heatContainer,
            "pruh chlazení" to LightDomekColors.coolContainer,
            "mřížka" to LightColorScheme.outlineVariant,
        ).forEach { (what, color) -> assertTrue("na snímku chybí $what", image.contains(color)) }
        // Koncová tečka je vpravo, vlevo může být barva vnitřní teploty jen z čáry.
        val leftThird = image.getSubimage(0, 0, image.width / 3, image.height)
        assertTrue("na snímku chybí vnitřní čára", leftThird.contains(LightColorScheme.primary))
    }

    @Test
    fun chartEmpty() = capture("graf-prazdny", emptyList())

    // Review Focus 5: čidla bez hodnot graf nesmí shodit, místo něj je text.
    @Test
    fun chartAllNull() {
        val samples = (0 until 60).map { index ->
            ChartSample(
                atMs = DEMO_END_MS - (59 - index) * 1_000L,
                wallTime = DEMO_WALL.minusSeconds(59L - index),
                inside = null,
                outside = null,
                target = null,
                heating = false,
                cooling = false,
            )
        }
        capture("graf-null", samples)
        compose.onNodeWithText("Graf se začne kreslit s${NBSP}prvními daty.").assertIsDisplayed()
    }

    // Vnitřní čidlo po celou dobu nehlásí, venkovní teplota a cíl ano: graf kreslí zbytek a pruhy.
    @Test
    fun chartWithoutInsideSensor() =
        capture("graf-bez-vnitrni", demoChart(DEMO_END_MS, DEMO_WALL).map { it.copy(inside = null) })

    // První Stav po připojení: jediný bod, takže osa X má nulové rozpětí.
    @Test
    fun chartSingleSample() = capture("graf-jeden-vzorek", listOf(demoChart(DEMO_END_MS, DEMO_WALL).last()), nowMs = DEMO_END_MS + 100)

    @Test
    fun chartDark() = capture("graf-dark", demoChart(DEMO_END_MS, DEMO_WALL), darkTheme = true)

    // Při větším písmu v systému rostou popisky, graf jim musí udělat místo, ne je uříznout.
    @Test
    fun chartLargeFont() = capture("graf-velke-pismo", demoChart(DEMO_END_MS, DEMO_WALL), fontScale = 1.6f)

    // Dvacet minut: příběh z náhledu čtyřikrát za sebou, takže okna 5 a 15 minut ukážou různý počet opakování.
    @Test
    fun chartFiveMinuteWindow() = capture("graf-5min", twentyMinutes(), ChartWindow.FIVE_MIN, nowMs = TWENTY_MIN_NOW_MS)

    @Test
    fun chartFifteenMinuteWindow() = capture("graf-15min", twentyMinutes(), ChartWindow.FIFTEEN_MIN, nowMs = TWENTY_MIN_NOW_MS)

    // Historie je kratší než okno: čára začíná až v pravé třetině.
    @Test
    fun chartShortHistoryInLongWindow() = capture("graf-15min-kratka-historie", demoChart(DEMO_END_MS, DEMO_WALL), ChartWindow.FIFTEEN_MIN)

    // Dvě hodiny po 1 s: čáry musí zůstat souvislé i po zředění na dva body na pixel.
    @Test
    fun chartTwoHours() = capture("graf-2h", twoHours(), nowMs = TWO_HOURS_NOW_MS)

    @Test
    fun chartGaps() = capture("graf-mezery", withGaps())
}

// Výřez snímku, který zabírá Canvas grafu. Najde se podle popisu pro čtečku obrazovky.
private fun ComposeContentTestRule.chartPixels(name: String): BufferedImage {
    val bounds = onNode(hasContentDescription("Graf teplot", substring = true)).fetchSemanticsNode().boundsInRoot
    return renderScreenshot(name).getSubimage(
        bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt(),
    )
}

private fun BufferedImage.contains(color: Color): Boolean {
    val argb = color.toArgb()
    return (0 until height).any { y -> (0 until width).any { x -> getRGB(x, y) == argb } }
}

private fun twentyMinutes(): List<ChartSample> = (3 downTo 0).flatMap { storiesAgo ->
    demoChart(TWENTY_MIN_NOW_MS - 400 - storiesAgo * STORY_MS, DEMO_WALL.minusMinutes(5L * storiesAgo))
}

// Cíl 21 → 23 → 22 °C. Vnitřní teplota ho následuje s prodlevou a topení nebo chlazení se spíná s hysterezí, takže vzniká
// několik pruhů. Venkovní teplota pomalu kolísá o 4 °C, aby osa Y měla rozpětí přes 6 °C a popisky po 2 °C.
private fun twoHours(): List<ChartSample> {
    val random = Random(7)
    var inside = 21.0
    var heating = false
    var cooling = false
    return (0 until 7_200).map { second ->
        val target = when {
            second < 2_400 -> 21.0
            second < 4_800 -> 23.0
            else -> 22.0
        }
        heating = if (heating) inside < target + 0.2 else inside < target - 0.3
        cooling = if (cooling) inside > target - 0.2 else inside > target + 0.3
        inside += when {
            heating -> 0.004
            cooling -> -0.004
            else -> -0.0006
        } + random.nextDouble(-0.01, 0.01)
        ChartSample(
            atMs = TWO_HOURS_NOW_MS - 400 - (7_199L - second) * 1_000,
            wallTime = DEMO_WALL.minusSeconds(7_199L - second),
            inside = inside,
            outside = 15.0 + 2 * sin(second / 1_800.0),
            target = target,
            heating = heating,
            cooling = cooling,
        )
    }
}

// Příběh z náhledu s výpadkem dat (22 s uprostřed topení), chybou vnitřního čidla (10 s) a bez venkovního čidla (40 s).
private fun withGaps(): List<ChartSample> = demoChart(DEMO_END_MS, DEMO_WALL)
    .filterNot { it.atMs in 90_000L..110_000L }
    .map { sample ->
        when (sample.atMs) {
            in 200_000L..209_000L -> sample.copy(inside = null)
            in 230_000L..269_000L -> sample.copy(outside = null)
            else -> sample
        }
    }
