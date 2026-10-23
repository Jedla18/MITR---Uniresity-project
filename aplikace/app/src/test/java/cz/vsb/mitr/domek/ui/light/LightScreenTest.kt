package cz.vsb.mitr.domek.ui.light

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.WITHOUT_STORED_LOOK
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE_TALL
import cz.vsb.mitr.domek.screenshots.WIDE_TALL
import cz.vsb.mitr.domek.screenshots.demoStateWithoutClockWarning
import cz.vsb.mitr.domek.screenshots.renderScreenshot
import cz.vsb.mitr.domek.screenshots.withStatus
import cz.vsb.mitr.domek.testing.EXACT
import cz.vsb.mitr.domek.testing.RecordingActions
import cz.vsb.mitr.domek.testing.assertNear
import cz.vsb.mitr.domek.testing.colorAt
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.isNear
import cz.vsb.mitr.domek.testing.textsBrokenInsideAWord
import cz.vsb.mitr.domek.ui.theme.DarkColorScheme
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LightColorScheme
import cz.vsb.mitr.domek.ui.theme.LightDomekColors
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.LightDraft
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val BLUE = Rgb(100, 110, 255)

// Pořadí ovladačů s číslem v Automatice shora dolů. Pořadí je pořadí ovladačů ve stromu obrazovky.
private enum class StepRow(val title: String, val key: SettingKey) {
    NIGHT_LIGHT("Noční světlo svítí", SettingKey.NIGHT_LIGHT_SECONDS),
    THRESHOLD("Práh šera", SettingKey.LUX_THRESHOLD),
}

private fun lit(rgb: Rgb, brightness: Int, manual: Boolean = true) =
    LightState(on = true, color = listOf(rgb.r, rgb.g, rgb.b), brightness = brightness, manual = manual)

private fun switchedOff(manual: Boolean = false) = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = manual)

private fun HouseUiState.withLight(light: LightState) = withStatus { it.copy(light = light) }

// Uložená barva a jas v Nastavení, kterými světlo svítí, kdykoli svítí (spec 3 v 2026-10-23-denik-a-vylepseni-design.md).
private fun HouseUiState.withStoredLook(rgb: Rgb, brightness: Int) =
    copy(settings = checkNotNull(settings).copy(lightColor = listOf(rgb.r, rgb.g, rgb.b), lightBrightness = brightness))

// Ruční světlo, které svítí uloženou barvou a jasem.
private fun HouseUiState.shining(rgb: Rgb, brightness: Int) = withLight(lit(rgb, brightness)).withStoredLook(rgb, brightness)

// Program na Raspberry, který uloženou barvu a jas nezná: výběr barvy nebo jasu světlo rovnou ručně zapne.
private fun HouseUiState.ofAnOlderProgram() = copy(settings = WITHOUT_STORED_LOOK)

private const val STORED_LOOK_HINT =
    "Barva a jas se uloží. Svítící světlo se hned přebarví, zhasnuté se jimi rozsvítí po zapnutí nebo v${NBSP}automatice."
private const val COMMAND_HINT = "Výběr barvy nebo jasu světlo rovnou ručně zapne."

// Poloměr jádra záře je 28 dp a uprostřed je žárovka, proto se barva jádra čte 20 dp vpravo od středu. Střed kruhu je 75 dp pod
// horním okrajem skupiny textů náhledu, protože kruh je v ní první.
private const val GLOW_RADIUS_DP = 75f
private const val GLOW_CORE_OFFSET_DP = 20f

// Obrazovka Světlo bez aplikace kolem: texty, podmínky a klepnutí. Obrazovka je tak vysoká, aby se celý obsah vešel a šel zkoušet
// bez posouvání. Skutečná písma a hustota telefonu jsou tu kvůli rozměrům a barvám, které se porovnávají.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class LightScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private var shown by mutableStateOf(HouseUiState())

    // Výchozí stav je demoState bez upozornění na hodiny: ruční modrá na 60 %, Raspberry v 10:00 a začátek večera v 19:00.
    private val base = demoStateWithoutClockWarning()

    private fun show(state: HouseUiState = base, wide: Boolean = false, fontScale: Float = 1f, darkTheme: Boolean = false) {
        shown = state
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) {
                    // Pozadí stránky je pod kartami jako v aplikaci, jinak by zešedlá bílá karta byla na snímku jen průhledná.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LightScreen(shown, actions, wide)
                    }
                }
            }
        }
    }

    private fun update(state: HouseUiState) {
        shown = state
        compose.waitForIdle()
    }

    // ----- Stavy -----

    private fun withSettings(change: (HouseSettings) -> HouseSettings) =
        base.copy(settings = change(checkNotNull(base.settings)))

    private fun withDraft(key: SettingKey, value: JsonPrimitive) = base.copy(drafts = mapOf(key to value))

    private fun disconnected() = base.copy(connection = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000))

    // Za 7 s od posledního Stavu (přijatého v 300 000 ms).
    private fun stale(state: HouseUiState = base): HouseUiState = state.copy(nowMs = 307_000)

    // ----- Vyhledávání prvků -----

    // Skupina textů náhledu (název a podtitul se čtou jako jeden celek), jejíž první prvek je kruh.
    private fun lamp(title: String) = compose.onNode(hasText(title))

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun swatch(name: String) = compose.onNode(hasContentDescription(name))

    private val isSlider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private fun brightnessSlider() = compose.onNode(isSlider)

    private val isStepperNumber =
        SemanticsMatcher("číslo ovladače") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Zadat číslo" }

    private fun plus(row: StepRow): SemanticsNodeInteraction = compose.onAllNodesWithContentDescription("Zvýšit")[row.ordinal]

    private fun minus(row: StepRow): SemanticsNodeInteraction = compose.onAllNodesWithContentDescription("Snížit")[row.ordinal]

    private fun number(row: StepRow): SemanticsNodeInteraction = compose.onAllNodes(isStepperNumber)[row.ordinal]

    // Nejmenší vzdálenost názvu řádku od ukazatele odesílání, aby se poznalo, ve kterém řádku se točí.
    private fun rowWithRing(): StepRow {
        val rings = compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes()
        assertEquals("ukazatel odesílání je právě jeden", 1, rings.size)
        val y = rings.single().boundsInRoot.center.y
        return StepRow.entries.minBy { row ->
            abs(compose.onNode(hasText(row.title) and !hasClickAction()).fetchSemanticsNode().boundsInRoot.center.y - y)
        }
    }

    // Barva obrazovky v kruhu náhledu, `dx` dp od jeho středu vodorovně a `dy` dp svisle.
    private fun lampColorAt(title: String, dx: Float, dy: Float = 0f): Color {
        val bounds = lamp(title).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        return compose.renderScreenshot("lamp")
            .colorAt(bounds.center.x + dx * density, bounds.top + (GLOW_RADIUS_DP + dy) * density)
    }

    private fun glowCore(title: String) = lampColorAt(title, GLOW_CORE_OFFSET_DP)

    private fun distance(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    // ----- Náhled -----

    @Test
    fun previewShowsTheTitleAndTheDetailOfTheManualLight() {
        show()

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Jas 60${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
    }

    // Ruční volba platí do začátku dalšího úseku podle času Raspberry a začátků úseků.
    @Test
    fun theManualChoiceEndsWhereTheNextDayPartStarts() {
        show()

        update(base.withStatus { it.copy(time = "2026-10-20T20:00:00") })
        compose.onNodeWithText("Jas 60${NBSP}% · ruční volba platí do 22:00").assertIsDisplayed()

        update(base.withStatus { it.copy(time = "2026-10-20T23:30:00") })
        compose.onNodeWithText("Jas 60${NBSP}% · ruční volba platí do 07:00").assertIsDisplayed()
    }

    // Začátek úseku, který uživatel právě mění, platí hned (rozpracovaná hodnota má přednost před Nastavením).
    @Test
    fun theEndOfTheManualChoiceFollowsTheDayStartsBeingEdited() {
        show(withDraft(SettingKey.EVENING_START, JsonPrimitive("18:30")))

        compose.onNodeWithText("Jas 60${NBSP}% · ruční volba platí do 18:30").assertIsDisplayed()
    }

    // Bez Nastavení neznáme začátky úseků, takže není do kdy.
    @Test
    fun withoutTheSettingsThereIsNoEndOfTheManualChoice() {
        show(base.copy(settings = null))

        compose.onNodeWithText("Jas 60${NBSP}%").assertIsDisplayed()
        compose.onNodeWithText("ruční volba", substring = true).assertDoesNotExist()
    }

    @Test
    fun anAutomaticLightNeverNamesAnEndOfTheChoice() {
        val evening = base.withStatus {
            it.copy(dayPartText = DayPart.EVENING.json, time = "2026-10-20T19:30:00", light = lit(Rgb(255, 170, 60), 60, manual = false))
        }
        show(evening)

        compose.onNodeWithText("Svítí · večerní světlo").assertIsDisplayed()
        compose.onNodeWithText("Jas 60${NBSP}%").assertIsDisplayed()
        compose.onNodeWithText("platí do", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSwitchedOffManualLightSaysWhenTheManualChoiceEnds() {
        show(base.withLight(switchedOff(manual = true)))

        compose.onNodeWithText("Zhasnuto · ručně").assertIsDisplayed()
        compose.onNodeWithText("Ruční volba platí do 19:00").assertIsDisplayed()
    }

    @Test
    fun anAutomaticLightThatIsOffHasNoDetail() {
        show(base.withLight(switchedOff()))

        compose.onNodeWithText("Zhasnuto · venku je dost světla").assertIsDisplayed()
        compose.onNodeWithText("Jas 0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("platí do", substring = true).assertDoesNotExist()
    }

    @Test
    fun theTitleFollowsTheDayPartFromTheStatus() {
        show()

        update(base.withStatus { it.copy(dayPartText = DayPart.NIGHT.json, light = switchedOff()) })
        compose.onNodeWithText("Zhasnuto · noc").assertIsDisplayed()

        update(base.withStatus { it.copy(dayPartText = DayPart.NIGHT.json, light = lit(Rgb(255, 120, 30), 15, manual = false)) })
        compose.onNodeWithText("Noční světlo · pohyb").assertIsDisplayed()
        compose.onNodeWithText("Jas 15${NBSP}%").assertIsDisplayed()
    }

    // Starší program: výběr barvy nebo jasu světlo rovnou ručně zapne (spec 6.3), takže náhled to ukazuje dřív, než to potvrdí
    // Stav.
    @Test
    fun thePreviewOfAnOlderProgramFollowsTheLightDraft() {
        show(base.withLight(switchedOff()).ofAnOlderProgram().copy(lightDraft = LightDraft(brightness = 70, setAtMs = 300_000)))

        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
        compose.onNodeWithText("Jas 70${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
    }

    // S uloženou barvou a jasem se výběr jen uloží: svítící světlo se v náhledu přebarví a zůstane v automatice.
    @Test
    fun thePreviewRecolorsAnAutomaticLightByTheDraftAndKeepsItAutomatic() {
        val evening = base.withStatus {
            it.copy(dayPartText = DayPart.EVENING.json, time = "2026-10-20T19:30:00", light = lit(BLUE, 60, manual = false))
        }
        show(evening.copy(lightDraft = LightDraft(brightness = 70, setAtMs = 300_000)))

        compose.onNodeWithText("Svítí · večerní světlo").assertIsDisplayed()
        compose.onNodeWithText("Jas 70${NBSP}%").assertIsDisplayed()
    }

    // ----- Kruh náhledu -----

    @Test
    fun theLampGlowsInTheRealColorOfTheLight() {
        show(base.withLight(lit(BLUE, 100)))
        val bright = glowCore("Svítí · ručně")

        update(base.withLight(lit(BLUE, 20)))
        val dim = glowCore("Svítí · ručně")

        // Jádro se s jasem zesvětluje k bílé kartě, takže plný jas je blíž skutečné barvě než slabý.
        val card = LightColorScheme.surface
        assertTrue("jádro při plném jasu je modré: $bright", bright.blue > bright.red + 0.3f)
        assertTrue("slabý jas je bližší bílé kartě než plný: $dim x $bright", distance(dim, card) < distance(bright, card))
        assertTrue("slabý jas má pořád modrý nádech: $dim", dim.blue > dim.red)
    }

    @Test
    fun theLampIsGreyWhileTheLightIsOff() {
        show(base.withLight(switchedOff()))

        assertNear("zhasnuté světlo", LightDomekColors.neutralContainer, glowCore("Zhasnuto · venku je dost světla"), EXACT)
    }

    // Bílé světlo na bílé kartě by nebylo vidět, proto má kruh šedý podklad jako zhasnuté světlo a bílý je jen jeho střed. Barevné
    // světlo podklad nepotřebuje, ani světlé (žluté).
    @Test
    fun aWhiteLampSitsOnAGreyPlateAndAColoredOneDoesNot() {
        // Záře mizí 0,75 dp před okrajem kruhu, takže hned za ní je na snímku čistá barva podkladu.
        fun edge(title: String) = lampColorAt(title, dx = GLOW_RADIUS_DP - 0.4f)

        show(base.withLight(lit(Rgb(255, 255, 255), 100)))
        assertNear("okraj bílého světla", LightDomekColors.neutralContainer, edge("Svítí · ručně"), 0.02f)
        assertNear("střed bílého světla", Color.White, glowCore("Svítí · ručně"), EXACT)

        update(base.withLight(lit(Rgb(255, 200, 60), 100)))
        assertNear("okraj žlutého světla", LightColorScheme.surface, edge("Svítí · ručně"), EXACT)

        update(base.withLight(lit(BLUE, 60)))
        assertNear("okraj modrého světla", LightColorScheme.surface, edge("Svítí · ručně"), EXACT)
    }

    // Na tmavé kartě je modrá záře dobře vidět i bez podkladu a bílá taky; podklad je jen pro záři, která by na kartě zanikla.
    @Test
    fun inTheDarkThemeNeitherABlueNorAWhiteLampNeedsAPlate() {
        fun edge(title: String) = lampColorAt(title, dx = GLOW_RADIUS_DP - 0.4f)

        show(base.withLight(lit(BLUE, 60)), darkTheme = true)
        assertNear("okraj modrého světla", DarkColorScheme.surface, edge("Svítí · ručně"), EXACT)

        update(base.withLight(lit(Rgb(255, 255, 255), 100)))
        assertNear("okraj bílého světla", DarkColorScheme.surface, edge("Svítí · ručně"), EXACT)
    }

    // Žárovka je na sytých barvách bílá, na světlých tmavá.
    @Test
    fun theBulbIsWhiteOnADeepColorAndDarkOnAPaleOne() {
        fun bulb(title: String) = lampColorAt(title, dx = 0f)

        show(base.withLight(lit(BLUE, 100)))
        assertNear("žárovka na modré", Color.White, bulb("Svítí · ručně"), EXACT)

        update(base.withLight(lit(Rgb(255, 255, 255), 100)))
        assertTrue("žárovka na bílé je tmavá", bulb("Svítí · ručně").luminance() < 0.3f)

        update(base.withLight(lit(Rgb(255, 200, 60), 100)))
        assertTrue("žárovka na žluté je tmavá", bulb("Svítí · ručně").luminance() < 0.3f)
    }

    // ----- Tlačítka -----

    @Test
    fun theButtonsSendTheLightActions() {
        show()

        button("Zapnout").performClick()
        button("Vypnout").performClick()
        button("Vrátit automatiku").performClick()

        assertEquals(listOf("lightAction(ON)", "lightAction(OFF)", "lightAction(AUTO)"), actions.calls)
    }

    // Přepnout by dělalo totéž co Zapnout a Vypnout, stav světla je přitom vidět. Obrazovka ho proto nemá (rozhodl uživatel
    // 23. 10. 2026), příkaz prepnout ale Raspberry dál zná.
    @Test
    fun thereIsNoToggleButton() {
        show()

        assertEquals(0, compose.onAllNodesWithText("Přepnout").fetchSemanticsNodes().size)
    }

    @Test
    fun theButtonsAreDisabledWithoutAConnectionAndBeforeTheSettingsArrive() {
        val labels = listOf("Zapnout", "Vypnout", "Vrátit automatiku")
        show(disconnected())
        labels.forEach { button(it).assertIsNotEnabled() }

        update(base.copy(settings = null))
        labels.forEach { button(it).assertIsNotEnabled() }

        update(base)
        labels.forEach { button(it).assertIsEnabled() }
    }

    // Vrátit automatiku má smysl jen u ruční volby. Když o světle rozhoduje automatika, tlačítko zešedne (rozhodl uživatel
    // 23. 10. 2026), ostatní tlačítka ne.
    @Test
    fun returnToAutomaticIsDisabledWhileTheAutomationRunsTheLight() {
        show(base.withLight(switchedOff(manual = false)))
        button("Vrátit automatiku").assertIsNotEnabled()
        listOf("Zapnout", "Vypnout").forEach { button(it).assertIsEnabled() }

        update(base.withLight(lit(BLUE, 60, manual = false)))
        button("Vrátit automatiku").assertIsNotEnabled()

        update(base.withLight(switchedOff(manual = true)))
        button("Vrátit automatiku").assertIsEnabled()
    }

    // U starých dat aplikace neví, jestli ruční volba pořád platí, tak tlačítko zůstane dostupné jako ostatní ovladače (spec 6.5).
    @Test
    fun returnToAutomaticStaysEnabledWithStaleData() {
        show(stale(base.withLight(switchedOff(manual = false))))

        button("Vrátit automatiku").assertIsEnabled()
    }

    // Starší program výběrem barvy světlo ručně zapne. Náhled to ukáže hned, a tak jde automatiku hned i vrátit.
    @Test
    fun returnToAutomaticIsEnabledAsSoonAsAColorSwitchesTheLightOnManually() {
        show(base.ofAnOlderProgram().withLight(switchedOff()).copy(lightDraft = LightDraft(color = BLUE, setAtMs = 300_000)))

        button("Vrátit automatiku").assertIsEnabled()
    }

    @Test
    fun turnOnAndOffShareARowAndTheAutomaticButtonIsBelowThem() {
        show()
        assertTurnOnAndOffShareARowAndTheAutomaticButtonIsBelowThem()
    }

    // Nejčastější šířka telefonu je 360 dp a tlačítka se i na ní mají vejít vedle sebe jako na náhledu.
    @Test
    @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun turnOnAndOffShareARowOnA360DpPhoneToo() {
        show()
        assertTurnOnAndOffShareARowAndTheAutomaticButtonIsBelowThem()
    }

    // Tlačítka jsou vidět 44 dp vysoká, ale jako přepínač režimu na Termostatu mají dotykovou plochu 48 dp, kterou jim Material 3
    // rezervuje i v rozvržení. Řada tlačítek tak s mezerou 8 dp zabere aspoň 56 dp.
    @Test
    fun theButtonsKeepMaterialsTouchTargetOf48Dp() {
        show()

        val rowPitch = button("Vrátit automatiku").getBoundsInRoot().top - button("Zapnout").getBoundsInRoot().top

        assertTrue("řada tlačítek i s mezerou má $rowPitch", rowPitch >= 48.dp + 8.dp)
    }

    private fun assertTurnOnAndOffShareARowAndTheAutomaticButtonIsBelowThem() {
        val turnOn = button("Zapnout").getBoundsInRoot()
        val turnOff = button("Vypnout").getBoundsInRoot()
        val automatic = button("Vrátit automatiku").getBoundsInRoot()

        assertEquals("tlačítka jsou v jedné řadě", turnOn.top.value, turnOff.top.value, 0.5f)
        assertTrue("pořadí zleva: $turnOn, $turnOff", turnOn.right <= turnOff.left)
        assertTrue("Vrátit automatiku je pod nimi: $turnOff, $automatic", automatic.top >= turnOff.bottom)
        assertEquals("a je přes celou šířku karty", automatic.left.value, turnOn.left.value, 0.5f)
        assertEquals("a je přes celou šířku karty", automatic.right.value, turnOff.right.value, 0.5f)
    }

    // ----- Barva -----

    @Test
    fun theGridHasTheElevenPresetsAndTheCustomColorInTwoRowsOfSix() {
        show()

        val names = LIGHT_PRESETS.map { it.name } + "vlastní"
        val bounds = names.map { swatch(it).getBoundsInRoot() }
        assertEquals(12, bounds.size)
        val firstRow = bounds.take(6)
        val secondRow = bounds.drop(6)
        firstRow.forEach { assertEquals("první řada", firstRow.first().top.value, it.top.value, 0.5f) }
        secondRow.forEach { assertEquals("druhá řada", secondRow.first().top.value, it.top.value, 0.5f) }
        assertTrue("druhá řada je pod první", secondRow.first().top >= firstRow.first().bottom)
        assertEquals("sloupce druhé řady jsou pod sloupci první", firstRow.map { it.left.value }, secondRow.map { it.left.value })
        assertEquals("pořadí zleva", firstRow.map { it.left.value }.sorted(), firstRow.map { it.left.value })
        firstRow.zipWithNext().forEach { (a, b) -> assertTrue("kolečka se nepřekrývají: $a, $b", a.right <= b.left) }
    }

    // Vybraná je uložená barva, kterou světlo svítí (spec 3 v 2026-10-23-denik-a-vylepseni-design.md).
    @Test
    fun theSelectedPresetIsTheStoredColorAndItsNameIsShownNextToTheTitle() {
        show()

        swatch("modrá").assertIsSelected()
        LIGHT_PRESETS.filter { it.name != "modrá" }.forEach { swatch(it.name).assertIsNotSelected() }
        swatch("vlastní").assertIsNotSelected()
        compose.onNodeWithText("modrá").assertIsDisplayed()
    }

    @Test
    fun everyPresetCanBeTheSelectedOne() {
        show()

        LIGHT_PRESETS.forEach { preset ->
            update(base.shining(preset.rgb, 60))
            swatch(preset.name).assertIsSelected()
            compose.onNodeWithText(preset.name).assertIsDisplayed()
        }
    }

    // Barva, která není mezi předvolbami, označí „vlastní“.
    @Test
    fun aColorOutsideThePresetsSelectsTheCustomOne() {
        show(base.shining(Rgb(200, 40, 140), 60))

        swatch("vlastní").assertIsSelected()
        LIGHT_PRESETS.forEach { swatch(it.name).assertIsNotSelected() }
        compose.onNodeWithText("vlastní").assertIsDisplayed()
    }

    // Zhasnuté světlo ukazuje uloženou barvu a jas, kterými se příště rozsvítí, tedy ne černou a 0 %, které hlásí Stav.
    @Test
    fun aSwitchedOffLightShowsTheStoredColorAndBrightness() {
        show(base.withLight(switchedOff()).withStoredLook(Rgb(255, 138, 61), 40))

        swatch("oranžová").assertIsSelected()
        (LIGHT_PRESETS.map { it.name }.filter { it != "oranžová" } + "vlastní").forEach { swatch(it).assertIsNotSelected() }
        compose.onNodeWithText("oranžová").assertIsDisplayed()
        compose.onNodeWithText("40${NBSP}%").assertIsDisplayed()
        brightnessSlider().assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(40f, 1f..100f)))
    }

    @Test
    fun aStoredColorOutsideThePresetsSelectsTheCustomOneEvenWhileTheLightIsOff() {
        show(base.withLight(switchedOff()).withStoredLook(Rgb(200, 40, 140), 80))

        swatch("vlastní").assertIsSelected()
        compose.onNodeWithText("vlastní").assertIsDisplayed()
        compose.onNodeWithText("80${NBSP}%").assertIsDisplayed()
    }

    // Starší program uloženou barvu nehlásí. Zhasnuté světlo nemá svítící barvu, takže není vybrané nic. Raspberry u zhasnutého
    // světla hlásí černou, a ta není „vlastní“.
    @Test
    fun anOlderProgramsSwitchedOffLightSelectsNothing() {
        show(base.withLight(switchedOff()).ofAnOlderProgram())

        (LIGHT_PRESETS.map { it.name } + "vlastní").forEach { swatch(it).assertIsNotSelected() }
        compose.onNodeWithText("vlastní").assertDoesNotExist()
        compose.onNodeWithText("modrá").assertDoesNotExist()
    }

    // Raspberry může u zhasnutého světla hlásit i poslední barvu. Zhasnuté světlo ale nesvítí žádnou, takže není vybraná žádná.
    @Test
    fun anOlderProgramsSwitchedOffLightSelectsNothingEvenWhenItKeepsItsLastColor() {
        show(base.withLight(LightState(on = false, color = listOf(BLUE.r, BLUE.g, BLUE.b), brightness = 0, manual = true)).ofAnOlderProgram())

        (LIGHT_PRESETS.map { it.name } + "vlastní").forEach { swatch(it).assertIsNotSelected() }
        compose.onNodeWithText("modrá").assertDoesNotExist()
    }

    @Test
    fun theSelectionFollowsTheColorDraftBeforeTheStatusConfirmsIt() {
        show(base.copy(lightDraft = LightDraft(color = Rgb(61, 214, 140), setAtMs = 300_000)))

        swatch("zelená").assertIsSelected()
        swatch("modrá").assertIsNotSelected()
        compose.onNodeWithText("Svítí · ručně").assertIsDisplayed()
    }

    // Starší program: samotná barva rozsvítí zhasnuté světlo naplno a samotný jas bíle, jako to udělá Raspberry; náhled a posuvník
    // to ukážou dřív, než to potvrdí Stav.
    @Test
    fun anOlderProgramsDraftOfASwitchedOffLightShowsWhatTheRaspberryWillUse() {
        val off = base.withLight(switchedOff()).ofAnOlderProgram()
        show(off.copy(lightDraft = LightDraft(color = Rgb(61, 214, 140), setAtMs = 300_000)))
        compose.onNodeWithText("Jas 100${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
        swatch("zelená").assertIsSelected()
        brightnessSlider().assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(100f, 1f..100f)))

        update(off.copy(lightDraft = LightDraft(brightness = 40, setAtMs = 300_000)))
        compose.onNodeWithText("Jas 40${NBSP}% · ruční volba platí do 19:00").assertIsDisplayed()
        swatch("bílá").assertIsSelected()
    }

    // Výběr barvy u zhasnutého světla světlo nerozsvítí: klepnutí barvu vybere (ViewModel ji uloží do Nastavení) a náhled zůstane
    // zhasnutý, zatímco karta ukazuje rozpracovanou barvu.
    @Test
    fun pickingAColorWhileTheLightIsOffKeepsThePreviewOff() {
        val off = base.withLight(switchedOff())
        show(off)

        swatch("oranžová").performClick()
        assertEquals(listOf("setLightColor(255, 138, 61)"), actions.calls)

        update(off.copy(lightDraft = LightDraft(color = Rgb(255, 138, 61), setAtMs = 300_000)))
        compose.onNodeWithText("Zhasnuto · venku je dost světla").assertIsDisplayed()
        assertNear("zhasnuté světlo", LightDomekColors.neutralContainer, glowCore("Zhasnuto · venku je dost světla"), EXACT)
        swatch("oranžová").assertIsSelected()
        compose.onNodeWithText("60${NBSP}%").assertIsDisplayed()
    }

    @Test
    fun tappingAPresetSetsItsColor() {
        show()

        swatch("červená").performClick()
        swatch("teplá bílá").performClick()

        assertEquals(listOf("setLightColor(244, 67, 54)", "setLightColor(255, 214, 160)"), actions.calls)
    }

    // Kolečko je vidět 36 dp velké, ale dotyková plocha je 48 dp: klepnutí do 5 dp vedle kolečka (a vně něj) ho vybere taky.
    @Test
    fun aTapJustBesideASwatchStillHitsIt() {
        show()

        swatch("červená").performTouchInput { click(Offset(-5.dp.toPx(), centerY)) }
        swatch("limetková").performTouchInput { click(Offset(width + 5.dp.toPx(), centerY)) }

        assertEquals(listOf("setLightColor(244, 67, 54)", "setLightColor(181, 229, 80)"), actions.calls)
    }

    @Test
    fun theSwatchesAreDisabledWithoutAConnection() {
        show(disconnected())
        (LIGHT_PRESETS.map { it.name } + "vlastní").forEach { swatch(it).assertIsNotEnabled() }

        swatch("červená").performClick()
        swatch("vlastní").performClick()

        assertEquals(emptyList<String>(), actions.calls)
        compose.onNodeWithText("Vlastní barva").assertDoesNotExist()

        update(base)
        (LIGHT_PRESETS.map { it.name } + "vlastní").forEach { swatch(it).assertIsEnabled() }
    }

    @Test
    fun theCustomColorOpensTheDialogWithTheChosenColor() {
        show()

        swatch("vlastní").performClick()
        compose.onNodeWithText("Vlastní barva").assertIsDisplayed()
        compose.onNodeWithText("Použít").performClick()

        // Odstín a sytost modré dávají po přepočtu zase tu samou modrou.
        assertEquals(listOf("setLightColor(100, 110, 255)"), actions.calls)
        compose.onNodeWithText("Vlastní barva").assertDoesNotExist()
    }

    // Otočení obrazovky s otevřeným dialogem ho nezavře.
    @Test
    fun theColorDialogStaysOpenAfterRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DomekTheme(darkTheme = false) { LightScreen(base, actions, wide = false) } }
        swatch("vlastní").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Vlastní barva").assertIsDisplayed()
    }

    @Test
    fun cancellingTheDialogChangesNothing() {
        show()

        swatch("vlastní").performClick()
        compose.onNodeWithText("Zrušit").performClick()

        assertEquals(emptyList<String>(), actions.calls)
        compose.onNodeWithText("Vlastní barva").assertDoesNotExist()
    }

    @Test
    fun theDialogStartsFromTheColorDraftToo() {
        show(base.copy(lightDraft = LightDraft(color = Rgb(0, 255, 0), setAtMs = 300_000)))

        swatch("vlastní").performClick()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf("setLightColor(0, 255, 0)"), actions.calls)
    }

    // Zhasnuté světlo hlásí černou, dialog ale začne uloženou barvou, kterou se světlo příště rozsvítí.
    @Test
    fun theDialogOfASwitchedOffLightStartsFromTheStoredColor() {
        show(base.withLight(switchedOff()).withStoredLook(Rgb(200, 40, 140), 80))

        swatch("vlastní").performClick()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf("setLightColor(200, 40, 140)"), actions.calls)
    }

    // Bez zvolené barvy (zhasnuté světlo staršího programu) začne dialog bílou.
    @Test
    fun withoutAChosenColorTheDialogStartsFromWhite() {
        show(base.withLight(switchedOff()).ofAnOlderProgram())

        swatch("vlastní").performClick()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf("setLightColor(255, 255, 255)"), actions.calls)
    }

    // ----- Jas -----

    @Test
    fun brightnessShowsTheValueOfTheLightNextToTheSlider() {
        show()

        compose.onNodeWithText("Jas").assertIsDisplayed()
        compose.onNodeWithText("60${NBSP}%").assertIsDisplayed()
        brightnessSlider().assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(60f, 1f..100f)))
    }

    // Hodnota posuvníku se bere ze světla s rozpracovaným jasem, aby se při tažení nevracel.
    @Test
    fun theSliderFollowsTheBrightnessDraft() {
        show(base.copy(lightDraft = LightDraft(brightness = 25, setAtMs = 300_000)))

        compose.onNodeWithText("25${NBSP}%").assertIsDisplayed()
        brightnessSlider().assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(25f, 1f..100f)))
    }

    // Jas se posílá při každé změně, ne až po puštění; ViewModel odesílání slučuje.
    @Test
    fun everyChangeOfTheSliderSendsTheRoundedBrightness() {
        show()

        brightnessSlider().performSemanticsAction(SemanticsActions.SetProgress) { it(30.4f) }
        brightnessSlider().performSemanticsAction(SemanticsActions.SetProgress) { it(70.6f) }
        brightnessSlider().performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        brightnessSlider().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        // Jas 0 % aplikace neposílá, nejnižší hodnota posuvníku je 1 %.
        assertEquals(
            listOf("setLightBrightness(30)", "setLightBrightness(71)", "setLightBrightness(100)", "setLightBrightness(1)"),
            actions.calls,
        )
    }

    @Test
    fun theSliderIsDisabledWithoutAConnection() {
        show(disconnected())
        brightnessSlider().assertIsNotEnabled()

        update(base.copy(settings = null))
        brightnessSlider().assertIsNotEnabled()

        update(base)
        brightnessSlider().assertIsEnabled()
    }

    // Starší program uložený jas nehlásí. Zhasnuté světlo hlásí jas 0 %, ale posuvník začíná na 1 %.
    @Test
    fun theSliderOfAnOlderProgramsSwitchedOffLightSitsAtItsLowEnd() {
        show(base.withLight(switchedOff()).ofAnOlderProgram())

        compose.onNodeWithText("0${NBSP}%").assertIsDisplayed()
        brightnessSlider().assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 1f..100f)))
    }

    // S uloženou barvou a jasem se výběr jen uloží a světlo ho použije, až svítí (spec 3).
    @Test
    fun theHintSaysThatTheChoiceIsStored() {
        show()

        compose.onNodeWithText(STORED_LOOK_HINT).assertIsDisplayed()
        compose.onNodeWithText(COMMAND_HINT).assertDoesNotExist()
    }

    // Dokud Nastavení nepřišlo, není jasné, co výběr udělá, takže vysvětlivka nic netvrdí.
    @Test
    fun noHintBeforeTheSettingsArrive() {
        show(base.copy(settings = null))

        compose.onNodeWithText(STORED_LOOK_HINT).assertDoesNotExist()
        compose.onNodeWithText(COMMAND_HINT).assertDoesNotExist()
    }

    @Test
    fun anOlderProgramsHintSaysThatChoosingAColorOrBrightnessSwitchesTheLightOnManually() {
        show(base.ofAnOlderProgram())

        compose.onNodeWithText(COMMAND_HINT).assertIsDisplayed()
        compose.onNodeWithText(STORED_LOOK_HINT).assertDoesNotExist()
    }

    // ----- Automatika -----

    @Test
    fun theSwitchShowsTheSettingAndWritesTheOppositeValue() {
        show()
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
        compose.onNodeWithText("Rozsvítit při pohybu").assertIsDisplayed()
        compose.onNodeWithText("v${NBSP}noci na chvíli rozsvítí noční světlo").assertIsDisplayed()

        compose.onNode(isToggleable()).performClick()
        update(withSettings { it.copy(lightOnMotion = false) })
        compose.onNode(isToggleable()).assertIsOff().performClick()

        assertEquals(listOf("setLightOnMotion(false)", "setLightOnMotion(true)"), actions.calls)
    }

    @Test
    fun theSwitchFollowsTheDraftBeforeTheRaspberryConfirmsIt() {
        show(withDraft(SettingKey.LIGHT_ON_MOTION, JsonPrimitive(false)))

        compose.onNode(isToggleable()).assertIsOff()
    }

    // Raspberry se starším programem klíč nezná: přepínač je zašedlý a podtitul to vysvětluje.
    @Test
    fun aRaspberryWithoutTheKeyShowsADisabledSwitchWithAnExplanation() {
        show(withSettings { it.copy(lightOnMotion = null) })

        compose.onNode(isToggleable()).assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Rozsvítit při pohybu").assertIsDisplayed()
        compose.onNodeWithText("Raspberry tuto volbu zatím nepodporuje").assertIsDisplayed()
        compose.onNodeWithText("v${NBSP}noci na chvíli rozsvítí noční světlo").assertDoesNotExist()

        compose.onNode(isToggleable()).performClick()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun theSwitchIsDisabledWithoutAConnectionAndBeforeTheSettingsArrive() {
        show(disconnected())
        compose.onNode(isToggleable()).assertIsNotEnabled()

        compose.onNode(isToggleable()).performClick()
        assertEquals(emptyList<String>(), actions.calls)

        update(base.copy(settings = null))
        compose.onNode(isToggleable()).assertIsNotEnabled()

        update(base)
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    // Dokud se Nastavení nepřečetlo, nemá smysl tvrdit, že Raspberry volbu nezná: přepínač jen čeká na data.
    @Test
    fun beforeTheSettingsArriveTheSwitchDoesNotClaimThatTheRaspberryLacksTheOption() {
        show(base.copy(settings = null))

        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("v${NBSP}noci na chvíli rozsvítí noční světlo").assertIsDisplayed()
        compose.onNodeWithText("Raspberry tuto volbu zatím nepodporuje").assertDoesNotExist()
    }

    @Test
    fun theStepperRowsHaveTheirNamesNotesAndValues() {
        show()

        compose.onNodeWithText("Automatika").assertIsDisplayed()
        compose.onNodeWithText("Noční světlo svítí").assertIsDisplayed()
        compose.onNodeWithText("5–600${NBSP}s").assertIsDisplayed()
        number(StepRow.NIGHT_LIGHT).assertTextEquals("30${NBSP}s")
        compose.onNodeWithText("Práh šera").assertIsDisplayed()
        compose.onNodeWithText("teď venku 320${NBSP}lx").assertIsDisplayed()
        number(StepRow.THRESHOLD).assertTextEquals("50${NBSP}lx")
    }

    @Test
    fun eachStepperChangesItsOwnSetting() {
        show()

        plus(StepRow.NIGHT_LIGHT).performClick()
        minus(StepRow.NIGHT_LIGHT).performClick()
        plus(StepRow.THRESHOLD).performClick()
        minus(StepRow.THRESHOLD).performClick()

        assertEquals(
            listOf(
                "changeSetting(nocni_svetlo_s, 35.0)",
                "changeSetting(nocni_svetlo_s, 25.0)",
                "changeSetting(prah_lux, 55.0)",
                "changeSetting(prah_lux, 45.0)",
            ),
            actions.calls,
        )
    }

    @Test
    fun valuesAreDraftFirstAndTheNextStepStartsFromTheDraft() {
        show(
            base.copy(
                drafts = mapOf(
                    SettingKey.NIGHT_LIGHT_SECONDS to JsonPrimitive(125),
                    SettingKey.LUX_THRESHOLD to JsonPrimitive(1500.0),
                ),
            ),
        )

        number(StepRow.NIGHT_LIGHT).assertTextEquals("125${NBSP}s")
        number(StepRow.THRESHOLD).assertTextEquals("1500${NBSP}lx")

        plus(StepRow.NIGHT_LIGHT).performClick()
        plus(StepRow.THRESHOLD).performClick()
        assertEquals(listOf("changeSetting(nocni_svetlo_s, 130.0)", "changeSetting(prah_lux, 2000.0)"), actions.calls)
    }

    @Test
    fun steppersAreDisabledWithoutAConnectionAndBeforeTheSettingsArrive() {
        show(disconnected())
        StepRow.entries.forEach {
            plus(it).assertIsNotEnabled()
            minus(it).assertIsNotEnabled()
        }

        update(base.copy(settings = null))
        StepRow.entries.forEach { plus(it).assertIsNotEnabled() }

        update(base.copy(connection = ConnectionState.Simulated))
        StepRow.entries.forEach {
            plus(it).assertIsEnabled()
            minus(it).assertIsEnabled()
        }
    }

    // Bez Nastavení není co ukázat, tak je u každého řádku „—“.
    @Test
    fun beforeTheSettingsArriveEveryValueIsADash() {
        show(base.copy(settings = null))

        StepRow.entries.forEach { number(it).assertTextEquals("—") }
    }

    @Test
    fun theRingTurnsOnlyInTheRowThatIsBeingWritten() {
        show()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()

        StepRow.entries.forEach { row ->
            update(base.copy(pendingKeys = setOf(row.key)))

            assertEquals("ukazatel u řádku ${row.title}", row, rowWithRing())
        }
    }

    // Při odesílání se jen otáčí ukazatel, tlačítka ani čísla se nesmí pohnout.
    @Test
    fun nothingMovesWhileWritesArePending() {
        fun layout() = StepRow.entries.flatMap {
            listOf(minus(it).getBoundsInRoot(), number(it).getBoundsInRoot(), plus(it).getBoundsInRoot())
        }
        show()
        val settled = layout()

        update(base.copy(pendingKeys = StepRow.entries.map { it.key }.toSet()))

        assertEquals(settled, layout())
    }

    // Venkovní osvětlení je hodnota ze Stavu, ne z Nastavení.
    @Test
    fun theThresholdNoteShowsTheLightOutsideFromTheStatus() {
        show()
        compose.onNodeWithText("teď venku 320${NBSP}lx").assertIsDisplayed()

        update(base.withStatus { it.copy(lux = 1234.0) })
        compose.onNodeWithText("teď venku 1234${NBSP}lx").assertIsDisplayed()

        update(base.withStatus { it.copy(lux = 0.4) })
        compose.onNodeWithText("teď venku 0${NBSP}lx").assertIsDisplayed()
    }

    @Test
    fun withoutALightSensorTheThresholdHasNoNote() {
        show(base.withStatus { it.copy(lux = null) })

        compose.onNodeWithText("Práh šera").assertIsDisplayed()
        compose.onNodeWithText("teď venku", substring = true).assertDoesNotExist()
    }

    // ----- Stará data -----

    // Spec 6.5: hodnoty ze Stavu (kruh a texty náhledu, osvětlení venku) zešednou, ovládací prvky ne.
    @Test
    fun staleDataDimThePreviewButNotTheControls() {
        show()
        val fresh = glowCore("Svítí · ručně")
        assertTrue("čerstvý kruh je modrý: $fresh", fresh.blue > fresh.red + 0.1f)

        update(stale())
        val dimmed = glowCore("Svítí · ručně")
        assertTrue("kruh zešedl: $fresh -> $dimmed", !isNear(fresh, dimmed))
        assertTrue("kruh ztratil sytost: $fresh -> $dimmed", dimmed.blue - dimmed.red < fresh.blue - fresh.red)

        button("Zapnout").assertIsEnabled()
        swatch("modrá").assertIsEnabled()
        brightnessSlider().assertIsEnabled()
        compose.onNode(isToggleable()).assertIsEnabled()
        plus(StepRow.NIGHT_LIGHT).assertIsEnabled()
    }

    // Osvětlení venku je hodnota ze Stavu, takže při starých datech zešedne i v Automatice.
    @Test
    fun staleDataDimTheLightLevelOutsideToo() {
        // Nejtmavší bod textu: zešedlý text je světlejší.
        fun darkestPointOfTheNote(): Float {
            val bounds = compose.onNodeWithText("teď venku 320${NBSP}lx").fetchSemanticsNode().boundsInRoot
            val image = compose.renderScreenshot("note")
            return (bounds.top.roundToInt() until bounds.bottom.roundToInt()).minOf { y ->
                (bounds.left.roundToInt() until bounds.right.roundToInt()).minOf { x -> image.colorAt(x.toFloat(), y.toFloat()).luminance() }
            }
        }
        show()
        val fresh = darkestPointOfTheNote()

        update(stale())
        val dimmed = darkestPointOfTheNote()

        assertTrue("text zešedl: $fresh -> $dimmed", dimmed > fresh + 0.2f)
    }

    // ----- Rozvržení -----

    @Test
    fun theCardTitlesAreHeadings() {
        show()

        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        val titles = headings.map { it.config[SemanticsProperties.Text].joinToString { text -> text.text } }
        assertEquals(listOf("Barva", "Jas", "Automatika"), titles)
    }

    @Test
    fun onANarrowScreenTheCardsAreInOneColumnInTheOrderOfThePreview() {
        show()

        val tops = listOf(
            lamp("Svítí · ručně").getBoundsInRoot().top,
            compose.onNodeWithText("Barva").getBoundsInRoot().top,
            compose.onNodeWithText("Jas").getBoundsInRoot().top,
            compose.onNodeWithText("Automatika").getBoundsInRoot().top,
        )
        assertEquals("pořadí: náhled, barva, jas, automatika: $tops", tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
    }

    @Test
    @Config(qualifiers = WIDE_TALL)
    fun onAWideScreenThePreviewIsOnTheLeftAndColorBrightnessAndAutomationOnTheRight() {
        show(wide = true)

        val preview = lamp("Svítí · ručně").getBoundsInRoot()
        val buttons = button("Vypnout").getBoundsInRoot()
        val color = compose.onNodeWithText("Barva").getBoundsInRoot()
        val brightness = compose.onNodeWithText("Jas").getBoundsInRoot()
        val automation = compose.onNodeWithText("Automatika").getBoundsInRoot()

        assertTrue("barva je vpravo od náhledu: $preview, $color", color.left > preview.right)
        assertTrue("barva je vpravo od tlačítek: $buttons, $color", color.left > buttons.right)
        assertTrue("automatika je pod barvou ve stejném sloupci: $color, $automation", automation.top > brightness.bottom)
        assertEquals("sloupce začínají nahoře ve stejné výšce", preview.top.value, color.top.value, 20f)
        assertTrue("jas je ve sloupci vpravo: $brightness", brightness.left > preview.right)
    }

    @Test
    fun noTextIsCutOffAtTheNormalFontSize() {
        show()

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    @Test
    fun noTextIsCutOffAtALargeFontSize() {
        show(withSettings { it.copy(lightOnMotion = null) }, fontScale = 1.5f)

        assertEquals(emptyList<String>(), compose.cutOffTexts())
        listOf("Zapnout", "Vypnout", "Vrátit automatiku").forEach { button(it).assertIsDisplayed() }
    }

    // Na úzké obrazovce a při velkém písmu se žádné tlačítko nezkracuje. Když se nevejdou do jedné řady, přejdou na další.
    @Test
    @Config(qualifiers = "w320dp-h1200dp-xxhdpi")
    fun buttonsWrapInsteadOfBeingCutOffOnANarrowPhoneWithLargeFonts() {
        show(fontScale = 1.3f)

        assertEquals(emptyList<String>(), compose.cutOffTexts())
        listOf("Zapnout", "Vypnout", "Vrátit automatiku").forEach { button(it).assertIsDisplayed() }
    }

    // Při dvojnásobném písmu se Zapnout a Vypnout vedle sebe nevejdou, tak se Vypnout přesune pod Zapnout, místo aby se popisek
    // zalomil uprostřed slova.
    @Test
    @Config(qualifiers = "w320dp-h1200dp-xxhdpi")
    fun atDoubleFontSizeTurnOffMovesBelowTurnOnInsteadOfBreakingItsLabel() {
        show(fontScale = 2f)

        assertEquals(emptyList<String>(), compose.textsBrokenInsideAWord())
        val turnOn = button("Zapnout").getBoundsInRoot()
        val turnOff = button("Vypnout").getBoundsInRoot()
        assertTrue("Vypnout je pod Zapnout: $turnOn, $turnOff", turnOff.top >= turnOn.bottom)
    }

    // Levý sloupec tabletu na výšku (318 dp) je užší než telefon, Zapnout a Vypnout se do něj ale vejdou vedle sebe.
    @Test
    @Config(qualifiers = "w720dp-h1200dp-xxhdpi")
    fun inTheNarrowLeftColumnOfATabletTurnOnAndOffStillShareARow() {
        show(wide = true)

        assertEquals(emptyList<String>(), compose.textsBrokenInsideAWord())
        assertTurnOnAndOffShareARowAndTheAutomaticButtonIsBelowThem()
    }

    // Do obrazovky Světlo se stav bez dat nedostane (DomekApp ukazuje obrazovku spojení), ale nesmí kvůli němu spadnout.
    @Test
    fun withoutDataNothingIsDrawn() {
        show(HouseUiState())

        compose.onNodeWithText("Barva").assertDoesNotExist()
        compose.onNodeWithText("Automatika").assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithText("Zapnout").fetchSemanticsNodes().size)
    }
}
