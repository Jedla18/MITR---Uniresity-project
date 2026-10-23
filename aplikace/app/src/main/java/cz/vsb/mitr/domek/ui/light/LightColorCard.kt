package cz.vsb.mitr.domek.ui.light

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatPercent
import cz.vsb.mitr.domek.ui.components.CardTitle
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.Hint
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.theme.DISABLED_ALPHA
import cz.vsb.mitr.domek.viewmodel.ChosenLook
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.storesLightLook
import kotlin.math.roundToInt

// Rozměry z náhledu 3: kolečka barev (.sw) a posuvník jasu (.sl).
private val SwatchSize = 36.dp

// Dotyková plocha kolečka je 48 dp. Kolečko je v ní vidět 36 dp velké a mezera 12 dp mezi řádky z náhledu je jejím okrajem.
private val SwatchCell = 48.dp
private val SwatchGap = 8.dp
private const val SWATCH_COLUMNS = 6
private val SelectionGap = 3.dp
private val SelectionRing = 2.dp
private const val SWATCH_BORDER_ALPHA = 0.12f

private val ThumbSize = 22.dp
private val ActiveTrackHeight = 6.dp
private val InactiveTrackHeight = 4.dp

private const val CUSTOM_COLOR = "vlastní"

// Duha kolečka „vlastní“ z náhledu (conic-gradient).
private val RainbowBrush = Brush.sweepGradient(
    listOf(
        Color(0xFFFF3333),
        Color(0xFFFFDD33),
        Color(0xFF33EE55),
        Color(0xFF33DDEE),
        Color(0xFF4455FF),
        Color(0xFFEE33EE),
        Color(0xFFFF3333),
    ),
)

private const val MIN_BRIGHTNESS = 1

// Nejvyšší jas, který Raspberry hlásí a přijímá; podle něj se počítá i záře kruhu v náhledu.
internal const val MAX_BRIGHTNESS = 100
private val BrightnessRange = MIN_BRIGHTNESS.toFloat()..MAX_BRIGHTNESS.toFloat()

// Vysvětlivka podle toho, co výběr udělá. Raspberry s uloženou barvou a jasem ho jen uloží (spec 3 v
// 2026-10-23-denik-a-vylepseni-design.md), starší program světlo rovnou ručně zapne (spec 6.3).
private const val STORED_LOOK_HINT =
    "Barva a jas se uloží. Svítící světlo se hned přebarví, zhasnuté se jimi rozsvítí po zapnutí nebo v${NBSP}automatice."
private const val COMMAND_HINT = "Výběr barvy nebo jasu světlo rovnou ručně zapne."

// Barva a jas na jedné kartě (spec 6.3): předvolby a „vlastní“ barva, posuvník jasu a vysvětlivka. Ukazují zvolenou barvu a jas,
// takže s uloženou barvou a jasem i u zhasnutého světla.
@Composable
internal fun LightColorCard(state: HouseUiState, actions: HouseActions, look: ChosenLook, onChooseCustom: () -> Unit) {
    val enabled = state.controlsEnabled
    // Označená je předvolba zvolené barvy, jiná barva je „vlastní“. Bez zvolené barvy (zhasnuté světlo staršího programu hlásí
    // černou) není označené nic.
    val preset = look.color?.let(::presetFor)
    val customSelected = look.color != null && preset == null
    val selectedName = if (customSelected) CUSTOM_COLOR else preset?.name
    DomekCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            CardTitle("Barva")
            if (selectedName != null) {
                Text(text = selectedName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = mockupText(12.5f))
            }
        }
        ColorGrid(preset, customSelected, enabled, onPick = actions::setLightColor, onChooseCustom = onChooseCustom)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            CardTitle("Jas")
            Text(text = formatPercent(look.brightness.toDouble()), style = mockupText(15f, FontWeight.Medium))
        }
        BrightnessSlider(look.brightness, enabled, onChange = actions::setLightBrightness)
        // Dokud Nastavení nepřišlo, není jasné, co výběr udělá, takže vysvětlivka chybí.
        if (state.settings != null) Hint(if (state.storesLightLook) STORED_LOOK_HINT else COMMAND_HINT, Modifier)
    }
}

// Šest sloupců po 48 dp: 11 předvoleb a na konci kolečko „vlastní“.
@Composable
private fun ColorGrid(
    selected: LightPreset?,
    customSelected: Boolean,
    enabled: Boolean,
    onPick: (Rgb) -> Unit,
    onChooseCustom: () -> Unit,
) {
    val cells: List<GridCell> = LIGHT_PRESETS.map { GridCell.Preset(it) } + GridCell.Custom
    Column(Modifier.padding(top = 6.dp).selectableGroup()) {
        cells.chunked(SWATCH_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(SwatchGap)) {
                row.forEach { cell ->
                    when (cell) {
                        is GridCell.Preset -> Swatch(
                            description = cell.preset.name,
                            selected = cell.preset == selected,
                            enabled = enabled,
                            onClick = { onPick(cell.preset.rgb) },
                            fill = Modifier.background(cell.preset.rgb.toColor(), CircleShape),
                            modifier = Modifier.weight(1f),
                        )
                        GridCell.Custom -> Swatch(
                            description = CUSTOM_COLOR,
                            selected = customSelected,
                            enabled = enabled,
                            onClick = onChooseCustom,
                            fill = Modifier.drawBehind {
                                // conic-gradient začíná nahoře, sweepGradient vpravo.
                                rotate(-90f) { drawCircle(RainbowBrush) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface GridCell {
    data class Preset(val preset: LightPreset) : GridCell

    data object Custom : GridCell
}

// Kolečko s kroužkem kolem, když je vybrané: mezera barvy karty a pak kroužek v hlavní barvě (box-shadow z náhledu). Políčko pro
// dotek je vysoké 48 dp a Compose rozšíří dotykovou plochu kolečka na jeho šířku. Kroužek se kreslí před ořezem, aby ho ořez
// kolečka neuřízl.
@Composable
private fun Swatch(description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, fill: Modifier, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.height(SwatchCell).alpha(if (enabled) 1f else DISABLED_ALPHA), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(SwatchSize)
                .drawBehind {
                    if (selected) {
                        val radius = size.minDimension / 2
                        drawCircle(scheme.primary, radius + (SelectionGap + SelectionRing).toPx())
                        drawCircle(scheme.surface, radius + SelectionGap.toPx())
                    }
                }
                .clip(CircleShape)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = description }
                .then(fill)
                .border(1.dp, scheme.onSurface.copy(alpha = SWATCH_BORDER_ALPHA), CircleShape),
        )
    }
}

// Jas se posílá při každé změně, ne až po puštění: ViewModel odesílání slučuje, takže LED reaguje živě (spec 6.3 a 7). Hodnota je
// zvolený jas i s rozpracovanou volbou, aby se posuvník při tažení nevracel. Zhasnuté světlo staršího programu hlásí jas 0 %,
// posuvník začíná na 1 %.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrightnessSlider(brightness: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val percent = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    val stateText = formatPercent(percent.toDouble())
    Slider(
        value = percent.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            // Čtečka přečte hodnotu s jednotkou, ne jen číslo.
            .semantics {
                contentDescription = "Jas"
                stateDescription = stateText
            },
        enabled = enabled,
        valueRange = BrightnessRange,
        // Posuvník z náhledu: tenká dráha, výraznější vyplněná část a kulatý jezdec.
        thumb = { Box(Modifier.size(ThumbSize).shadow(2.dp, CircleShape).background(scheme.primary, CircleShape)) },
        // Dráha posuvníku je z obou stran užší o půl jezdce, protože se po ní jezdec pohybuje středem. V náhledu sahá dráha až
        // k okrajům, proto se kreslí o poloměr jezdce za svoje hranice.
        track = { slider ->
            Canvas(Modifier.fillMaxWidth().height(ActiveTrackHeight)) {
                val overhang = ThumbSize.toPx() / 2
                val inactiveHeight = InactiveTrackHeight.toPx()
                val activeHeight = ActiveTrackHeight.toPx()
                drawRoundRect(
                    color = scheme.primaryContainer,
                    topLeft = Offset(-overhang, (size.height - inactiveHeight) / 2),
                    size = Size(size.width + 2 * overhang, inactiveHeight),
                    cornerRadius = CornerRadius(inactiveHeight / 2),
                )
                drawRoundRect(
                    color = scheme.primary,
                    topLeft = Offset(-overhang, 0f),
                    size = Size(overhang + slider.coercedValueAsFraction * size.width, activeHeight),
                    cornerRadius = CornerRadius(activeHeight / 2),
                )
            }
        },
    )
}
