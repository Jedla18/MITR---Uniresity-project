package cz.vsb.mitr.domek.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.NoActions
import cz.vsb.mitr.domek.screenshots.NoSystem
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.screenshots.PHONE_LANDSCAPE
import cz.vsb.mitr.domek.screenshots.demoState
import cz.vsb.mitr.domek.testing.RecordingActions
import cz.vsb.mitr.domek.testing.RecordingSystem
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.textsBrokenInsideAWord
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.LogCategory
import cz.vsb.mitr.domek.viewmodel.LogEntry
import cz.vsb.mitr.domek.viewmodel.LogEvent
import cz.vsb.mitr.domek.viewmodel.LogKind
import java.time.LocalDateTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class DomekAppTest {
    @get:Rule
    val compose = createComposeRule()

    // Stejný kanál jako ve ViewModelu: hláška poslaná dřív, než ji aplikace začne sbírat, se neztratí.
    private val messages = Channel<String>(Channel.BUFFERED)
    private val messageFlow = messages.receiveAsFlow()

    // Stav je v mutableStateOf, aby ho test mohl za běhu měnit jako ViewModel (třeba přijdou data nebo se změní spojení).
    private var appState by mutableStateOf(demoState())

    private fun showApp(state: HouseUiState = demoState(), actions: HouseActions = NoActions, system: SystemActions = NoSystem) {
        appState = state
        compose.setContent { DomekTheme(darkTheme = false) { DomekApp(appState, actions, system, messageFlow) } }
    }

    private fun updateState(state: HouseUiState) {
        appState = state
        compose.waitForIdle()
    }

    // Okraje systému (stavový řádek, navigační lišta, vybrání displeje) přijdou do Compose přes kořenový pohled.
    private fun dispatchSystemBarInsets(left: Dp = 0.dp, top: Dp = 0.dp, right: Dp = 0.dp, bottom: Dp = 0.dp) {
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val insets = with(compose.density) {
            WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(left.roundToPx(), top.roundToPx(), right.roundToPx(), bottom.roundToPx()),
                )
                .build()
        }
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private fun assertDpEquals(expected: Dp, actual: Dp, message: String) =
        assertEquals(message, expected.value, actual.value, 0.01f)

    @Test
    fun titleIsAppNameOnOverviewAndTabNameElsewhere() {
        showApp()
        compose.title().assertTextEquals("Chytrý dům")
        compose.tab(DomekTab.THERMOSTAT).performClick()
        compose.title().assertTextEquals("Termostat")
        compose.tab(DomekTab.LIGHT).performClick()
        compose.title().assertTextEquals("Světlo")
        compose.tab(DomekTab.LOG).performClick()
        compose.title().assertTextEquals("Deník")
        compose.tab(DomekTab.SETTINGS).performClick()
        compose.title().assertTextEquals("Nastavení")
        compose.tab(DomekTab.OVERVIEW).performClick()
        compose.title().assertTextEquals("Chytrý dům")
    }

    @Test
    fun onlyTheSelectedTabIsMarkedSelected() {
        showApp()
        compose.tab(DomekTab.OVERVIEW).assertIsSelected()
        compose.tab(DomekTab.LIGHT).assertIsNotSelected()

        compose.tab(DomekTab.LIGHT).performClick()

        compose.tab(DomekTab.OVERVIEW).assertIsNotSelected()
        compose.tab(DomekTab.LIGHT).assertIsSelected()
        compose.tab(DomekTab.THERMOSTAT).assertIsNotSelected()
        compose.tab(DomekTab.LOG).assertIsNotSelected()
        compose.tab(DomekTab.SETTINGS).assertIsNotSelected()
    }

    private val tabTitles = listOf("Přehled", "Termostat", "Světlo", "Deník", "Nastavení")
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tabBounds() = tabTitles.map { title -> compose.onNode(hasText(title) and isTab).getBoundsInRoot() }

    // Spec 2.1: pátá záložka Deník je mezi Světlem a Nastavením.
    @Test
    fun bottomBarHasFiveTabsWithTheLogBetweenLightAndSettings() {
        showApp()

        assertEquals(tabTitles.size, compose.onAllNodes(isTab).fetchSemanticsNodes().size)
        tabBounds().zipWithNext().forEach { (left, right) -> assertTrue("záložky zleva doprava: $left, $right", right.left >= left.right) }
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun sideRailHasFiveTabsWithTheLogBetweenLightAndSettings() {
        showApp()

        tabBounds().zipWithNext().forEach { (upper, lower) -> assertTrue("záložky shora dolů: $upper, $lower", lower.top >= upper.bottom) }
    }

    private fun showAppWithFontScale(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme = false) { DomekApp(demoState(), NoActions, NoSystem, messageFlow) }
            }
        }
    }

    // Popisky záložek, které se při velkém písmu zalomily uprostřed slova nebo se nevešly.
    private fun brokenTabLabels(): List<String> {
        val broken = compose.textsBrokenInsideAWord() + compose.cutOffTexts() + compose.verticallyClippedTexts()
        return broken.filter { it in tabTitles }
    }

    // Pět záložek je na telefonu užších; ani při velkém písmu se popisek nesmí rozdělit („Termos-tat“) ani uříznout. Šířky textů
    // měří jen skutečné písmo, proto NATIVE.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabLabelsStayWholeWithLargeFontOnAPhone() {
        showAppWithFontScale(1.5f)

        assertEquals(emptyList<String>(), brokenTabLabels())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun tabLabelsStayWholeWithLargeFontInTheSideRail() {
        showAppWithFontScale(1.5f)

        assertEquals(emptyList<String>(), brokenTabLabels())
    }

    // Popisek se zmenšuje nejvýš na velikost při běžném písmu. Při menším písmu je ta větší než nastavená, takže se nezmenšuje.
    @Test
    fun theSmallestTabLabelSizeIsNeverAboveTheLabelSize() {
        assertEquals(8f, tabLabelMinFontSize(12.sp, fontScale = 1.5f).value, 0.001f)
        assertEquals(12f, tabLabelMinFontSize(12.sp, fontScale = 1f).value, 0.001f)
        assertEquals(12f, tabLabelMinFontSize(12.sp, fontScale = 0.85f).value, 0.001f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabLabelsStayWholeWithASmallFont() {
        showAppWithFontScale(0.85f)

        tabTitles.forEach { compose.onNode(hasText(it) and isTab).assertIsDisplayed() }
        assertEquals(emptyList<String>(), brokenTabLabels())
    }

    // Na nízké obrazovce telefonu na šířku se pět záložek při velkém písmu nevejde, boční lišta se proto dá posouvat.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun theLastRailTabIsReachableWithAVeryLargeFont() {
        showAppWithFontScale(2f)

        compose.tab(DomekTab.SETTINGS).performScrollTo().assertIsDisplayed()
        val tab = compose.tab(DomekTab.SETTINGS).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("Nastavení je celé na obrazovce: $tab, $root", tab.bottom <= root.bottom)
    }

    @Test
    fun pillShowsConnectionStateAndOpensSettingsWhenTapped() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))
        compose.title().assertTextEquals("Chytrý dům")

        compose.onNode(hasText("Hledám…") and hasClickAction()).performClick()

        compose.title().assertTextEquals("Nastavení")
        compose.tab(DomekTab.SETTINGS).assertIsSelected()
    }

    @Test
    fun messagesAreShownOneByOneWithOkAction() {
        showApp()
        messages.trySend("První hláška")
        messages.trySend("Druhá hláška")
        compose.waitForIdle()

        compose.onNodeWithText("První hláška").assertIsDisplayed()
        compose.onNodeWithText("Druhá hláška").assertDoesNotExist()
        compose.onNodeWithText("OK").assertHasClickAction()

        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("První hláška").assertDoesNotExist()
        compose.onNodeWithText("Druhá hláška").assertIsDisplayed()
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DomekTheme(darkTheme = false) { DomekApp(demoState(), NoActions, NoSystem, messageFlow) } }
        compose.tab(DomekTab.LIGHT).performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.title().assertTextEquals("Světlo")
        compose.tab(DomekTab.LIGHT).assertIsSelected()
    }

    @Test
    fun narrowScreenHasTabsAtTheBottom() {
        showApp()
        val root = compose.onRoot().getBoundsInRoot()
        val overview = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot()
        val settings = compose.tab(DomekTab.SETTINGS).getBoundsInRoot()

        assertTrue("záložky jsou dole: $overview", overview.top > root.height / 2)
        assertTrue("záložky jsou vedle sebe: $overview, $settings", settings.left > overview.right)
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun wideScreenHasTabsInSideRail() {
        showApp()
        val root = compose.onRoot().getBoundsInRoot()
        val overview = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot()
        val settings = compose.tab(DomekTab.SETTINGS).getBoundsInRoot()

        assertTrue("lišta je vlevo: $overview", overview.right < 90.dp)
        assertTrue("záložky jsou pod sebou: $overview, $settings", settings.top > overview.bottom)
        assertTrue("lišta je od horního okraje: $overview, kořen $root", overview.top < root.height / 4)
    }

    @Test
    fun topBarAndBottomTabsStayClearOfSystemBars() {
        showApp()
        val titleTop = compose.title().getBoundsInRoot().top
        val tabsTop = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top

        dispatchSystemBarInsets(top = 24.dp, bottom = 16.dp)

        assertDpEquals(titleTop + 24.dp, compose.title().getBoundsInRoot().top, "titulek pod stavovým řádkem")
        assertDpEquals(tabsTop - 16.dp, compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top, "dolní lišta nad navigační lištou")
    }

    // Boční lišta odsadí okraj, na kterém stojí. Obsah vedle ní ho nesmí odsadit podruhé, jinak by byl od lišty
    // o šířku vybrání displeje dál, než má být.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun startInsetNextToTheSideRailIsAppliedOnlyOnce() {
        showApp()
        val railTabLeft = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().left
        val titleLeft = compose.title().getBoundsInRoot().left

        dispatchSystemBarInsets(left = 40.dp)

        assertDpEquals(railTabLeft + 40.dp, compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().left, "lišta se posune o okraj")
        assertDpEquals(titleLeft + 40.dp, compose.title().getBoundsInRoot().left, "titulek se posune jen o stejný okraj")
    }

    // Ostatní okraje boční lišta s obsahem nesdílí: horní a pravý dopadne jen na záhlaví, horní i na samotnou lištu.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun otherInsetsStillReachTheTopBarNextToTheSideRail() {
        showApp()
        val pill = hasText("Připojeno") and hasClickAction()
        val titleTop = compose.title().getBoundsInRoot().top
        val pillRight = compose.onNode(pill).getBoundsInRoot().right
        val railTab = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot()

        dispatchSystemBarInsets(top = 24.dp, right = 40.dp)

        assertDpEquals(titleTop + 24.dp, compose.title().getBoundsInRoot().top, "titulek pod stavovým řádkem")
        assertDpEquals(pillRight - 40.dp, compose.onNode(pill).getBoundsInRoot().right, "štítek před pravým okrajem")
        assertDpEquals(railTab.top + 24.dp, compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top, "lišta pod stavovým řádkem")
        assertDpEquals(railTab.left, compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().left, "pravý okraj lištu neposune")
    }

    private val searchTitle = "Hledám dům „Termostat“"
    private val sheetTitle = "V dosahu je víc domů"
    private val twoDevices = listOf(
        FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61),
        FoundDevice("DC:A6:32:7F:03:1B", "Termostat", -78),
    )
    private val closeSheet = hasClickAction() and hasContentDescription("Close sheet")

    // Před prvními daty ukazují Přehled, Termostat a Světlo stav spojení, Nastavení je přístupné vždy (spec 6.0).
    @Test
    fun overviewThermostatAndLightShowTheConnectionScreenUntilTheFirstData() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))

        listOf(DomekTab.OVERVIEW, DomekTab.THERMOSTAT, DomekTab.LIGHT).forEach { tab ->
            compose.tab(tab).performClick()
            compose.onNodeWithText(searchTitle).assertExists("stav spojení na záložce ${tab.title}")
        }
    }

    // Obrazovka dostává od aplikace akce ViewModelu i aktivity, ne prázdné. Tlačítka by jinak nic neudělala.
    @Test
    fun connectionScreenButtonsReachTheViewModelAndTheActivity() {
        val actions = RecordingActions()
        val system = RecordingSystem()
        showApp(HouseUiState(connection = ConnectionState.PermissionsMissing), actions, system.actions())

        compose.onNodeWithText("Povolit").performClick()
        compose.onNodeWithText("Spustit simulaci").performClick()

        assertEquals(listOf("requestPermissions"), system.calls)
        assertEquals(listOf("selectSource(SIMULATION)"), actions.calls)
    }

    @Test
    fun settingsAreNotReplacedByTheConnectionScreen() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))

        compose.tab(DomekTab.SETTINGS).performClick()

        compose.onNodeWithText(searchTitle).assertDoesNotExist()
    }

    // Deník zapisuje i spojení, proto je přístupný už před prvními daty (spec 2.1).
    @Test
    fun theLogIsReachableBeforeTheFirstData() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))

        compose.tab(DomekTab.LOG).performClick()

        compose.title().assertTextEquals("Deník")
        compose.onNodeWithText(searchTitle).assertDoesNotExist()
        compose.onNodeWithText("Zatím se nic nestalo.").assertIsDisplayed()
    }

    @Test
    fun theLogTabShowsTheEntriesOfTheState() {
        val lost = LogEvent(LogCategory.CONNECTION, LogKind.CONNECTION_LOST, "Spojení přerušeno", "znovu se připojuji…")
        val entry = LogEntry(1, LocalDateTime.of(2026, 10, 20, 10, 2, 15), lost)
        showApp(HouseUiState(connection = ConnectionState.Scanning, log = listOf(entry)))

        compose.tab(DomekTab.LOG).performClick()

        compose.onNodeWithText("Spojení přerušeno", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("10:02:15", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theTabsShowTheirOwnContentOnceTheFirstDataArrives() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))
        compose.onNodeWithText(searchTitle).assertExists()

        updateState(demoState())

        compose.onNodeWithText(searchTitle).assertDoesNotExist()
    }

    // Po „Připojit“ můžou zůstat stará data, výběr domku se má ukázat i tak, a to na každé záložce.
    @Test
    fun deviceSheetOpensWheneverTheStateIsChoosingDeviceOnAnyTabWithOrWithoutData() {
        val choosing = ConnectionState.ChoosingDevice(twoDevices)
        showApp(HouseUiState())

        listOf("bez dat" to HouseUiState(connection = choosing), "se starými daty" to demoState().copy(connection = choosing))
            .forEach { (what, state) ->
                updateState(state)
                DomekTab.entries.forEach { tab ->
                    compose.tab(tab).performClick()
                    compose.onNodeWithText(sheetTitle).assertExists("výběr domku na záložce ${tab.title}, $what")
                }
            }
    }

    @Test
    fun deviceSheetIsNotShownInOtherStates() {
        showApp(HouseUiState(connection = ConnectionState.Scanning))
        compose.onNodeWithText(sheetTitle).assertDoesNotExist()

        updateState(demoState())
        compose.onNodeWithText(sheetTitle).assertDoesNotExist()
    }

    @Test
    fun choosingADeviceInTheSheetCallsChooseDevice() {
        val actions = RecordingActions()
        showApp(HouseUiState(connection = ConnectionState.ChoosingDevice(twoDevices)), actions)

        compose.onNodeWithText("Termostat · DC:A6:32:7F:03:1B · signál -78${NBSP}dBm").performClick()

        assertEquals(listOf("chooseDevice(DC:A6:32:7F:03:1B)"), actions.calls)
    }

    // Přehled nahrazuje zástupný text, jakmile jsou data. Karta termostatu a graf jsou vidět hned.
    @Test
    fun overviewTabShowsTheOverviewOnceThereIsData() {
        showApp()

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
        compose.onNodeWithText("Teploty").assertIsDisplayed()
    }

    // Dlaždice světla je poslední, takže se k ní musí dojet posouváním, a pak přepne záložku.
    @Test
    fun lightTileOpensTheLightTab() {
        showApp()
        compose.onNode(hasClickAction() and hasText("Svítí")).performScrollTo().performClick()

        compose.title().assertTextEquals("Světlo")
        compose.tab(DomekTab.LIGHT).assertIsSelected()
    }

    // Obsah Přehledu je delší než obrazovka a po dojetí na konec se nesmí schovat pod dolní lištu.
    @Test
    fun overviewScrollsAboveTheBottomTabs() {
        showApp()
        val lightTile = compose.onNode(hasClickAction() and hasText("Svítí"))
        lightTile.performScrollTo()

        val tabsTop = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top
        assertTrue("poslední dlaždice je nad záložkami: ${lightTile.getBoundsInRoot()}", lightTile.getBoundsInRoot().bottom <= tabsTop)
    }

    // Na šířku (boční lišta) má graf svůj sloupec vpravo od karty termostatu.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun overviewHasTwoColumnsOnAWideScreen() {
        showApp()

        val mode = compose.onNodeWithText("Režim Auto").getBoundsInRoot()
        val chart = compose.onNodeWithText("Teploty").getBoundsInRoot()
        assertTrue("graf je vpravo od karty termostatu: $mode, $chart", chart.left > mode.right)
    }

    // Termostat nahrazuje zástupný text, jakmile jsou data: režim, cíle i „nikdo doma“ jsou vidět.
    @Test
    fun thermostatTabShowsTheThermostatOnceThereIsData() {
        showApp()
        compose.tab(DomekTab.THERMOSTAT).performClick()

        compose.title().assertTextEquals("Termostat")
        compose.onNodeWithText("Režim").assertIsDisplayed()
        compose.onNodeWithText("Cílové teploty").assertIsDisplayed()
    }

    // Obrazovka dostává akce ViewModelu: segment režimu volá setMode a ovladač cíle changeSetting.
    @Test
    fun thermostatControlsReachTheViewModel() {
        val actions = RecordingActions()
        showApp(actions = actions)
        compose.tab(DomekTab.THERMOSTAT).performClick()

        compose.onNode(hasText("Ruční") and hasClickAction()).performClick()
        compose.onAllNodesWithContentDescription("Zvýšit")[0].performClick()

        assertEquals(listOf("setMode(MANUAL)", "changeSetting(cil_den, 23.5)"), actions.calls)
    }

    // Souhrn nahoře nesmí zajíždět pod záhlaví. Poslední karta je delší než obrazovka a po dojetí na konec se nesmí schovat pod
    // dolní lištu.
    @Test
    fun thermostatStaysBetweenTheTopBarAndTheBottomTabs() {
        showApp()
        compose.tab(DomekTab.THERMOSTAT).performClick()
        val summary = compose.onAllNodesWithText("Uvnitř")[0].getBoundsInRoot()
        val titleBottom = compose.title().getBoundsInRoot().bottom
        assertTrue("souhrn je pod záhlavím: $summary, titulek končí na $titleBottom", summary.top >= titleBottom)

        val lastStepper = compose.onAllNodesWithContentDescription("Zvýšit")[4]
        lastStepper.performScrollTo()

        val tabsTop = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top
        assertTrue("poslední ovladač je nad záložkami: ${lastStepper.getBoundsInRoot()}", lastStepper.getBoundsInRoot().bottom <= tabsTop)
    }

    // Na šířku (boční lišta) jsou cíle ve druhém sloupci vpravo od režimu.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun thermostatHasTwoColumnsOnAWideScreen() {
        showApp()
        compose.tab(DomekTab.THERMOSTAT).performClick()

        val mode = compose.onNode(hasText("Vypnuto") and hasClickAction()).getBoundsInRoot()
        val targets = compose.onNodeWithText("Cílové teploty").getBoundsInRoot()
        assertTrue("cíle jsou vpravo od režimu: $mode, $targets", targets.left > mode.right)
    }

    // Světlo nahrazuje zástupný text, jakmile jsou data: náhled, barva i automatika jsou vidět.
    @Test
    fun lightTabShowsTheLightScreenOnceThereIsData() {
        showApp()
        compose.tab(DomekTab.LIGHT).performClick()

        compose.title().assertTextEquals("Světlo")
        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Barva").assertIsDisplayed()
    }

    // Obrazovka dostává akce ViewModelu: tlačítko světla, kolečko barvy, přepínač rozsvěcení pohybem i ovladač prahu šera.
    @Test
    fun lightControlsReachTheViewModel() {
        val actions = RecordingActions()
        showApp(actions = actions)
        compose.tab(DomekTab.LIGHT).performClick()

        compose.onNode(hasText("Vypnout") and hasClickAction()).performClick()
        compose.onNode(hasContentDescription("červená")).performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onAllNodesWithContentDescription("Zvýšit")[1].performScrollTo().performClick()

        assertEquals(
            listOf("lightAction(OFF)", "setLightColor(244, 67, 54)", "setLightOnMotion(false)", "changeSetting(prah_lux, 55.0)"),
            actions.calls,
        )
    }

    // Náhled nahoře nesmí zajíždět pod záhlaví. Poslední ovladač je pod okrajem obrazovky a po dojetí na konec se nesmí schovat
    // pod dolní lištu.
    @Test
    fun lightStaysBetweenTheTopBarAndTheBottomTabs() {
        showApp()
        compose.tab(DomekTab.LIGHT).performClick()
        val preview = compose.onNodeWithText("Svítí · ručně").getBoundsInRoot()
        val titleBottom = compose.title().getBoundsInRoot().bottom
        assertTrue("náhled je pod záhlavím: $preview, titulek končí na $titleBottom", preview.top >= titleBottom)

        val lastStepper = compose.onAllNodesWithContentDescription("Zvýšit")[1]
        lastStepper.performScrollTo()

        val tabsTop = compose.tab(DomekTab.OVERVIEW).getBoundsInRoot().top
        assertTrue("poslední ovladač je nad záložkami: ${lastStepper.getBoundsInRoot()}", lastStepper.getBoundsInRoot().bottom <= tabsTop)
    }

    // Na šířku (boční lišta) je barva ve druhém sloupci vpravo od náhledu s tlačítky.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun lightHasTwoColumnsOnAWideScreen() {
        showApp()
        compose.tab(DomekTab.LIGHT).performClick()

        val buttons = compose.onNode(hasText("Vypnout") and hasClickAction()).getBoundsInRoot()
        val color = compose.onNodeWithText("Barva").getBoundsInRoot()
        assertTrue("barva je vpravo od tlačítek: $buttons, $color", color.left > buttons.right)
    }

    // Zavřený panel zůstane zavřený, i když se seznam doplňuje. Otevře se znovu, až když výběr začne od začátku.
    @Test
    fun dismissedSheetStaysClosedWhileChoosingAndComesBackWithTheNextChoice() {
        val choosing = HouseUiState(connection = ConnectionState.ChoosingDevice(twoDevices))
        showApp(choosing)
        compose.onNode(closeSheet).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(sheetTitle).assertDoesNotExist()

        updateState(HouseUiState(connection = ConnectionState.ChoosingDevice(twoDevices + FoundDevice("E4:5F:01:12:AB:90", "Termostat", -83))))
        compose.onNodeWithText(sheetTitle).assertDoesNotExist()

        updateState(HouseUiState(connection = ConnectionState.Scanning))
        updateState(choosing)
        compose.onNodeWithText(sheetTitle).assertExists()
    }
}
