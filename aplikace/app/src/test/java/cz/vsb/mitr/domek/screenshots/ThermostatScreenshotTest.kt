package cz.vsb.mitr.domek.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.DomekTab
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Ukazatel odesílání se točí donekonečna, proto se hodiny testu zastaví v půli otáčky.
private const val ANIMATION_MS = 400L

// Celá aplikace na záložce Termostat, aby šel snímek porovnat s náhledem 2 i s horní a dolní lištou. Snímky jsou vysoké, aby
// byl vidět celý posouvaný obsah; jen „termostat-demo-telefon“ má velikost telefonu jako náhled.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class ThermostatScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf(HouseUiState())

    private fun capture(
        name: String,
        state: HouseUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        animated: Boolean = false,
    ) {
        // Odesílání se doplní až po přepnutí záložky: na Přehledu se točí ukazatel cíle, který platí, a hodiny testu se
        // zastavují až na Termostatu, kde se mají ukazatele zastavit v půli otáčky.
        shown = if (animated) state.copy(pendingKeys = emptySet()) else state
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) { DomekApp(shown, NoActions, NoSystem, emptyFlow()) }
            }
        }
        compose.tab(DomekTab.THERMOSTAT).performClick()
        compose.title().assertTextEquals("Termostat")
        if (animated) {
            shown = state
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(ANIMATION_MS)
        }
        compose.saveScreenshot(name)
    }

    private val isSegment = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    // Režim, který Raspberry hlásí ve Stavu i v Nastavení (ruční cíl 22,0 °C platí).
    private fun HouseUiState.inMode(mode: ThermostatMode, target: Double?): HouseUiState =
        copy(settings = checkNotNull(settings).copy(mode = mode)).withStatus { it.copy(mode = mode, target = target) }

    // Náhled 2: topí, režim Auto, ve dne platí denní cíl, doma je někdo (poslední pohyb před 40 s). Pod hodnotami v souhrnu je
    // řádek s prahy: topení pod cílem 23,0 °C o 0,3 °C, chlazení nad ním o 1,0 °C.
    @Test
    fun demo() {
        capture("termostat-demo", demoStateWithoutClockWarning())

        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Cílové teploty").assertIsDisplayed()
        compose.onNodeWithText("Nikdo doma").assertIsDisplayed()
        compose.onNodeWithText("Topí").assertIsDisplayed()
        compose.onNodeWithText("Topení naskočí pod 22,7${NBSP}°C, chlazení nad 24,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("platí teď").assertIsDisplayed()
        compose.onNodeWithText("Doma je někdo · poslední pohyb před 40${NBSP}s").assertIsDisplayed()
        compose.onNodeWithText("30${NBSP}min").assertIsDisplayed()
    }

    // Přesně velikost telefonu z náhledu 2: spodní karta zajíždí pod dolní lištu a posouvá se.
    @Test
    @Config(qualifiers = PHONE)
    fun demoOnPhoneScreen() {
        capture("termostat-demo-telefon", demoStateWithoutClockWarning())

        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Cílové teploty").assertIsDisplayed()
    }

    // Nastavení mode = MANUAL: zvýrazněný je ruční řádek a vysvětlivka říká, že cíl platí bez ohledu na denní dobu.
    @Test
    fun manual() {
        capture("termostat-rucni", demoStateWithoutClockWarning().inMode(ThermostatMode.MANUAL, target = 22.0))

        compose.onNodeWithText("Drží ruční cíl bez ohledu na denní dobu.").assertIsDisplayed()
        compose.onAllNodesWithText("Ruční").assertCountEquals(2)
        compose.onNode(hasText("Ruční") and isSegment).assertIsSelected()
        compose.onNodeWithText("platí teď").assertIsDisplayed()
    }

    // Nikdo není doma: stavový řádek říká, o kolik je cíl snížený, a platný cíl v souhrnu je nižší.
    @Test
    fun nobodyHome() {
        val state = demoStateWithoutClockWarning().withStatus { it.copy(nobodyHome = true, target = 20.0) }
        capture("termostat-nikdo-doma", state)

        compose.onNodeWithText("Nikdo doma · cíl je snížený o${NBSP}3,0${NBSP}°C").assertIsDisplayed()
        compose.onNode(hasText("Platný cíl") and hasText("20,0${NBSP}°C")).assertIsDisplayed()
        compose.onNodeWithText("Topení naskočí pod 19,7${NBSP}°C, chlazení nad 21,0${NBSP}°C").assertIsDisplayed()
    }

    // Uživatel právě klepl na „Ruční“ a zápis ještě neskončil: volba i zvýrazněný řádek se změnily hned, souhrn ještě ne.
    @Test
    fun manualBeingWritten() {
        val state = demoStateWithoutClockWarning().copy(
            drafts = mapOf(SettingKey.MODE to JsonPrimitive(ThermostatMode.MANUAL.json)),
            pendingKeys = setOf(SettingKey.MODE),
        )
        capture("termostat-rucni-odesilani", state)

        compose.onNodeWithText("Drží ruční cíl bez ohledu na denní dobu.").assertIsDisplayed()
        compose.onNode(hasText("Platný cíl") and hasText("23,0${NBSP}°C")).assertIsDisplayed()
    }

    // Noc: platí noční cíl a vysvětlivka to říká.
    @Test
    fun night() {
        val state = demoStateWithoutClockWarning()
            .withStatus { it.copy(dayPartText = DayPart.NIGHT.json, time = "2026-10-20T22:10:00", target = 20.0) }
        capture("termostat-noc", state)

        compose.onNodeWithText("Ve dne a${NBSP}večer drží denní cíl, v${NBSP}noci noční. Teď je noc, takže platí noční.")
            .assertIsDisplayed()
        compose.onNodeWithText("platí teď").assertIsDisplayed()
    }

    @Test
    fun off() {
        capture("termostat-vypnuto", demoStateWithoutClockWarning().inMode(ThermostatMode.OFF, target = null).withStatus {
            it.copy(heating = false)
        })

        compose.onNodeWithText("Termostat netopí ani nechladí.").assertIsDisplayed()
        compose.onNodeWithText("platí teď").assertDoesNotExist()
        compose.onNode(hasText("Platný cíl") and hasText("—")).assertIsDisplayed()
        // Bez cíle nejsou ani prahy.
        compose.onNodeWithText("naskočí pod", substring = true).assertDoesNotExist()
    }

    // Cíl je dosažený: štítek „V klidu“ a pohyb nebyl zatím žádný.
    @Test
    fun idleWithoutAnyMotionYet() {
        val state = demoStateWithoutClockWarning().withStatus { it.copy(heating = false, insideTemp = 23.0) }.copy(lastMotionAt = null)
        capture("termostat-klid", state)

        compose.onNodeWithText("V${NBSP}klidu").assertIsDisplayed()
        compose.onNodeWithText("Pohyb zatím nezaznamenán").assertIsDisplayed()
    }

    // Každé klepnutí na −/+ hned změní číslo a vedle něj se otáčí ukazatel. Číslo ani tlačítka se přitom nehýbou.
    @Test
    fun targetsBeingWritten() {
        val state = demoStateWithoutClockWarning().copy(
            drafts = mapOf(
                SettingKey.TARGET_DAY to JsonPrimitive(23.5),
                SettingKey.NOBODY_HOME_AFTER to JsonPrimitive(31),
            ),
            pendingKeys = setOf(SettingKey.TARGET_DAY, SettingKey.NOBODY_HOME_AFTER),
        )
        capture("termostat-odesilani", state, animated = true)

        compose.onNodeWithText("23,5${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("31${NBSP}min").assertIsDisplayed()
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(2)
    }

    // Za 7 s od posledního Stavu: souhrn (i s řádkem prahů) a stavový řádek zešednou, ovládací prvky ne.
    @Test
    fun staleData() {
        capture("termostat-stara-data", demoStateWithoutClockWarning().copy(nowMs = 307_000))

        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Stará data · 7${NBSP}s").assertIsDisplayed()
        compose.onNodeWithText("Topení naskočí pod 22,7${NBSP}°C, chlazení nad 24,0${NBSP}°C").assertIsDisplayed()
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou.
    @Test
    fun dark() {
        capture("termostat-dark", demoStateWithoutClockWarning(), darkTheme = true)

        compose.onNodeWithText("platí teď").assertIsDisplayed()
    }

    // Při větším písmu v systému se popisky řádků zalamují a štítek stavu přejde pod teploty, nic se nesmí oříznout.
    @Test
    fun largeFont() {
        capture("termostat-velke-pismo", demoStateWithoutClockWarning(), fontScale = 1.5f)

        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Topení naskočí pod 22,7${NBSP}°C, chlazení nad 24,0${NBSP}°C").assertIsDisplayed()
    }

    // Na šířku je vlevo souhrn s režimem a vpravo cíle s „nikdo doma“.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun landscape() {
        capture("termostat-sirka", demoStateWithoutClockWarning())

        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Cílové teploty").assertIsDisplayed()
    }
}
