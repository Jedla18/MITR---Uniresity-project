package cz.vsb.mitr.domek.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.NO_VALUE
import cz.vsb.mitr.domek.ui.components.CardTitle
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState

// Odsazení textu volby zdroje od okraje karty: kruh 24 dp a mezera 8 dp. Pod stejným odsazením je řádek zařízení.
private val SourceTextIndent = 32.dp

// Karta Zdroj dat (spec 6.4, bod 3): volba Bluetooth, nebo simulace, údaje o připojeném domku a tlačítko Odpojit či Připojit.
// Funguje v každém stavu spojení, i před prvními daty a při výběru domku, protože na datech nezávisí.
@Composable
internal fun SourceCard(state: HouseUiState, actions: HouseActions) {
    val bluetooth = state.sourceKind == SourceKind.BLUETOOTH
    val connection = state.connection
    DomekCard {
        CardTitle("Zdroj dat")
        SourceOption(
            selected = bluetooth,
            title = "Raspberry přes Bluetooth",
            note = "dům „Termostat“",
            onSelect = { choose(state, actions, SourceKind.BLUETOOTH) },
        )
        if (bluetooth && connection is ConnectionState.Connected) DeviceLine(connection)
        SourceOption(
            selected = !bluetooth,
            title = "Simulace",
            note = "vymyšlená data, funguje bez Raspberry",
            onSelect = { choose(state, actions, SourceKind.SIMULATION) },
        )
        if (bluetooth) {
            // Po ručním odpojení se aplikace sama nepřipojuje, jinak je spojení vždy co ukončit, i při hledání domku.
            val disconnected = connection == ConnectionState.DisconnectedByUser
            ActionButton(
                label = if (disconnected) "Připojit" else "Odpojit",
                icon = if (disconnected) Icons.Rounded.Bluetooth else Icons.Rounded.BluetoothDisabled,
                filled = false,
                enabled = true,
                onClick = if (disconnected) actions::connect else actions::disconnect,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// Znovu zvolený zdroj by ViewModel jen ignoroval.
private fun choose(state: HouseUiState, actions: HouseActions, kind: SourceKind) {
    if (kind != state.sourceKind) actions.selectSource(kind)
}

// Celý řádek je volba, kruh slouží jen jako ukazatel, takže čtečka obrazovky čte jeden přepínač s oběma texty.
@Composable
private fun SourceOption(selected: Boolean, title: String, note: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(text = title, style = mockupText(15f, FontWeight.Medium))
            Text(
                text = note,
                modifier = Modifier.padding(top = 1.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = mockupText(12f),
            )
        }
    }
}

// Adresa, síla signálu a MTU domku, ke kterému je telefon připojený. Signál se čte až po připojení. Minus je spojovník jako u všech
// čísel v aplikaci (i ve výběru domku).
@Composable
private fun DeviceLine(connection: ConnectionState.Connected) {
    val signal = connection.rssi?.let { "$it${NBSP}dBm" } ?: NO_VALUE
    Text(
        text = "${connection.address} · signál $signal · MTU${NBSP}${connection.mtu}",
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SourceTextIndent, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = mockupText(12.5f),
    )
}
