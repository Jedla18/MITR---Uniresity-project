package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.formatLux
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Rozměry a polohy ovladače. Běží se skutečnými písmy a hustotou telefonu (NATIVE), protože v běžném režimu Robolectricu je
// text jen zlomkem skutečné šířky a ukazatel odesílání by se vždy vešel do minimální šířky čísla, takže by posun nešlo vidět.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ValueStepperLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private var pending by mutableStateOf(false)

    // Ovladač je zarovnaný doprava jako v řádcích karet, takže každé jeho rozšíření posune tlačítko −.
    private fun show(size: StepperSize, key: SettingKey, value: Double, format: (Double) -> String) {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ValueStepper(value, key, format, {}, enabled = true, pending = pending, size = size)
                }
            }
        }
    }

    private fun minus() = compose.onNodeWithContentDescription("Snížit")

    private fun plus() = compose.onNodeWithContentDescription("Zvýšit")

    private fun ring() = compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))

    private fun minutes(value: Double) = "${formatDecimal(value, 0)}${NBSP}min"

    // Při každém klepnutí na − nebo + se ukazatel odesílání objeví a po odeslání zase zmizí. Tlačítka ani číslo se přitom
    // nesmí pohnout, jinak by obrazovka při předvádění pořád poskakovala.
    private fun assertNothingMovesWhilePending(size: StepperSize, key: SettingKey, value: Double, format: (Double) -> String) {
        show(size, key, value, format)
        fun layout() = listOf(
            minus().getBoundsInRoot(),
            compose.onNodeWithText(format(value)).getBoundsInRoot(),
            plus().getBoundsInRoot(),
        )
        val settled = layout()

        pending = true
        compose.waitForIdle()
        ring().assertExists()
        assertEquals("tlačítka a číslo během odesílání", settled, layout())

        pending = false
        compose.waitForIdle()
        assertEquals("tlačítka a číslo po odeslání", settled, layout())
    }

    @Test
    fun compactTemperatureDoesNotMoveWhilePending() =
        assertNothingMovesWhilePending(StepperSize.COMPACT, SettingKey.TARGET_DAY, 23.0, ::formatTemp)

    @Test
    fun compactMinutesDoNotMoveWhilePending() =
        assertNothingMovesWhilePending(StepperSize.COMPACT, SettingKey.NOBODY_HOME_AFTER, 30.0, ::minutes)

    // Široký text (nad minimální šířkou čísla) nemá okolo sebe žádné volné místo, ukazatel proto nesmí nic roztáhnout.
    @Test
    fun compactWideTextDoesNotMoveWhilePending() =
        assertNothingMovesWhilePending(StepperSize.COMPACT, SettingKey.LUX_THRESHOLD, 10000.0, ::formatLux)

    @Test
    fun largeStepperDoesNotMoveWhilePending() =
        assertNothingMovesWhilePending(StepperSize.LARGE, SettingKey.TARGET_DAY, 23.0, ::formatTemp)

    // Ukazatel malého ovladače se jen přikreslí k číslu, takže nesmí zakrýt žádné z tlačítek.
    private fun assertRingIsBetweenTheButtons(key: SettingKey, value: Double, format: (Double) -> String) {
        pending = true
        show(StepperSize.COMPACT, key, value, format)

        val ring = ring().getBoundsInRoot()
        val minus = minus().getBoundsInRoot()
        val plus = plus().getBoundsInRoot()
        assertTrue("ukazatel $ring je vpravo od tlačítka −: $minus", ring.left >= minus.right)
        assertTrue("ukazatel $ring je vlevo od tlačítka +: $plus", ring.right <= plus.left)
    }

    @Test
    fun compactRingDoesNotCoverTheButtonsNextToATemperature() =
        assertRingIsBetweenTheButtons(SettingKey.TARGET_DAY, 23.0, ::formatTemp)

    @Test
    fun compactRingDoesNotCoverTheButtonsNextToWideText() =
        assertRingIsBetweenTheButtons(SettingKey.LUX_THRESHOLD, 10000.0, ::formatLux)
}
