package cz.vsb.mitr.domek.ui.settings

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.DayStarts
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE_TALL
import cz.vsb.mitr.domek.screenshots.WIDE_TALL
import cz.vsb.mitr.domek.screenshots.demoState
import cz.vsb.mitr.domek.screenshots.demoStateWithoutClockWarning
import cz.vsb.mitr.domek.screenshots.renderScreenshot
import cz.vsb.mitr.domek.screenshots.withStatus
import cz.vsb.mitr.domek.testing.EXACT
import cz.vsb.mitr.domek.testing.SettingsActions
import cz.vsb.mitr.domek.testing.assertNear
import cz.vsb.mitr.domek.testing.colorAt
import cz.vsb.mitr.domek.testing.cutOffTexts
import cz.vsb.mitr.domek.testing.hourOnDial
import cz.vsb.mitr.domek.testing.isNear
import cz.vsb.mitr.domek.testing.pickMinute
import cz.vsb.mitr.domek.testing.verticallyClippedTexts
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.piTime
import java.time.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val HINT = "„Teď“ posune začátek na aktuální čas Raspberry. Původní časy pak vrátí tlačítko níž."
private const val RESTORE = "Vrátit původní časy"
private const val SYNC = "Nastavit čas podle telefonu"
private const val BLUETOOTH = "Raspberry přes Bluetooth"
private const val SIMULATION = "Simulace"
// Síla signálu má spojovník jako minus, stejně jako ostatní čísla v aplikaci a výběr domku.
private const val DEVICE_LINE = "B8:27:EB:4A:19:C2 · signál -61${NBSP}dBm · MTU${NBSP}517"

private val TIME_TEXT = Regex("""\d\d:\d\d|—""")

// Barvy vložených prvků z náhledu: upozornění na hodiny (.warn), tlačítko „Teď“ (.tb), řádek zařízení (.dev) a čára mezi
// řádky (.tr2). Tolerance pokrývá odchylku dvou odstínů z 255, protože se míchají z barev schématu.
private val WARNING_TINT = Color(0xFFFFF0D6)
private val NOW_BUTTON_TINT = Color(0xFFE2F3F3)
private val DEVICE_LINE_TINT = Color(0xFFEAF0F2)
private val DIVIDER_TINT = Color(0xFFEDF1F3)
private const val TINT_TOLERANCE = 0.015f

// Výška jednoho řádku textu z náhledů: velikost písma × 1,35 (řádek zařízení má 12,5, upozornění 13).
private val DEVICE_LINE_HEIGHT = (12.5f * 1.35f).dp
private val WARNING_LINE_HEIGHT = (13f * 1.35f).dp

private val ORIGINAL_STARTS = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0))
private val ORIGINAL_TIMES = mapOf(DayPart.DAY to "07:00", DayPart.EVENING to "19:00", DayPart.NIGHT to "22:00")

private val TWO_DEVICES = listOf(
    FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61),
    FoundDevice("DC:A6:32:7F:03:1B", "Termostat", -78),
)

// Obrazovka Nastavení bez aplikace kolem: texty, podmínky a klepnutí. Obrazovka je tak vysoká, aby se celý obsah vešel a šel
// zkoušet bez posouvání. Skutečná písma a hustota telefonu jsou tu kvůli rozměrům, které se porovnávají.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = SettingsActions()
    private var shown by mutableStateOf(HouseUiState())

    // Výchozí stav je demoState bez upozornění na hodiny, jako ho mají ostatní obrazovky. Upozornění má vlastní testy.
    private val base = demoStateWithoutClockWarning()

    private fun show(state: HouseUiState = base, wide: Boolean = false, fontScale: Float = 1f) {
        shown = state
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme = false) {
                    // Pozadí stránky je pod kartami jako v aplikaci, jinak by zešedlá bílá karta byla na snímku jen průhledná.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsScreen(shown, actions, wide)
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

    private fun HouseUiState.withDayPart(part: DayPart) = withStatus { it.copy(dayPartText = part.json) }

    private fun HouseUiState.withPiTime(time: String) = withStatus { it.copy(time = time) }

    // Za 7 s od posledního Stavu (přijatého v 300 000 ms).
    private fun HouseUiState.stale() = copy(nowMs = 307_000)

    private fun HouseUiState.disconnectedWithTheOldData() =
        copy(connection = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000))

    // Hodiny Raspberry předbíhají telefon o tolik vteřin v okamžiku přijetí Stavu (záporně jdou pozadu).
    private fun HouseUiState.withClockDifference(seconds: Long): HouseUiState {
        val received = checkNotNull(received)
        val piTime = checkNotNull(piTime)
        return copy(received = received.copy(receivedAtWall = piTime.minusSeconds(seconds)))
    }

    // ----- Vyhledávání prvků -----

    // Čas začátku úseku je jediné tlačítko s textem HH:MM, nebo „—“, když čas není znám. Pořadí je pořadí řádků.
    private val isTimeButton = SemanticsMatcher("tlačítko s časem začátku") { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        node.config.getOrNull(SemanticsActions.OnClick) != null && text != null && TIME_TEXT.matches(text)
    }

    private fun timeButton(part: DayPart): SemanticsNodeInteraction = compose.onAllNodes(isTimeButton)[part.ordinal]

    private fun nowButton(part: DayPart): SemanticsNodeInteraction =
        compose.onAllNodes(hasText("Teď") and hasClickAction())[part.ordinal]

    private val isSource = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun source(title: String) = compose.onNode(hasText(title) and isSource)

    private fun connectionButton(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun assertNoConnectionButton() {
        compose.onNode(hasClickAction() and (hasText("Odpojit") or hasText("Připojit"))).assertDoesNotExist()
    }

    // ----- Denní doba: záhlaví a řádky -----

    @Test
    fun theCardNamesTheCurrentPartOfTheDayFromTheStatus() {
        show()
        compose.onNodeWithText("Denní doba").assertIsDisplayed()
        compose.onNodeWithText("teď je den").assertIsDisplayed()

        update(base.withDayPart(DayPart.EVENING))
        compose.onNodeWithText("teď je večer").assertIsDisplayed()
        compose.onNodeWithText("teď je den").assertDoesNotExist()

        update(base.withDayPart(DayPart.NIGHT))
        compose.onNodeWithText("teď je noc").assertIsDisplayed()
    }

    @Test
    fun anUnknownPartOfTheDayIsNamedAsItCame() {
        show(base.withStatus { it.copy(dayPartText = "odpoledne") })

        compose.onNodeWithText("teď je odpoledne").assertIsDisplayed()
    }

    @Test
    fun withoutAPartOfTheDayInTheStatusNoneIsClaimed() {
        show(base.withStatus { it.copy(dayPartText = null) })

        compose.onNodeWithText("teď je", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutAStatusNoPartOfTheDayIsClaimed() {
        show(base.copy(received = null))

        compose.onNodeWithText("teď je", substring = true).assertDoesNotExist()
    }

    @Test
    fun rowsHaveTheirNamesAndTheStartsFromTheSettings() {
        show()

        listOf("Den od", "Večer od", "Noc od").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        timeButton(DayPart.DAY).assertTextEquals("07:00")
        timeButton(DayPart.EVENING).assertTextEquals("19:00")
        timeButton(DayPart.NIGHT).assertTextEquals("22:00")
        compose.onNodeWithText(HINT).assertIsDisplayed()
    }

    // Rozpracovaná hodnota má před hodnotou z Raspberry přednost, aby uživatel hned viděl, co nastavil (spec 7).
    @Test
    fun startsAreDraftFirst() {
        show(base.copy(drafts = mapOf(SettingKey.NIGHT_START to JsonPrimitive("10:05"))))

        timeButton(DayPart.NIGHT).assertTextEquals("10:05")
        timeButton(DayPart.DAY).assertTextEquals("07:00")
    }

    // Bez Nastavení není co ukázat, tak je u každého řádku „—“ a nic z denní doby se nedá změnit.
    @Test
    fun withoutTheSettingsEveryStartIsADashAndNothingCanBeChanged() {
        show(base.copy(settings = null))

        DayPart.entries.forEach { part ->
            timeButton(part).assertTextEquals("—")
            timeButton(part).assertIsNotEnabled()
            nowButton(part).assertIsNotEnabled()
        }
        compose.onNodeWithText(RESTORE).assertIsNotEnabled()
    }

    @Test
    fun withoutConnectionTheStartsCannotBeChanged() {
        show(base.disconnectedWithTheOldData().copy(savedDayStarts = ORIGINAL_STARTS))

        DayPart.entries.forEach { part ->
            timeButton(part).assertTextEquals(ORIGINAL_TIMES.getValue(part))
            timeButton(part).assertIsNotEnabled()
            nowButton(part).assertIsNotEnabled()
        }
        compose.onNodeWithText(RESTORE).assertIsNotEnabled()
    }

    // ----- Denní doba: „Teď“ -----

    @Test
    fun nowButtonsMoveTheirOwnStartToTheTimeOfTheRaspberry() {
        show()

        nowButton(DayPart.DAY).performClick()
        nowButton(DayPart.EVENING).performClick()
        nowButton(DayPart.NIGHT).performClick()

        assertEquals(listOf("setDayStartNow(DAY)", "setDayStartNow(EVENING)", "setDayStartNow(NIGHT)"), actions.calls)
    }

    // „Teď“ potřebuje spojení, Nastavení, Stav s čitelným časem a čerstvá data (spec 6.4).
    @Test
    fun nowButtonsNeedAConnectionTheSettingsAStatusWithATimeAndFreshData() {
        fun assertNowButtons(enabled: Boolean, why: String) = DayPart.entries.forEach { part ->
            val node = nowButton(part).fetchSemanticsNode()
            assertEquals("Teď u ${part.name}, $why", enabled, SemanticsProperties.Disabled !in node.config)
        }
        show()
        assertNowButtons(true, "čerstvá data")

        update(base.disconnectedWithTheOldData())
        assertNowButtons(false, "bez spojení")

        update(base.copy(settings = null))
        assertNowButtons(false, "bez Nastavení")

        update(base.copy(received = null))
        assertNowButtons(false, "bez Stavu")

        update(base.stale())
        assertNowButtons(false, "stará data")

        update(base.withPiTime("neni cas"))
        assertNowButtons(false, "nečitelný čas ve Stavu")

        update(base.copy(connection = ConnectionState.Simulated))
        assertNowButtons(true, "simulace")
    }

    // ----- Denní doba: vrácení původních časů -----

    @Test
    fun restoreButtonNeedsTheRememberedStarts() {
        show()
        compose.onNodeWithText(RESTORE).assertIsNotEnabled()

        update(base.copy(savedDayStarts = ORIGINAL_STARTS))
        compose.onNodeWithText(RESTORE).assertIsEnabled()
    }

    @Test
    fun restoreButtonRestoresTheOriginalStarts() {
        show(base.copy(savedDayStarts = ORIGINAL_STARTS))

        compose.onNodeWithText(RESTORE).performClick()

        assertEquals(listOf("restoreDayStarts"), actions.calls)
    }

    // Zašedlé tlačítko se nedá klepnout, i kdyby se o to test pokusil.
    @Test
    fun disabledButtonsDoNothing() {
        show(base.copy(settings = null))

        compose.onNodeWithText(RESTORE).performClick()
        nowButton(DayPart.DAY).performClick()
        timeButton(DayPart.DAY).performClick()
        compose.onNodeWithText("Uložit").assertDoesNotExist()

        assertEquals(emptyList<String>(), actions.calls)
    }

    // ----- Denní doba: výběr času -----

    @Test
    fun tappingATimeOpensThePickerForThatPart() {
        show()

        listOf(DayPart.DAY to "Začátek dne", DayPart.EVENING to "Začátek večera", DayPart.NIGHT to "Začátek noci").forEach { (part, title) ->
            timeButton(part).performClick()

            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("Uložit").assertIsDisplayed()
            compose.onNodeWithText("Zrušit").assertIsDisplayed()
            compose.onNodeWithText("Zrušit").performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test
    fun cancellingThePickerWritesNothing() {
        show()
        timeButton(DayPart.EVENING).performClick()

        compose.onNodeWithText("Zrušit").performClick()

        compose.onNodeWithText("Začátek večera").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
    }

    // Uložení stejného času by jen zbytečně poslalo totéž a „zapnulo“ Vrátit původní časy, ačkoli se nic nezměnilo.
    @Test
    fun savingTheSameTimeWritesNothingAndClosesThePicker() {
        show()
        timeButton(DayPart.EVENING).performClick()

        compose.onNodeWithText("Uložit").performClick()

        compose.onNodeWithText("Začátek večera").assertDoesNotExist()
        assertEquals(emptyList<String>(), actions.calls)
    }

    // Zvolený čas se zapíše jako začátek úseku, od jehož řádku se výběr otevřel.
    @Test
    fun savingAChangedTimeWritesItAsTheStartOfThatPart() {
        show()
        timeButton(DayPart.EVENING).performClick()

        compose.onNode(hourOnDial(20)).performClick()
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf("setDayStart(EVENING, 20:00)"), actions.calls)
        compose.onNodeWithText("Začátek večera").assertDoesNotExist()
    }

    @Test
    fun theMinutesOfThePickedTimeAreWrittenToo() {
        show()
        timeButton(DayPart.NIGHT).performClick()

        compose.onNode(hourOnDial(23)).performClick()
        compose.pickMinute(45)
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf("setDayStart(NIGHT, 23:45)"), actions.calls)
    }

    // Výběr začíná u času, který řádek ukazuje, tedy i u rozpracovaného.
    @Test
    fun thePickerOpensAtTheTimeTheRowShowsEvenWhenItIsADraft() {
        show(base.copy(drafts = mapOf(SettingKey.NIGHT_START to JsonPrimitive("10:05"))))

        timeButton(DayPart.NIGHT).performClick()

        compose.onNode(hasContentDescription("Select hour")).assertTextEquals("10")
        compose.onNode(hasContentDescription("Select minutes")).assertTextEquals("05")
        compose.onNode(hourOnDial(10)).assertIsSelected()
    }

    // Zrušený výběr nezůstane rozpracovaný: při dalším otevření začíná znovu od času řádku.
    @Test
    fun aCancelledPickerForgetsWhatWasPickedInIt() {
        show()
        timeButton(DayPart.DAY).performClick()
        compose.onNode(hourOnDial(9)).performClick()
        compose.onNodeWithText("Zrušit").performClick()

        timeButton(DayPart.DAY).performClick()

        compose.onNode(hasContentDescription("Select hour")).assertTextEquals("07")
        assertEquals(emptyList<String>(), actions.calls)
    }

    // ----- Otočení telefonu -----

    // Otočení telefonu přes 600 dp přepne rozvržení z jednoho sloupce na dva (nebo zpět) a aktivita se znovu vytvoří. Otevřený
    // výběr času se musí obnovit u stejného úseku a s časem, který už byl zvolen. Karta Denní doba se po přepnutí skládá na jiném
    // místě stromu, proto výběr drží celá obrazovka. Zkouška je přísnější než skutečné otočení: obnovení nejdřív počká na
    // překreslení, takže výběr musí přežít i samotné přepnutí rozvržení.
    private fun assertPickerSurvivesRotation(wideBefore: Boolean) {
        val restoration = StateRestorationTester(compose)
        val wide = mutableStateOf(wideBefore)
        restoration.setContent { DomekTheme(darkTheme = false) { SettingsScreen(base, actions, wide.value) } }
        timeButton(DayPart.EVENING).performClick()
        compose.onNode(hourOnDial(20)).performClick()
        compose.onNodeWithText("Začátek večera").assertIsDisplayed()

        wide.value = !wideBefore
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Začátek večera").assertIsDisplayed()
        compose.onNode(hourOnDial(20)).assertIsSelected()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun theOpenPickerSurvivesARotationFromTheNarrowToTheWideLayout() = assertPickerSurvivesRotation(wideBefore = false)

    @Test
    fun theOpenPickerSurvivesARotationFromTheWideToTheNarrowLayout() = assertPickerSurvivesRotation(wideBefore = true)

    // Bez rotace nic nezůstává otevřené: po obnovení stavu bez otevřeného výběru se výběr sám neobjeví.
    @Test
    fun aRestoreWithoutAnOpenPickerShowsNoPicker() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DomekTheme(darkTheme = false) { SettingsScreen(base, actions, wide = false) } }

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Uložit").assertDoesNotExist()
    }

    // ----- Čas Raspberry -----

    @Test
    fun clockCardShowsTheTimesOfTheRaspberryAndOfThePhoneWithDateAndSeconds() {
        show()

        compose.onNodeWithText("Čas Raspberry").assertIsDisplayed()
        compose.onNode(hasText("Raspberry") and hasText("20. 10. 2026 10:00:03")).assertIsDisplayed()
        compose.onNode(hasText("Telefon") and hasText("20. 10. 2026 10:02:15")).assertIsDisplayed()
    }

    // Den, měsíc a hodina bez úvodní nuly, minuty a vteřiny vždy dvouciferné.
    @Test
    fun clockFormatHasNoLeadingZerosInDayMonthAndHour() {
        show(base.withPiTime("2026-03-04T09:05:07"))

        compose.onNode(hasText("Raspberry") and hasText("4. 3. 2026 9:05:07")).assertIsDisplayed()
    }

    @Test
    fun withoutAStatusTheRaspberryClockIsADashAndThePhoneClockStays() {
        show(base.copy(received = null))

        compose.onNode(hasText("Raspberry") and hasText("—")).assertIsDisplayed()
        compose.onNode(hasText("Telefon") and hasText("20. 10. 2026 10:02:15")).assertIsDisplayed()
    }

    @Test
    fun theRaspberryClockFollowsTheLatestStatusAndThePhoneClockTheTick() {
        show()

        update(base.withPiTime("2026-10-20T10:00:04").copy(phoneNow = base.phoneNow.plusSeconds(1)))

        compose.onNode(hasText("Raspberry") and hasText("20. 10. 2026 10:00:04")).assertIsDisplayed()
        compose.onNode(hasText("Telefon") and hasText("20. 10. 2026 10:02:16")).assertIsDisplayed()
    }

    @Test
    fun warningAppearsOnlyAboveOneMinuteOfDifference() {
        show(demoState())
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}2${NBSP}min 12${NBSP}s pozadu.").assertIsDisplayed()

        update(base.withClockDifference(-60))
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()

        update(base.withClockDifference(-61))
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}1${NBSP}min 1${NBSP}s pozadu.").assertIsDisplayed()

        update(base.withClockDifference(60))
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()

        update(base.withClockDifference(61))
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}1${NBSP}min 1${NBSP}s napřed.").assertIsDisplayed()

        update(base.withClockDifference(0))
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutAStatusThereIsNoWarning() {
        show(demoState().copy(received = null))

        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()
    }

    @Test
    fun syncButtonSendsThePhoneTime() {
        show()

        compose.onNodeWithText(SYNC).performClick()

        assertEquals(listOf("syncClock"), actions.calls)
    }

    @Test
    fun syncButtonNeedsAConnectionAndTheSettings() {
        show(base.disconnectedWithTheOldData())
        compose.onNodeWithText(SYNC).assertIsNotEnabled()

        update(base.copy(settings = null))
        compose.onNodeWithText(SYNC).assertIsNotEnabled()

        update(base.copy(connection = ConnectionState.Simulated))
        compose.onNodeWithText(SYNC).assertIsEnabled()

        update(base)
        compose.onNodeWithText(SYNC).assertIsEnabled()
    }

    // ----- Zdroj dat -----

    @Test
    fun bothSourcesAreOfferedAndTheCurrentOneIsSelected() {
        show()

        compose.onNodeWithText("Zdroj dat").assertIsDisplayed()
        compose.onNode(hasText(BLUETOOTH) and hasText("dům „Termostat“")).assertIsDisplayed()
        compose.onNode(hasText(SIMULATION) and hasText("vymyšlená data, funguje bez Raspberry")).assertIsDisplayed()
        source(BLUETOOTH).assertIsSelected()
        source(SIMULATION).assertIsNotSelected()

        update(base.copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated))
        source(BLUETOOTH).assertIsNotSelected()
        source(SIMULATION).assertIsSelected()
    }

    @Test
    fun choosingTheOtherSourceSwitchesToIt() {
        show()
        source(SIMULATION).performClick()
        assertEquals(listOf("selectSource(SIMULATION)"), actions.calls)

        actions.calls.clear()
        update(base.copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated))
        source(BLUETOOTH).performClick()
        assertEquals(listOf("selectSource(BLUETOOTH)"), actions.calls)
    }

    // Znovu zvolený zdroj by ViewModel jen ignoroval, tak se ani nezkouší.
    @Test
    fun choosingTheCurrentSourceDoesNothing() {
        show()

        source(BLUETOOTH).performClick()

        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun deviceLineNamesTheAddressTheSignalAndTheMtuWhileConnected() {
        show()
        compose.onNodeWithText(DEVICE_LINE).assertIsDisplayed()

        update(base.copy(connection = ConnectionState.Connected("DC:A6:32:7F:03:1B", mtu = 23, rssi = -78)))
        compose.onNodeWithText("DC:A6:32:7F:03:1B · signál -78${NBSP}dBm · MTU${NBSP}23").assertIsDisplayed()
    }

    // Signál se čte až po připojení, do té doby je u něj „—“.
    @Test
    fun deviceLineShowsADashBeforeTheSignalIsRead() {
        show(base.copy(connection = ConnectionState.Connected("B8:27:EB:4A:19:C2", mtu = 517, rssi = null)))

        compose.onNodeWithText("B8:27:EB:4A:19:C2 · signál — · MTU${NBSP}517").assertIsDisplayed()
    }

    @Test
    fun deviceLineIsOnlyThereWhileConnected() {
        val states = listOf(
            ConnectionState.Scanning,
            ConnectionState.ChoosingDevice(TWO_DEVICES),
            ConnectionState.Connecting(attempt = 1),
            ConnectionState.Preparing,
            ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000),
            ConnectionState.DisconnectedByUser,
        )
        show()
        states.forEach { connection ->
            update(base.copy(connection = connection))
            compose.onNodeWithText("signál", substring = true).assertDoesNotExist()
        }
        update(base.copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated))
        compose.onNodeWithText("signál", substring = true).assertDoesNotExist()
    }

    @Test
    fun theButtonOffersToDisconnectUnlessTheUserAlreadyDid() {
        val disconnect = listOf(
            ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, -61),
            ConnectionState.Scanning,
            ConnectionState.ChoosingDevice(TWO_DEVICES),
            ConnectionState.Connecting(attempt = 2),
            ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000),
            ConnectionState.BluetoothOff,
        )
        show()
        disconnect.forEach { connection ->
            update(base.copy(connection = connection))
            connectionButton("Odpojit").assertIsEnabled()
            compose.onNodeWithText("Připojit").assertDoesNotExist()
        }

        update(base.copy(connection = ConnectionState.DisconnectedByUser))
        connectionButton("Připojit").assertIsEnabled()
        compose.onNodeWithText("Odpojit").assertDoesNotExist()
    }

    @Test
    fun theButtonDisconnectsAndConnects() {
        show()
        connectionButton("Odpojit").performClick()
        assertEquals(listOf("disconnect"), actions.calls)

        actions.calls.clear()
        update(base.copy(connection = ConnectionState.DisconnectedByUser))
        connectionButton("Připojit").performClick()
        assertEquals(listOf("connect"), actions.calls)
    }

    // Připojení patří jen k Bluetooth, simulace nemá co odpojit.
    @Test
    fun theSimulationHasNoConnectionButton() {
        show(base.copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated))

        assertNoConnectionButton()
    }

    // Zdroj dat funguje i před prvními daty a při výběru domku (spec 6.0): Odpojit zastaví hledání a Připojit ho spustí znovu.
    @Test
    fun sourceCardWorksWhileChoosingADeviceWithoutAnyData() {
        show(HouseUiState(connection = ConnectionState.ChoosingDevice(TWO_DEVICES)))

        source(BLUETOOTH).assertIsSelected()
        source(BLUETOOTH).assertIsEnabled()
        source(SIMULATION).assertIsEnabled()
        connectionButton("Odpojit").assertIsEnabled()
        connectionButton("Odpojit").performClick()
        assertEquals(listOf("disconnect"), actions.calls)

        actions.calls.clear()
        update(HouseUiState(connection = ConnectionState.DisconnectedByUser))
        connectionButton("Připojit").assertIsEnabled()
        connectionButton("Připojit").performClick()
        assertEquals(listOf("connect"), actions.calls)
    }

    @Test
    fun sourceCardWorksBeforeTheFirstData() {
        show(HouseUiState(connection = ConnectionState.Scanning))

        source(SIMULATION).performClick()
        connectionButton("Odpojit").performClick()

        assertEquals(listOf("selectSource(SIMULATION)", "disconnect"), actions.calls)
    }

    // ----- Barvy z náhledu -----

    private fun colorAtNode(node: SemanticsNodeInteraction, dx: Float, dy: Float, name: String): Color {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        return compose.renderScreenshot(name).colorAt(bounds.center.x + dx * density, bounds.center.y + dy * density)
    }

    // Pozadí upozornění na hodiny (.warn) je v náhledu #FFF0D6. Vzorek je v odsazení nad textem, kde je jen pozadí.
    @Test
    fun theClockWarningHasTheBackgroundFromThePreview() {
        show(demoState())

        val warning = compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}2${NBSP}min 12${NBSP}s pozadu.")
        assertNear("pozadí upozornění", WARNING_TINT, colorAtNode(warning, 0f, -14f, "upozornění"), TINT_TOLERANCE)
    }

    // Tlačítko „Teď“ (.tb) má v náhledu #E2F3F3. Vzorek je u levého okraje, kde ještě není text.
    @Test
    fun theNowButtonHasTheTintFromThePreview() {
        show()

        assertNear("tlačítko Teď", NOW_BUTTON_TINT, colorAtNode(nowButton(DayPart.DAY), -17f, 0f, "teď"), TINT_TOLERANCE)
    }

    // Řádek zařízení (.dev) má plochu #EAF0F2 jako jiné vložené řádky karet. Vzorek je v odsazení vlevo od textu.
    @Test
    fun theDeviceLineHasTheSurfaceFromThePreview() {
        show()

        val bounds = compose.onNodeWithText(DEVICE_LINE).fetchSemanticsNode().boundsInRoot
        val color = compose.renderScreenshot("zařízení").colorAt(bounds.left - 5 * compose.density.density, bounds.center.y)
        assertNear("pozadí řádku zařízení", DEVICE_LINE_TINT, color, EXACT)
    }

    // Čára mezi řádky (.tr2) má v náhledu #EDF1F3 a je jen mezi řádky, ne nad prvním.
    @Test
    fun rowsAreSeparatedByTheHairlineFromThePreview() {
        show()
        val image = compose.renderScreenshot("čáry")
        val density = compose.density.density
        val x = compose.onNodeWithText("Den od").fetchSemanticsNode().boundsInRoot.left + 20 * density

        fun hasHairline(from: Float, to: Float) =
            (from.toInt()..to.toInt()).any { y -> isNear(DIVIDER_TINT, image.colorAt(x, y.toFloat()), TINT_TOLERANCE) }

        fun centerY(part: DayPart) = timeButton(part).fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("čára mezi Den a Večer", hasHairline(centerY(DayPart.DAY), centerY(DayPart.EVENING)))
        assertTrue("čára mezi Večer a Noc", hasHairline(centerY(DayPart.EVENING), centerY(DayPart.NIGHT)))
        // Mezi pruhem s hodinami a prvním řádkem čára není.
        assertTrue("nad prvním řádkem čára není", !hasHairline(centerY(DayPart.DAY) - 40 * density, centerY(DayPart.DAY) - 10 * density))
    }

    // ----- Stará data -----

    // Nejtmavší bod textu: dokud je text plný, je blízko barvě písma, po zešednutí je světlejší.
    private fun inkOf(text: String): Float {
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val image = compose.renderScreenshot("inkoust")
        var darkest = 1f
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val c = image.colorAt(x.toFloat(), y.toFloat())
                darkest = minOf(darkest, 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue)
            }
        }
        return darkest
    }

    // Spec 6.5: hodnoty ze Stavu (čas Raspberry, upozornění, úsek dne) zešednou. Čas telefonu a časy začátků z Nastavení ne.
    @Test
    fun staleDataDimTheValuesFromTheStatusButNotTheOthers() {
        val texts = mapOf(
            "čas Raspberry" to "20. 10. 2026 10:00:03",
            "upozornění" to "Hodiny Raspberry jdou o${NBSP}2${NBSP}min 12${NBSP}s pozadu.",
            "úsek dne" to "teď je den",
            "čas telefonu" to "20. 10. 2026 10:02:15",
            "čas začátku" to "07:00",
        )
        show(demoState())
        val fresh = texts.mapValues { inkOf(it.value) }

        update(demoState().stale())
        val stale = texts.mapValues { inkOf(it.value) }

        listOf("čas Raspberry", "upozornění", "úsek dne").forEach { name ->
            assertTrue("$name zešedlo: čerstvé ${fresh[name]}, stará ${stale[name]}", stale.getValue(name) > fresh.getValue(name) + 0.15f)
        }
        listOf("čas telefonu", "čas začátku").forEach { name ->
            assertEquals("$name zůstalo plné", fresh.getValue(name), stale.getValue(name), 0.02f)
        }
    }

    // ----- Rozvržení -----

    @Test
    fun theCardTitlesAreHeadings() {
        show()

        val headings = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        val titles = headings.map { it.config[SemanticsProperties.Text].joinToString { text -> text.text } }
        assertEquals(listOf("Denní doba", "Čas Raspberry", "Zdroj dat"), titles)
    }

    @Test
    fun onANarrowScreenTheCardsAreInOneColumnInTheOrderOfThePreview() {
        show()

        val tops = listOf("Denní doba", "Čas Raspberry", "Zdroj dat").map { title ->
            compose.onNodeWithText(title).getBoundsInRoot().top
        }
        assertEquals("pořadí karet: denní doba, čas, zdroj: $tops", tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
    }

    @Test
    @Config(qualifiers = WIDE_TALL)
    fun onAWideScreenTheDayTimeIsOnTheLeftAndTheClockAndTheSourceOnTheRight() {
        show(wide = true)

        val dayTime = compose.onNodeWithText("Denní doba").getBoundsInRoot()
        val rightmostOnTheLeft = nowButton(DayPart.NIGHT).getBoundsInRoot()
        val clock = compose.onNodeWithText("Čas Raspberry").getBoundsInRoot()
        val sourceCard = compose.onNodeWithText("Zdroj dat").getBoundsInRoot()

        assertTrue("čas je vpravo od denní doby: $rightmostOnTheLeft, $clock", clock.left > rightmostOnTheLeft.right)
        assertTrue("zdroj je vpravo od denní doby: $rightmostOnTheLeft, $sourceCard", sourceCard.left > rightmostOnTheLeft.right)
        assertTrue("zdroj je pod časem: $clock, $sourceCard", sourceCard.top > clock.top)
        assertEquals("obě karty začínají nahoře ve svých sloupcích", dayTime.top.value, clock.top.value, 0.5f)
    }

    @Test
    fun noTextIsCutOffAtTheNormalFontSize() {
        show(demoState())

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Při velkém písmu se popisky řádků zalomí a tlačítka se přesunou pod text, nic se nezkracuje.
    @Test
    fun noTextIsCutOffAtALargeFontSize() {
        show(demoState().copy(savedDayStarts = ORIGINAL_STARTS), fontScale = 1.5f)

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Popisek tlačítka se při velkém písmu zalomí a tlačítko naroste. Pevná výška by druhý řádek uřízla, což cutOffTexts nepozná.
    @Test
    fun noTextIsClippedInsideItsBoxAtLargeFontSizes() {
        show(demoState().copy(savedDayStarts = ORIGINAL_STARTS), fontScale = 1.5f)

        assertEquals(emptyList<String>(), compose.verticallyClippedTexts())
    }

    @Test
    fun noTextIsClippedInsideItsBoxAtTheLargestFontSize() {
        show(demoState().copy(savedDayStarts = ORIGINAL_STARTS), fontScale = 2f)

        assertEquals(emptyList<String>(), compose.verticallyClippedTexts())
    }

    // Obrazovka se bez dat nezhroutí a ukáže jen to, co data nepotřebuje.
    @Test
    fun withoutDataTheScreenStillDrawsAllThreeCards() {
        show(HouseUiState())

        listOf("Denní doba", "Čas Raspberry", "Zdroj dat").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Řádek zařízení a varování o hodinách jsou na náhledu na jednom řádku, tak se nesmí zalomit ani na telefonu.
    @Test
    fun theDeviceLineAndTheClockWarningFitOnOneLineOnAPhone() {
        show(demoState())

        listOf(
            DEVICE_LINE to DEVICE_LINE_HEIGHT,
            "Hodiny Raspberry jdou o${NBSP}2${NBSP}min 12${NBSP}s pozadu." to WARNING_LINE_HEIGHT,
        ).forEach { (text, lineHeight) ->
            val bounds = compose.onNodeWithText(text).getBoundsInRoot()
            assertTrue("„$text“ je na jednom řádku: $bounds", bounds.height < lineHeight * 1.5f)
        }
    }
}
