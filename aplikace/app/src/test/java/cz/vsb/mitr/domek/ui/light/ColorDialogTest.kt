package cz.vsb.mitr.domek.ui.light

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.screenshots.PHONE_TALL
import cz.vsb.mitr.domek.screenshots.renderScreenshot
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Dialog s vlastní barvou: odstín a sytost posuvníky, náhled a tlačítka. Dialog je v jiném okně než obsah testu, ale test Compose
// ho vidí stejně jako zbytek.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_TALL)
class ColorDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val confirmed = mutableListOf<Rgb>()
    private var dismissed = 0

    // Barva světla, kterou dialog dostává. Dá se změnit i za běhu dialogu, jako když světlo změní automatika.
    private var lightColor by mutableStateOf(Rgb(0, 0, 0))

    // Snímek obrazovky určuje velikost okna aplikace, takže pod dialogem musí být něco, co zabírá místo.
    private val dialogOnPage: @Composable () -> Unit = {
        DomekTheme(darkTheme = false) {
            Box(Modifier.fillMaxSize().background(Color.White))
            ColorDialog(lightColor, onConfirm = { confirmed += it }, onDismiss = { dismissed++ })
        }
    }

    private fun show(initial: Rgb) {
        lightColor = initial
        compose.setContent(dialogOnPage)
    }

    private fun hue() = compose.onNode(hasContentDescription("Odstín"))

    private fun saturation() = compose.onNode(hasContentDescription("Sytost"))

    private fun SemanticsNodeInteraction.dragTo(value: Float) {
        performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun SemanticsNodeInteraction.stateText(): String = fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    // Kolik bodů snímku má přesně danou barvu. Náhled je kruh 48 dp, takže jich má na snímku desetitisíce.
    private fun pixelsOf(rgb: Rgb): Int {
        val image = compose.renderScreenshot("dialog")
        val argb = (0xFF shl 24) or (rgb.r shl 16) or (rgb.g shl 8) or rgb.b
        return (0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) == argb } }
    }

    @Test
    fun hasATitleSlidersAndTheTwoButtons() {
        show(Rgb(100, 110, 255))

        compose.onNodeWithText("Vlastní barva").assertIsDisplayed()
        compose.onNodeWithText("Odstín").assertIsDisplayed()
        compose.onNodeWithText("Sytost").assertIsDisplayed()
        compose.onNodeWithText("Použít").assertIsDisplayed()
        compose.onNodeWithText("Zrušit").assertIsDisplayed()
        hue().assertIsDisplayed()
        saturation().assertIsDisplayed()
    }

    // Výchozí odstín a sytost jsou ty z barvy světla (android.graphics.Color.colorToHSV).
    @Test
    fun startsOnTheHueAndSaturationOfTheInitialColor() {
        show(Rgb(100, 110, 255))

        assertEquals("236°", hue().stateText())
        assertEquals("61${NBSP}%", saturation().stateText())
        compose.onNodeWithText("236°").assertIsDisplayed()
        compose.onNodeWithText("61${NBSP}%").assertIsDisplayed()
    }

    @Test
    fun confirmingWithoutTouchingTheSlidersGivesTheInitialColorBack() {
        show(Rgb(100, 110, 255))

        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(100, 110, 255)), confirmed)
        assertEquals(0, dismissed)
    }

    // Červená (244, 67, 54) nemá plný jas, takže ji odstín a sytost samy nepopíšou. Nedotčený dialog ji vrátí beze změny a ukazuje
    // ji i v náhledu; jinak by z předvolby po „Použít“ byla o kousek světlejší vlastní barva.
    @Test
    fun anUntouchedDialogKeepsAPresetThatIsNotFullyBright() {
        val red = Rgb(244, 67, 54)
        show(red)
        assertTrue("náhled je v původní barvě", pixelsOf(red) > 10_000)

        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(red), confirmed)
    }

    // Světlo se může změnit samo (automatika), zatímco je dialog otevřený. Nedotčený dialog drží barvu z chvíle otevření, takže
    // „Použít“ vrátí tu, kterou ukazuje náhled.
    @Test
    fun anUntouchedDialogKeepsTheColorItWasOpenedWithWhenTheLightChangesMeanwhile() {
        val opened = Rgb(100, 110, 255)
        val changed = Rgb(244, 67, 54)
        show(opened)

        lightColor = changed
        compose.waitForIdle()

        assertTrue("náhled zůstal v barvě z otevření", pixelsOf(opened) > 10_000)
        assertEquals("nová barva světla v náhledu není", 0, pixelsOf(changed))
        compose.onNodeWithText("Použít").performClick()
        assertEquals(listOf(opened), confirmed)
    }

    // Zhasnuté světlo (černá) nemá odstín ani sytost, takže dialog začne na bílé. Když se světlo mezitím rozsvítí, zůstane na bílé.
    @Test
    fun aDialogOpenedOnABlackLightStaysOnWhiteWhenTheLightComesOnMeanwhile() {
        show(Rgb(0, 0, 0))

        lightColor = Rgb(100, 110, 255)
        compose.waitForIdle()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(255, 255, 255)), confirmed)
    }

    // Jakmile uživatel posuvníkem pohne, platí barva z odstínu a sytosti, tedy s plným jasem.
    @Test
    fun movingASliderPicksAFullyBrightColorEvenFromAPresetThatIsNot() {
        show(Rgb(244, 67, 54))

        hue().dragTo(120f)
        compose.onNodeWithText("Použít").performClick()

        // Sytost zůstala z červené (1 − 54/244), odstín je zelený.
        assertEquals(listOf(Rgb(56, 255, 56)), confirmed)
    }

    @Test
    fun theSlidersChooseTheHueAndTheSaturation() {
        show(Rgb(100, 110, 255))

        hue().dragTo(120f)
        compose.onNodeWithText("Použít").performClick()
        // Sytost zůstala z původní barvy (155 z 255), jen odstín je zelený.
        assertEquals(listOf(Rgb(100, 255, 100)), confirmed)
    }

    @Test
    fun fullSaturationIsAPureColorAndNoSaturationIsWhite() {
        show(Rgb(100, 110, 255))

        hue().dragTo(0f)
        saturation().dragTo(100f)
        compose.onNodeWithText("Použít").performClick()
        saturation().dragTo(0f)
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(255, 0, 0), Rgb(255, 255, 255)), confirmed)
    }

    @Test
    fun theSlidersCoverTheWholeRange() {
        show(Rgb(100, 110, 255))

        hue().dragTo(360f)
        assertEquals("360°", hue().stateText())
        saturation().dragTo(100f)
        assertEquals("100${NBSP}%", saturation().stateText())
        hue().dragTo(0f)
        assertEquals("0°", hue().stateText())
    }

    // Světlo, které je zhasnuté (černá), nemá odstín ani sytost, dialog začne na bílé.
    @Test
    fun aBlackLightStartsOnWhite() {
        show(Rgb(0, 0, 0))

        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(255, 255, 255)), confirmed)
    }

    // Náhled je velký kruh v barvě, kterou posuvníky právě dávají.
    @Test
    fun thePreviewShowsTheColorThatIsBeingPicked() {
        show(Rgb(100, 110, 255))
        assertTrue("náhled je v původní barvě", pixelsOf(Rgb(100, 110, 255)) > 10_000)
        assertEquals("zelenou ještě nemá", 0, pixelsOf(Rgb(100, 255, 100)))

        hue().dragTo(120f)
        compose.waitForIdle()

        assertTrue("náhled je zelený", pixelsOf(Rgb(100, 255, 100)) > 10_000)
        assertEquals("modrá z náhledu zmizela", 0, pixelsOf(Rgb(100, 110, 255)))
    }

    @Test
    fun cancellingOnlyDismisses() {
        show(Rgb(100, 110, 255))
        hue().dragTo(10f)

        compose.onNodeWithText("Zrušit").performClick()

        assertEquals(emptyList<Rgb>(), confirmed)
        assertEquals(1, dismissed)
    }

    // Otočení obrazovky s otevřeným dialogem nesmí zahodit, co už uživatel vybral.
    @Test
    fun thePickedColorSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DomekTheme(darkTheme = false) { ColorDialog(Rgb(100, 110, 255), onConfirm = { confirmed += it }, onDismiss = {}) }
        }
        hue().dragTo(120f)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(100, 255, 100)), confirmed)
    }

    // Světlo se změní samo a obrazovka se otočí dřív, než uživatel na dialog sáhne. Obnovený dialog drží barvu z chvíle otevření,
    // takže se posuvníky, náhled i „Použít“ shodují.
    @Test
    fun anUntouchedDialogKeepsTheColorItWasOpenedWithWhenTheLightChangesAndTheScreenIsRecreated() {
        val opened = Rgb(100, 110, 255)
        val changed = Rgb(244, 67, 54)
        lightColor = opened
        val restoration = StateRestorationTester(compose)
        restoration.setContent(dialogOnPage)
        lightColor = changed
        compose.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(opened), confirmed)
        // Posuvníky i náhled ukazují tutéž barvu, kterou „Použít“ vrátilo (dialog se po „Použít“ v testu nezavírá).
        assertEquals("236°", hue().stateText())
        assertEquals("61${NBSP}%", saturation().stateText())
        assertTrue("náhled je v barvě z otevření", pixelsOf(opened) > 10_000)
        assertEquals("nová barva světla v náhledu není", 0, pixelsOf(changed))
    }

    // Totéž pro zhasnuté světlo (černá), které se mezitím rozsvítí: dialog začal na bílé a po obnovení na ní zůstane.
    @Test
    fun aDialogOpenedOnABlackLightStaysOnWhiteWhenTheLightComesOnAndTheScreenIsRecreated() {
        lightColor = Rgb(0, 0, 0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent(dialogOnPage)
        lightColor = Rgb(100, 110, 255)
        compose.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Použít").performClick()

        assertEquals(listOf(Rgb(255, 255, 255)), confirmed)
    }
}
