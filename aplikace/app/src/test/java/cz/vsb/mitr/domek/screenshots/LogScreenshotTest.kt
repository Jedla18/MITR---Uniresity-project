package cz.vsb.mitr.domek.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.overlappingTexts
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.textsBrokenInsideAWord
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.DomekTab
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseLog
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.LogEntry
import cz.vsb.mitr.domek.viewmodel.LogTime
import cz.vsb.mitr.domek.viewmodel.changedFields
import kotlin.math.abs
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Příběh grafu z náhledu (demoChart) zapsaný Deníkem, jak by ho zapsal ViewModel: cíl 22 → 25 → 21 → 23 °C a topení a chlazení
// podle něj, k tomu výpadek spojení, porucha čidla, pohyb, ruční světlo a odmítnutý zápis. Sekunda 300 je DEMO_WALL (10:02:15).
fun demoLog(): List<LogEntry> {
    val log = HouseLog()
    var settings = DEFAULTS.copy(targetDay = 22.0)
    var status = demoStatus().copy(insideTemp = 22.0, target = 22.0, heating = false, light = LightState(false, listOf(0, 0, 0), 0, false))
    var last: ReceivedStatus? = null
    val connected = ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, rssi = -61)
    val waiting = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 143_000)

    fun at(second: Int) = LogTime(DEMO_WALL.minusSeconds(300L - second), second * 1_000L)
    fun stav(second: Int, change: (HouseStatus) -> HouseStatus) {
        status = change(status)
        val time = at(second)
        val received = ReceivedStatus(status, time.ms, time.wall)
        log.status(last, received, settings)
        last = received
    }
    // Změny dělá aplikace: zápis odejde chvíli předtím, než Raspberry pošle nové Nastavení.
    fun nastaveni(second: Int, change: (HouseSettings) -> HouseSettings) {
        val changed = change(settings)
        log.appWrote(changedFields(settings, changed), at(second).ms - 500)
        log.settings(settings, changed, at(second))
        settings = changed
    }

    log.connection(ConnectionState.Idle, connected, at(2))
    stav(3) { it }
    nastaveni(59) { it.copy(targetDay = 25.0) }
    stav(60) { it.copy(target = 25.0, heating = true) }
    stav(120) { it.copy(insideTemp = 25.0, heating = false) }
    log.connection(connected, waiting, at(140))
    log.connection(waiting, connected, at(149))
    stav(150) { it }
    nastaveni(179) { it.copy(targetDay = 21.0) }
    stav(180) { it.copy(target = 21.0, cooling = true) }
    stav(190) { it.copy(errors = listOf("osvetleni"), lux = null) }
    // Dva pohyby za sebou bez jiné události mezi nimi se sloučí do jednoho řádku.
    stav(200) { it.copy(motion = true) }
    stav(203) { it.copy(motion = false) }
    stav(230) { it.copy(motion = true) }
    // Barvu a jas aplikace jen uloží, zhasnuté světlo se jimi rozsvítí až po „Zapnout“.
    nastaveni(231) { it.copy(lightColor = listOf(100, 110, 255), lightBrightness = 60) }
    stav(232) { it.copy(light = LightState(true, listOf(100, 110, 255), 60, true)) }
    stav(233) { it.copy(motion = false, errors = emptyList(), lux = 320.0) }
    stav(260) { it.copy(insideTemp = 21.0, cooling = false) }
    log.message("Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo.", at(265))
    nastaveni(269) { it.copy(targetDay = 23.0) }
    stav(270) { it.copy(target = 23.0, heating = true) }
    return log.entries
}

// Celá aplikace na záložce Deník (spec 2.1, náhled varianty B). Snímky se prohlížejí, testy hlídají, co oko snadno přehlédne:
// nic není oříznuté, zalomené uprostřed slova ani přes jiný text.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LogScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, state: HouseUiState, darkTheme: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) { DomekApp(state, NoActions, NoSystem, emptyFlow()) }
            }
        }
        compose.tab(DomekTab.LOG).performClick()
        compose.title().assertTextEquals("Deník")
        compose.saveScreenshot(name)
    }

    private fun assertNothingCutOrOverlapping() {
        assertEquals("oříznuté texty", emptyList<String>(), compose.cutOffTexts() + compose.verticallyClippedTexts())
        assertEquals("slova zalomená uprostřed", emptyList<String>(), compose.textsBrokenInsideAWord())
        assertEquals("texty přes sebe", emptyList<String>(), compose.overlappingTexts())
    }

    private val withLog = demoState().copy(log = demoLog())

    // Nahoře je nejnovější událost, pod ní nastavení cíle, které ji způsobilo, a dlouhá hláška zalomená na víc řádků.
    @Test
    fun phone() {
        capture("denik-telefon", withLog)

        assertNothingCutOrOverlapping()
        val newest = compose.onNodeWithText("10:01:45", useUnmergedTree = true).getBoundsInRoot()
        val cause = compose.onNodeWithText("Denní cíl: 21,0 → 23,0${NBSP}°C", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("nejnovější nahoře: $newest, $cause", newest.bottom <= cause.top)
        compose.onNodeWithText("Raspberry změnu odmítlo", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun tablet() {
        capture("denik-tablet", withLog)

        assertNothingCutOrOverlapping()
        val root = compose.onRoot().getBoundsInRoot()
        val row = compose.onNodeWithText("Denní cíl: 21,0 → 23,0${NBSP}°C").getBoundsInRoot()
        assertTrue("seznam je nejvýš 720 dp široký: $row", row.right - row.left <= 720.dp)
        // Obsah je napravo od boční lišty, takže střed seznamu je střed místa vedle ní, ne celé obrazovky.
        val rail = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot()
        val contentCenter = (rail.right + root.right) / 2
        assertTrue("seznam je uprostřed: $row, lišta $rail", abs(((row.left + row.right) / 2 - contentCenter).value) < 1f)
    }

    // Telefon na šířku: boční lišta a seznam nejvýš 720 dp uprostřed místa vedle ní.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() {
        capture("denik-sirka", withLog)

        assertNothingCutOrOverlapping()
        val row = compose.onNodeWithText("Denní cíl: 21,0 → 23,0${NBSP}°C").getBoundsInRoot()
        assertTrue("seznam je nejvýš 720 dp široký: $row", row.right - row.left <= 720.dp)
    }

    // Před prvními daty: Deník je přístupný a říká, od kdy zapisuje.
    @Test
    fun empty() {
        capture("denik-prazdny", HouseUiState(connection = ConnectionState.Scanning))

        compose.onNodeWithText("Zatím se nic nestalo.").assertIsDisplayed()
        compose.onNodeWithText("Deník zapisuje změny od chvíle, kdy aplikace dostane první data.").assertIsDisplayed()
        assertNothingCutOrOverlapping()
    }

    // Při větším písmu se časy, nadpisy i podrobnosti zalamují, nic se nesmí oříznout ani překrýt.
    @Test
    fun largeFont() {
        capture("denik-velke-pismo", withLog, fontScale = 1.5f)

        assertNothingCutOrOverlapping()
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou.
    @Test
    fun dark() {
        capture("denik-dark", withLog, darkTheme = true)

        assertNothingCutOrOverlapping()
    }
}
