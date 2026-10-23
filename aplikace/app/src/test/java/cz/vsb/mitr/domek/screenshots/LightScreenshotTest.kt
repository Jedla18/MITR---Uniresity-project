package cz.vsb.mitr.domek.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.textsBrokenInsideAWord
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.DomekTab
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Celá aplikace na záložce Světlo, aby šel snímek porovnat s náhledem 3 i s horní a dolní lištou. Snímky jsou vysoké, aby byl
// vidět celý posouvaný obsah; jen „svetlo-demo-telefon“ má velikost telefonu jako náhled.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class LightScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf(HouseUiState())

    private fun show(state: HouseUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        shown = state
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) { DomekApp(shown, NoActions, NoSystem, emptyFlow()) }
            }
        }
        compose.tab(DomekTab.LIGHT).performClick()
        compose.title().assertTextEquals("Světlo")
    }

    private fun capture(name: String, state: HouseUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        show(state, darkTheme, fontScale)
        compose.saveScreenshot(name)
    }

    private fun HouseUiState.withLight(light: LightState) = withStatus { it.copy(light = light) }

    // Uložená barva a jas v Nastavení (spec 3 v 2026-10-23-denik-a-vylepseni-design.md).
    private fun HouseUiState.withStoredLook(r: Int, g: Int, b: Int, brightness: Int) =
        copy(settings = checkNotNull(settings).copy(lightColor = listOf(r, g, b), lightBrightness = brightness))

    // Světlo svítí vždy uloženou barvou a jasem (docs/ble-rozhrani.md, Světlo v automatice), takže je Nastavení má taky.
    private fun HouseUiState.shining(r: Int, g: Int, b: Int, brightness: Int, manual: Boolean = true) =
        withLight(LightState(on = true, color = listOf(r, g, b), brightness = brightness, manual = manual)).withStoredLook(r, g, b, brightness)

    private val off = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = false)

    // Náhled 3: ruční modrá na 60 %, ruční volba platí do začátku večera, Raspberry umí rozsvěcení pohybem.
    @Test
    fun demo() {
        capture("svetlo-demo", demoStateWithoutClockWarning())

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Jas 60${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
        compose.onNodeWithText("Barva").assertIsDisplayed()
        compose.onNodeWithText("modrá").assertIsDisplayed()
        compose.onNodeWithText("Automatika").assertIsDisplayed()
        compose.onNodeWithText("teď venku 320${NBSP}lx").assertIsDisplayed()
    }

    // Přesně velikost telefonu z náhledu 3: spodní karta zajíždí pod dolní lištu a posouvá se.
    @Test
    @Config(qualifiers = PHONE)
    fun demoOnPhoneScreen() {
        capture("svetlo-demo-telefon", demoStateWithoutClockWarning())

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Barva").assertIsDisplayed()
    }

    // Automatika ve dne: světlo nesvítí, protože je venku dost světla. Šedý kruh a pod názvem stavu není podtitul. Karta ukazuje
    // uloženou modrou na 60 %, kterou se světlo příště rozsvítí.
    @Test
    fun autoDayOff() {
        capture("svetlo-auto-den-zhasnuto", demoStateWithoutClockWarning().withLight(off))

        compose.onNodeWithText("Zhasnuto · venku je dost světla").assertIsDisplayed()
        compose.onNodeWithText("modrá").assertIsDisplayed()
    }

    // Zhasnuté světlo s uloženou oranžovou na 40 %: karta ukazuje barvu i jas, náhled zůstává šedý a vysvětlivka říká, že se výběr
    // jen uloží.
    @Test
    fun offWithStoredColor() {
        capture("svetlo-zhasnuto-ulozena-barva", demoStateWithoutClockWarning().withLight(off).withStoredLook(255, 138, 61, 40))

        assertTheOffLightShowsTheStoredOrange()
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    @Test
    fun offWithStoredColorLargeFont() {
        val state = demoStateWithoutClockWarning().withLight(off).withStoredLook(255, 138, 61, 40)
        capture("svetlo-zhasnuto-ulozena-barva-velke-pismo", state, fontScale = 1.5f)

        assertTheOffLightShowsTheStoredOrange()
        assertEquals(emptyList<String>(), compose.cutOffTexts())
        assertEquals(emptyList<String>(), compose.textsBrokenInsideAWord())
    }

    private fun assertTheOffLightShowsTheStoredOrange() {
        compose.onNodeWithText("Zhasnuto · venku je dost světla").assertIsDisplayed()
        compose.onNodeWithText("oranžová").assertIsDisplayed()
        compose.onNodeWithText("40${NBSP}%").assertIsDisplayed()
        compose.onNodeWithText("Barva a jas se uloží.", substring = true).assertIsDisplayed()
    }

    // Raspberry nezná nocni_svetlo_pri_pohybu: přepínač je zašedlý a podtitul to vysvětluje.
    @Test
    fun unsupportedLightOnMotion() {
        val state = demoStateWithoutClockWarning()
        capture("svetlo-nepodporuje", state.copy(settings = checkNotNull(state.settings).copy(lightOnMotion = null)))

        compose.onNodeWithText("Raspberry tuto volbu zatím nepodporuje").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("v${NBSP}noci na chvíli rozsvítí noční světlo").assertDoesNotExist()
    }

    // „vlastní“ otevře dialog s odstínem a sytostí a s náhledem barvy, která je teď na světle.
    @Test
    fun colorDialog() {
        show(demoStateWithoutClockWarning())
        compose.onNode(hasContentDescription("vlastní")).performClick()
        compose.saveScreenshot("svetlo-dialog-barvy")

        compose.onNodeWithText("Vlastní barva").assertIsDisplayed()
        compose.onNodeWithText("Použít").assertIsDisplayed()
        compose.onNodeWithText("Zrušit").assertIsDisplayed()
    }

    // Barva, která není mezi předvolbami: kroužek je kolem „vlastní“ a nadpis říká „vlastní“.
    @Test
    fun customColor() {
        capture("svetlo-vlastni", demoStateWithoutClockWarning().shining(200, 40, 140, 80))

        compose.onNodeWithText("vlastní").assertIsDisplayed()
        compose.onNodeWithText("Jas 80${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
    }

    // Bílé světlo na bílé kartě by bez okraje a tmavé žárovky nebylo vidět.
    @Test
    fun whiteLight() {
        capture("svetlo-bila", demoStateWithoutClockWarning().shining(255, 255, 255, 100))

        compose.onNodeWithText("bílá").assertIsDisplayed()
    }

    // Automatika: večerní světlo, které si Raspberry rozsvítilo samo. Teplá žlutá není ruční, takže není „platí do“.
    @Test
    fun autoEveningOn() {
        val state = demoStateWithoutClockWarning()
            .withStatus { it.copy(dayPartText = DayPart.EVENING.json, time = "2026-10-20T19:30:00") }
            .shining(255, 170, 60, 60, manual = false)
        capture("svetlo-auto-vecer", state)

        compose.onNodeWithText("Svítí · večerní světlo").assertIsDisplayed()
        compose.onNodeWithText("Jas 60${NBSP}%").assertIsDisplayed()
    }

    // Ruční vypnutí: šedý kruh a podtitul s koncem ruční volby.
    @Test
    fun manualOff() {
        capture("svetlo-rucne-zhasnuto", demoStateWithoutClockWarning().withLight(off.copy(manual = true)))

        compose.onNodeWithText("Zhasnuto · ručně").assertIsDisplayed()
        compose.onNodeWithText("Ruční volba platí do 19:00").assertIsDisplayed()
    }

    // Za 7 s od posledního Stavu: náhled zešedne, ovládací prvky ne.
    @Test
    fun staleData() {
        capture("svetlo-stara-data", demoStateWithoutClockWarning().copy(nowMs = 307_000))

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Stará data · 7${NBSP}s").assertIsDisplayed()
    }

    // Dokud se Nastavení nepřečte, jsou všechny ovládací prvky zašedlé a ovladače ukazují „—“.
    @Test
    fun withoutSettings() {
        val state = demoStateWithoutClockWarning().copy(settings = null)
        capture("svetlo-bez-nastaveni", state)

        compose.onNode(hasText("Zapnout")).assertIsNotEnabled()
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou.
    @Test
    fun dark() {
        capture("svetlo-dark", demoStateWithoutClockWarning(), darkTheme = true)

        compose.onNodeWithText("Barva").assertIsDisplayed()
    }

    @Test
    fun darkWhiteLight() {
        capture("svetlo-dark-bila", demoStateWithoutClockWarning().shining(255, 255, 255, 100), darkTheme = true)

        compose.onNodeWithText("bílá").assertIsDisplayed()
    }

    @Test
    fun darkOff() {
        capture("svetlo-dark-zhasnuto", demoStateWithoutClockWarning().withLight(off), darkTheme = true)

        compose.onNodeWithText("Zhasnuto · venku je dost světla").assertIsDisplayed()
    }

    // Při větším písmu v systému se tlačítka řadí pod sebe a texty se zalamují, nic se nesmí oříznout.
    @Test
    fun largeFont() {
        capture("svetlo-velke-pismo", demoStateWithoutClockWarning(), fontScale = 1.5f)

        compose.onNodeWithText("Barva").assertIsDisplayed()
    }

    // Na šířku je vlevo náhled s tlačítky a vpravo barva, jas a automatika.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun landscape() {
        capture("svetlo-sirka", demoStateWithoutClockWarning())

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Barva").assertIsDisplayed()
    }

    // Dialog barvy na šířku: obsah dialogu se musí dát posunout, protože se nevejde.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun colorDialogLandscape() {
        show(demoStateWithoutClockWarning())
        compose.onNode(hasContentDescription("vlastní")).performClick()
        compose.saveScreenshot("svetlo-dialog-barvy-sirka")

        compose.onNodeWithText("Vlastní barva").assertIsDisplayed()
    }
}
