package cz.vsb.mitr.domek.ui.connection

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhonelinkOff
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.SystemActions
import cz.vsb.mitr.domek.ui.components.secondsUntil
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState

// Rozměry a pohyb podle náhledu „Hledání domku“ (.empty, .rings, .prog).
private val DiscSize = 116.dp
private val IconSize = 48.dp
private val ProgressWidth = 180.dp
private val ButtonHeight = 44.dp
private val ButtonSidePadding = 20.dp

// Prstenec začíná 14 dp za okrajem kruhu a při pulzování se zvětšuje z 0,8 na 1,3 násobek.
private val RingRadius = DiscSize / 2 + 14.dp
private val RingStroke = 2.dp
private const val RING_START_SCALE = 0.8f
private const val RING_END_SCALE = 1.3f
private const val PULSE_MS = 2200

// Prstenec na vrcholu pulzu přesahuje kruh o necelých 37 dp. Obsah má nahoře 16 dp odsazení a zbytek dostane pulzující
// kruh navíc, aby prstenec neuřízl posouvací kontejner, když je málo místa (telefon na šířku).
private val ContentPadding = 16.dp
private val RingRoom = 21.dp

// Odstavec je o desetinu užší než sloupec, aby se věty lámaly jako na náhledu („Párování není potřeba.“ na druhém řádku).
private const val TEXT_WIDTH_FRACTION = 0.9f

// ease-out z CSS, stejně jako na náhledu.
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

// Co obrazovka ukazuje. Stav spojení se na to překládá na jednom místě, aby byla vidět celá tabulka stavů (spec 6.5).
private class ConnectionContent(
    val icon: ImageVector,
    val title: String,
    val text: String? = null,
    // Hledání a připojování: ikona pulzuje a pod textem běží ukazatel. Ostatní stavy čekají na uživatele nebo na čas,
    // proto mají červený kruh jako štítek stavu nahoře.
    val busy: Boolean = false,
    val action: ConnectionAction? = null,
    // Telefon bez BLE má jedinou možnost, simulaci, a ta je tam hlavním tlačítkem (action).
    val offersSimulation: Boolean = true,
)

private class ConnectionAction(val label: String, val onClick: () -> Unit, val icon: ImageVector? = null)

private fun simulationAction(actions: HouseActions) =
    ConnectionAction("Spustit simulaci", { actions.selectSource(SourceKind.SIMULATION) }, Icons.Rounded.Science)

private fun connectionContent(state: HouseUiState, actions: HouseActions, system: SystemActions): ConnectionContent =
    when (val connection = state.connection) {
        ConnectionState.Idle, ConnectionState.Scanning, is ConnectionState.ChoosingDevice -> ConnectionContent(
            icon = Icons.AutoMirrored.Rounded.BluetoothSearching,
            title = "Hledám dům „Termostat“",
            text = "Raspberry musí být zapnuté a v dosahu. Párování není potřeba.",
            busy = true,
        )
        is ConnectionState.Connecting -> ConnectionContent(
            icon = Icons.Rounded.Bluetooth,
            title = "Připojuji se k domu…",
            text = "Pokus č. ${connection.attempt}",
            busy = true,
        )
        // Connected a Simulated bez dat trvají jen okamžik před prvním Stavem, proto vypadají jako příprava spojení.
        ConnectionState.Preparing, is ConnectionState.Connected, ConnectionState.Simulated -> ConnectionContent(
            icon = Icons.Rounded.Bluetooth,
            title = "Připojuji se k domu…",
            busy = true,
        )
        is ConnectionState.WaitingToReconnect -> ConnectionContent(
            icon = Icons.Rounded.SyncProblem,
            title = "Spojení se přerušilo",
            text = "Další pokus za ${secondsUntil(connection.retryAtMs, state.nowMs)}${NBSP}s.",
        )
        ConnectionState.DisconnectedByUser -> ConnectionContent(
            icon = Icons.Rounded.LinkOff,
            title = "Odpojeno",
            action = ConnectionAction("Připojit", actions::connect),
        )
        ConnectionState.PermissionsMissing -> permissionsContent(system)
        ConnectionState.BluetoothOff -> ConnectionContent(
            icon = Icons.Rounded.BluetoothDisabled,
            title = "Bluetooth je vypnutý",
            action = ConnectionAction("Zapnout Bluetooth", system.enableBluetooth),
        )
        ConnectionState.LocationOff -> ConnectionContent(
            icon = Icons.Rounded.LocationOff,
            title = "Zapni polohu",
            text = "Android 11 a starší potřebuje zapnutou polohu, aby našel Bluetooth zařízení.",
            action = ConnectionAction("Otevřít nastavení polohy", system.openLocationSettings),
        )
        ConnectionState.NoBluetooth -> ConnectionContent(
            icon = Icons.Rounded.PhonelinkOff,
            title = "Telefon nemá Bluetooth LE",
            text = "Aplikace může běžet jen v simulaci.",
            action = simulationAction(actions),
            offersSimulation = false,
        )
        ConnectionState.MissingService -> ConnectionContent(
            icon = Icons.Rounded.ErrorOutline,
            title = "Tohle zařízení není chytrý dům",
            text = "Zařízení „Termostat“ nemá službu chytrého domu. Zkontroluj program na Raspberry.",
            action = ConnectionAction("Zkusit znovu", actions::retryConnection),
        )
    }

// Po trvalém zamítnutí systém další žádost o oprávnění nezobrazí, takže „Povolit“ by bylo mrtvé klepnutí.
private fun permissionsContent(system: SystemActions): ConnectionContent {
    val denied = system.permissionsPermanentlyDenied
    return ConnectionContent(
        icon = Icons.Rounded.Lock,
        title = "Aplikace potřebuje přístup k Bluetooth",
        // Za jednopísmennou předložkou je nezlomitelná mezera, aby „v“ nezůstalo na konci řádku.
        text = if (denied) "Oprávnění je zamítnuté. Povol ho v${NBSP}nastavení aplikace." else "Bez něj nenajde dům v okolí.",
        action = if (denied) {
            ConnectionAction("Otevřít nastavení", system.openAppSettings)
        } else {
            ConnectionAction("Povolit", system.requestPermissions)
        },
    )
}

// Obrazovka, kterou ukazují Přehled, Termostat a Světlo, dokud nepřijde první Stav (spec 6.0 a 6.5).
@Composable
fun ConnectionScreen(state: HouseUiState, actions: HouseActions, system: SystemActions, modifier: Modifier = Modifier) {
    val content = connectionContent(state, actions, system)
    // Obsah je uprostřed a na šířku, kde je málo místa, se dá posouvat.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 34.dp, vertical = ContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        StatusDisc(content.icon, content.busy)
        Text(
            text = content.title,
            modifier = Modifier.semantics { heading() },
            // Vyvážené lámání, aby u dvouřádkového nadpisu nezůstalo na druhém řádku jediné slovo.
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                lineBreak = LineBreak.Heading,
            ),
            textAlign = TextAlign.Center,
        )
        if (content.text != null) {
            Text(
                text = content.text,
                modifier = Modifier.fillMaxWidth(TEXT_WIDTH_FRACTION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (content.busy) LinearProgressIndicator(Modifier.padding(top = 10.dp).width(ProgressWidth))
        Column(
            modifier = Modifier.padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.action?.let { ConnectionButton(it, filled = true) }
            if (content.offersSimulation) ConnectionButton(simulationAction(actions), filled = false)
        }
    }
}

@Composable
private fun StatusDisc(icon: ImageVector, busy: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val container = if (busy) scheme.primaryContainer else scheme.errorContainer
    val content = if (busy) scheme.onPrimaryContainer else scheme.onErrorContainer
    val roomForRings = if (busy) RingRoom else 0.dp
    Box(Modifier.padding(top = roomForRings, bottom = 14.dp).size(DiscSize), contentAlignment = Alignment.Center) {
        if (busy) PulsingRings(scheme.inversePrimary, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize), tint = content)
        }
    }
}

// Dva prstence se rozšiřují z kruhu a mizí, druhý o půl cyklu za prvním. Hodnoty se čtou až při kreslení, takže pohyb
// obrazovku nepřekomponovává.
@Composable
private fun PulsingRings(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val first = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = EaseOut)),
        label = "firstRing",
    )
    val second = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(PULSE_MS, easing = EaseOut),
            // Druhý prstenec začíná uprostřed cyklu hned, ne až po půl cyklu čekání.
            initialStartOffset = StartOffset(PULSE_MS / 2, StartOffsetType.FastForward),
        ),
        label = "secondRing",
    )
    Canvas(modifier) {
        drawRing(color, first.value)
        drawRing(color, second.value)
    }
}

private fun DrawScope.drawRing(color: Color, progress: Float) {
    val scale = lerp(RING_START_SCALE, RING_END_SCALE, progress)
    drawCircle(
        color = color.copy(alpha = 1f - progress),
        radius = RingRadius.toPx() * scale,
        style = Stroke(width = RingStroke.toPx() * scale),
    )
}

// Výška 44 dp z náhledu je nejmenší: při velkém písmu se popisek zalomí a tlačítko naroste, pevná výška by druhý řádek uřízla.
@Composable
private fun ConnectionButton(action: ConnectionAction, filled: Boolean) {
    val label: @Composable RowScope.() -> Unit = {
        if (action.icon != null) {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(action.label)
    }
    val padding = PaddingValues(horizontal = ButtonSidePadding)
    if (filled) {
        Button(onClick = action.onClick, modifier = Modifier.heightIn(min = ButtonHeight), contentPadding = padding, content = label)
    } else {
        // Náhled má popisek v hlavní barvě a okraj tmavší než čára karet (outline). Material 3 1.4 dává popisku barvu
        // popisků, proto se obě nastavují zde; okraj se odvozuje z barvy popisků, aby fungoval i v tmavém motivu.
        OutlinedButton(
            onClick = action.onClick,
            modifier = Modifier.heightIn(min = ButtonHeight),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            contentPadding = padding,
            content = label,
        )
    }
}
