package cz.vsb.mitr.domek.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.screenshots.NoSystem
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.screenshots.PHONE_LANDSCAPE
import cz.vsb.mitr.domek.screenshots.WIDE_TALL
import cz.vsb.mitr.domek.screenshots.demoState
import cz.vsb.mitr.domek.testing.SettingsActions
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.title
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

private const val SHEET_TITLE = "V dosahu je víc domů"

private val TWO_DEVICES = listOf(
    FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61),
    FoundDevice("DC:A6:32:7F:03:1B", "Termostat", -78),
)

// Nastavení v celé aplikaci: záložka ukazuje obrazovku, její tlačítka volají akce ViewModelu a Zdroj dat funguje, i když
// aplikace čeká na výběr domku.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class SettingsTabTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = SettingsActions()
    private var appState by mutableStateOf(demoState())

    private fun showApp(state: HouseUiState = demoState()) {
        appState = state
        compose.setContent { DomekTheme(darkTheme = false) { DomekApp(appState, actions, NoSystem, emptyFlow()) } }
    }

    private fun updateState(state: HouseUiState) {
        appState = state
        compose.waitForIdle()
    }

    private fun openSettings() {
        compose.tab(DomekTab.SETTINGS).performClick()
        compose.title().assertTextEquals("Nastavení")
    }

    // Tlačítko je pod okrajem obrazovky, tak se k němu nejdřív dojede posouváním.
    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    private val closeSheet = hasClickAction() and hasContentDescription("Close sheet")

    @Test
    fun theSettingsTabShowsTheSettingsScreenNotAPlaceholder() {
        showApp()
        openSettings()

        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("Čas Raspberry").assertIsDisplayed()
        compose.onNodeWithText("Zdroj dat").assertExists()
    }

    // Obrazovka dostává akce ViewModelu, ne prázdné: tlačítka by jinak nic neudělala.
    @Test
    fun settingsButtonsReachTheViewModel() {
        showApp()
        openSettings()

        compose.onAllNodesWithText("Teď")[1].performClick()
        button("Nastavit čas podle telefonu").performClick()
        button("Odpojit").performClick()

        assertEquals(listOf("setDayStartNow(EVENING)", "syncClock", "disconnect"), actions.calls)
    }

    @Test
    fun theTimePickerOpensAndSavesThroughTheApp() {
        showApp()
        openSettings()

        compose.onNode(hasText("07:00") and hasClickAction()).performClick()
        compose.onNodeWithText("Začátek dne").assertIsDisplayed()
        compose.onNodeWithText("Zrušit").performClick()

        compose.onNodeWithText("Začátek dne").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
    }

    // Před prvními daty ukazují ostatní záložky stav spojení, Nastavení je přístupné vždy a funguje v něm Zdroj dat (spec 6.0).
    @Test
    fun theSourceCardWorksBeforeTheFirstData() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))
        openSettings()

        button("Odpojit").performClick()
        assertEquals(listOf("disconnect"), actions.calls)

        updateState(HouseUiState(connection = ConnectionState.DisconnectedByUser))
        button("Připojit").performClick()
        assertEquals(listOf("disconnect", "connect"), actions.calls)
    }

    // Spec 6.0 a 6.5: Zdroj dat funguje, i když aplikace čeká na výběr domku a nemá žádná data. Panel s výběrem se dá zavřít,
    // pak jde zdroj odpojit, a „Připojit“ spustí nové hledání, po kterém se panel vrátí, když najde víc domků.
    @Test
    fun theSourceCardStaysUsableWhileChoosingADeviceWithoutData() {
        showApp(HouseUiState(connection = ConnectionState.ChoosingDevice(TWO_DEVICES)))
        openSettings()
        compose.onNodeWithText(SHEET_TITLE).assertExists()

        compose.onNode(closeSheet).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
        button("Odpojit").performClick()
        assertEquals(listOf("disconnect"), actions.calls)

        // Po odpojení hledání skončí a panel zmizí.
        updateState(HouseUiState(connection = ConnectionState.DisconnectedByUser))
        compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
        button("Připojit").performClick()
        assertEquals(listOf("disconnect", "connect"), actions.calls)

        // Nové hledání najde zase víc domků: panel se vrátí a pod ním zůstává Nastavení. Titulek se nehledá, protože posouvání
        // ukryje nadpis karty tak, že ho pomocník title() bere za nejvyšší.
        updateState(HouseUiState(connection = ConnectionState.Scanning))
        updateState(HouseUiState(connection = ConnectionState.ChoosingDevice(TWO_DEVICES)))
        compose.onNodeWithText(SHEET_TITLE).assertExists()
        compose.tab(DomekTab.SETTINGS).assertIsSelected()
    }

    // Otočení telefonu v celé aplikaci: šířka přejde přes 600 dp, objeví se boční lišta a obrazovka se přepne z jednoho sloupce
    // na dva. Otevřený výběr času se po obnovení stavu musí ukázat u stejného úseku, na stejné záložce. Šířku mění obal kolem
    // aplikace, skutečné otočení ji mění konfigurací.
    @Test
    @Config(qualifiers = WIDE_TALL)
    fun theOpenPickerSurvivesARotationOfThePhoneInTheWholeApp() {
        val restoration = StateRestorationTester(compose)
        val width = mutableStateOf(384.dp)
        restoration.setContent {
            DomekTheme(darkTheme = false) {
                Box(Modifier.width(width.value).fillMaxHeight()) { DomekApp(appState, actions, NoSystem, emptyFlow()) }
            }
        }
        compose.tab(DomekTab.SETTINGS).performClick()
        compose.onNode(hasText("19:00") and hasClickAction()).performClick()
        compose.onNodeWithText("Začátek večera").assertIsDisplayed()
        assertEquals(
            "před otočením je jeden sloupec",
            compose.onNodeWithText("Denní doba").getBoundsInRoot().left,
            compose.onNodeWithText("Čas Raspberry").getBoundsInRoot().left,
        )

        width.value = 832.dp
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Začátek večera").assertIsDisplayed()
        compose.tab(DomekTab.SETTINGS).assertIsSelected()
        val dayTime = compose.onNodeWithText("Denní doba").getBoundsInRoot()
        val clock = compose.onNodeWithText("Čas Raspberry").getBoundsInRoot()
        assertTrue("po otočení jsou dva sloupce: $dayTime, $clock", clock.left > dayTime.right)
        assertEquals(emptyList<String>(), actions.calls)
    }

    // Na šířku (boční lišta) je Denní doba vlevo a Čas Raspberry vpravo od ní.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun theSettingsHaveTwoColumnsOnAWideScreen() {
        showApp()
        openSettings()

        val nowButton = compose.onAllNodesWithText("Teď")[0].getBoundsInRoot()
        val clock = compose.onNodeWithText("Čas Raspberry").getBoundsInRoot()
        assertTrue("čas je vpravo od denní doby: $nowButton, $clock", clock.left > nowButton.right)
        assertEquals(DayPart.entries.size, compose.onAllNodesWithText("Teď").fetchSemanticsNodes().size)
    }
}
