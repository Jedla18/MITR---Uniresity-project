package cz.vsb.mitr.domek.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ValueStepperTest {
    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<Double>()

    // Hodnota se po změně překreslí jako se skutečným ViewModelem, takže opakování kroků navazuje na novou hodnotu.
    private fun showStepper(enabled: Boolean = true, pending: Boolean = false) {
        compose.setContent {
            var value by remember { mutableStateOf(23.0) }
            DomekTheme(darkTheme = false) {
                ValueStepper(
                    value, SettingKey.TARGET_DAY, ::formatTemp,
                    onChange = { changes += it; value = it }, enabled = enabled, pending = pending,
                )
            }
        }
    }

    @Test
    fun tapStepsOnceBySettingStep() {
        showStepper()
        compose.onNodeWithContentDescription("Zvýšit").performClick()
        compose.onNodeWithContentDescription("Zvýšit").performClick()
        compose.onNodeWithContentDescription("Snížit").performClick()
        assertEquals(listOf(23.5, 24.0, 23.5), changes)
    }

    @Test
    fun holdingRepeatsAfter400MsEvery100MsAndReleaseAddsNoStep() {
        showStepper()
        compose.mainClock.autoAdvance = false
        val plus = compose.onNodeWithContentDescription("Zvýšit")

        plus.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(350)
        assertEquals(emptyList<Double>(), changes)
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(23.5), changes)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(listOf(23.5, 24.0, 24.5), changes)

        plus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(23.5, 24.0, 24.5), changes)
    }

    @Test
    fun disabledStepperIgnoresButtonsAndNumber() {
        showStepper(enabled = false)
        compose.onNodeWithContentDescription("Zvýšit").performClick()
        compose.onNodeWithContentDescription("Snížit").performClick()
        compose.onNodeWithText(formatTemp(23.0)).performClick()
        assertEquals(emptyList<Double>(), changes)
        compose.onNodeWithText("Uložit").assertDoesNotExist()
    }

    @Test
    fun typedNumberAcceptsCommaAndDotAndIsClampedToRange() {
        showStepper()
        enterNumber("23,5")
        enterNumber("21.5")
        enterNumber("50")
        enterNumber("-3")
        assertEquals(listOf(23.5, 21.5, 35.0, 5.0), changes)
    }

    @Test
    fun cancelledOrUnreadableNumberChangesNothing() {
        showStepper()
        compose.onNodeWithText(formatTemp(23.0)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("abc")
        compose.onNodeWithText("Uložit").assertIsNotEnabled()
        compose.onNodeWithText("Zrušit").performClick()
        compose.onNodeWithText("Uložit").assertDoesNotExist()
        assertEquals(emptyList<Double>(), changes)
    }

    @Test
    fun pendingShowsProgressNextToNumber() {
        showStepper(pending = true)
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun settledValueHasNoProgress() {
        showStepper(pending = false)
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    }

    private fun enterNumber(text: String) {
        compose.onNodeWithText(formatTemp(changes.lastOrNull() ?: 23.0)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNodeWithText("Uložit").performClick()
    }
}
