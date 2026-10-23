package cz.vsb.mitr.domek.ui.connection

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.testing.RecordingActions
import cz.vsb.mitr.domek.testing.RecordingSystem
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SIMULATION = "Spustit simulaci"
private const val SEARCH_TITLE = "Hledám dům „Termostat“"
private const val SEARCH_TEXT = "Raspberry musí být zapnuté a v dosahu. Párování není potřeba."
private const val CONNECTING_TITLE = "Připojuji se k domu…"
private const val PERMISSIONS_TITLE = "Aplikace potřebuje přístup k Bluetooth"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class ConnectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private val system = RecordingSystem()
    private var state by mutableStateOf(HouseUiState())
    private var permissionsPermanentlyDenied by mutableStateOf(false)
    private var fontScale by mutableFloatStateOf(1f)

    @Before
    fun showScreen() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme = false) { ConnectionScreen(state, actions, system.actions(permissionsPermanentlyDenied)) }
            }
        }
    }

    private fun show(connection: ConnectionState, nowMs: Long = 0, permanentlyDenied: Boolean = false) {
        state = HouseUiState(connection = connection, nowMs = nowMs)
        permissionsPermanentlyDenied = permanentlyDenied
        compose.waitForIdle()
    }

    private fun textOf(node: SemanticsNode): String = node.config[SemanticsProperties.Text].joinToString("") { it.text }

    // Texty obrazovky bez tlačítek, shora dolů. Tlačítko slučuje svůj text do jednoho uzlu s akcí klepnutí.
    private fun shownTexts(): List<String> =
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
        ).fetchSemanticsNodes().map(::textOf)

    private fun buttons(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map(::textOf)

    private class Case(
        val connection: ConnectionState,
        val texts: List<String>,
        val buttons: List<String>,
        val permanentlyDenied: Boolean = false,
    ) {
        override fun toString() = "$connection${if (permanentlyDenied) " (trvale zamítnuto)" else ""}"
    }

    private val searching = Case(ConnectionState.Scanning, listOf(SEARCH_TITLE, SEARCH_TEXT), listOf(SIMULATION))

    // Tabulka stavů ze spec 6.5 včetně přesných textů. Kromě „Telefon nemá Bluetooth LE“ má každý stav i „Spustit simulaci“.
    private val cases = listOf(
        searching,
        Case(ConnectionState.Idle, searching.texts, searching.buttons),
        Case(ConnectionState.ChoosingDevice(listOf(FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61))), searching.texts, searching.buttons),
        Case(ConnectionState.Connecting(attempt = 2), listOf(CONNECTING_TITLE, "Pokus č. 2"), listOf(SIMULATION)),
        Case(ConnectionState.Preparing, listOf(CONNECTING_TITLE), listOf(SIMULATION)),
        Case(
            ConnectionState.WaitingToReconnect(attempt = 3, retryAtMs = 4_000),
            listOf("Spojení se přerušilo", "Další pokus za 4${NBSP}s."),
            listOf(SIMULATION),
        ),
        Case(ConnectionState.DisconnectedByUser, listOf("Odpojeno"), listOf("Připojit", SIMULATION)),
        Case(
            ConnectionState.PermissionsMissing,
            listOf(PERMISSIONS_TITLE, "Bez něj nenajde dům v okolí."),
            listOf("Povolit", SIMULATION),
        ),
        Case(
            ConnectionState.PermissionsMissing,
            listOf(PERMISSIONS_TITLE, "Oprávnění je zamítnuté. Povol ho v${NBSP}nastavení aplikace."),
            listOf("Otevřít nastavení", SIMULATION),
            permanentlyDenied = true,
        ),
        Case(ConnectionState.BluetoothOff, listOf("Bluetooth je vypnutý"), listOf("Zapnout Bluetooth", SIMULATION)),
        Case(
            ConnectionState.LocationOff,
            listOf("Zapni polohu", "Android 11 a starší potřebuje zapnutou polohu, aby našel Bluetooth zařízení."),
            listOf("Otevřít nastavení polohy", SIMULATION),
        ),
        Case(
            ConnectionState.NoBluetooth,
            listOf("Telefon nemá Bluetooth LE", "Aplikace může běžet jen v simulaci."),
            listOf(SIMULATION),
        ),
        Case(
            ConnectionState.MissingService,
            listOf("Tohle zařízení není chytrý dům", "Zařízení „Termostat“ nemá službu chytrého domu. Zkontroluj program na Raspberry."),
            listOf("Zkusit znovu", SIMULATION),
        ),
    )

    @Test
    fun everyStateShowsExactlyItsTextsAndButtons() {
        cases.forEach { case ->
            show(case.connection, nowMs = 0, permanentlyDenied = case.permanentlyDenied)
            assertEquals("texty ve stavu $case", case.texts, shownTexts())
            assertEquals("tlačítka ve stavu $case", case.buttons, buttons())
        }
    }

    // Connected a Simulated bez dat trvají jen okamžik před prvním Stavem. Obrazovka v nich nesmí spadnout a čeká na Stav.
    @Test
    fun connectedAndSimulatedWithoutDataWaitForTheFirstStatus() {
        listOf(ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, rssi = null), ConnectionState.Simulated).forEach { connection ->
            show(connection)
            assertEquals("texty ve stavu $connection", listOf(CONNECTING_TITLE), shownTexts())
        }
    }

    @Test
    fun titleIsAHeading() {
        show(ConnectionState.DisconnectedByUser)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Odpojeno")).assertExists()
    }

    @Test
    fun simulationButtonStartsTheSimulationInEveryState() {
        cases.forEach { case ->
            actions.calls.clear()
            show(case.connection, permanentlyDenied = case.permanentlyDenied)

            compose.onNodeWithText(SIMULATION).performClick()

            assertEquals("po klepnutí ve stavu $case", listOf("selectSource(SIMULATION)"), actions.calls)
            assertEquals("systémové akce ve stavu $case", emptyList<String>(), system.calls)
        }
    }

    // Co udělá hlavní tlačítko stavu. Aktivita za systémovými akcemi nic nehlídá, proto je každé tlačítko jen ve svém stavu.
    @Test
    fun mainButtonsCallTheirOwnAction() {
        class Click(val case: Case, val label: String, val actionCalls: List<String>, val systemCalls: List<String>)

        listOf(
            Click(cases.first { it.connection == ConnectionState.DisconnectedByUser }, "Připojit", listOf("connect"), emptyList()),
            Click(cases.first { it.connection == ConnectionState.PermissionsMissing && !it.permanentlyDenied }, "Povolit", emptyList(), listOf("requestPermissions")),
            Click(cases.first { it.permanentlyDenied }, "Otevřít nastavení", emptyList(), listOf("openAppSettings")),
            Click(cases.first { it.connection == ConnectionState.BluetoothOff }, "Zapnout Bluetooth", emptyList(), listOf("enableBluetooth")),
            Click(cases.first { it.connection == ConnectionState.LocationOff }, "Otevřít nastavení polohy", emptyList(), listOf("openLocationSettings")),
            Click(cases.first { it.connection == ConnectionState.MissingService }, "Zkusit znovu", listOf("retryConnection"), emptyList()),
        ).forEach { click ->
            actions.calls.clear()
            system.calls.clear()
            show(click.case.connection, permanentlyDenied = click.case.permanentlyDenied)

            compose.onNodeWithText(click.label).performClick()

            assertEquals("akce po „${click.label}“", click.actionCalls, actions.calls)
            assertEquals("systémové akce po „${click.label}“", click.systemCalls, system.calls)
        }
    }

    // Při velkém písmu se dlouhý popisek tlačítka zalomí na dva řádky a tlačítko naroste, místo aby druhý řádek uřízlo. Text měří
    // jen skutečná grafika (NATIVE), ve výchozí grafice Robolectricu se nikdy nezalomí.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTwoLineButtonLabelIsNotCutOffAtALargeFont() {
        fontScale = 2f
        show(ConnectionState.LocationOff)

        compose.onNodeWithText("Otevřít nastavení polohy").assertIsDisplayed()
        assertEquals(emptyList<String>(), compose.verticallyClippedTexts())
    }

    // Čas do dalšího pokusu se zaokrouhluje nahoru, takže 0 s ukáže až chvíli, kdy pokus opravdu začíná, a nikdy nejde pod nulu.
    @Test
    fun waitingCountdownRoundsUpAndNeverGoesBelowZero() {
        val retryAtMs = 10_000L
        listOf(6_000L to 4, 6_001L to 4, 7_000L to 3, 9_500L to 1, 9_999L to 1, 10_000L to 0, 12_000L to 0).forEach { (nowMs, seconds) ->
            show(ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = retryAtMs), nowMs = nowMs)
            assertEquals("v čase $nowMs", "Další pokus za $seconds${NBSP}s.", shownTexts().last())
        }
    }
}
