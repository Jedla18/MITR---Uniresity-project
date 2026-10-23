package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.number

// Součásti karet s Nastavením, které mají Termostat a Světlo společné: nadpis karty, poznámka pod ovladači, řádek s ovladačem
// a krokový ovladač hodnoty z Nastavení.

// Zaoblení řádku s ovladačem z náhledu (.vr). Řádek, který se dá klepnout celý (přepínač), si podle něj ořízne vlnu.
internal val SettingRowShape = RoundedCornerShape(16.dp)

// Poznámky pod ovladači mají v náhledu (.hint) řádkování 1,4, jinde 1,35.
internal const val HINT_LINE_HEIGHT = 1.4f

// Řádek, který právě platí, je v náhledu (#E2F3F3) světlejší než primaryContainer (#CBEDED); takhle sytý by v bílé kartě
// převládl nad ovladači. Barva se proto vezme z primaryContainer jen zčásti, aby odstín fungoval i v tmavém motivu.
private const val ACTIVE_ROW_STRENGTH = 0.6f

@Composable
internal fun CardTitle(text: String) {
    Text(text = text, modifier = Modifier.semantics { heading() }, style = mockupText(15f, FontWeight.Medium))
}

@Composable
internal fun Hint(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = mockupText(12.5f).copy(lineHeight = (12.5f * HINT_LINE_HEIGHT).sp),
    )
}

// Řádek s názvem a poznámkou vlevo a ovladačem vpravo (.vr). Řádek, který právě platí (active), je zvýrazněný a má štítek.
// Poznámka může chybět. `modifier` je před okraji řádku, takže se přes něj dá klepnout na celý řádek; `noteModifier` patří jen
// poznámce (třeba zešednutí hodnoty ze Stavu).
@Composable
internal fun SettingRow(
    title: String,
    note: String?,
    control: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    noteModifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (active) scheme.primaryContainer.copy(alpha = ACTIVE_ROW_STRENGTH) else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tint, SettingRowShape)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = mockupText(15f, FontWeight.Medium))
            if (note != null || active) {
                // Štítek se při velkém písmu zalomí pod poznámku, místo aby ji stlačil.
                FlowRow(
                    modifier = Modifier.padding(top = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (note != null) Text(text = note, modifier = noteModifier, color = scheme.onSurfaceVariant, style = mockupText(12f))
                    if (active) NowBadge()
                }
            }
        }
        control()
    }
}

@Composable
private fun NowBadge() {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = "platí teď",
        modifier = Modifier
            .background(scheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        color = scheme.onPrimary,
        style = mockupText(11f, FontWeight.Medium),
    )
}

// Hodnota je rozpracovaná, nebo z Nastavení. Bez Nastavení se ukáže „—“ a tlačítka jsou zašedlá.
@Composable
internal fun SettingStepper(state: HouseUiState, actions: HouseActions, key: SettingKey, format: (Double?) -> String) {
    val shown = state.number(key)
    ValueStepper(
        value = shown ?: 0.0,
        key = key,
        format = { format(if (shown == null) null else it) },
        onChange = { actions.changeSetting(key, it) },
        enabled = state.controlsEnabled && shown != null,
        pending = key in state.pendingKeys,
        size = StepperSize.COMPACT,
    )
}
