package cz.vsb.mitr.domek.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.DayStarts
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.DomekTab
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import java.time.LocalTime
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Celá aplikace na záložce Nastavení, aby šel snímek porovnat s náhledem 4 i s horní a dolní lištou. Snímky jsou vysoké,
// aby byl vidět celý posouvaný obsah; jen „nastaveni-demo-telefon“ má velikost telefonu jako náhled.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        state: HouseUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        beforeShot: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) { DomekApp(state, NoActions, NoSystem, emptyFlow()) }
            }
        }
        compose.tab(DomekTab.SETTINGS).performClick()
        compose.title().assertTextEquals("Nastavení")
        beforeShot()
        compose.saveScreenshot(name)
    }

    // Texty a ovládání karty „Zdroj dat“ při Bluetooth připojeném k domku (zbytek obrazovky se řeší v jednotlivých testech).
    private fun assertBluetoothSource() {
        compose.onNodeWithText("Zdroj dat").assertIsDisplayed()
        compose.onNode(hasText("Raspberry přes Bluetooth") and hasText("dům „Termostat“")).assertIsSelected()
        compose.onNodeWithText("B8:27:EB:4A:19:C2 · signál -61${NBSP}dBm · MTU${NBSP}517").assertIsDisplayed()
        compose.onNode(hasText("Simulace") and hasText("vymyšlená data, funguje bez Raspberry")).assertIsDisplayed()
        compose.onNode(hasText("Odpojit") and hasClickAction()).assertIsEnabled()
    }

    // Náhled 4: den, značka 10:00 na pruhu, tři řádky s časy, hodiny Raspberry o 2 min 12 s pozadu (varování) a Bluetooth.
    @Test
    fun demo() {
        capture("nastaveni-demo", demoState())

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("teď je den").assertIsDisplayed()
        compose.onNodeWithText("10:00").assertIsDisplayed()
        listOf("0", "6", "12", "18", "24").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("Den od" to "07:00", "Večer od" to "19:00", "Noc od" to "22:00").forEach { (name, time) ->
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNode(hasText(time) and hasClickAction()).assertIsEnabled()
        }
        compose.onAllNodesWithText("Teď").assertCountEquals(3)
        compose.onNodeWithText("„Teď“ posune začátek na aktuální čas Raspberry. Původní časy pak vrátí tlačítko níž.")
            .assertIsDisplayed()
        compose.onNodeWithText("Vrátit původní časy").assertIsNotEnabled()

        compose.onNodeWithText("Čas Raspberry").assertIsDisplayed()
        compose.onNode(hasText("Raspberry") and hasText("20. 10. 2026 10:00:03")).assertIsDisplayed()
        compose.onNode(hasText("Telefon") and hasText("20. 10. 2026 10:02:15")).assertIsDisplayed()
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}2${NBSP}min 12${NBSP}s pozadu.").assertIsDisplayed()
        compose.onNodeWithText("Nastavit čas podle telefonu").assertIsEnabled()

        assertBluetoothSource()
    }

    // Přesně velikost telefonu z náhledu 4: Zdroj dat zajíždí pod dolní lištu a posouvá se.
    @Test
    @Config(qualifiers = PHONE)
    fun demoOnPhoneScreen() {
        capture("nastaveni-demo-telefon", demoState())

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("Čas Raspberry").assertIsDisplayed()
    }

    // Před prvními daty: místo časů „—“, ovládání denní doby a času je zakázané, Zdroj dat funguje.
    @Test
    fun withoutData() {
        val state = demoState().copy(
            received = null,
            settings = null,
            connection = ConnectionState.Scanning,
            chart = emptyList(),
            lastMotionAt = null,
        )
        capture("nastaveni-bez-dat", state)

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("teď je", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("—").assertCountEquals(4)
        compose.onAllNodesWithText("Teď").assertCountEquals(3)
        compose.onAllNodesWithText("Teď").fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodesWithText("Teď")[index].assertIsNotEnabled()
        }
        compose.onNodeWithText("Vrátit původní časy").assertIsNotEnabled()
        compose.onNodeWithText("Nastavit čas podle telefonu").assertIsNotEnabled()
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()

        compose.onNode(hasText("Raspberry přes Bluetooth") and hasText("dům „Termostat“")).assertIsSelected()
        compose.onNodeWithText("signál", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Odpojit") and hasClickAction()).assertIsEnabled()
    }

    // „Teď“ u začátku noci v 10:05 (čas Raspberry): pruh ukazuje noc od 10:05 do 19:00, původní časy jdou vrátit.
    @Test
    fun afterNow() {
        val originals = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0))
        val piTime = "2026-10-20T10:05:12"
        val state = demoState()
            .copy(settings = DEFAULTS.copy(nightStart = "10:05"), savedDayStarts = originals)
            .withStatus { it.copy(time = piTime, dayPartText = DayPart.NIGHT.json) }
            .withClocksInSync()
            .copy(phoneNow = DEMO_WALL.withMinute(5).withSecond(24))
        capture("nastaveni-po-ted", state)

        compose.onNodeWithText("teď je noc").assertIsDisplayed()
        compose.onNode(hasText("10:05") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText("Vrátit původní časy").assertIsEnabled()
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()
    }

    // Simulace: druhá volba je vybraná, řádek zařízení a tlačítko Odpojit patří jen k Bluetooth.
    @Test
    fun simulation() {
        val state = demoState().copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated)
        capture("nastaveni-simulace", state)

        compose.onNode(hasText("Simulace") and hasText("vymyšlená data, funguje bez Raspberry")).assertIsSelected()
        compose.onNodeWithText("signál", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Odpojit").assertDoesNotExist()
        compose.onNodeWithText("Připojit").assertDoesNotExist()
    }

    // Po ručním odpojení: Bluetooth zůstává vybrané a místo Odpojit je Připojit.
    @Test
    fun disconnected() {
        capture("nastaveni-odpojeno", demoState().copy(connection = ConnectionState.DisconnectedByUser))

        compose.onNode(hasText("Připojit") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText("Odpojit").assertDoesNotExist()
        compose.onNodeWithText("signál", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Vrátit původní časy").assertIsNotEnabled()
    }

    // Za 7 s od posledního Stavu: čas Raspberry, značka a varování zešednou, „Teď“ je zakázané, časy a zdroj dat ne.
    @Test
    fun staleData() {
        capture("nastaveni-stara-data", demoState().copy(nowMs = 307_000))

        compose.onNodeWithText("Stará data · 7${NBSP}s").assertIsDisplayed()
        compose.onAllNodesWithText("Teď").fetchSemanticsNodes().indices.forEach { index ->
            compose.onAllNodesWithText("Teď")[index].assertIsNotEnabled()
        }
        compose.onNode(hasText("07:00") and hasClickAction()).assertIsEnabled()
    }

    // Výběr času: dialog s ciferníkem nad obrazovkou.
    @Test
    fun timePicker() {
        capture("nastaveni-vyber-casu", demoState()) {
            compose.onNode(hasText("19:00") and hasClickAction()).performClick()
            compose.onNodeWithText("Začátek večera").assertIsDisplayed()
            compose.onNodeWithText("Uložit").assertIsDisplayed()
            compose.onNodeWithText("Zrušit").assertIsDisplayed()
        }
    }

    // Na šířku je obrazovka jen 384 dp vysoká, ciferník se tam musí vejít vedle času.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun timePickerLandscape() {
        capture("nastaveni-vyber-casu-sirka", demoState()) {
            compose.onNode(hasText("22:00") and hasClickAction()).performClick()
            compose.onNodeWithText("Začátek noci").assertIsDisplayed()
            compose.onNodeWithText("Uložit").assertIsDisplayed()
        }
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou.
    @Test
    fun dark() {
        capture("nastaveni-dark", demoState(), darkTheme = true)

        compose.onNodeWithText("teď je den").assertIsDisplayed()
    }

    // Při větším písmu v systému se řádky a popisky zalamují, nic se nesmí oříznout.
    @Test
    fun largeFont() {
        capture("nastaveni-velke-pismo", demoState(), fontScale = 1.5f)

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
    }

    // Na šířku je vlevo Denní doba a vpravo Čas Raspberry se Zdrojem dat.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun landscape() {
        capture("nastaveni-sirka", demoState())

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("Čas Raspberry").assertIsDisplayed()
    }
}
