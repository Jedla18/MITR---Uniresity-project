package cz.vsb.mitr.domek.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.NO_VALUE
import cz.vsb.mitr.domek.domain.clockDiffText
import cz.vsb.mitr.domek.domain.dayPartTitle
import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.domain.title
import cz.vsb.mitr.domek.ui.components.CardTitle
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.Hint
import cz.vsb.mitr.domek.ui.components.TwoPane
import cz.vsb.mitr.domek.ui.components.icon
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.components.tint
import cz.vsb.mitr.domek.ui.theme.DISABLED_ALPHA
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.ui.theme.staleDim
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.clockDiffSeconds
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.dayStarts
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.piTime
import cz.vsb.mitr.domek.viewmodel.status
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val HINT = "„Teď“ posune začátek na aktuální čas Raspberry. Původní časy pak vrátí tlačítko níž."

// Upozornění na hodiny se v Nastavení ukáže už při rozdílu nad minutu, na Přehledu až nad dvě (spec 11).
private const val CLOCK_WARNING_AFTER_SECONDS = 60L

// Tlačítko „Teď“ je v náhledu (#E2F3F3) světlejší než primaryContainer (#CBEDED). Míchá se s plochou karty ze stejného důvodu
// jako zvýrazněný řádek na Termostatu.
private const val TONAL_STRENGTH = 0.6f

// Čára mezi řádky je v náhledu (#EDF1F3) světlejší než outlineVariant.
private const val DIVIDER_STRENGTH = 0.6f

// Okraj obrysových tlačítek je v náhledu tmavší než čára karet (outline): jako na obrazovce spojení mezi plochou a barvou popisků.
private const val OUTLINE_STRENGTH = 0.5f

// Pozadí upozornění na hodiny je v náhledu (#FFF0D6) světlejší než oranžový kontejner (#FFE7C2).
private const val WARNING_FADE = 0.35f

private val ButtonHeight = 44.dp

// Řádek denní doby má dotykové plochy 48 dp, odsazení 1,5 dp dává výšku řádku 51 dp z náhledu.
private val StartRowPadding = 1.5.dp

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M. yyyy H:mm:ss", Locale.ROOT)

// Nastavení (spec 6.4): denní doba, čas Raspberry a zdroj dat. Je dostupné vždy, i před prvními daty, kdy jsou časy „—“, ovládání
// denní doby a času zašedlé a funguje jen Zdroj dat.
@Composable
fun SettingsScreen(state: HouseUiState, actions: HouseActions, wide: Boolean, modifier: Modifier = Modifier) {
    // Úsek, jehož čas se právě vybírá, přežije otočení telefonu, proto rememberSaveable. Drží ho celá obrazovka, ne karta Denní
    // doba: otočení obvykle překročí 600 dp, takže se karta skládá v jiné větvi TwoPane (jeden sloupec, nebo dva) na jiném místě
    // stromu a její uložený stav by se po otočení nenašel. Obrazovka stojí na stejném místě v obou rozvrženích.
    var editing by rememberSaveable { mutableStateOf<DayPart?>(null) }
    TwoPane(
        wide = wide,
        modifier = modifier,
        left = { DayTimeCard(state, actions, onEdit = { editing = it }) },
        right = {
            ClockCard(state, actions)
            SourceCard(state, actions)
        },
    )
    val part = editing
    val starts = state.dayStarts
    if (part != null && starts != null) {
        TimePickerDialog(
            title = pickerTitle(part),
            initial = starts.startOf(part),
            onConfirm = { time ->
                editing = null
                // Stejný čas by jen zbytečně poslal totéž a „zapnul“ Vrátit původní časy, ačkoli se nic nezměnilo.
                if (time != starts.startOf(part)) actions.setDayStart(part, time)
            },
            onDismiss = { editing = null },
        )
    }
}

// ----- Denní doba -----

@Composable
private fun DayTimeCard(state: HouseUiState, actions: HouseActions, onEdit: (DayPart) -> Unit) {
    val starts = state.dayStarts
    val piTime = state.piTime
    // Stará data: čas Raspberry ze Stavu zešedne, časy začátků z Nastavení ne (spec 6.5).
    val dim = Modifier.staleDim(state.isStale)
    val canEdit = state.controlsEnabled && starts != null
    // „Teď“ bere čas Raspberry z posledního Stavu. Bez čitelného času nebo se starými daty by ViewModel nic neudělal (spec 6.4).
    val canSetNow = state.controlsEnabled && piTime != null && !state.isStale
    DomekCard {
        // Při velkém písmu se „teď je …“ zalomí pod nadpis.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            CardTitle("Denní doba")
            state.status?.let { CurrentPart(it, dim) }
        }
        DayTimeline(starts, piTime?.toLocalTime(), Modifier.padding(top = 2.dp), stale = state.isStale)
        Column(Modifier.padding(top = 4.dp)) {
            DayPart.entries.forEachIndexed { index, part ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_STRENGTH))
                StartRow(
                    part = part,
                    time = starts?.startOf(part),
                    canEdit = canEdit,
                    canSetNow = canSetNow,
                    onEdit = { onEdit(part) },
                    onNow = { actions.setDayStartNow(part) },
                )
            }
        }
        Hint(HINT, Modifier.padding(top = 8.dp))
        ActionButton(
            label = "Vrátit původní časy",
            icon = Icons.AutoMirrored.Rounded.Undo,
            filled = false,
            enabled = state.controlsEnabled && state.savedDayStarts != null,
            onClick = actions::restoreDayStarts,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun pickerTitle(part: DayPart): String =
    when (part) {
        DayPart.DAY -> SettingKey.DAY_START
        DayPart.EVENING -> SettingKey.EVENING_START
        DayPart.NIGHT -> SettingKey.NIGHT_START
    }.label.replaceFirstChar { it.titlecase() }

// Úsek, který podle Raspberry právě platí, s ikonou jako na kartě termostatu na Přehledu. Nový úsek se ukáže, jak přišel,
// bez ikony; Stav bez úseku nic netvrdí.
@Composable
private fun CurrentPart(status: HouseStatus, modifier: Modifier) {
    val title = status.dayPartTitle ?: return
    val part = status.dayPart
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (part != null) Icon(part.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = part.tint())
        Text(
            text = "teď je ${title.lowercase()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = mockupText(13.5f, FontWeight.Medium),
        )
    }
}

@Composable
private fun StartRow(
    part: DayPart,
    time: LocalTime?,
    canEdit: Boolean,
    canSetNow: Boolean,
    onEdit: () -> Unit,
    onNow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = StartRowPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(part.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = part.tint())
        Text(text = "${part.title} od", modifier = Modifier.weight(1f), style = mockupText(15f))
        TimeButton(time, canEdit, onEdit)
        NowButton(canSetNow, onNow)
    }
}

// Čas jako obrysové tlačítko, které otevře výběr času.
@Composable
private fun TimeButton(time: LocalTime?, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ChipButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outline),
        // Odsazení 12 a 5 dp z náhledu plus okraj 1 dp: v CSS okraj velikost zvětšuje, okraj Surface ne.
        padding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(text = time?.let(::formatHhMm) ?: NO_VALUE, style = mockupText(17f, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun NowButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ChipButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = lerp(scheme.surface, scheme.primaryContainer, TONAL_STRENGTH),
        contentColor = scheme.primary,
        border = null,
        padding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text = "Teď", style = mockupText(13.5f, FontWeight.Medium), maxLines = 1)
    }
}

// Malé tlačítko s přesnými rozměry z náhledu. Material tlačítka mají výšku nejméně 40 dp, tohle je vysoké jen podle obsahu,
// ale Surface mu dává dotykovou plochu 48 dp.
@Composable
private fun ChipButton(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    color: Color,
    contentColor: Color,
    border: BorderStroke?,
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fadedUnless(enabled).semantics { role = Role.Button },
        enabled = enabled,
        shape = shape,
        color = color,
        contentColor = contentColor,
        border = border,
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

// ----- Čas Raspberry -----

@Composable
private fun ClockCard(state: HouseUiState, actions: HouseActions) {
    val diff = state.clockDiffSeconds
    // Čas Raspberry i rozdíl jsou ze Stavu, takže se při starých datech zašednou (spec 6.5).
    val dim = Modifier.staleDim(state.isStale)
    DomekCard {
        CardTitle("Čas Raspberry")
        Column(Modifier.padding(top = 6.dp)) {
            ClockRow("Raspberry", state.piTime?.let(::formatClock) ?: NO_VALUE, dim)
            ClockRow("Telefon", formatClock(state.phoneNow), Modifier)
        }
        if (diff != null && abs(diff) > CLOCK_WARNING_AFTER_SECONDS) {
            ClockWarning(clockDiffText(diff), Modifier.padding(top = 8.dp).then(dim))
        }
        ActionButton(
            label = "Nastavit čas podle telefonu",
            icon = Icons.Rounded.Sync,
            filled = true,
            enabled = state.controlsEnabled,
            onClick = actions::syncClock,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// Štítek a hodnota se čtečce obrazovky čtou spolu. Při velkém písmu se hodnota zalomí pod štítek, místo aby se rozdělila.
@Composable
private fun ClockRow(label: String, value: String, valueModifier: Modifier) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = mockupText(12.5f))
        Text(text = value, modifier = valueModifier, style = mockupText(14f))
    }
}

@Composable
private fun ClockWarning(text: String, modifier: Modifier) {
    val colors = LocalDomekColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(lerp(colors.staleContainer, MaterialTheme.colorScheme.surface, WARNING_FADE), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.onStaleContainer)
        Text(text = text, color = colors.onStaleContainer, style = mockupText(13f))
    }
}

private fun formatClock(time: LocalDateTime): String = ClockFormat.format(time)

// ----- Společné (používá je i karta Zdroj dat v SourceCard.kt) -----

// Tlačítko přes celou šířku karty. Zašedlé vypadá jako aktivní, jen průhlednější, jako v náhledu. Výška 44 dp je nejmenší:
// při velkém písmu se popisek zalomí a tlačítko naroste, pevná výška by druhý řádek uřízla.
@Composable
internal fun ActionButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val buttonModifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight).fadedUnless(enabled)
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
    if (filled) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(disabledContainerColor = scheme.primary, disabledContentColor = scheme.onPrimary),
            content = content,
        )
    } else {
        // Popisek v hlavní barvě a okraj z barvy popisků, aby fungoval i v tmavém motivu (jako „Spustit simulaci“).
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary, disabledContentColor = scheme.primary),
            border = BorderStroke(1.dp, scheme.onSurfaceVariant.copy(alpha = OUTLINE_STRENGTH)),
            content = content,
        )
    }
}

private fun Modifier.fadedUnless(enabled: Boolean): Modifier = if (enabled) this else alpha(DISABLED_ALPHA)
