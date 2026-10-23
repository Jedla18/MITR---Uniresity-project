package cz.vsb.mitr.domek.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.status

// Chyba čidla, kterou simulace přepíná: Stav ji hlásí v seznamu chyb (spec 5.4).

// Karta jen v simulaci (spec 5.4): tlačítka nahrazují zásahy do skutečného domku. Porucha čidla se přepíná, proto je to
// přepínací štítek, který je vybraný, dokud Stav chybu hlásí.
@Composable
fun SimulationCard(state: HouseUiState, actions: HouseActions, modifier: Modifier = Modifier) {
    val sensorFault = state.status?.errors?.contains(METEO_FAULT) == true
    DomekCard(modifier) {
        Text(
            text = "Ovládání simulace",
            modifier = Modifier.semantics { heading() },
            style = mockupText(15f, FontWeight.Medium),
        )
        Text(
            text = "Nahrazuje zásahy do skutečného domu.",
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = mockupText(12.5f),
        )
        // Štítky mají dotykovou plochu 48 dp při výšce 32 dp, takže mezi řadami je mezera i bez rozestupu.
        FlowRow(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationAction.entries.forEach { action ->
                if (action == SimulationAction.TOGGLE_SENSOR_FAULT) {
                    FilterChip(
                        selected = sensorFault,
                        onClick = { actions.simulation(action) },
                        label = { Text(action.label) },
                        // Popisek má barvu textu jako u ostatních štítků; Material 3 dává přepínacímu šedý.
                        colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                        leadingIcon = if (sensorFault) {
                            {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                } else {
                    AssistChip(onClick = { actions.simulation(action) }, label = { Text(action.label) })
                }
            }
        }
    }
}
