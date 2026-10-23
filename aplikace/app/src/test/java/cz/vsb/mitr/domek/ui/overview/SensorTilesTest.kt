package cz.vsb.mitr.domek.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.demoStateWithoutClockWarning
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Kolik dlaždic je v řadě podle šířky, kterou mají k dispozici, a podle velikosti písma. Dlaždice stojí samy v okně širokém
// 1000 dp a test jim šířku nastavuje, protože na široké obrazovce jsou sloupce mnohem užší než telefon (na tabletu na výšku je
// levý sloupec 318 dp). Písmo je skutečné (NATIVE), protože šířka „1013 hPa“ je to, o co tu jde.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-xxhdpi")
class SensorTilesTest {
    @get:Rule
    val compose = createComposeRule()

    private var tilesWidth by mutableStateOf(360.dp)
    private var scale by mutableStateOf(1f)
    private var smallTiles by mutableStateOf(false)

    private fun set(width: Dp, fontScale: Float, compact: Boolean) {
        tilesWidth = width
        scale = fontScale
        smallTiles = compact
    }

    private fun show(width: Dp, fontScale: Float = 1f, compact: Boolean = false) {
        set(width, fontScale, compact)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                DomekTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SensorTiles(
                            state = demoStateWithoutClockWarning(),
                            onOpenLight = {},
                            modifier = Modifier.width(tilesWidth),
                            compact = smallTiles,
                        )
                    }
                }
            }
        }
    }

    private fun change(width: Dp, fontScale: Float, compact: Boolean) {
        set(width, fontScale, compact)
        compose.waitForIdle()
    }

    private fun tile(label: String, value: String) = compose.onNode(hasText(label) and hasText(value))

    // Počet dlaždic první řady: dlaždice, které leží vedle první, začínají ve stejné výšce.
    private fun columns(): Int {
        val tops = listOf("Venku" to "21,8${NBSP}°C", "Vlhkost" to "45${NBSP}%", "Tlak" to "1013${NBSP}hPa")
            .map { (label, value) -> tile(label, value).getBoundsInRoot().top }
        return tops.count { it == tops.first() }
    }

    @Test
    fun threeColumnsFitAPhoneInPortrait() {
        show(360.dp)

        assertEquals(3, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Levý sloupec tabletu Galaxy Tab A8 na výšku je 318 dp široký a „1013 hPa“ se do třetiny z něj ještě vejde.
    @Test
    fun threeColumnsFitTheLeftColumnOfATabletInPortrait() {
        show(318.dp)

        assertEquals(3, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Dlaždice na telefonu na šířku jsou menší a levý sloupec je 333 dp široký, takže se vejdou po třech.
    @Test
    fun threeCompactColumnsFitTheLeftColumnOfAPhoneInLandscape() {
        show(333.dp, compact = true)

        assertEquals(3, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // V užším sloupci by se „1013 hPa“ do třetiny nevešlo, tak jsou dlaždice po dvou, i když je písmo normální.
    @Test
    fun aNarrowerColumnHasTwoColumnsEvenAtTheNormalFontSize() {
        show(290.dp)

        assertEquals(2, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    @Test
    fun theCompactTilesAreNarrowerSoThreeOfThemFitWhereTheRegularOnesDoNot() {
        show(290.dp, compact = true)

        assertEquals(3, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    @Test
    fun aLargeFontSizeNeedsTwoColumnsOnAPhoneInPortrait() {
        show(360.dp, fontScale = 1.5f)

        assertEquals(2, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Kdyby se hodnota nevešla ani do poloviny, je každá dlaždice na vlastním řádku.
    @Test
    fun aVeryNarrowColumnHasOneTile() {
        show(190.dp)

        assertEquals(1, columns())
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Hodnota dlaždice se nesmí zkrátit při žádné šířce ani velikosti písma, ať se dlaždice řadí po jedné, po dvou nebo po třech.
    @Test
    fun noValueIsCutOffAtAnyWidthOrFontSize() {
        show(360.dp)
        val cutOff = buildList {
            listOf(false, true).forEach { compact ->
                listOf(1f, 1.15f, 1.3f, 1.5f, 2f).forEach { fontScale ->
                    listOf(200, 225, 260, 290, 318, 333, 360, 400, 520, 720).forEach { width ->
                        change(width.dp, fontScale, compact)
                        compose.cutOffTexts().forEach { add("$it (šířka $width dp, písmo $fontScale×, compact $compact)") }
                    }
                }
            }
        }

        assertEquals(emptyList<String>(), cutOff)
    }
}
