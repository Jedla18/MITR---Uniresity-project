package cz.vsb.mitr.domek.ui.light

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.domain.formatPercent
import cz.vsb.mitr.domek.ui.components.mockupText
import kotlin.math.roundToInt

private val PreviewSize = 48.dp
private val ColorTrackHeight = 14.dp
private val ColorThumbSize = 28.dp
private val ColorThumbRing = 3.dp

private const val MAX_HUE = 360f
private const val HUE_STEP = 60f
private const val MAX_SATURATION_PERCENT = 100f

// Zhasnuté světlo Raspberry hlásí jako černou.
private val BLACK = Rgb(0, 0, 0)

// Model Rgb je čistý Kotlin bez Parcelable, takže se při otočení obrazovky ukládá jako tři složky.
private val RgbSaver = listSaver<Rgb, Int>(save = { listOf(it.r, it.g, it.b) }, restore = { Rgb(it[0], it[1], it[2]) })

// Duha stopy odstínu: v každém kroku po 60° je jedna ze šesti základních barev a poslední je zase červená.
private val HueColors = List((MAX_HUE / HUE_STEP).toInt() + 1) { hsvToRgb(it * HUE_STEP, 1f).toColor() }

// Výchozí odstín a sytost podle barvy světla. Jas se v dialogu nevolí, o ten se stará posuvník jasu na obrazovce, takže barva
// z dialogu má vždy plný jas.
private fun Rgb.hueAndSaturation(): Pair<Float, Float> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb(r, g, b), hsv)
    return hsv[0] to hsv[1]
}

// Výběr vlastní barvy (spec 6.3): odstín a sytost s náhledem. „Použít“ vrátí vybranou barvu, „Zrušit“ nic nemění.
@Composable
fun ColorDialog(initial: Rgb, onConfirm: (Rgb) -> Unit, onDismiss: () -> Unit) {
    // Světlo se může změnit samo (automatika), zatímco je dialog otevřený, i s otočením obrazovky. Nedotčený dialog proto drží barvu
    // z chvíle otevření a po obnovení ji dostane zpět, aby „Použít“, náhled a posuvníky ukazovaly totéž.
    val opened = rememberSaveable(saver = RgbSaver) { initial }
    val start = remember { opened.hueAndSaturation() }
    // Vybraná barva přežije otočení obrazovky s otevřeným dialogem.
    var hue by rememberSaveable { mutableFloatStateOf(start.first) }
    var saturation by rememberSaveable { mutableFloatStateOf(start.second) }
    // Odstín a sytost popíšou barvu celou jen při plném jasu. Dokud uživatel na posuvníky nesáhne, platí proto původní barva, jinak
    // by třeba červená předvolba (jas 96 %) po „Použít“ zesvětlala a stala se vlastní. Černá (zhasnuté světlo) žádnou barvu nemá,
    // ta začíná na bílé.
    var touched by rememberSaveable { mutableStateOf(false) }
    val picked = if (touched || opened == BLACK) hsvToRgb(hue, saturation) else opened
    AlertDialog(
        onDismissRequest = onDismiss,
        // Náhled je v řádku s nadpisem, aby se dialog vešel i na výšku telefonu na šířku.
        title = {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                // Při velkém písmu se nadpis zalomí, místo aby stlačil náhled.
                Text("Vlastní barva", Modifier.weight(1f))
                Box(
                    Modifier
                        .size(PreviewSize)
                        .background(picked.toColor(), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        },
        text = {
            // Obsah se při velkém písmu na šířku nemusí vejít, tak se dá posouvat.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorSlider(
                    label = "Odstín",
                    valueText = "${hue.roundToInt()}°",
                    value = hue,
                    onValueChange = {
                        hue = it
                        touched = true
                    },
                    valueRange = 0f..MAX_HUE,
                    track = Brush.horizontalGradient(HueColors),
                    thumbColor = hsvToRgb(hue, 1f).toColor(),
                )
                ColorSlider(
                    label = "Sytost",
                    valueText = formatPercent((saturation * MAX_SATURATION_PERCENT).toDouble()),
                    value = saturation * MAX_SATURATION_PERCENT,
                    onValueChange = {
                        saturation = it / MAX_SATURATION_PERCENT
                        touched = true
                    },
                    valueRange = 0f..MAX_SATURATION_PERCENT,
                    // Od bílé k čisté barvě vybraného odstínu.
                    track = Brush.horizontalGradient(listOf(hsvToRgb(hue, 0f).toColor(), hsvToRgb(hue, 1f).toColor())),
                    thumbColor = picked.toColor(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("Použít") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

// Popisek s hodnotou nad posuvníkem, jehož dráha je barevný pruh a jezdec má barvu, kterou právě dává.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    track: Brush,
    thumbColor: Color,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurface, style = mockupText(15f, FontWeight.Medium))
        Text(text = valueText, style = mockupText(15f))
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            // Čtečka přečte název posuvníku a hodnotu s jednotkou.
            .semantics {
                contentDescription = label
                stateDescription = valueText
            },
        valueRange = valueRange,
        thumb = {
            Box(
                Modifier
                    .size(ColorThumbSize)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(ColorThumbRing)
                    .background(thumbColor, CircleShape),
            )
        },
        // Dráha posuvníku je z obou stran užší o půl jezdce, takže pruh začíná a končí pod středem jezdce na krajní poloze a barva
        // pod jezdcem je ta, kterou jezdec ukazuje.
        track = { Box(Modifier.fillMaxWidth().height(ColorTrackHeight).background(track, CircleShape)) },
    )
}
