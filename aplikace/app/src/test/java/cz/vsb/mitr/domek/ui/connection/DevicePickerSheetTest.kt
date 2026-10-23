package cz.vsb.mitr.domek.ui.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val SHEET_TITLE = "V dosahu je víc domů"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class DevicePickerSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val strong = FoundDevice("B8:27:EB:4A:19:C2", "Termostat", -48)
    private val middle = FoundDevice("DC:A6:32:7F:03:1B", "Termostat", -61)
    private val weak = FoundDevice("E4:5F:01:12:AB:90", "Termostat", -83)

    private var devices by mutableStateOf(listOf(weak, strong, middle))
    private val chosen = mutableListOf<String>()

    @Before
    fun showSheet() {
        compose.setContent { DomekTheme(darkTheme = false) { DevicePickerSheet(devices) { chosen += it } } }
    }

    private fun row(device: FoundDevice) = "${device.name} · ${device.address} · signál ${device.rssi}${NBSP}dBm"

    // Řádky shora dolů. Hledá se podle oddělovače, který má jen řádek zařízení, ne nadpis panelu.
    private fun rows(): List<String> =
        compose.onAllNodes(hasText(" · ", substring = true)).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun showsTheTitleAndOneRowPerDeviceWithAddressAndSignal() {
        compose.onNodeWithText(SHEET_TITLE).assertExists()
        assertEquals(setOf(row(strong), row(middle), row(weak)), rows().toSet())
        assertEquals(3, rows().size)
    }

    // Spec 6.5: seznam je seřazený podle síly signálu, nejsilnější nahoře. Zařízení přišla od nejslabšího.
    @Test
    fun rowsAreSortedByTheStrongestSignalWhenTheSheetOpens() {
        assertEquals(listOf(row(strong), row(middle), row(weak)), rows())
    }

    // Hledání při výběru dál běží a signál se mění. Kdyby se řádky řadily znovu, poskakovaly by pod prstem (stejně se
    // zdroj snaží, aby neposkakovaly), proto se pořadí z otevření drží a nové zařízení přibývá na konec.
    @Test
    fun laterSignalChangesKeepTheOrderAndNewDevicesGoLast() {
        val weakGotStronger = weak.copy(rssi = -40)
        val newcomer = FoundDevice("DC:A6:32:11:22:33", "Termostat", -30)

        devices = listOf(strong, weakGotStronger, middle, newcomer)
        compose.waitForIdle()

        assertEquals(listOf(row(strong), row(middle), row(weakGotStronger), row(newcomer)), rows())
    }

    // Zařízení můžou být desítky (třeba celá učebna). Seznam se posouvá a vybrat jde i poslední řádek.
    @Test
    fun aLongListScrollsInsideTheSheet() {
        val classroom = (1..30).map { FoundDevice("DC:A6:32:00:00:%02X".format(it), "Termostat", -40 - it) }
        devices = classroom
        compose.waitForIdle()
        val last = classroom.last()
        compose.onNodeWithText(row(last)).assertDoesNotExist()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(row(last)))
        compose.onNodeWithText(row(last)).performClick()

        assertEquals(listOf(last.address), chosen)
    }

    @Test
    fun tappingARowChoosesItsAddress() {
        compose.onNodeWithText(row(middle)).performClick()

        assertEquals(listOf(middle.address), chosen)
    }

    // Zavřený panel zmizí i s oknem, které jinak zůstane nad aplikací a zachytává dotyky. Nic se přitom nevybere.
    @Test
    fun dismissingTheSheetHidesItWithoutChoosingAnything() {
        compose.onNode(hasClickAction() and hasContentDescription("Close sheet")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
        assertEquals(emptyList<String>(), chosen)
    }
}
