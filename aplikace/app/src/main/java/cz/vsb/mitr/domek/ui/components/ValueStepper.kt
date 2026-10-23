package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.stepValue
import cz.vsb.mitr.domek.ui.theme.DISABLED_ALPHA

enum class StepperSize { LARGE, COMPACT }

private const val HOLD_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 100L
private val PENDING_SIZE = 12.dp
private val PENDING_GAP = 6.dp

// Poloha ukazatele malého ovladače vůči pravému hornímu rohu čísla. Vodorovně končí 4 dp před tlačítkem + (mezera mezi číslem
// a tlačítkem je 6 dp), svisle je nad horním okrajem číslic, takže nezasáhne ani široký text.
private val PENDING_CORNER_X = 2.dp
private val PENDING_CORNER_Y = (-8).dp

// LARGE je krokové tlačítko karty termostatu na Přehledu (bílý pruh přes celou šířku), COMPACT patří do řádků
// s popiskem vlevo (náhledy Termostat a Světlo).
@Composable
fun ValueStepper(
    value: Double,
    key: SettingKey,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
    enabled: Boolean,
    pending: Boolean,
    size: StepperSize = StepperSize.COMPACT,
    label: String? = null,
) {
    val large = size == StepperSize.LARGE
    var typing by remember { mutableStateOf(false) }
    val buttonSize = if (large) 48.dp else 38.dp
    val iconSize = if (large) 24.dp else 20.dp

    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (large) {
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                        .padding(8.dp)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = if (large) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(up = false, buttonSize, iconSize, enabled) { onChange(stepValue(key, value, up = false)) }
        StepperNumber(format(value), label, large, pending, enabled, onClick = { typing = true })
        StepButton(up = true, buttonSize, iconSize, enabled) { onChange(stepValue(key, value, up = true)) }
    }

    if (typing) {
        TypedValueDialog(
            value = value,
            key = key,
            title = label ?: key.label.replaceFirstChar { it.titlecase() },
            onSave = {
                typing = false
                onChange(it)
            },
            onDismiss = { typing = false },
        )
    }
}

@Composable
private fun StepButton(up: Boolean, diameter: Dp, iconSize: Dp, enabled: Boolean, onStep: () -> Unit) {
    val currentOnStep by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .size(diameter)
            .repeatWhileHeld(enabled) { currentOnStep() }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button) { currentOnStep() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (up) Icons.Rounded.Add else Icons.Rounded.Remove,
            contentDescription = if (up) "Zvýšit" else "Snížit",
            modifier = Modifier.size(iconSize),
        )
    }
}

// Podržení opakuje krok: poprvé po 400 ms, pak každých 100 ms. Obyčejné klepnutí obslouží clickable. Puštění po
// opakování se spotřebuje dřív, než ho clickable uvidí, takže nepřidá ještě jeden krok.
private fun Modifier.repeatWhileHeld(enabled: Boolean, onRepeat: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var repeated = false
                var waitMs = HOLD_DELAY_MS
                while (true) {
                    val ended = withTimeoutOrNull(waitMs) {
                        val up = waitForUpOrCancellation(PointerEventPass.Initial)
                        if (up != null && repeated) up.consume()
                    }
                    if (ended != null) break
                    repeated = true
                    onRepeat()
                    waitMs = REPEAT_EVERY_MS
                }
            }
        }
    }

// Číslo se při odesílání nesmí pohnout, ani tlačítka kolem něj. Velká verze proto drží místo pro ukazatel pořád a zrcadlově
// i vlevo, takže číslo zůstane uprostřed. Malá by se tím oproti náhledu rozšířila (číslo má šířku nejméně 68 dp a text
// s ukazatelem by se do ní nevešel), proto se její ukazatel jen přikreslí k hornímu rohu čísla a žádné místo nezabírá.
// Kreslí se vedle sloupce s číslem, ne v něm, protože ten ořezává ripple a okraj čísla by ukazatel uřízl.
@Composable
private fun StepperNumber(text: String, label: String?, large: Boolean, pending: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClickLabel = "Zadat číslo", role = Role.Button, onClick = onClick)
                .then(if (large) Modifier.padding(horizontal = 12.dp, vertical = 2.dp) else Modifier.widthIn(min = 68.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (large) Spacer(Modifier.width(PENDING_GAP + PENDING_SIZE))
                Text(
                    text = text,
                    style = if (large) {
                        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium)
                    } else {
                        MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)
                    },
                    maxLines = 1,
                )
                if (large) {
                    Box(Modifier.padding(start = PENDING_GAP).size(PENDING_SIZE)) {
                        if (pending) PendingRing(Modifier.matchParentSize())
                    }
                }
            }
        }
        if (pending && !large) {
            PendingRing(Modifier.align(Alignment.TopEnd).offset(PENDING_CORNER_X, PENDING_CORNER_Y).size(PENDING_SIZE))
        }
    }
}

@Composable
private fun PendingRing(modifier: Modifier) {
    CircularProgressIndicator(
        modifier = modifier,
        strokeWidth = 2.dp,
        trackColor = MaterialTheme.colorScheme.primaryContainer,
    )
}

@Composable
private fun TypedValueDialog(value: Double, key: SettingKey, title: String, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    // Předvyplněné číslo je označené, takže ho první napsaná číslice přepíše.
    var field by remember {
        val text = editText(value)
        mutableStateOf(TextFieldValue(text, selection = TextRange(0, text.length)))
    }
    val typed = typedValue(field.text, key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focusRequester = remember { FocusRequester() }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.focusRequester(focusRequester),
                singleLine = true,
                suffix = { Text(key.unit) },
                supportingText = { Text("Povoleno je ${rangeText(key)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { typed?.let(onSave) }),
            )
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { typed?.let(onSave) }, enabled = typed != null) { Text("Uložit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}

// Přijme desetinnou čárku i tečku. Číslo mimo rozsah se ořízne, protože rozsah hlídá aplikace sama (spec 7).
private fun typedValue(text: String, key: SettingKey): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(key.min, key.max)

// Bez zbytečné nuly za čárkou („23“, ale „23,5“); hodnoty Nastavení mají nejvýš jedno desetinné místo.
private fun editText(value: Double): String = formatDecimal(value, if (value % 1.0 == 0.0) 0 else 1)

private fun rangeText(key: SettingKey): String = "${formatDecimal(key.min, 0)}–${formatDecimal(key.max, 0)}$NBSP${key.unit}"
