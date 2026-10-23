package cz.vsb.mitr.domek.ui.overview

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.WITHOUT_STORED_LOOK
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.formatPiTime
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
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
import cz.vsb.mitr.domek.testing.fontSizeSp
import cz.vsb.mitr.domek.testing.isNear
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LightColorScheme
import cz.vsb.mitr.domek.ui.theme.LightDomekColors
import cz.vsb.mitr.domek.viewmodel.ChartWindow
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

// Přehled bez aplikace kolem: texty, podmínky a klepnutí. Obrazovka je tak vysoká, aby se celý obsah vešel a šel zkoušet
// bez posouvání. Skutečná písma a hustota telefonu jsou tu kvůli rozměrům a barvám, které se porovnávají.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class OverviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private var openedLight = 0
    private var shown by mutableStateOf(HouseUiState())
    private var compactLayout by mutableStateOf(false)

    // Výchozí stav je demoState, jen bez upozornění na hodiny: ty se v demoState liší o 2 min 12 s, tedy nad prahem.
    private val base = demoStateWithoutClockWarning()

    private val simulated = base.copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated)

    private fun show(state: HouseUiState = base, wide: Boolean = false, fontScale: Float = 1f, compact: Boolean = false) {
        shown = state
        compactLayout = compact
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme = false) {
                    // Pozadí stránky je pod kartami jako v aplikaci, jinak by zešedlá bílá karta byla na snímku jen průhledná.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        OverviewScreen(shown, actions, wide, onOpenLight = { openedLight++ }, compact = compactLayout)
                    }
                }
            }
        }
    }

    private fun update(state: HouseUiState) {
        shown = state
        compose.waitForIdle()
    }

    private fun setCompact(compact: Boolean) {
        compactLayout = compact
        compose.waitForIdle()
    }

    // Raspberry o `seconds` napřed (záporně pozadu) oproti telefonu v okamžiku přijetí Stavu.
    private fun withClockDiff(seconds: Long): HouseUiState =
        base.withStatus { it.copy(time = formatPiTime(checkNotNull(base.received).receivedAtWall.plusSeconds(seconds))) }

    // Za 7 s od posledního Stavu (přijatého v 300 000 ms).
    private fun stale(state: HouseUiState = base): HouseUiState = state.copy(nowMs = 307_000)

    private fun HouseUiState.withErrors(vararg codes: String) = withStatus { it.copy(errors = codes.toList()) }

    private fun HouseUiState.withoutLight() =
        withStatus { it.copy(light = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = false)) }

    private fun HouseUiState.off() =
        withStatus { it.copy(mode = ThermostatMode.OFF, target = null, heating = false, cooling = false) }

    // Dlaždice je jeden sloučený prvek se štítkem i hodnotou. Samotný štítek se hledat nedá: „Venku“ je i v legendě grafu a
    // „Pohyb“ je i tlačítko simulace.
    private fun tile(label: String, value: String) = compose.onNode(hasText(label) and hasText(value))

    private val isGroup = SemanticsMatcher("je sloučená skupina") { it.config.isMergingSemanticsOfDescendants }

    // Tlačítko simulace se pozná podle klepnutí, na rozdíl od dlaždice se stejným popiskem.
    private fun simulationButton(action: SimulationAction) = compose.onNode(hasText(action.label) and hasClickAction())

    // Barva 8 dp od pravého horního rohu prvku, tedy uvnitř zaoblení bez textu.
    private fun colorInsideCorner(node: SemanticsNodeInteraction): Color {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val inset = 8 * compose.density.density
        return compose.renderScreenshot("corner").colorAt(bounds.right - inset, bounds.top + inset)
    }

    // Barva obrazovky vpravo od prvního uzlu s tímto textem, `dp` od jeho okraje.
    private fun colorRightOf(text: String, dp: Float): Color {
        val bounds = compose.onAllNodesWithText(text)[0].fetchSemanticsNode().boundsInRoot
        return compose.renderScreenshot("color").colorAt(bounds.right + dp * compose.density.density, bounds.center.y)
    }

    // Barva obrazovky `dp` nad levým okrajem prvního uzlu s tímto textem.
    private fun colorAbove(text: String, dp: Float): Color {
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        return compose.renderScreenshot("color").colorAt(bounds.left, bounds.top - dp * compose.density.density)
    }

    // ----- Bannery -----

    @Test
    fun freshDataWithoutErrorsAndWithClocksInSyncShowNoBanner() {
        show()

        compose.onNodeWithText("Data jsou", substring = true).assertDoesNotExist()
        compose.onNodeWithText("neodpovídá", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Srovnat").assertDoesNotExist()
    }

    @Test
    fun dataThreeSecondsOldAreStillFresh() {
        show(base.copy(nowMs = 303_000))

        compose.onNodeWithText("Data jsou", substring = true).assertDoesNotExist()
    }

    @Test
    fun staleDataBannerNamesTheAgeAndSaysTheRaspberryStoppedSending() {
        show(stale())

        compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
        compose.onNodeWithText("Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.").assertIsDisplayed()
    }

    @Test
    fun staleDataBannerShowsMinutesOnceTheAgeGrows() {
        show(base.copy(nowMs = 300_000 + 125_000))

        compose.onNodeWithText("Data jsou 2${NBSP}min 5${NBSP}s stará").assertIsDisplayed()
    }

    @Test
    fun staleDataInSimulationGetsTheSameExplanation() {
        show(stale(simulated))

        compose.onNodeWithText("Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.").assertIsDisplayed()
    }

    // Každý stav, ve kterém se aplikace po výpadku sama znovu připojuje: hledá, připojuje se, nebo čeká na další pokus.
    @Test
    fun staleDataAfterTheConnectionIsLostSaysItIsReconnecting() {
        listOf(
            ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 309_000),
            ConnectionState.Connecting(attempt = 2),
            ConnectionState.Preparing,
            ConnectionState.Idle,
            ConnectionState.Scanning,
            ConnectionState.ChoosingDevice(emptyList()),
        ).forEachIndexed { index, connection ->
            val state = stale(base.copy(connection = connection))
            if (index == 0) show(state) else update(state)

            compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
            compose.onNodeWithText("Spojení se přerušilo, znovu se připojuji…").assertIsDisplayed()
            compose.onNodeWithText("Raspberry přestalo posílat stav.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Dům je odpojený.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Dům teď není připojený.").assertDoesNotExist()
        }
    }

    // Připojení brání vypnutý Bluetooth, chybějící oprávnění, vypnutá poloha, telefon bez BLE nebo zařízení bez služby Domek.
    // Aplikace se v nich sama nepřipojí, takže banner nic neslibuje a neodporuje červenému štítku nahoře.
    @Test
    fun staleDataWhileSomethingBlocksTheConnectionOnlySaysTheHouseIsNotConnected() {
        listOf(
            ConnectionState.BluetoothOff,
            ConnectionState.PermissionsMissing,
            ConnectionState.LocationOff,
            ConnectionState.NoBluetooth,
            ConnectionState.MissingService,
        ).forEachIndexed { index, connection ->
            val state = stale(base.copy(connection = connection))
            if (index == 0) show(state) else update(state)

            compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
            compose.onNodeWithText("Dům teď není připojený.").assertIsDisplayed()
            compose.onNodeWithText("Spojení se přerušilo", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Raspberry přestalo posílat stav.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Dům je odpojený.", substring = true).assertDoesNotExist()
        }
    }

    // Po ručním odpojení se aplikace nepřipojuje sama, takže banner neslibuje připojování a ukáže, kde se domek připojí.
    @Test
    fun staleDataAfterTheUserDisconnectedPointsToTheSettings() {
        show(stale(base.copy(connection = ConnectionState.DisconnectedByUser)))

        compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
        compose.onNodeWithText("Dům je odpojený. Připojit ho jde v${NBSP}Nastavení.").assertIsDisplayed()
        compose.onNodeWithText("Spojení se přerušilo", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Raspberry přestalo posílat stav.", substring = true).assertDoesNotExist()
    }

    @Test
    fun everyErrorCodeGetsItsOwnBanner() {
        show(base.withErrors("meteo", "osvetleni", "displej", "svetlo", "nastaveni"))

        listOf(
            "Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá" to
                "Venkovní teplota, vlhkost a tlak chybí. Dokud čidlo nejde přečíst, termostat netopí ani nechladí.",
            "Čidlo osvětlení (TSL2561) neodpovídá" to "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí.",
            "Displej domu nefunguje" to "Dům běží dál, jen bez displeje.",
            "Světlo (RGB LED) nejde ovládat" to "Aplikace ukazuje, jak má světlo svítit, ne jak opravdu svítí.",
            "Raspberry nemůže uložit nastavení" to "Změny platí, ale po restartu Raspberry se vrátí dřívější nastavení.",
        ).forEach { (title, detail) ->
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(detail).assertIsDisplayed()
        }
    }

    @Test
    fun unknownErrorCodeIsShownWithItsCode() {
        show(base.withErrors("pam"))

        compose.onNodeWithText("Raspberry hlásí chybu: pam").assertIsDisplayed()
    }

    @Test
    fun clockBannerAppearsOnlyAboveTwoMinutes() {
        show(withClockDiff(-120))
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()

        update(withClockDiff(120))
        compose.onNodeWithText("Hodiny Raspberry", substring = true).assertDoesNotExist()

        update(withClockDiff(-121))
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}2${NBSP}min 1${NBSP}s pozadu.").assertIsDisplayed()
        compose.onNodeWithText("Na hodinách závisí denní doba.").assertIsDisplayed()

        update(withClockDiff(121))
        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}2${NBSP}min 1${NBSP}s napřed.").assertIsDisplayed()
    }

    @Test
    fun clockBannerActionSendsTheClockCommand() {
        show(withClockDiff(-10_800))

        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}3${NBSP}h pozadu.").assertIsDisplayed()
        compose.onNodeWithText("Srovnat").performClick()

        assertEquals(listOf("syncClock"), actions.calls)
    }

    @Test
    fun bannersAreOrderedStaleDataThenErrorsThenClockAndAllAreAboveTheThermostatCard() {
        show(stale(withClockDiff(-600)).withErrors("meteo", "osvetleni"))

        val tops = listOf(
            "Data jsou 7${NBSP}s stará",
            "Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
            "Čidlo osvětlení (TSL2561) neodpovídá",
            "Hodiny Raspberry jdou o${NBSP}10${NBSP}min pozadu.",
            // Stav má čas Raspberry o 10 minut dřív než telefon.
            "Den · 09:50",
        ).map { compose.onNodeWithText(it).getBoundsInRoot().top }

        assertEquals("pořadí: stará data, chyby, hodiny, karta termostatu: $tops", tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
    }

    // ----- Karta termostatu -----

    @Test
    fun heroShowsDayPartWithRaspberryTimeModeAndInsideTemperature() {
        show()

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
        compose.onNodeWithText("Režim Auto").assertIsDisplayed()
        // „Uvnitř“ je ještě v legendě grafu.
        compose.onAllNodesWithText("Uvnitř").assertCountEquals(2)
        compose.onNodeWithText("22,4").assertIsDisplayed()
        compose.onNodeWithText("°C").assertIsDisplayed()
    }

    @Test
    fun heroNamesTheDayPartFromTheStatus() {
        show()

        listOf(
            Triple(DayPart.DAY, "2026-10-20T10:00:03", "Den · 10:00"),
            Triple(DayPart.EVENING, "2026-10-20T19:30:00", "Večer · 19:30"),
            Triple(DayPart.NIGHT, "2026-10-20T22:05:59", "Noc · 22:05"),
        ).forEach { (part, time, text) ->
            update(base.withStatus { it.copy(dayPartText = part.json, time = time) })

            compose.onNodeWithText(text).assertIsDisplayed()
        }
    }

    // Nový úsek dne se ukáže tak, jak ho Raspberry poslalo (docs/ble-rozhrani.md, Formát JSON).
    @Test
    fun heroShowsAnUnknownDayPartAsItCame() {
        show(base.withStatus { it.copy(dayPartText = "odpoledne") })

        compose.onNodeWithText("odpoledne · 10:00").assertIsDisplayed()
    }

    // Před prvním krokem domku chybí čas i úsek; karta pak místo nich ukáže pomlčku.
    @Test
    fun heroWithoutDayPartAndTimeShowsADash() {
        show(base.withStatus { it.copy(dayPartText = null, time = null) })

        compose.onAllNodesWithText("—").assertCountEquals(1)
        compose.onNodeWithText("Režim Auto").assertIsDisplayed()
    }

    @Test
    fun heroWithoutDayPartShowsJustTheRaspberryTime() {
        show(base.withStatus { it.copy(dayPartText = null) })

        compose.onNodeWithText("10:00").assertIsDisplayed()
    }

    @Test
    fun heroWithoutReadableRaspberryTimeShowsJustTheDayPart() {
        show(base.withStatus { it.copy(time = "nesmysl") })

        compose.onNodeWithText("Den").assertIsDisplayed()
    }

    @Test
    fun heroChipFollowsWhatTheThermostatIsDoing() {
        show()
        // Štítek a legenda grafu mají u Topí a Chladí stejný popisek.
        compose.onAllNodesWithText("Topí").assertCountEquals(2)
        compose.onAllNodesWithText("Chladí").assertCountEquals(1)
        compose.onNodeWithText("V${NBSP}klidu").assertDoesNotExist()

        update(base.withStatus { it.copy(heating = false, cooling = true) })
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
        compose.onAllNodesWithText("Chladí").assertCountEquals(2)

        update(base.withStatus { it.copy(heating = false, cooling = false) })
        compose.onNodeWithText("V${NBSP}klidu").assertIsDisplayed()
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
        compose.onAllNodesWithText("Chladí").assertCountEquals(1)

        update(base.off())
        compose.onNodeWithText("Vypnuto").assertIsDisplayed()
        compose.onNodeWithText("V${NBSP}klidu").assertDoesNotExist()
    }

    // Vypnutý termostat netopí, i kdyby poslední Stav ještě nesl příznak topení.
    @Test
    fun heroInOffModeNeverClaimsToBeHeating() {
        show(base.withStatus { it.copy(mode = ThermostatMode.OFF, target = null, heating = true) })

        compose.onNodeWithText("Vypnuto").assertIsDisplayed()
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
    }

    @Test
    fun heroIsTintedWarmWhenHeatingAndColdWhenCooling() {
        // Šest dp nad textem je ještě odsazení karty, tedy začátek přechodu.
        fun topOfHero() = colorAbove("Den · 10:00", 6f)

        show()
        assertNear("topení", LightDomekColors.heatContainer, topOfHero())

        update(base.withStatus { it.copy(heating = false, cooling = true) })
        assertNear("chlazení", LightDomekColors.coolContainer, topOfHero())

        update(base.withStatus { it.copy(heating = false, cooling = false) })
        assertNear("klid", LightColorScheme.surface, topOfHero())

        update(base.withStatus { it.copy(heating = true) })
        assertNear("znovu topení", LightDomekColors.heatContainer, topOfHero())
    }

    @Test
    fun heroChipHasTheHeatCoolAndNeutralColours() {
        // Za textem štítku je ještě odsazení 14 dp.
        show()
        assertNear("Topí", LightDomekColors.heat, colorRightOf("Topí", 7f))

        update(base.withStatus { it.copy(heating = false, cooling = true) })
        assertNear("Chladí", LightDomekColors.cool, colorRightOf("Chladí", 7f))

        update(base.withStatus { it.copy(heating = false, cooling = false) })
        assertNear("V${NBSP}klidu", LightDomekColors.neutralContainer, colorRightOf("V${NBSP}klidu", 7f))
    }

    @Test
    fun heroStepperChangesTheTargetThatIsInForce() {
        show()
        compose.onNodeWithText("Denní cíl").assertIsDisplayed()
        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()

        compose.onNodeWithContentDescription("Zvýšit").performClick()
        compose.onNodeWithContentDescription("Snížit").performClick()

        assertEquals(listOf("changeSetting(cil_den, 23.5)", "changeSetting(cil_den, 22.5)"), actions.calls)
    }

    @Test
    fun heroStepperUsesTheDayTargetInTheEveningToo() {
        show(base.withStatus { it.copy(dayPartText = DayPart.EVENING.json, time = "2026-10-20T19:30:00") })

        compose.onNodeWithText("Denní cíl").assertIsDisplayed()
        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()
    }

    @Test
    fun heroStepperUsesTheNightTargetAtNight() {
        show(base.withStatus { it.copy(dayPartText = DayPart.NIGHT.json, time = "2026-10-20T22:30:00", target = 20.0) })

        compose.onNodeWithText("Noční cíl").assertIsDisplayed()
        compose.onNodeWithText("20,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithContentDescription("Snížit").performClick()

        assertEquals(listOf("changeSetting(cil_noc, 19.5)"), actions.calls)
    }

    @Test
    fun heroStepperUsesTheManualTargetInManualMode() {
        show(base.withStatus { it.copy(mode = ThermostatMode.MANUAL, target = 22.0) })

        compose.onNodeWithText("Režim Ruční").assertIsDisplayed()
        compose.onNodeWithText("Ruční cíl").assertIsDisplayed()
        compose.onNodeWithText("22,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zvýšit").performClick()

        assertEquals(listOf("changeSetting(cil_rucni, 22.5)"), actions.calls)
    }

    // Rozpracovaná hodnota má před hodnotou z Raspberry přednost (spec 7).
    @Test
    fun heroStepperShowsTheDraftImmediately() {
        show(base.copy(drafts = mapOf(SettingKey.TARGET_DAY to JsonPrimitive(24.5))))

        compose.onNodeWithText("24,5${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("23,0${NBSP}°C").assertDoesNotExist()
    }

    @Test
    fun heroStepperFallsBackToTheValidTargetBeforeTheSettingsArrive() {
        show(base.copy(settings = null))

        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zvýšit").assertIsNotEnabled()
    }

    @Test
    fun heroStepperWithoutAnyTargetShowsDashAndIsDisabled() {
        show(base.copy(settings = null).withStatus { it.copy(target = null) })

        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zvýšit").assertIsNotEnabled()
    }

    @Test
    fun heroStepperIsDisabledWithoutAConnection() {
        show(base.copy(connection = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 305_000)))
        compose.onNodeWithContentDescription("Zvýšit").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Snížit").assertIsNotEnabled()

        update(simulated)
        compose.onNodeWithContentDescription("Zvýšit").assertIsEnabled()
    }

    // Při odesílání se jen otáčí ukazatel, číslo ani tlačítka se nesmí pohnout (viditelné poskočení při každém klepnutí).
    @Test
    fun theHeroDoesNotMoveWhileAWriteIsPending() {
        fun layout() = listOf(
            compose.onNodeWithContentDescription("Snížit").getBoundsInRoot(),
            compose.onNodeWithText("23,0${NBSP}°C").getBoundsInRoot(),
            compose.onNodeWithContentDescription("Zvýšit").getBoundsInRoot(),
            compose.onNodeWithText("Den · 10:00").getBoundsInRoot(),
            compose.onNodeWithText("Teploty").getBoundsInRoot(),
        )

        show()
        val settled = layout()
        update(base.copy(pendingKeys = setOf(SettingKey.TARGET_DAY)))

        assertEquals(settled, layout())
    }

    @Test
    fun offModeReplacesTheStepperWithATurnOnButton() {
        show(base.off())

        compose.onNodeWithText("Termostat je vypnutý").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zvýšit").assertDoesNotExist()
        compose.onNodeWithText("Denní cíl").assertDoesNotExist()

        compose.onNodeWithText("Zapnout (Auto)").performClick()

        assertEquals(listOf("setMode(AUTO)"), actions.calls)
    }

    @Test
    fun turnOnButtonIsDisabledWithoutAConnectionAndWhileTheModeIsBeingWritten() {
        show(base.off().copy(connection = ConnectionState.Scanning))
        compose.onNodeWithText("Zapnout (Auto)").assertIsNotEnabled()

        update(base.off())
        compose.onNodeWithText("Zapnout (Auto)").assertIsEnabled()

        update(base.off().copy(pendingKeys = setOf(SettingKey.MODE)))
        compose.onNodeWithText("Zapnout (Auto)").assertIsNotEnabled()
    }

    @Test
    fun nobodyHomeLabelShowsTheSetbackAndTheTargetInForce() {
        show(base.withStatus { it.copy(nobodyHome = true, target = 20.0) })

        compose.onNodeWithText("Nikdo doma · cíl -3,0${NBSP}°C, platí 20,0${NBSP}°C").assertIsDisplayed()
        // Stepper dál ukazuje cíl z Nastavení, který se mění.
        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()
    }

    @Test
    fun nobodyHomeLabelInManualModeClaimsNoSetback() {
        show(base.withStatus { it.copy(mode = ThermostatMode.MANUAL, nobodyHome = true, target = 22.0) })

        compose.onNodeWithText("Nikdo doma · platí 22,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("Nikdo doma · cíl", substring = true).assertDoesNotExist()
    }

    @Test
    fun nobodyHomeLabelIsAbsentWhenSomebodyIsHome() {
        show()

        compose.onNodeWithText("Nikdo doma", substring = true).assertDoesNotExist()
    }

    @Test
    fun nobodyHomeLabelWithoutSettingsHasNoSetback() {
        show(base.copy(settings = null).withStatus { it.copy(nobodyHome = true, target = 20.0) })

        compose.onNodeWithText("Nikdo doma · platí 20,0${NBSP}°C").assertIsDisplayed()
    }

    // Záporná nula by se vypsala jako „-0,0“.
    @Test
    fun nobodyHomeLabelWithZeroSetbackHasNoNegativeZero() {
        val state = base.copy(settings = checkNotNull(base.settings).copy(nobodyHomeSetback = 0.0))
        show(state.withStatus { it.copy(nobodyHome = true, target = 23.0) })

        compose.onNodeWithText("Nikdo doma · cíl 0,0${NBSP}°C, platí 23,0${NBSP}°C").assertIsDisplayed()
    }

    @Test
    fun missingInsideTemperatureShowsDashAndTheSensorNote() {
        show(base.withStatus { it.copy(insideTemp = null) })

        compose.onNodeWithText("Uvnitř · chyba čidla").assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("°C").assertDoesNotExist()
    }

    // Jednotka je jako horní index: horní okraj písmen „°C“ ladí s horním okrajem číslic (náhled). Měří se na snímku, ne z rozměrů
    // textů, protože jednotka se jen posouvá vrstvou.
    @Test
    fun theUnitIsRaisedToTheTopOfTheDigits() {
        show()
        val image = compose.renderScreenshot("unit")
        fun topOfInk(text: String, ink: Color): Float {
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return (bounds.top.roundToInt() until bounds.bottom.roundToInt()).first { y ->
                (bounds.left.roundToInt() until bounds.right.roundToInt()).any { x ->
                    isNear(ink, image.colorAt(x.toFloat(), y.toFloat()), 0.05f)
                }
            }.toFloat()
        }

        val digits = topOfInk("22,4", LightColorScheme.onSurface)
        val unit = topOfInk("°C", LightColorScheme.onSurfaceVariant)

        assertTrue("horní okraj jednotky ($unit px) ladí s okrajem číslic ($digits px)", abs(unit - digits) <= 2 * compose.density.density)
    }

    // ----- Kompaktní rozměry (náhled „Na šířku“) -----

    // Na náhledu „Na šířku“ (.cmp) je číslo 46 px místo 60 px a jednotka 20 px místo 26 px.
    @Test
    fun theCompactCardHasTheSmallerNumberAndUnitOfTheLandscapeMockup() {
        show()
        fun number() = compose.onNodeWithText("22,4", useUnmergedTree = true).fontSizeSp()
        fun unit() = compose.onNodeWithText("°C", useUnmergedTree = true).fontSizeSp()
        assertEquals("číslo", 60f, number(), 0.01f)
        assertEquals("jednotka", 26f, unit(), 0.01f)

        setCompact(true)

        assertEquals("číslo", 46f, number(), 0.01f)
        assertEquals("jednotka", 20f, unit(), 0.01f)
    }

    // Karta je o 22 dp nižší (číslo o 14 dp, odsazení nad ním o 4 dp a pod ním o 4 dp), dlaždice o 6,7 dp (odsazení o 2 + 2 dp
    // a hodnota 16 místo 18 px při řádkování 1,35).
    @Test
    fun theCompactCardAndTilesAreLowerByWhatTheLandscapeMockupSaves() {
        show()
        val venku = { tile("Venku", "21,8${NBSP}°C").getBoundsInRoot() }
        fun heroHeight() = (venku().top - compose.onNodeWithText("Den · 10:00").getBoundsInRoot().top).value
        fun tileHeight() = venku().height.value
        val regularHero = heroHeight()
        val regularTile = tileHeight()

        setCompact(true)

        assertEquals("karta termostatu", 22f, regularHero - heroHeight(), 1f)
        assertEquals("dlaždice", 6.7f, regularTile - tileHeight(), 1f)
    }

    // Jednotka je zvednutá o 18 dp místo 24 dp, takže její horní okraj ladí s horním okrajem menších číslic.
    @Test
    fun theUnitIsRaisedToTheTopOfTheDigitsInTheCompactCardToo() {
        show(compact = true)
        val image = compose.renderScreenshot("unit-compact")
        fun topOfInk(text: String, ink: Color): Float {
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return (bounds.top.roundToInt() until bounds.bottom.roundToInt()).first { y ->
                (bounds.left.roundToInt() until bounds.right.roundToInt()).any { x ->
                    isNear(ink, image.colorAt(x.toFloat(), y.toFloat()), 0.05f)
                }
            }.toFloat()
        }

        val digits = topOfInk("22,4", LightColorScheme.onSurface)
        val unit = topOfInk("°C", LightColorScheme.onSurfaceVariant)

        assertTrue("horní okraj jednotky ($unit px) ladí s okrajem číslic ($digits px)", abs(unit - digits) <= 2 * compose.density.density)
    }

    // ----- Dlaždice -----

    @Test
    fun tilesShowTheSixValuesFromTheStatus() {
        show()

        listOf(
            "Venku" to "21,8${NBSP}°C",
            "Vlhkost" to "45${NBSP}%",
            "Tlak" to "1013${NBSP}hPa",
            "Osvětlení" to "320${NBSP}lx",
            "Pohyb" to "Ne",
            "Světlo" to "Svítí",
        ).forEach { (label, value) -> tile(label, value).assertIsDisplayed() }
        compose.onNodeWithText("chyba čidla").assertDoesNotExist()
    }

    @Test
    fun missingSensorValuesShowDashWithTheNoteForThatSensor() {
        show(
            base.withStatus {
                it.copy(outsideTemp = null, humidity = null, pressure = null, lux = null, errors = listOf("meteo", "osvetleni"))
            },
        )

        compose.onAllNodesWithText("—").assertCountEquals(4)
        compose.onAllNodesWithText("chyba čidla").assertCountEquals(3)
        compose.onAllNodesWithText("čidlo chybí").assertCountEquals(1)
        compose.onNodeWithText("světlo · práh", substring = true).assertDoesNotExist()
    }

    // BMP280 vlhkost neměří: Raspberry pošle null bez poruchy v chyby (docs/ble-rozhrani.md, Stav), takže to chyba čidla není.
    @Test
    fun humidityWithoutAFaultIsNotMeasuredRatherThanASensorError() {
        show(base.withStatus { it.copy(humidity = null) })

        tile("Vlhkost", "neměří se").assertIsDisplayed()
        compose.onNodeWithText("chyba čidla").assertDoesNotExist()
    }

    @Test
    fun oneMissingSensorDoesNotAffectTheOthers() {
        show(base.withStatus { it.copy(humidity = null) })

        compose.onAllNodesWithText("—").assertCountEquals(1)
        compose.onNodeWithText("21,8${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("1013${NBSP}hPa").assertIsDisplayed()
        compose.onNodeWithText("320${NBSP}lx").assertIsDisplayed()
    }

    @Test
    fun lightLevelNoteComparesLuxWithTheThreshold() {
        fun withLux(lux: Double) = base.withStatus { it.copy(lux = lux) }

        show(withLux(49.0))
        compose.onNodeWithText("šero · práh 50").assertIsDisplayed()

        // Šero začne pod prahem a skončí až nad 1,3násobkem prahu (docs/ble-rozhrani.md, Světlo v automatice). Mezi tím
        // záleží na tom, co bylo předtím, a to aplikace neví, takže ukáže jen práh.
        update(withLux(50.0))
        compose.onNodeWithText("práh 50").assertIsDisplayed()

        update(withLux(65.0))
        compose.onNodeWithText("práh 50").assertIsDisplayed()

        update(withLux(65.5))
        compose.onNodeWithText("světlo · práh 50").assertIsDisplayed()

        update(withLux(320.0))
        compose.onNodeWithText("světlo · práh 50").assertIsDisplayed()
    }

    @Test
    fun lightLevelNoteFollowsTheThresholdBeingEdited() {
        show(base.copy(drafts = mapOf(SettingKey.LUX_THRESHOLD to JsonPrimitive(400.0))))

        compose.onNodeWithText("šero · práh 400").assertIsDisplayed()
    }

    @Test
    fun lightLevelWithoutTheThresholdHasNoNote() {
        show(base.copy(settings = null))

        compose.onNodeWithText("320${NBSP}lx").assertIsDisplayed()
        compose.onNodeWithText("práh", substring = true).assertDoesNotExist()
    }

    @Test
    fun motionTileShowsYesWithTheTimeOfTheLastMotion() {
        show(base.withStatus { it.copy(motion = true) }.copy(lastMotionAt = DEMO_WALL.minusSeconds(5)))

        compose.onNodeWithText("Ano").assertIsDisplayed()
        compose.onNodeWithText("před 5${NBSP}s").assertIsDisplayed()
    }

    @Test
    fun motionTileShowsNoAndHowLongAgoTheLastMotionWas() {
        show()

        compose.onNodeWithText("Ne").assertIsDisplayed()
        compose.onNodeWithText("před 40${NBSP}s").assertIsDisplayed()

        update(base.copy(lastMotionAt = DEMO_WALL.minusSeconds(95)))
        compose.onNodeWithText("před 1${NBSP}min").assertIsDisplayed()
    }

    @Test
    fun motionTileWithoutAnyMotionYetSaysNotYet() {
        show(base.copy(lastMotionAt = null))

        compose.onNodeWithText("Ne").assertIsDisplayed()
        compose.onNodeWithText("zatím ne").assertIsDisplayed()
    }

    @Test
    fun motionTileIsHighlightedOnlyWhileThereIsMotion() {
        show()
        assertNear("bez pohybu", LightColorScheme.surface, colorInsideCorner(tile("Pohyb", "Ne")))

        update(base.withStatus { it.copy(motion = true) })
        assertNear("pohyb", LightColorScheme.primaryContainer, colorInsideCorner(tile("Pohyb", "Ano")))
    }

    @Test
    fun lightTileShowsTheTextsFromTheLightState() {
        show()
        compose.onNodeWithText("Svítí").assertIsDisplayed()
        compose.onNodeWithText("ručně · 60${NBSP}%").assertIsDisplayed()

        update(base.withStatus { it.copy(light = LightState(on = true, color = listOf(255, 200, 100), brightness = 40, manual = false)) })
        compose.onNodeWithText("auto · 40${NBSP}%").assertIsDisplayed()

        update(base.withoutLight())
        compose.onNodeWithText("Zhasnuto").assertIsDisplayed()
        compose.onNodeWithText("auto").assertIsDisplayed()
    }

    // Starší program: výběr barvy nebo jasu světlo rovnou ručně zapne, dlaždice to ukazuje dřív, než to potvrdí Stav (spec 6.3).
    @Test
    fun lightTileOfAnOlderProgramShowsTheLightDraft() {
        show(base.withoutLight().copy(settings = WITHOUT_STORED_LOOK, lightDraft = LightDraft(brightness = 70, setAtMs = 300_000)))

        compose.onNodeWithText("Svítí").assertIsDisplayed()
        compose.onNodeWithText("ručně · 70${NBSP}%").assertIsDisplayed()
    }

    // S uloženou barvou a jasem se výběr jen uloží (spec 3 v 2026-10-23-denik-a-vylepseni-design.md): zhasnuté světlo zůstane
    // zhasnuté a svítící ukáže nový jas a zůstane, jak bylo.
    @Test
    fun lightTileShowsAStoredLookDraftWithoutSwitchingTheLightOn() {
        show(base.withoutLight().copy(lightDraft = LightDraft(brightness = 70, setAtMs = 300_000)))
        compose.onNodeWithText("Zhasnuto").assertIsDisplayed()
        compose.onNodeWithText("auto").assertIsDisplayed()

        update(base.copy(lightDraft = LightDraft(brightness = 70, setAtMs = 300_000)))
        compose.onNodeWithText("Svítí").assertIsDisplayed()
        compose.onNodeWithText("ručně · 70${NBSP}%").assertIsDisplayed()
    }

    @Test
    fun lightTileHasTheRealColourOfTheLightOnlyWhileItShines() {
        fun lightTileHasPixelsOf(color: Color): Boolean {
            val tile = compose.onNode(hasText("Světlo") and isGroup).fetchSemanticsNode().boundsInRoot
            val image = compose.renderScreenshot("light")
            return (tile.top.roundToInt() until tile.bottom.roundToInt()).any { y ->
                (tile.left.roundToInt() until tile.right.roundToInt()).any { x -> image.getRGB(x, y) == color.toArgb() }
            }
        }
        val blue = Color(0xFF646EFF)
        val orange = Color(0xFFFF8000)

        show()
        assertTrue("světlo svítí v modré", lightTileHasPixelsOf(blue))

        update(base.withStatus { it.copy(light = it.light.copy(color = listOf(255, 128, 0))) })
        assertTrue("barva se mění se Stavem", lightTileHasPixelsOf(orange))
        assertTrue("původní barva zmizela", !lightTileHasPixelsOf(blue))

        update(base.withStatus { it.copy(light = it.light.copy(color = listOf(255, 128, 0), on = false)) })
        assertTrue("zhasnuté světlo nemá barvu", !lightTileHasPixelsOf(orange))
    }

    @Test
    fun onlyTheLightTileOpensTheLightTab() {
        show()

        tile("Venku", "21,8${NBSP}°C").performClick()
        tile("Pohyb", "Ne").performClick()
        tile("Osvětlení", "320${NBSP}lx").performClick()
        assertEquals(0, openedLight)

        tile("Světlo", "Svítí").performClick()
        assertEquals(1, openedLight)
    }

    @Test
    fun lightTileSaysWhatTheClickDoes() {
        show()

        tile("Světlo", "Svítí").assert(
            SemanticsMatcher("klepnutí otevře Světlo") { it.config[SemanticsActions.OnClick].label == "Otevřít záložku Světlo" },
        )
    }

    // ----- Graf -----

    @Test
    fun theChartWindowComesFromTheStateAndChangesGoToTheActions() {
        show(base.copy(chartWindow = ChartWindow.FIFTEEN_MIN))
        compose.onNodeWithText(ChartWindow.FIFTEEN_MIN.label).assertIsSelected()
        compose.onNodeWithText(ChartWindow.ALL.label).assertIsNotSelected()

        compose.onNodeWithText(ChartWindow.FIVE_MIN.label).performClick()

        assertEquals(listOf("setChartWindow(FIVE_MIN)"), actions.calls)
    }

    // ----- Ovládání simulace -----

    @Test
    fun simulationCardIsOnlyInTheSimulation() {
        show()
        compose.onNodeWithText("Ovládání simulace").assertDoesNotExist()

        update(simulated)
        compose.onNodeWithText("Ovládání simulace").assertIsDisplayed()
        compose.onNodeWithText("Nahrazuje zásahy do skutečného domu.").assertIsDisplayed()
    }

    @Test
    fun simulationButtonsPerformTheirActions() {
        show(simulated)

        SimulationAction.entries.forEach { simulationButton(it).performClick() }

        assertEquals(SimulationAction.entries.map { "simulation($it)" }, actions.calls)
    }

    @Test
    fun sensorFaultChipIsSelectedWhileTheStatusReportsTheFault() {
        show(simulated)
        compose.onNodeWithText("Porucha BME280").assertIsNotSelected()

        update(simulated.withErrors("meteo"))
        compose.onNodeWithText("Porucha BME280").assertIsSelected()

        update(simulated.withErrors("osvetleni"))
        compose.onNodeWithText("Porucha BME280").assertIsNotSelected()
    }

    // ----- Stará data -----

    // Spec 6.5: hodnoty zešednou, banner ne. Bílá karta po zešednutí je světle šedá o pár odstínů, proto se u ní srovnává přesně.
    @Test
    fun staleDataDimTheHeroTilesAndChartButNotTheBanner() {
        fun cornerOfTile() = colorInsideCorner(tile("Venku", "21,8${NBSP}°C"))
        fun besideChartTitle() = colorRightOf("Teploty", 30f)

        show()
        assertNear("štítek Topí", LightDomekColors.heat, colorRightOf("Topí", 7f))
        assertNear("dlaždice", LightColorScheme.surface, cornerOfTile(), EXACT)
        assertNear("karta grafu", LightColorScheme.surface, besideChartTitle(), EXACT)

        update(stale())
        assertNear("banner zůstává plný", LightDomekColors.staleContainer, colorRightOf("Data jsou 7${NBSP}s stará", 20f), EXACT)
        assertTrue("štítek Topí zešedl", !isNear(LightDomekColors.heat, colorRightOf("Topí", 7f)))
        assertTrue("dlaždice zešedla", !isNear(LightColorScheme.surface, cornerOfTile(), EXACT))
        assertTrue("karta grafu zešedla", !isNear(LightColorScheme.surface, besideChartTitle(), EXACT))
    }

    // ----- Rozvržení -----

    @Test
    fun noTextIsCutOffAtTheNormalFontSize() {
        show(base.withErrors("meteo", "osvetleni").copy(lastMotionAt = null))

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    // Při velkém písmu se dlaždice řadí po dvou a nic se nezkracuje.
    @Test
    fun noTextIsCutOffAtALargeFontSize() {
        show(base.withErrors("meteo", "osvetleni").copy(lastMotionAt = null), fontScale = 1.5f)

        assertEquals(emptyList<String>(), compose.cutOffTexts())
    }

    @Test
    fun tilesAreInThreeColumnsAtTheNormalFontSize() {
        show()

        val tops = listOf("Venku" to "21,8${NBSP}°C", "Vlhkost" to "45${NBSP}%", "Tlak" to "1013${NBSP}hPa")
            .map { (label, value) -> tile(label, value).getBoundsInRoot().top }
        assertEquals("první řada má tři dlaždice vedle sebe: $tops", 1, tops.distinct().size)
    }

    @Test
    fun tilesAreInTwoColumnsAtALargeFontSize() {
        show(fontScale = 1.5f)

        val first = tile("Venku", "21,8${NBSP}°C").getBoundsInRoot()
        val second = tile("Vlhkost", "45${NBSP}%").getBoundsInRoot()
        val third = tile("Tlak", "1013${NBSP}hPa").getBoundsInRoot()
        assertEquals("první řada má dvě dlaždice: $first, $second", first.top, second.top)
        assertTrue("třetí dlaždice je v druhé řadě: $second, $third", third.top > second.bottom)
        assertEquals("a stojí pod první", first.left, third.left)
    }

    @Test
    fun onANarrowScreenTheChartIsBetweenTheThermostatCardAndTheTiles() {
        show(simulated)

        val hero = compose.onNodeWithText("Den · 10:00").getBoundsInRoot()
        val chart = compose.onNodeWithText("Teploty").getBoundsInRoot()
        val tiles = tile("Venku", "21,8${NBSP}°C").getBoundsInRoot()
        val simulation = compose.onNodeWithText("Ovládání simulace").getBoundsInRoot()

        assertTrue("graf je pod kartou termostatu: $hero, $chart", hero.bottom < chart.top)
        assertTrue("dlaždice jsou pod grafem: $chart, $tiles", chart.bottom < tiles.top)
        assertTrue("ovládání simulace je poslední: $tiles, $simulation", tiles.bottom < simulation.top)
    }

    @Test
    @Config(qualifiers = WIDE_TALL)
    fun onAWideScreenTheChartHasItsOwnColumnOnTheRight() {
        show(simulated, wide = true)

        val mode = compose.onNodeWithText("Režim Auto").getBoundsInRoot()
        val tiles = tile("Venku", "21,8${NBSP}°C").getBoundsInRoot()
        val simulation = compose.onNodeWithText("Ovládání simulace").getBoundsInRoot()
        val chart = compose.onNodeWithText("Teploty").getBoundsInRoot()

        assertTrue("graf je vpravo od karty termostatu: $mode, $chart", chart.left > mode.right)
        assertTrue("graf je vpravo od dlaždic: $tiles, $chart", chart.left > tiles.right)
        assertTrue("graf je nahoře ve svém sloupci: $chart", chart.top < 40.dp)
        assertTrue("dlaždice jsou pod kartou termostatu: $mode, $tiles", mode.bottom < tiles.top)
        assertTrue("ovládání simulace je v levém sloupci pod dlaždicemi: $tiles, $simulation", tiles.bottom < simulation.top)
        assertTrue("ovládání simulace je vlevo od grafu: $simulation, $chart", simulation.right < chart.left)
    }

    // Do Přehledu se stav bez dat nedostane (DomekApp ukazuje obrazovku spojení), ale nesmí kvůli němu spadnout.
    @Test
    fun withoutDataNothingIsDrawn() {
        show(HouseUiState())

        compose.onNodeWithText("Teploty").assertDoesNotExist()
        compose.onNodeWithText("Uvnitř").assertDoesNotExist()
    }
}
