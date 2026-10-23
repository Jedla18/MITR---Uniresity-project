package cz.vsb.mitr.domek.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AppScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Telefon na výšku: dole čtyři záložky, vybraný Přehled, nahoře zelený štítek Připojeno.
    @Test
    fun appPortrait() {
        compose.setContent { DomekTheme(darkTheme = false) { DomekApp(demoState(), NoActions, NoSystem, emptyFlow()) } }
        compose.saveScreenshot("app-prehled")
    }

    // Na šířku je boční lišta místo dolní a záhlaví je vedle ní.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun appLandscape() {
        compose.setContent { DomekTheme(darkTheme = false) { DomekApp(demoState(), NoActions, NoSystem, emptyFlow()) } }
        compose.saveScreenshot("app-sirka")
    }
}
