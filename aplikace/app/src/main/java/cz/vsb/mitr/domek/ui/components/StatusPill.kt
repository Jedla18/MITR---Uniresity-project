package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.statusAgeMs

enum class PillTone { OK, NEUTRAL, CONNECTING, STALE, ERROR, SIMULATION }

data class PillModel(val text: String, val tone: PillTone)

// Pořadí větví je priorita ze spec 6.0: chyby a odpojení přebijí stará data a ta přebijí Připojeno i Simulaci.
fun pillFor(state: HouseUiState): PillModel = when (val connection = state.connection) {
    ConnectionState.NoBluetooth -> PillModel("Bez Bluetooth", PillTone.ERROR)
    ConnectionState.PermissionsMissing -> PillModel("Chybí oprávnění", PillTone.ERROR)
    ConnectionState.BluetoothOff -> PillModel("Bluetooth vypnutý", PillTone.ERROR)
    ConnectionState.LocationOff -> PillModel("Poloha vypnutá", PillTone.ERROR)
    ConnectionState.MissingService -> PillModel("Neznámé zařízení", PillTone.ERROR)
    is ConnectionState.WaitingToReconnect ->
        PillModel("Odpojeno · znovu za ${secondsUntil(connection.retryAtMs, state.nowMs)}${NBSP}s", PillTone.ERROR)
    ConnectionState.DisconnectedByUser -> PillModel("Odpojeno", PillTone.ERROR)
    ConnectionState.Idle, ConnectionState.Scanning -> PillModel("Hledám…", PillTone.NEUTRAL)
    is ConnectionState.ChoosingDevice -> PillModel("Vyber dům", PillTone.NEUTRAL)
    is ConnectionState.Connecting, ConnectionState.Preparing -> PillModel("Připojuji…", PillTone.CONNECTING)
    is ConnectionState.Connected -> staleOr(state, PillModel("Připojeno", PillTone.OK))
    ConnectionState.Simulated -> staleOr(state, PillModel("Simulace", PillTone.SIMULATION))
}

private fun staleOr(state: HouseUiState, fresh: PillModel): PillModel {
    val ageMs = state.statusAgeMs
    return if (state.isStale && ageMs != null) PillModel("Stará data · ${ageMs / 1000}${NBSP}s", PillTone.STALE) else fresh
}

// Nahoru, aby odpočet ukázal 0 s až ve chvíli, kdy má další pokus opravdu začít. Sdílí ho štítek i obrazovka spojení,
// aby obě ukazovaly stejné číslo.
internal fun secondsUntil(atMs: Long, nowMs: Long): Long = ((atMs - nowMs).coerceAtLeast(0) + 999) / 1000

@Composable
fun StatusPill(model: PillModel, onClick: () -> Unit) {
    val (container, content) = pillColors(model.tone)
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 9.dp, top = 5.dp, end = 11.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(content, CircleShape))
            Text(
                text = model.text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, letterSpacing = 0.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun pillColors(tone: PillTone): Pair<Color, Color> {
    val colors = LocalDomekColors.current
    return when (tone) {
        PillTone.OK -> colors.okContainer to colors.onOkContainer
        PillTone.NEUTRAL -> colors.neutralContainer to colors.onNeutralContainer
        PillTone.CONNECTING -> colors.connectingContainer to colors.onConnectingContainer
        PillTone.STALE -> colors.staleContainer to colors.onStaleContainer
        PillTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        PillTone.SIMULATION -> colors.simulationContainer to colors.onSimulationContainer
    }
}
