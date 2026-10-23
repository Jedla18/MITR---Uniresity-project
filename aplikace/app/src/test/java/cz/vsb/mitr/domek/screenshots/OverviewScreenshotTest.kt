package cz.vsb.mitr.domek.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.DomekApp
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

// Celá aplikace na záložce Přehled, aby šel snímek porovnat s náhledem 1 i s horní a dolní lištou. Snímky jsou vysoké,
// aby byl vidět celý posouvaný obsah; jen „prehled-demo-telefon“ má velikost telefonu jako náhled.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class OverviewScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        state: HouseUiState,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        animated: Boolean = false,
    ) {
        compose.mainClock.autoAdvance = !animated
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DomekTheme(darkTheme) { DomekApp(state, NoActions, NoSystem, emptyFlow()) }
            }
        }
        if (animated) compose.mainClock.advanceTimeBy(ANIMATION_MS)
        compose.saveScreenshot(name)
    }

    // Náhled 1: červeně tónovaná karta, Topí, Denní cíl 23,0 °C, graf a šest dlaždic.
    @Test
    fun demo() {
        capture("prehled-demo", demoStateWithoutClockWarning())

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
        compose.onNodeWithText("Režim Auto").assertIsDisplayed()
        compose.onNodeWithText("22,4").assertIsDisplayed()
        compose.onNodeWithText("Denní cíl").assertIsDisplayed()
        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()
        // Štítek a legenda grafu mají stejný popisek.
        compose.onAllNodesWithText("Topí").assertCountEquals(2)
        compose.onNodeWithText("Teploty").assertIsDisplayed()
        listOf("21,8${NBSP}°C", "45${NBSP}%", "1013${NBSP}hPa", "320${NBSP}lx", "Ne", "Svítí").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Data jsou", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ovládání simulace").assertDoesNotExist()
    }

    // Přesně velikost telefonu z náhledu 1: spodní řada dlaždic zajíždí pod dolní lištu.
    @Test
    @Config(qualifiers = PHONE)
    fun demoOnPhoneScreen() {
        capture("prehled-demo-telefon", demoStateWithoutClockWarning())

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
        compose.onNodeWithText("Teploty").assertIsDisplayed()
    }

    @Test
    fun cooling() {
        val state = demoStateWithoutClockWarning()
            .withStatus { it.copy(heating = false, cooling = true, insideTemp = 24.1, target = 22.0) }
            .copy(settings = DEFAULTS.copy(targetDay = 22.0))
        capture("prehled-chladi", state)

        compose.onNodeWithText("24,1").assertIsDisplayed()
        compose.onNodeWithText("22,0${NBSP}°C").assertIsDisplayed()
        // Legenda grafu má Topí i Chladí, štítek přibude jen u Chladí.
        compose.onAllNodesWithText("Chladí").assertCountEquals(2)
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
    }

    // Cíl je dosažený, nic netopí ani nechladí a světlo je zhasnuté (dlaždice má šedý obrys žárovky).
    @Test
    fun idle() {
        val unlit = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = false)
        val state = demoStateWithoutClockWarning().withStatus {
            it.copy(heating = false, cooling = false, insideTemp = 23.0, light = unlit)
        }
        capture("prehled-klid", state)

        compose.onNodeWithText("V${NBSP}klidu").assertIsDisplayed()
        compose.onAllNodesWithText("Topí").assertCountEquals(1)
        compose.onNodeWithText("Zhasnuto").assertIsDisplayed()
    }

    @Test
    fun thermostatOff() {
        val state = demoStateWithoutClockWarning()
            .withStatus { it.copy(mode = ThermostatMode.OFF, target = null, heating = false, cooling = false) }
            .copy(settings = DEFAULTS.copy(mode = ThermostatMode.OFF))
        capture("prehled-vypnuto", state)

        compose.onNodeWithText("Režim Vypnuto").assertIsDisplayed()
        compose.onNodeWithText("Termostat je vypnutý").assertIsDisplayed()
        compose.onNodeWithText("Zapnout (Auto)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zvýšit").assertDoesNotExist()
    }

    @Test
    fun nobodyHome() {
        val state = demoStateWithoutClockWarning().withStatus { it.copy(nobodyHome = true, target = 20.0) }
        capture("prehled-nikdo-doma", state)

        compose.onNodeWithText("Nikdo doma · cíl -3,0${NBSP}°C, platí 20,0${NBSP}°C").assertIsDisplayed()
        compose.onNodeWithText("23,0${NBSP}°C").assertIsDisplayed()
    }

    // Noc: platí noční cíl a čas je čas Raspberry.
    @Test
    fun night() {
        val state = demoState()
            .withStatus { it.copy(dayPartText = DayPart.NIGHT.json, time = "2026-10-20T22:10:00", target = 20.0) }
            .withClocksInSync()
        capture("prehled-noc", state)

        compose.onNodeWithText("Noc · 22:10").assertIsDisplayed()
        compose.onNodeWithText("Noční cíl").assertIsDisplayed()
        compose.onNodeWithText("20,0${NBSP}°C").assertIsDisplayed()
    }

    // Za 7 s od posledního Stavu: oranžová karta nahoře a zešedlé hodnoty pod ní.
    @Test
    fun staleData() {
        capture("prehled-stara-data", demoStateWithoutClockWarning().copy(nowMs = 307_000))

        compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
        compose.onNodeWithText("Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.").assertIsDisplayed()
    }

    // Po ručním odpojení: banner říká, kde se domek připojí, ovládací prvky jsou zašedlé a pilulka je červená „Odpojeno“.
    @Test
    fun staleDataAfterTheUserDisconnected() {
        val state = demoStateWithoutClockWarning().copy(connection = ConnectionState.DisconnectedByUser, nowMs = 307_000)
        capture("prehled-odpojeno", state)

        compose.onNodeWithText("Data jsou 7${NBSP}s stará").assertIsDisplayed()
        compose.onNodeWithText("Dům je odpojený. Připojit ho jde v${NBSP}Nastavení.").assertIsDisplayed()
        compose.onNodeWithText("Odpojeno").assertIsDisplayed()
    }

    // Review Focus 5: čidla bez hodnot nesmí obrazovku shodit, místo hodnot je „—“ s poznámkou. Při poruše BME280 Raspberry netopí
    // ani nechladí (docs/ble-rozhrani.md).
    @Test
    fun sensorErrors() {
        val state = demoStateWithoutClockWarning().withStatus {
            it.copy(
                errors = listOf("meteo", "osvetleni"),
                outsideTemp = null,
                humidity = null,
                pressure = null,
                lux = null,
                heating = false,
            )
        }
        capture("prehled-chyby", state)

        listOf(
            "Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
            "Venkovní teplota, vlhkost a tlak chybí. Dokud čidlo nejde přečíst, termostat netopí ani nechladí.",
            "Čidlo osvětlení (TSL2561) neodpovídá",
            "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí.",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithText("—").assertCountEquals(4)
        compose.onAllNodesWithText("chyba čidla").assertCountEquals(3)
        compose.onNodeWithText("čidlo chybí").assertIsDisplayed()
    }

    @Test
    fun missingInsideTemperature() {
        capture("prehled-bez-vnitrni", demoStateWithoutClockWarning().withStatus { it.copy(insideTemp = null) })

        compose.onNodeWithText("Uvnitř · chyba čidla").assertIsDisplayed()
        compose.onNodeWithText("°C").assertDoesNotExist()
    }

    @Test
    fun clockThreeHoursBehind() {
        // Telefon má v demoState 10:02:15, takže Raspberry s časem 07:02:15 jde o tři hodiny pozadu.
        capture("prehled-hodiny", demoState().withStatus { it.copy(time = "2026-10-20T07:02:15") })

        compose.onNodeWithText("Hodiny Raspberry jdou o${NBSP}3${NBSP}h pozadu.").assertIsDisplayed()
        compose.onNodeWithText("Na hodinách závisí denní doba.").assertIsDisplayed()
        compose.onNodeWithText("Srovnat").assertIsDisplayed()
    }

    @Test
    fun simulation() {
        val state = demoStateWithoutClockWarning().copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated)
        capture("prehled-simulace", state)

        compose.onNodeWithText("Ovládání simulace").assertIsDisplayed()
        compose.onNodeWithText("Nahrazuje zásahy do skutečného domu.").assertIsDisplayed()
        // „Pohyb“ je i popisek dlaždice, tlačítko se pozná podle klepnutí.
        SimulationAction.entries.forEach { compose.onNode(hasText(it.label) and hasClickAction()).assertIsDisplayed() }
    }

    // Porucha BME280 z ovládání simulace: štítek je vybraný, nahoře je červená karta a venkovní hodnoty chybí.
    @Test
    fun simulationWithSensorFault() {
        val state = demoStateWithoutClockWarning()
            .copy(sourceKind = SourceKind.SIMULATION, connection = ConnectionState.Simulated)
            .withStatus { it.copy(errors = listOf("meteo"), outsideTemp = null, humidity = null, pressure = null, heating = false) }
        capture("prehled-simulace-porucha", state)

        compose.onNodeWithText("Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá").assertIsDisplayed()
        compose.onNodeWithText("Porucha BME280").assertIsSelected()
    }

    // Krok − nebo + hned změní zobrazené číslo a vedle něj se otáčí ukazatel odesílání.
    @Test
    fun pendingTarget() {
        val state = demoStateWithoutClockWarning().copy(
            drafts = mapOf(SettingKey.TARGET_DAY to JsonPrimitive(23.5)),
            pendingKeys = setOf(SettingKey.TARGET_DAY),
        )
        capture("prehled-odesilani", state, animated = true)

        compose.onNodeWithText("23,5${NBSP}°C").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou.
    @Test
    fun dark() {
        capture("prehled-dark", demoStateWithoutClockWarning(), darkTheme = true)

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
    }

    // Při větším písmu v systému roste velká teplota i dlaždice, nic se nesmí oříznout.
    @Test
    fun largeFont() {
        capture("prehled-velke-pismo", demoStateWithoutClockWarning(), fontScale = 1.5f)

        compose.onNodeWithText("22,4").assertIsDisplayed()
    }

    // Na šířku má graf vlastní sloupec vpravo.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun landscape() {
        capture("prehled-sirka", demoStateWithoutClockWarning())

        compose.onNodeWithText("Den · 10:00").assertIsDisplayed()
        compose.onNodeWithText("Teploty").assertIsDisplayed()
    }
}
