package cz.vsb.mitr.domek.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.screenshots.PHONE_TALL
import cz.vsb.mitr.domek.testing.hourOnDial
import cz.vsb.mitr.domek.testing.pickMinute
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class TimePickerDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val confirmed = mutableListOf<LocalTime>()
    private var dismissed = 0

    private fun show(initial: LocalTime = LocalTime.of(7, 5)) {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                TimePickerDialog(title = "Začátek dne", initial = initial, onConfirm = { confirmed += it }, onDismiss = { dismissed++ })
            }
        }
    }

    @Test
    fun showsTheTitleTheInitialTimeAndBothButtons() {
        show(LocalTime.of(7, 5))

        compose.onNodeWithText("Začátek dne").assertIsDisplayed()
        compose.onNodeWithText("07").assertIsDisplayed()
        compose.onNodeWithText("05").assertIsDisplayed()
        compose.onNodeWithText("Uložit").assertIsDisplayed()
        compose.onNodeWithText("Zrušit").assertIsDisplayed()
    }

    // 24h ciferník: dva kruhy čísel 0 až 23 a žádné dopoledne a odpoledne.
    @Test
    fun usesADialWithAll24Hours() {
        show()

        (0..23).forEach { compose.onNode(hourOnDial(it)).assertExists() }
        compose.onNodeWithText("AM").assertDoesNotExist()
        compose.onNodeWithText("PM").assertDoesNotExist()
    }

    @Test
    fun theHourOfTheInitialTimeIsSelectedOnTheDial() {
        show(LocalTime.of(19, 40))

        compose.onNode(hourOnDial(19)).assertIsSelected()
        compose.onNodeWithText("19").assertIsDisplayed()
        compose.onNodeWithText("40").assertIsDisplayed()
    }

    @Test
    fun savingWithoutAChangeConfirmsTheInitialTime() {
        show(LocalTime.of(7, 5))

        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf(LocalTime.of(7, 5)), confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun cancellingDismissesWithoutConfirming() {
        show()

        compose.onNodeWithText("Zrušit").performClick()

        assertEquals(emptyList<LocalTime>(), confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun pickedHourAndMinuteAreConfirmed() {
        show(LocalTime.of(7, 5))

        compose.onNode(hourOnDial(10)).performClick()
        compose.pickMinute(35)
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf(LocalTime.of(10, 35)), confirmed)
    }

    // Vnitřní kruh má hodiny 12 až 23, vnější 0 až 11. Minuty zůstávají, dokud se nezmění.
    @Test
    fun theInnerRingGivesTheAfternoonHours() {
        show(LocalTime.of(7, 5))

        compose.onNode(hourOnDial(19)).performClick()
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf(LocalTime.of(19, 5)), confirmed)
    }

    @Test
    fun midnightIsHourZero() {
        show(LocalTime.of(7, 5))

        compose.onNode(hourOnDial(0)).performClick()
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf(LocalTime.of(0, 5)), confirmed)
    }

    // Minuty jdou změnit i bez hodiny.
    @Test
    fun minutesCanBeChangedOnTheirOwn() {
        show(LocalTime.of(7, 5))

        compose.pickMinute(30)
        compose.onNodeWithText("Uložit").performClick()

        assertEquals(listOf(LocalTime.of(7, 30)), confirmed)
    }
}
