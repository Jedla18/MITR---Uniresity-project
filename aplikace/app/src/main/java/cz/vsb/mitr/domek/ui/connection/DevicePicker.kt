package cz.vsb.mitr.domek.ui.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.domain.NBSP

// Spodní panel, který DomekApp otevírá, kdykoli je v dosahu víc domků „Termostat“ (spec 5.3 a 6.5).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(devices: List<FoundDevice>, onChoose: (String) -> Unit) {
    // Zavřený panel (tah dolů, klepnutí mimo, Zpět) zmizí i s oknem, které jinak zůstane nad aplikací a zachytává dotyky.
    // Výběr tím nic nevybere: hledání dál běží, jen se pod panelem dá znovu ovládat aplikace (třeba „Spustit simulaci“).
    // Panel se otevře znovu při dalším výběru, protože ten vznikne v nové kompozici.
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!dismissed) {
        val rows = rowsInStableOrder(devices)
        ModalBottomSheet(
            onDismissRequest = { dismissed = true },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = "V dosahu je víc domů",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            )
            // Zařízení můžou být desítky (třeba celá učebna), takže se seznam posouvá.
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows, key = { it.address }) { device -> DeviceRow(device, onChoose) }
            }
        }
    }
}

// Řazení podle síly signálu (nejsilnější nahoře, spec 6.5) platí při otevření panelu. Hledání pak běží dál a signál
// kolísá, takže by se řádky při každé aktualizaci prohazovaly pod prstem. Proto se pořadí drží a nové zařízení přibývá
// na konec, stejně jako v BleHouseDataSource.
@Composable
private fun rowsInStableOrder(devices: List<FoundDevice>): List<FoundDevice> {
    val order = remember { devices.sortedByDescending { it.rssi }.mapTo(mutableListOf()) { it.address } }
    devices.forEach { if (it.address !in order) order += it.address }
    val byAddress = devices.associateBy { it.address }
    return order.mapNotNull(byAddress::get)
}

@Composable
private fun DeviceRow(device: FoundDevice, onChoose: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { onChoose(device.address) }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(device.name) }
                append(" · ${device.address} · signál ${device.rssi}${NBSP}dBm")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
