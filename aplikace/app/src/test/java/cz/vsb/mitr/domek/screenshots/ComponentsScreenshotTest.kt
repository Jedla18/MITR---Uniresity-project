package cz.vsb.mitr.domek.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.clockDiffText
import cz.vsb.mitr.domek.domain.formatLux
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.domain.piErrorDetail
import cz.vsb.mitr.domek.domain.piErrorText
import cz.vsb.mitr.domek.ui.components.Banner
import cz.vsb.mitr.domek.ui.components.BannerTone
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.StatusPill
import cz.vsb.mitr.domek.ui.components.StepperSize
import cz.vsb.mitr.domek.ui.components.TwoPane
import cz.vsb.mitr.domek.ui.components.ValueStepper
import cz.vsb.mitr.domek.ui.components.pillFor
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.ui.theme.staleDim
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComponentsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun components() {
        compose.setContent { DomekTheme(darkTheme = false) { ComponentsGallery() } }
        compose.saveScreenshot("components")
    }

    // Tmavé odstíny náhledy nemají, tak se aspoň prohlédnou vedle světlých.
    @Test
    fun componentsDark() {
        compose.setContent { DomekTheme(darkTheme = true) { ComponentsGallery() } }
        compose.saveScreenshot("components-dark")
    }

    // Dva sloupce jako na náhledu „Na šířku“; levý je delší než obrazovka, takže je vidět, že se posouvá zvlášť.
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun twoPaneWide() {
        compose.setContent {
            DomekTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    TwoPane(
                        wide = true,
                        left = { repeat(4) { SampleCard("Levý sloupec ${it + 1}") } },
                        right = { SampleCard("Pravý sloupec") },
                    )
                }
            }
        }
        compose.saveScreenshot("two-pane-wide")
    }
}

@Composable
private fun ComponentsGallery() {
    val demo = demoState()
    // Štítky ze skutečných stavů, ve stejném pořadí jako na náhledu „Stav připojení v horní liště“.
    val pills = listOf(
        demo,
        HouseUiState(connection = ConnectionState.Scanning),
        HouseUiState(connection = ConnectionState.Connecting(attempt = 1)),
        demo.copy(nowMs = 307_000),
        demo.copy(connection = ConnectionState.WaitingToReconnect(attempt = 2, retryAtMs = 304_000)),
        demo.copy(connection = ConnectionState.Simulated),
    ).map(::pillFor)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pills.forEach { StatusPill(it, onClick = {}) }
        }
        DomekCard(container = LocalDomekColors.current.heatContainer) {
            ValueStepper(
                23.5, SettingKey.TARGET_DAY, ::formatTemp, {},
                enabled = true, pending = true, size = StepperSize.LARGE, label = "Denní cíl",
            )
        }
        DomekCard {
            StepperRow("Noční cíl") {
                ValueStepper(20.0, SettingKey.TARGET_NIGHT, ::formatTemp, {}, enabled = true, pending = false)
            }
            StepperRow("Snížit cíl po") {
                ValueStepper(30.0, SettingKey.NOBODY_HOME_AFTER, { "${it.toInt()}${NBSP}min" }, {}, enabled = true, pending = true)
            }
            StepperRow("Práh šera (zakázaný)") {
                ValueStepper(50.0, SettingKey.LUX_THRESHOLD, ::formatLux, {}, enabled = false, pending = false)
            }
        }
        Banner(BannerTone.STALE, "Data jsou 7${NBSP}s stará", "Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.")
        Banner(BannerTone.ERROR, piErrorText("osvetleni"), piErrorDetail("osvetleni"))
        Banner(BannerTone.WARNING, clockDiffText(-132), "Na hodinách závisí denní doba.", actionLabel = "Srovnat")
        // Vlevo čerstvá data, vpravo tatáž karta se staleDim.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DimSample(stale = false, modifier = Modifier.weight(1f))
            DimSample(stale = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepperRow(name: String, stepper: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        stepper()
    }
}

@Composable
private fun DimSample(stale: Boolean, modifier: Modifier) {
    val colors = LocalDomekColors.current
    DomekCard(modifier = modifier.staleDim(stale), container = colors.heatContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTemp(22.4), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(
                text = "Topí",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(colors.heat, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SampleCard(title: String) {
    DomekCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Karta přes celou šířku sloupce.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Druhý řádek, aby karta měla výšku.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
