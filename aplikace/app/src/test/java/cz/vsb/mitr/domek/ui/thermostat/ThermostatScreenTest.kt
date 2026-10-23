package cz.vsb.mitr.domek.ui.thermostat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.DEMO_WALL
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
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LightColorScheme
import cz.vsb.mitr.domek.ui.theme.LightDomekColors
import cz.vsb.mitr.domek.viewmodel.HouseUiState
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

// Řádky s ovladačem shora dolů: tři cíle a dva řádky „nikdo doma“. Pořadí je pořadí ovladačů ve stromu obrazovky.
private enum class StepRow(val title: String) {
    DAY("Denní"),
    NIGHT("Noční"),
    MANUAL("Ruční"),
    AFTER("Snížit cíl po"),
    SETBACK("Snížit o"),
}

private val STEP_KEYS = mapOf(
    StepRow.DAY to SettingKey.TARGET_DAY,
    StepRow.NIGHT to SettingKey.TARGET_NIGHT,
    StepRow.MANUAL to SettingKey.TARGET_MANUAL,
    StepRow.AFTER to SettingKey.NOBODY_HOME_AFTER,
    StepRow.SETBACK to SettingKey.NOBODY_HOME_SETBACK,
)

// Vysvětlivka Auto: za jednopísmennými „a“ a „v“ je nezlomitelná mezera, aby nezůstaly na konci řádku.
private fun autoExplanation(now: String, applies: String) =
    "Ve dne a${NBSP}večer drží denní cíl, v${NBSP}noci noční. Teď je $now, takže platí $applies."

// Řádek s prahy: topení naskočí pod cílem o víc než 0,3 °C a chlazení nad cílem o víc než 1,0 °C (docs/ble-rozhrani.md).
private fun thresholds(heatBelow: String, coolAbove: String) =
    "Topení naskočí pod $heatBelow${NBSP}°C, chlazení nad $coolAbove${NBSP}°C"

// Útlum „nikdo doma“ platí jen v režimu Auto (docs/ble-rozhrani.md, Cíl).
private val SETBACK_NOTE = "0–10${NBSP}°C, jen v${NBSP}Auto"

// Odstín zvýrazněného řádku z náhledu (.vr.act), světlejší než primaryContainer. Tolerance pokrývá odchylku dvou odstínů z 255,
// protože obrazovka míchá primaryContainer s bílou kartou.
private val ACTIVE_ROW_TINT = Color(0xFFE2F3F3)
private const val ACTIVE_ROW_TOLERANCE = 0.015f

private const val MANUAL_EXPLANATION = "Drží ruční cíl bez ohledu na denní dobu."
private const val OFF_EXPLANATION = "Termostat netopí ani nechladí."
private val TARGETS_HINT =
    "5–35${NBSP}°C po 0,5${NBSP}°C. Podržením tlačítka se hodnota mění rychleji, klepnutím na číslo jde napsat přímo."

// Obrazovka Termostat bez aplikace kolem: texty, podmínky a klepnutí. Obrazovka je tak vysoká, aby se celý obsah vešel a šel
// zkoušet bez posouvání. Skutečná písma a hustota telefonu jsou tu kvůli rozměrům a barvám, které se porovnávají.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class ThermostatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private var shown by mutableStateOf(HouseUiState())

    // Výchozí stav je demoState bez upozornění na hodiny (na této obrazovce nehraje roli, ale drží stejný příběh jako náhledy).
    private val base = demoStateWithoutClockWarning()

    private fun show(state: HouseUiState = base, wide: Boolean = false, fontScale: Float = 1f) {
        shown = state
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme = false) {
                    // Pozadí stránky je pod kartami jako v aplikaci, jinak by zešedlá bílá karta byla na snímku jen průhledná.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ThermostatScreen(shown, actions, wide)
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

    private fun HouseUiState.withMode(mode: ThermostatMode, target: Double?) =
        copy(settings = checkNotNull(settings).copy(mode = mode)).withStatus {
            it.copy(mode = mode, target = target, heating = target != null && it.heating)
        }

    private fun manual() = base.withMode(ThermostatMode.MANUAL, target = 22.0)

    private fun off() = base.withMode(ThermostatMode.OFF, target = null)

    private fun atNight() = base.withStatus { it.copy(dayPartText = DayPart.NIGHT.json, time = "2026-10-20T22:30:00", target = 20.0) }

    private fun inTheEvening() = base.withStatus { it.copy(dayPartText = DayPart.EVENING.json, time = "2026-10-20T19:30:00") }

    private fun withDraft(key: SettingKey, value: JsonPrimitive) = base.copy(drafts = mapOf(key to value))

    // Za 7 s od posledního Stavu (přijatého v 300 000 ms).
    private fun stale(state: HouseUiState = base): HouseUiState = state.copy(nowMs = 307_000)

    // ----- Vyhledávání prvků -----

    private val isSegment = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    // Číslo ovladače se pozná podle popisku klepnutí, na rozdíl od stejného textu v souhrnu.
    private val isStepperNumber =
        SemanticsMatcher("číslo ovladače") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Zadat číslo" }

    // Segment režimu: „Ruční“ a „Vypnuto“ jsou i názvy řádku a štítek stavu, ty se ale nedají klepnout.
    private fun segment(mode: ThermostatMode) = compose.onNode(hasText(mode.label) and isSegment)

    private fun rowTitle(row: StepRow) = compose.onNode(hasText(row.title) and !hasClickAction())

    private fun plus(row: StepRow): SemanticsNodeInteraction = compose.onAllNodesWithContentDescription("Zvýšit")[row.ordinal]

    private fun minus(row: StepRow): SemanticsNodeInteraction = compose.onAllNodesWithContentDescription("Snížit")[row.ordinal]

    private fun number(row: StepRow): SemanticsNodeInteraction = compose.onAllNodes(isStepperNumber)[row.ordinal]

    private fun titleBounds(row: StepRow) = rowTitle(row).fetchSemanticsNode().boundsInRoot

    // Řádek, u kterého je štítek „platí teď“: poslední z cílových řádků, jehož název je nad štítkem.
    private fun rowWithBadge(): StepRow? {
        val badges = compose.onAllNodesWithText("platí teď").fetchSemanticsNodes()
        if (badges.isEmpty()) return null
        assertEquals("štítek „platí teď“ je právě jeden", 1, badges.size)
        val badgeTop = badges.single().boundsInRoot.top
        return listOf(StepRow.DAY, StepRow.NIGHT, StepRow.MANUAL).lastOrNull { titleBounds(it).top <= badgeTop }
    }

    // Řádek, ve kterém se točí ukazatel odesílání: ten, jehož název je mu svisle nejblíž.
    private fun rowWithRing(): StepRow {
        val rings = compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes()
        assertEquals("ukazatel odesílání je právě jeden", 1, rings.size)
        val y = rings.single().boundsInRoot.center.y
        return StepRow.entries.minBy { abs(titleBounds(it).center.y - y) }
    }

    // Barva obrazovky 4 dp vlevo od názvu řádku: uvnitř odsazení řádku, tedy na jeho pozadí.
    private fun backgroundOf(row: StepRow): Color {
        val bounds = titleBounds(row)
        return compose.renderScreenshot("row").colorAt(bounds.left - 4 * compose.density.density, bounds.center.y)
    }

    // Barva obrazovky vpravo od prvního uzlu s tímto textem, `dp` od jeho okraje.
    private fun colorRightOf(text: String, dp: Float): Color {
        val bounds = compose.onAllNodesWithText(text)[0].fetchSemanticsNode().boundsInRoot
        return compose.renderScreenshot("color").colorAt(bounds.right + dp * compose.density.density, bounds.center.y)
    }

    // Jas nejtmavšího bodu snímku uvnitř prvku s tímto textem. Text je tmavší než karta, takže zeslábnutí je poznat podle toho, že
    // se nejtmavší bod zesvětlí.
    private fun darkestPixelOf(text: String): Float {
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val image = compose.renderScreenshot("darkest")
        var darkest = 1f
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                darkest = minOf(darkest, image.colorAt(x.toFloat(), y.toFloat()).luminance())
            }
        }
        return darkest
    }

    private val isChip = { text: String -> hasText(text) and !hasClickAction() }

    // ----- Souhrn -----

    @Test
    fun summaryShowsTheInsideTemperatureTheValidTargetAndTheChip() {
        show()

        compose.onNode(hasText("Uvnitř") and hasText("22,4${NBSP}°C")).assertIsDisplayed()
        compose.onNode(hasText("Platný cíl") and hasText("23,0${NBSP}°C")).assertIsDisplayed()
        compose.onNodeWithText("Topí").assertIsDisplayed()
    }

    @Test
    fun summaryChipFollowsWhatTheThermostatIsDoing() {
        show()
        compose.onNodeWithText("Topí").assertIsDisplayed()

        update(base.withStatus { it.copy(heating = false, cooling = true) })
        compose.onNodeWithText("Chladí").assertIsDisplayed()
        compose.onNodeWithText("Topí").assertDoesNotExist()

        update(base.withStatus { it.copy(heating = false, cooling = false) })
        compose.onNodeWithText("V${NBSP}klidu").assertIsDisplayed()

        update(off())
        compose.onNode(isChip("Vypnuto")).assertIsDisplayed()
        compose.onNodeWithText("V${NBSP}klidu").assertDoesNotExist()
    }

    // Vypnutý termostat netopí, i kdyby poslední Stav ještě nesl příznak topení.
    @Test
    fun summaryInOffModeNeverClaimsToBeHeating() {
        show(base.withStatus { it.copy(mode = ThermostatMode.OFF, target = null, heating = true) })

        compose.onNode(isChip("Vypnuto")).assertIsDisplayed()
        compose.onNodeWithText("Topí").assertDoesNotExist()
    }

    // Štítek říká, co termostat opravdu dělá. Rozpracovaná volba režimu na tom nic nezmění, dokud ji Raspberry nepotvrdí.
    @Test
    fun summaryChipIgnoresTheDraftModeUntilTheRaspberryConfirmsIt() {
        show(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.OFF.json)))

        compose.onNodeWithText("Topí").assertIsDisplayed()
        compose.onNode(isChip("Vypnuto")).assertDoesNotExist()
    }

    @Test
    fun summaryChipHasTheHeatCoolAndNeutralColours() {
        // Za textem štítku je ještě odsazení 14 dp.
        show()
        assertNear("Topí", LightDomekColors.heat, colorRightOf("Topí", 7f))

        update(base.withStatus { it.copy(heating = false, cooling = true) })
        assertNear("Chladí", LightDomekColors.cool, colorRightOf("Chladí", 7f))

        update(base.withStatus { it.copy(heating = false, cooling = false) })
        assertNear("V${NBSP}klidu", LightDomekColors.neutralContainer, colorRightOf("V${NBSP}klidu", 7f))
    }

    @Test
    fun summaryWithoutAnInsideTemperatureShowsDashAndTheSensorNote() {
        show(base.withStatus { it.copy(insideTemp = null) })

        compose.onNode(hasText("Uvnitř · chyba čidla") and hasText("—")).assertIsDisplayed()
    }

    @Test
    fun summaryShowsDashForTheValidTargetWhileTheThermostatIsOff() {
        show(off())

        compose.onNode(hasText("Platný cíl") and hasText("—")).assertIsDisplayed()
    }

    // Platný cíl je ten, který hlásí Raspberry; rozpracovaná hodnota se ukazuje jen v řádku s ovladačem.
    @Test
    fun summaryKeepsTheValidTargetFromTheStatusWhileTheRowShowsTheDraft() {
        show(withDraft(SettingKey.TARGET_DAY, JsonPrimitive(24.5)))

        compose.onNode(hasText("Platný cíl") and hasText("23,0${NBSP}°C")).assertIsDisplayed()
        number(StepRow.DAY).assertTextEquals("24,5${NBSP}°C")
    }

    // ----- Prahy -----

    @Test
    fun summaryShowsWhereTheThermostatStartsHeatingAndCooling() {
        show()

        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()
    }

    // Nahoře vlevo v kartě se stavem: pod hodnotami a zarovnaný s první z nich.
    @Test
    fun theThresholdsLineIsUnderTheValuesOfTheSummary() {
        show()

        val line = compose.onNodeWithText(thresholds("22,7", "24,0")).getBoundsInRoot()
        val inside = compose.onNode(hasText("Uvnitř") and hasText("22,4${NBSP}°C")).getBoundsInRoot()
        val target = compose.onNode(hasText("Platný cíl") and hasText("23,0${NBSP}°C")).getBoundsInRoot()
        assertTrue("řádek je pod hodnotami: $line, $inside, $target", line.top >= maxOf(inside.bottom, target.bottom))
        assertEquals("řádek začíná pod první hodnotou", inside.left.value, line.left.value, 0.5f)
    }

    // Prahy se počítají z platného cíle ve Stavu, ne z cíle jednoho z řádků.
    @Test
    fun thresholdsFollowTheValidTargetOfTheStatus() {
        show()
        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()

        update(atNight())
        compose.onNodeWithText(thresholds("19,7", "21,0")).assertIsDisplayed()
        compose.onNodeWithText(thresholds("22,7", "24,0")).assertDoesNotExist()

        update(manual())
        compose.onNodeWithText(thresholds("21,7", "23,0")).assertIsDisplayed()

        update(base.withStatus { it.copy(target = 22.5) })
        compose.onNodeWithText(thresholds("22,2", "23,5")).assertIsDisplayed()
    }

    // Rozpracovaná hodnota se ukazuje jen v řádku s ovladačem, prahy zůstávají podle Stavu.
    @Test
    fun thresholdsKeepTheTargetFromTheStatusWhileTheRowShowsTheDraft() {
        show(withDraft(SettingKey.TARGET_DAY, JsonPrimitive(24.5)))

        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()
        number(StepRow.DAY).assertTextEquals("24,5${NBSP}°C")
    }

    // Vypnutý termostat netopí ani nechladí a Stav nemá cíl, takže není co ukázat.
    @Test
    fun thresholdsAreMissingWithoutATarget() {
        show(off())
        compose.onNodeWithText("naskočí pod", substring = true).assertDoesNotExist()
        compose.onNodeWithText("chlazení nad", substring = true).assertDoesNotExist()

        update(base.withStatus { it.copy(target = null) })
        compose.onNodeWithText("naskočí pod", substring = true).assertDoesNotExist()
        compose.onNodeWithText("chlazení nad", substring = true).assertDoesNotExist()

        update(base)
        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()
    }

    // Při poruše BME280 termostat stojí (docs/ble-rozhrani.md, Hodnoty v poli chyby), takže prahy neplatí.
    @Test
    fun thresholdsAreHiddenWhileTheTemperatureSensorIsFaulty() {
        show(base.withStatus { it.copy(errors = listOf(METEO_FAULT)) })
        compose.onNodeWithText("naskočí pod", substring = true).assertDoesNotExist()

        update(base)
        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()
    }

    // Řádek je v kartě souhrnu, takže stará data ztlumí i jeho text.
    @Test
    fun staleDataDimTheThresholdsLine() {
        show()
        val fresh = darkestPixelOf(thresholds("22,7", "24,0"))

        update(stale())
        val dimmed = darkestPixelOf(thresholds("22,7", "24,0"))

        assertTrue("text prahů zešedl: nejtmavší bod $fresh → $dimmed", dimmed > fresh + 0.2f)
    }

    // Při velkém písmu se řádek zalomí pod hodnoty, nic se neořízne.
    @Test
    fun thresholdsAreWhollyVisibleAtALargeFontSize() {
        show(fontScale = 1.5f)

        compose.onNodeWithText(thresholds("22,7", "24,0")).assertIsDisplayed()
        assertEquals(emptyList<String>(), compose.cutOffTexts() + compose.verticallyClippedTexts())
    }

    // ----- Režim -----

    @Test
    fun modeHasThreeSegmentsAndTheOneFromTheSettingsIsSelected() {
        show()

        segment(ThermostatMode.AUTO).assertIsSelected()
        segment(ThermostatMode.MANUAL).assertIsNotSelected()
        segment(ThermostatMode.OFF).assertIsNotSelected()

        update(manual())
        segment(ThermostatMode.AUTO).assertIsNotSelected()
        segment(ThermostatMode.MANUAL).assertIsSelected()

        update(off())
        segment(ThermostatMode.OFF).assertIsSelected()
        segment(ThermostatMode.MANUAL).assertIsNotSelected()
    }

    // Rozpracovaná volba má přednost před Nastavením z Raspberry, aby uživatel hned viděl, co zvolil (spec 7).
    @Test
    fun theSelectedSegmentShowsTheDraftBeforeTheRaspberryConfirmsIt() {
        show(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.MANUAL.json)))

        segment(ThermostatMode.MANUAL).assertIsSelected()
        segment(ThermostatMode.AUTO).assertIsNotSelected()
    }

    // Než přijde Nastavení, ukazuje se režim ze Stavu.
    @Test
    fun beforeTheSettingsArriveTheSegmentShowsTheModeFromTheStatus() {
        show(manual().copy(settings = null))

        segment(ThermostatMode.MANUAL).assertIsSelected()
    }

    @Test
    fun tappingASegmentSetsThatMode() {
        show()

        segment(ThermostatMode.MANUAL).performClick()
        segment(ThermostatMode.OFF).performClick()

        assertEquals(listOf("setMode(MANUAL)", "setMode(OFF)"), actions.calls)
    }

    // Opakovaný zápis stejného režimu by jen zbytečně točil ukazatelem a zdržoval zápis.
    @Test
    fun tappingTheSelectedSegmentWritesNothing() {
        show()

        segment(ThermostatMode.AUTO).performClick()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun segmentsAreDisabledWithoutAConnectionAndBeforeTheSettingsArrive() {
        show(base.copy(connection = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000)))
        ThermostatMode.entries.forEach { segment(it).assertIsNotEnabled() }

        update(base.copy(settings = null))
        ThermostatMode.entries.forEach { segment(it).assertIsNotEnabled() }

        update(base)
        ThermostatMode.entries.forEach { segment(it).assertIsEnabled() }
    }

    @Test
    fun explanationOfAutoNamesTheDayPartAndTheTargetThatApplies() {
        show()
        compose.onNodeWithText(autoExplanation("den", "denní")).assertIsDisplayed()

        update(inTheEvening())
        compose.onNodeWithText(autoExplanation("večer", "denní")).assertIsDisplayed()

        update(atNight())
        compose.onNodeWithText(autoExplanation("noc", "noční")).assertIsDisplayed()
    }

    // Raspberry drží v Auto noční cíl jen v noci, takže u nového úseku platí denní (docs/ble-rozhrani.md, Cíl).
    @Test
    fun autoExplanationNamesAnUnknownDayPartAsItCame() {
        show(base.withStatus { it.copy(dayPartText = "odpoledne") })

        compose.onNodeWithText(autoExplanation("odpoledne", "denní")).assertIsDisplayed()
    }

    @Test
    fun autoExplanationWithoutDayPartLeavesOutTheCurrentPart() {
        show(base.withStatus { it.copy(dayPartText = null) })

        compose.onNodeWithText("Ve dne a${NBSP}večer drží denní cíl, v${NBSP}noci noční.").assertIsDisplayed()
    }

    @Test
    fun explanationOfManualAndOffDoNotDependOnTheDayPart() {
        show(manual())
        compose.onNodeWithText(MANUAL_EXPLANATION).assertIsDisplayed()

        update(off())
        compose.onNodeWithText(OFF_EXPLANATION).assertIsDisplayed()

        update(atNight().withMode(ThermostatMode.MANUAL, target = 22.0))
        compose.onNodeWithText(MANUAL_EXPLANATION).assertIsDisplayed()
    }

    @Test
    fun explanationFollowsTheDraftMode() {
        show(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.OFF.json)))

        compose.onNodeWithText(OFF_EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(autoExplanation("den", "denní")).assertDoesNotExist()
    }

    // ----- Cílové teploty -----

    @Test
    fun targetRowsHaveNamesNotesAndTheValuesFromTheSettings() {
        show()

        compose.onNodeWithText("Cílové teploty").assertIsDisplayed()
        rowTitle(StepRow.DAY).assertIsDisplayed()
        compose.onNodeWithText("den a${NBSP}večer").assertIsDisplayed()
        rowTitle(StepRow.NIGHT).assertIsDisplayed()
        compose.onNodeWithText("noc").assertIsDisplayed()
        rowTitle(StepRow.MANUAL).assertIsDisplayed()
        compose.onNodeWithText("režim Ruční").assertIsDisplayed()
        number(StepRow.DAY).assertTextEquals("23,0${NBSP}°C")
        number(StepRow.NIGHT).assertTextEquals("20,0${NBSP}°C")
        number(StepRow.MANUAL).assertTextEquals("22,0${NBSP}°C")
        compose.onNodeWithText(TARGETS_HINT).assertIsDisplayed()
    }

    @Test
    fun theDayTargetAppliesByDayAndInTheEvening() {
        show()
        assertEquals(StepRow.DAY, rowWithBadge())

        update(inTheEvening())
        assertEquals(StepRow.DAY, rowWithBadge())
    }

    @Test
    fun theNightTargetAppliesAtNight() {
        show(atNight())

        assertEquals(StepRow.NIGHT, rowWithBadge())
    }

    @Test
    fun theManualTargetAppliesInManualModeAtAnyTimeOfDay() {
        show(manual())
        assertEquals(StepRow.MANUAL, rowWithBadge())

        update(atNight().withMode(ThermostatMode.MANUAL, target = 22.0))
        assertEquals(StepRow.MANUAL, rowWithBadge())
    }

    @Test
    fun noTargetAppliesWhileTheThermostatIsOff() {
        show(off())

        assertEquals(null, rowWithBadge())
        compose.onNodeWithText("platí teď").assertDoesNotExist()
    }

    // Zvýrazněný řádek se řídí režimem s rozpracovanou volbou, ne režimem ze Stavu, který se změní až po zápisu.
    @Test
    fun theActiveRowFollowsTheDraftMode() {
        show(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.MANUAL.json)))
        assertEquals(StepRow.MANUAL, rowWithBadge())

        update(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.OFF.json)))
        assertEquals(null, rowWithBadge())

        update(withDraft(SettingKey.MODE, JsonPrimitive(ThermostatMode.AUTO.json)))
        assertEquals(StepRow.DAY, rowWithBadge())
    }

    // Úsek dne se bere ze Stavu (spec 6.1), stejně jako ve Přehledu.
    @Test
    fun theActiveRowFollowsTheDayPartFromTheStatus() {
        show(base.withStatus { it.copy(dayPartText = DayPart.NIGHT.json) })

        assertEquals(StepRow.NIGHT, rowWithBadge())
    }

    @Test
    fun theActiveRowIsHighlightedAndTheOthersStayWhite() {
        show()
        assertNear("denní řádek", ACTIVE_ROW_TINT, backgroundOf(StepRow.DAY), ACTIVE_ROW_TOLERANCE)
        assertNear("noční řádek", LightColorScheme.surface, backgroundOf(StepRow.NIGHT), EXACT)
        assertNear("ruční řádek", LightColorScheme.surface, backgroundOf(StepRow.MANUAL), EXACT)

        update(atNight())
        assertNear("denní řádek v noci", LightColorScheme.surface, backgroundOf(StepRow.DAY), EXACT)
        assertNear("noční řádek v noci", ACTIVE_ROW_TINT, backgroundOf(StepRow.NIGHT), ACTIVE_ROW_TOLERANCE)

        update(off())
        StepRow.entries.take(3).forEach { assertNear("řádek ${it.title} při vypnutí", LightColorScheme.surface, backgroundOf(it), EXACT) }
    }

    // ----- Ovladače -----

    @Test
    fun eachStepperChangesItsOwnSetting() {
        show()

        plus(StepRow.DAY).performClick()
        minus(StepRow.NIGHT).performClick()
        plus(StepRow.MANUAL).performClick()
        plus(StepRow.AFTER).performClick()
        minus(StepRow.SETBACK).performClick()

        assertEquals(
            listOf(
                "changeSetting(cil_den, 23.5)",
                "changeSetting(cil_noc, 19.5)",
                "changeSetting(cil_rucni, 22.5)",
                "changeSetting(nikdo_doma_po_min, 31.0)",
                "changeSetting(utlum_nikdo_doma, 2.5)",
            ),
            actions.calls,
        )
    }

    // Všechny řádky se dají změnit bez ohledu na to, který právě platí.
    @Test
    fun stepperOfARowThatDoesNotApplyStillChangesItsTarget() {
        show(manual())

        minus(StepRow.DAY).performClick()

        assertEquals(listOf("changeSetting(cil_den, 22.5)"), actions.calls)
    }

    @Test
    fun valuesAreDraftFirstAndTheNextStepStartsFromTheDraft() {
        show(
            base.copy(
                drafts = mapOf(
                    SettingKey.TARGET_DAY to JsonPrimitive(24.5),
                    SettingKey.TARGET_NIGHT to JsonPrimitive(18.5),
                    SettingKey.TARGET_MANUAL to JsonPrimitive(21.0),
                    SettingKey.NOBODY_HOME_AFTER to JsonPrimitive(45),
                    SettingKey.NOBODY_HOME_SETBACK to JsonPrimitive(4.5),
                ),
            ),
        )

        number(StepRow.DAY).assertTextEquals("24,5${NBSP}°C")
        number(StepRow.NIGHT).assertTextEquals("18,5${NBSP}°C")
        number(StepRow.MANUAL).assertTextEquals("21,0${NBSP}°C")
        number(StepRow.AFTER).assertTextEquals("45${NBSP}min")
        number(StepRow.SETBACK).assertTextEquals("4,5${NBSP}°C")

        plus(StepRow.DAY).performClick()
        minus(StepRow.AFTER).performClick()
        assertEquals(listOf("changeSetting(cil_den, 25.0)", "changeSetting(nikdo_doma_po_min, 44.0)"), actions.calls)
    }

    @Test
    fun steppersAreDisabledWithoutAConnectionAndBeforeTheSettingsArrive() {
        show(base.copy(connection = ConnectionState.Scanning))
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

    // Každý řádek má vlastní ukazatel odesílání podle svého klíče Nastavení.
    @Test
    fun theRingTurnsOnlyInTheRowThatIsBeingWritten() {
        show()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()

        StepRow.entries.forEach { row ->
            update(base.copy(pendingKeys = setOf(checkNotNull(STEP_KEYS[row]))))

            assertEquals("ukazatel u řádku ${row.title}", row, rowWithRing())
        }
    }

    // Při odesílání se jen otáčí ukazatel, tlačítka ani čísla se nesmí pohnout (viditelné poskočení při každém klepnutí).
    @Test
    fun nothingMovesWhileWritesArePending() {
        fun layout() = StepRow.entries.flatMap {
            listOf(minus(it).getBoundsInRoot(), number(it).getBoundsInRoot(), plus(it).getBoundsInRoot())
        }
        show()
        val settled = layout()

        update(base.copy(pendingKeys = STEP_KEYS.values.toSet()))

        assertEquals(settled, layout())
    }

    // ----- Nikdo doma -----

    @Test
    fun theStatusLineSaysSomebodyIsHomeWithTheTimeOfTheLastMotion() {
        show()
        compose.onNodeWithText("Doma je někdo · poslední pohyb před 40${NBSP}s").assertIsDisplayed()

        update(base.copy(lastMotionAt = DEMO_WALL.minusSeconds(95)))
        compose.onNodeWithText("Doma je někdo · poslední pohyb před 1${NBSP}min").assertIsDisplayed()
    }

    @Test
    fun theStatusLineSaysNobodyIsHomeAndByHowMuchTheTargetIsLowered() {
        show(base.withStatus { it.copy(nobodyHome = true, target = 20.0) })

        compose.onNodeWithText("Nikdo doma · cíl je snížený o${NBSP}3,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("Doma je někdo", substring = true).assertDoesNotExist()
    }

    // Snížení se ukazuje stejné jako v ovladači pod ním, tedy i rozpracované.
    @Test
    fun theStatusLineFollowsTheSetbackBeingEdited() {
        show(withDraft(SettingKey.NOBODY_HOME_SETBACK, JsonPrimitive(4.5)).withStatus { it.copy(nobodyHome = true, target = 18.5) })

        compose.onNodeWithText("Nikdo doma · cíl je snížený o${NBSP}4,5${NBSP}°C").assertIsDisplayed()
        number(StepRow.SETBACK).assertTextEquals("4,5${NBSP}°C")
    }

    @Test
    fun theStatusLineSaysNobodyIsHomeEvenWithoutTheSettings() {
        show(base.copy(settings = null).withStatus { it.copy(nobodyHome = true, target = 20.0) })

        compose.onNodeWithText("Nikdo doma · cíl je snížený").assertIsDisplayed()
    }

    @Test
    fun theStatusLineInManualModeSaysTheTargetIsNotLowered() {
        show(base.withStatus { it.copy(mode = ThermostatMode.MANUAL, nobodyHome = true, target = 22.0) })

        compose.onNodeWithText("Nikdo doma · v${NBSP}ručním režimu se cíl nesnižuje").assertIsDisplayed()
    }

    @Test
    fun theStatusLineWhenTheThermostatIsOffClaimsNoTarget() {
        show(base.withStatus { it.copy(mode = ThermostatMode.OFF, nobodyHome = true, target = null) })

        compose.onAllNodesWithText("Nikdo doma").assertCountEquals(2)
        compose.onNodeWithText("snížený", substring = true).assertDoesNotExist()
    }

    @Test
    fun theStatusLineWithoutAnyMotionYet() {
        show(base.copy(lastMotionAt = null))

        compose.onNodeWithText("Pohyb zatím nezaznamenán").assertIsDisplayed()
        compose.onNodeWithText("Doma je někdo", substring = true).assertDoesNotExist()
    }

    @Test
    fun nobodyHomeRowsHaveTheirNamesNotesAndValues() {
        show()

        compose.onNodeWithText("Nikdo doma").assertIsDisplayed()
        rowTitle(StepRow.AFTER).assertIsDisplayed()
        compose.onNodeWithText("minutách bez pohybu").assertIsDisplayed()
        number(StepRow.AFTER).assertTextEquals("30${NBSP}min")
        rowTitle(StepRow.SETBACK).assertIsDisplayed()
        compose.onNodeWithText(SETBACK_NOTE).assertIsDisplayed()
        number(StepRow.SETBACK).assertTextEquals("3,0${NBSP}°C")
    }

    // Poznámka o útlumu je delší, při velkém písmu se nesmí uříznout ani zalomit uprostřed slova a ovladač zůstane vedle ní.
    @Test
    fun theSetbackNoteStaysWholeAtALargeFontSize() {
        show(fontScale = 1.5f)

        compose.onNodeWithText(SETBACK_NOTE).assertIsDisplayed()
        number(StepRow.SETBACK).assertIsDisplayed()
        assertEquals(emptyList<String>(), (compose.cutOffTexts() + compose.textsBrokenInsideAWord()).filter { it == SETBACK_NOTE })
    }

    // ----- Stará data -----

    // Spec 6.5: hodnoty ze Stavu zešednou, ovládací prvky ne (ty se zešednou jen bez spojení).
    @Test
    fun staleDataDimTheSummaryAndTheStatusLineButNotTheControls() {
        // Posledních 6 dp řádku je jeho odsazení, tedy pozadí bez textu.
        fun besideTheStatusLine() = colorRightOf("Doma je někdo · poslední pohyb před 40${NBSP}s", -6f)

        show()
        assertNear("štítek Topí", LightDomekColors.heat, colorRightOf("Topí", 7f))
        assertNear("pozadí řádku s „nikdo doma“", LightColorScheme.surfaceContainer, besideTheStatusLine(), EXACT)
        assertNear("zvýrazněný řádek", ACTIVE_ROW_TINT, backgroundOf(StepRow.DAY), ACTIVE_ROW_TOLERANCE)

        update(stale())
        assertTrue("štítek Topí zešedl", !isNear(LightDomekColors.heat, colorRightOf("Topí", 7f)))
        assertTrue("řádek s „nikdo doma“ zešedl", !isNear(LightColorScheme.surfaceContainer, besideTheStatusLine(), EXACT))
        assertNear("zvýrazněný řádek zůstává plný", ACTIVE_ROW_TINT, backgroundOf(StepRow.DAY), ACTIVE_ROW_TOLERANCE)
        plus(StepRow.DAY).assertIsEnabled()
    }

    // ----- Rozvržení -----

    @Test
    fun theCardTitlesAreHeadings() {
        show()

        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        val titles = headings.map { it.config[SemanticsProperties.Text].joinToString { text -> text.text } }
        assertEquals(listOf("Režim", "Cílové teploty", "Nikdo doma"), titles)
    }

    @Test
    fun onANarrowScreenTheCardsAreInOneColumnInTheOrderOfThePreview() {
        show()

        val tops = listOf("Uvnitř", "Režim", "Cílové teploty", "Nikdo doma").map { title ->
            compose.onAllNodesWithText(title)[0].getBoundsInRoot().top
        }
        assertEquals("pořadí karet: souhrn, režim, cíle, nikdo doma: $tops", tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
    }

    @Test
    @Config(qualifiers = WIDE_TALL)
    fun onAWideScreenSummaryAndModeAreOnTheLeftAndTargetsAndNobodyHomeOnTheRight() {
        show(wide = true)

        val summary = compose.onAllNodesWithText("Uvnitř")[0].getBoundsInRoot()
        val mode = segment(ThermostatMode.OFF).getBoundsInRoot()
        val targets = compose.onNodeWithText("Cílové teploty").getBoundsInRoot()
        val nobodyHome = compose.onNodeWithText("Nikdo doma").getBoundsInRoot()

        assertTrue("cíle jsou vpravo od režimu: $mode, $targets", targets.left > mode.right)
        assertTrue("nikdo doma je vpravo od režimu: $mode, $nobodyHome", nobodyHome.left > mode.right)
        assertTrue("cíle jsou nahoře ve svém sloupci: $targets", targets.top < 40.dp)
        assertTrue("režim je pod souhrnem: $summary, $mode", summary.bottom < mode.top)
        assertTrue("nikdo doma je pod cíli: $targets, $nobodyHome", targets.bottom < nobodyHome.top)
        assertEquals("souhrn je nahoře ve svém sloupci", summary.top.value, targets.top.value, 0.5f)
    }

    @Test
    fun noTextIsCutOffAtTheNormalFontSize() {
        show(base.withStatus { it.copy(insideTemp = null) }.copy(lastMotionAt = null))

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Při velkém písmu se popisky řádků zalomí a štítek stavu se přesune pod teploty, nic se nezkracuje.
    @Test
    fun noTextIsCutOffAtALargeFontSize() {
        show(base.withStatus { it.copy(nobodyHome = true, target = 20.0) }, fontScale = 1.5f)

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Do obrazovky Termostat se stav bez dat nedostane (DomekApp ukazuje obrazovku spojení), ale nesmí kvůli němu spadnout.
    @Test
    fun withoutDataNothingIsDrawn() {
        show(HouseUiState())

        compose.onNodeWithText("Režim").assertDoesNotExist()
        compose.onNodeWithText("Uvnitř").assertDoesNotExist()
    }
}
