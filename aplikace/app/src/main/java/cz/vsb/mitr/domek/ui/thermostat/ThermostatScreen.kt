package cz.vsb.mitr.domek.ui.thermostat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.TargetSlot
import cz.vsb.mitr.domek.domain.activeTargetSlot
import cz.vsb.mitr.domek.domain.dayPartTitle
import cz.vsb.mitr.domek.domain.formatAgo
import cz.vsb.mitr.domek.domain.formatMinutes
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.domain.thermostatThresholds
import cz.vsb.mitr.domek.domain.thresholdsText
import cz.vsb.mitr.domek.domain.title
import cz.vsb.mitr.domek.ui.components.CardTitle
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.Hint
import cz.vsb.mitr.domek.ui.components.SettingRow
import cz.vsb.mitr.domek.ui.components.SettingStepper
import cz.vsb.mitr.domek.ui.components.ThermostatChip
import cz.vsb.mitr.domek.ui.components.TwoPane
import cz.vsb.mitr.domek.ui.components.activity
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.theme.staleDim
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.number
import cz.vsb.mitr.domek.viewmodel.settingValue
import cz.vsb.mitr.domek.viewmodel.status
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.contentOrNull

// Okraj přepínače je v náhledu tmavší než čára karet (outline), stejně jako u tlačítka „Spustit simulaci“ na obrazovce
// spojení: mezi outline a barvou popisků.
private const val SEGMENT_BORDER_STRENGTH = 0.42f

// Cílové řádky shora dolů (spec 6.2): název, poznámka a cíl, který řádek mění.
private enum class TargetRow(val slot: TargetSlot, val title: String, val note: String) {
    DAY(TargetSlot.DAY, "Denní", "den a${NBSP}večer"),
    NIGHT(TargetSlot.NIGHT, "Noční", "noc"),
    MANUAL(TargetSlot.MANUAL, "Ruční", "režim Ruční"),
}

private val TARGETS_HINT =
    "5–35${NBSP}°C po 0,5${NBSP}°C. Podržením tlačítka se hodnota mění rychleji, klepnutím na číslo jde napsat přímo."

// Termostat (spec 6.2): souhrn, režim, tři cíle a „nikdo doma“. Obrazovku spojení místo něj ukazuje DomekApp, dokud nepřijde
// první Stav, takže bez Stavu tu není co kreslit.
@Composable
fun ThermostatScreen(state: HouseUiState, actions: HouseActions, wide: Boolean, modifier: Modifier = Modifier) {
    val status = state.status ?: return
    val mode = state.shownMode(status)
    // Stará data: hodnoty ze Stavu zešednou, ovládací prvky ne (spec 6.5).
    val dim = Modifier.staleDim(state.isStale)
    TwoPane(
        wide = wide,
        modifier = modifier,
        left = {
            SummaryCard(status, dim)
            ModeCard(state, actions, mode, status)
        },
        right = {
            TargetsCard(state, actions, mode, status.dayPart)
            NobodyHomeCard(state, actions, status, dim)
        },
    )
}

// Režim s rozpracovanou volbou, aby zvýraznění a vysvětlivka následovaly klepnutí hned. Dokud se Nastavení nenačte, platí
// režim ze Stavu.
private fun HouseUiState.shownMode(status: HouseStatus): ThermostatMode {
    val json = settingValue(SettingKey.MODE)?.contentOrNull
    return ThermostatMode.entries.firstOrNull { it.json == json } ?: status.mode
}

@Composable
private fun SummaryCard(status: HouseStatus, modifier: Modifier) {
    DomekCard(modifier) {
        // Při velkém písmu se štítek stavu zalomí pod teploty místo toho, aby je stlačil.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryValue(if (status.insideTemp == null) "Uvnitř · chyba čidla" else "Uvnitř", formatTemp(status.insideTemp))
            SummaryValue("Platný cíl", formatTemp(status.target))
            ThermostatChip(status.activity())
        }
        // Prahy z platného cíle ve Stavu, ne z rozpracované hodnoty. Řádek je uvnitř karty, kterou stará data zešednou celou.
        // Při poruše BME280 termostat stojí (docs/ble-rozhrani.md, Hodnoty v poli chyby), takže prahy neplatí.
        thermostatThresholds(status.target)
            ?.takeIf { METEO_FAULT !in status.errors }
            ?.let { Hint(thresholdsText(it), Modifier.padding(top = 8.dp)) }
    }
}

// Popisek a číslo se čtečce obrazovky čtou jako jedna hodnota.
@Composable
private fun SummaryValue(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = mockupText(12.5f))
        Text(text = value, style = mockupText(22f, FontWeight.Medium))
    }
}

@Composable
private fun ModeCard(state: HouseUiState, actions: HouseActions, mode: ThermostatMode, status: HouseStatus) {
    DomekCard {
        CardTitle("Režim")
        ModeSelector(
            selected = mode,
            enabled = state.controlsEnabled,
            // Znovu zvolený režim by jen poslal totéž a zbytečně točil ukazatelem.
            onSelect = { if (it != mode) actions.setMode(it) },
            modifier = Modifier.padding(top = 10.dp),
        )
        Hint(modeExplanation(mode, status), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ModeSelector(selected: ThermostatMode, enabled: Boolean, onSelect: (ThermostatMode) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Okraje sousedních segmentů se překrývají, takže průhledná barva by byla uprostřed tmavší než na kraji.
    val border = lerp(scheme.outline, scheme.onSurfaceVariant, SEGMENT_BORDER_STRENGTH)
    val colors = SegmentedButtonDefaults.colors(activeBorderColor = border, inactiveBorderColor = border)
    val modes = ThermostatMode.entries
    // Segmenty si nechávají výchozí dotykovou plochu Material 3 (48 dp): přepínač je vidět 40 dp vysoký (náhled má 44 dp)
    // a karta je kvůli tomu o 4 dp vyšší.
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                enabled = enabled,
                colors = colors,
                label = { Text(mode.label) },
            )
        }
    }
}

// Bez úseku dne ve Stavu vysvětlivka neříká, který cíl teď platí.
private fun modeExplanation(mode: ThermostatMode, status: HouseStatus): String = when (mode) {
    ThermostatMode.AUTO -> {
        // Za jednopísmenným „a“ a „v“ je nezlomitelná mezera, aby nezůstaly na konci řádku.
        val rule = "Ve dne a${NBSP}večer drží denní cíl, v${NBSP}noci noční."
        val now = status.dayPartTitle
        if (now == null) {
            rule
        } else {
            val applies = TargetRow.entries.first { it.slot == activeTargetSlot(mode, status.dayPart) }.title.lowercase()
            "$rule Teď je ${now.lowercase()}, takže platí $applies."
        }
    }
    ThermostatMode.MANUAL -> "Drží ruční cíl bez ohledu na denní dobu."
    ThermostatMode.OFF -> "Termostat netopí ani nechladí."
}

@Composable
private fun TargetsCard(state: HouseUiState, actions: HouseActions, mode: ThermostatMode, dayPart: DayPart?) {
    val active = activeTargetSlot(mode, dayPart)
    DomekCard {
        CardTitle("Cílové teploty")
        Column(Modifier.padding(top = 4.dp)) {
            TargetRow.entries.forEach { row ->
                SettingRow(
                    title = row.title,
                    note = row.note,
                    active = row.slot == active,
                    control = { SettingStepper(state, actions, row.slot.key, ::formatTemp) },
                )
            }
        }
        Hint(TARGETS_HINT, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NobodyHomeCard(state: HouseUiState, actions: HouseActions, status: HouseStatus, dim: Modifier) {
    DomekCard {
        CardTitle("Nikdo doma")
        MotionLine(state, status, dim)
        SettingRow(
            title = "Snížit cíl po",
            note = "minutách bez pohybu",
            control = { SettingStepper(state, actions, SettingKey.NOBODY_HOME_AFTER, ::formatMinutes) },
        )
        SettingRow(
            title = "Snížit o",
            // Útlum platí jen v režimu Auto (docs/ble-rozhrani.md, Cíl).
            note = "0–10${NBSP}°C, jen v${NBSP}Auto",
            control = { SettingStepper(state, actions, SettingKey.NOBODY_HOME_SETBACK, ::formatTemp) },
        )
    }
}

// Stav pohybu ze Stavu a z času posledního pohybu. Snížení cíle se bere stejně jako v ovladači pod řádkem, tedy i
// rozpracované, aby oba říkaly totéž.
@Composable
private fun MotionLine(state: HouseUiState, status: HouseStatus, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val setback = state.number(SettingKey.NOBODY_HOME_SETBACK)
    val lastMotionAt = state.lastMotionAt
    val (icon, text) = when {
        // Raspberry snižuje cíl jen v Auto; v ručním režimu platí ruční cíl beze změny a vypnutý termostat cíl nemá.
        status.nobodyHome -> Icons.Rounded.PersonOff to when (status.mode) {
            ThermostatMode.AUTO -> "Nikdo doma · cíl je snížený" + if (setback == null) "" else " o${NBSP}${formatTemp(setback)}"
            ThermostatMode.MANUAL -> "Nikdo doma · v${NBSP}ručním režimu se cíl nesnižuje"
            ThermostatMode.OFF -> "Nikdo doma"
        }
        lastMotionAt != null -> {
            val ago = formatAgo(ChronoUnit.MILLIS.between(lastMotionAt, state.phoneNow))
            Icons.Rounded.Person to "Doma je někdo · poslední pohyb $ago"
        }
        else -> Icons.Rounded.Person to "Pohyb zatím nezaznamenán"
    }
    Row(
        modifier = modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = scheme.primary)
        Text(text = text, style = mockupText(13.5f))
    }
}
