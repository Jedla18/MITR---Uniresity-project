package cz.vsb.mitr.domek.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.SystemActions
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Pulzování a ukazatel se v půli pohybu, jak je ukazují náhledy.
private const val ANIMATION_MS = 400L

// Panel s výběrem domku najíždí zdola, proto se čeká, až se usadí.
private const val SHEET_MS = 1_000L

private val TWO_DEVICES = listOf(
    FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -61),
    FoundDevice("DC:A6:32:7F:03:1B", "Termostat", -78),
)

// Stavy před prvními daty. Celá aplikace, aby šly obrazovky porovnat s náhledy i s horní a dolní lištou.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ConnectionScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Hodiny testu stojí, aby šla animace zastavit v půli pohybu. S běžícími hodinami by test nekonečné animace vypnul
    // a pulzování by na snímku nebylo.
    private fun capture(
        name: String,
        connection: ConnectionState,
        system: SystemActions = NoSystem,
        darkTheme: Boolean = false,
        advanceMs: Long = ANIMATION_MS,
        nowMs: Long = 0,
    ) {
        val state = HouseUiState(connection = connection, nowMs = nowMs)
        compose.mainClock.autoAdvance = false
        compose.setContent { DomekTheme(darkTheme) { DomekApp(state, NoActions, system, emptyFlow()) } }
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.saveScreenshot(name)
    }

    @Test
    fun scanning() = capture("spojeni-scanning", ConnectionState.Scanning)

    @Test
    fun choosing() = capture("spojeni-choosing", ConnectionState.ChoosingDevice(TWO_DEVICES), advanceMs = SHEET_MS)

    @Test
    fun permissions() = capture("spojeni-permissions", ConnectionState.PermissionsMissing)

    @Test
    fun permissionsDenied() =
        capture("spojeni-permissions-denied", ConnectionState.PermissionsMissing, SystemActions({}, {}, {}, {}, true))

    @Test
    fun bluetoothOff() = capture("spojeni-bt-off", ConnectionState.BluetoothOff)

    // Odpočet do dalšího pokusu: 304,0 s − 300,4 s zaokrouhleno nahoru jsou 4 s.
    @Test
    fun waiting() =
        capture("spojeni-waiting", ConnectionState.WaitingToReconnect(attempt = 2, retryAtMs = 304_000), nowMs = 300_400)

    @Test
    fun missingService() = capture("spojeni-missing-service", ConnectionState.MissingService)

    @Test
    fun connecting() = capture("spojeni-connecting", ConnectionState.Connecting(attempt = 2))

    @Test
    fun disconnectedByUser() = capture("spojeni-disconnected", ConnectionState.DisconnectedByUser)

    @Test
    fun locationOff() = capture("spojeni-location-off", ConnectionState.LocationOff)

    @Test
    fun noBluetooth() = capture("spojeni-no-bluetooth", ConnectionState.NoBluetooth)

    // Na šířku je místa nejméně. Nejdelší stav (dvě tlačítka a tři řádky textu) se musí dát posunout.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun scanningLandscape() = capture("spojeni-scanning-sirka", ConnectionState.Scanning)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun locationOffLandscape() = capture("spojeni-location-off-sirka", ConnectionState.LocationOff)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun choosingLandscape() =
        capture("spojeni-choosing-sirka", ConnectionState.ChoosingDevice(TWO_DEVICES), advanceMs = SHEET_MS)

    @Test
    fun scanningDark() = capture("spojeni-scanning-dark", ConnectionState.Scanning, darkTheme = true)

    @Test
    fun permissionsDark() = capture("spojeni-permissions-dark", ConnectionState.PermissionsMissing, darkTheme = true)

    @Test
    fun choosingDark() =
        capture("spojeni-choosing-dark", ConnectionState.ChoosingDevice(TWO_DEVICES), darkTheme = true, advanceMs = SHEET_MS)
}
