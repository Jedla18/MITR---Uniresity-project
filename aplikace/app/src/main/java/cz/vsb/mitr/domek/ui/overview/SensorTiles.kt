package cz.vsb.mitr.domek.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.DUSK_END_FACTOR
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatAgo
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.formatLux
import cz.vsb.mitr.domek.domain.formatPercent
import cz.vsb.mitr.domek.domain.formatPressure
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.domain.lightTexts
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.components.widestTextWidth
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.number
import cz.vsb.mitr.domek.viewmodel.shownLight
import cz.vsb.mitr.domek.viewmodel.status
import java.time.temporal.ChronoUnit

private val TileShape = RoundedCornerShape(16.dp)

private val TileGap = 8.dp
private val IconSlot = 20.dp

// Hodnota null je chyba čidla, u osvětlení čidlo chybí (spec 6.0). Vlhkost null bez poruchy `meteo` posílá BMP280, které
// vlhkost neměří, a to porucha není (docs/ble-rozhrani.md, Stav).
private const val SENSOR_ERROR = "chyba čidla"
private const val SENSOR_MISSING = "čidlo chybí"
private const val NOT_MEASURED = "neměří se"

// Odsazení a velikost hodnoty dlaždice: z náhledu (.tl) a menší z náhledu „Na šířku“ (.cmp .tl).
private class TileMetrics(val sides: Dp, val top: Dp, val bottom: Dp, val valueSize: Float)

private val RegularTile = TileMetrics(sides = 10.dp, top = 10.dp, bottom = 9.dp, valueSize = 18f)
private val CompactTile = TileMetrics(sides = 9.dp, top = 8.dp, bottom = 7.dp, valueSize = 16f)

private const val MAX_COLUMNS = 3

// Nejširší hodnoty, které dlaždice ukazují. Počet sloupců se vybírá podle nich, ne podle právě zobrazených hodnot, aby se mřížka
// s každým Stavem nepřeskupovala.
private val WIDEST_VALUES = listOf("1013${NBSP}hPa", "65535${NBSP}lx", "-20,5${NBSP}°C", "Zhasnuto")

// Rezerva na zaokrouhlení: hodnota široká přesně jako dlaždice by se kvůli půlpixelu mohla zkrátit.
private val VALUE_SLACK = 2.dp

// Šest dlaždic nejvýš ve třech sloupcích (spec 6.1, bod 5). Řady se natahují na výšku nejvyšší dlaždice jako mřížka v náhledu.
// Sloupců je tolik, kolik se jich vejde, aniž by se zkrátila nejširší hodnota („1013 hPa“): levý sloupec na tabletu na výšku je
// užší než telefon a velké písmo hodnotu rozšíří, tak se dlaždice řadí po dvou, nebo po jedné.
@Composable
fun SensorTiles(state: HouseUiState, onOpenLight: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val status = state.status ?: return
    val metrics = if (compact) CompactTile else RegularTile
    val tiles: List<@Composable RowScope.() -> Unit> = listOf(
        {
            SensorTile(
                metrics = metrics,
                icon = { TileIcon(Icons.Rounded.DeviceThermostat) },
                label = "Venku",
                value = formatTemp(status.outsideTemp),
                subtitle = SENSOR_ERROR.takeIf { status.outsideTemp == null },
            )
        },
        {
            SensorTile(
                metrics = metrics,
                icon = { TileIcon(Icons.Rounded.WaterDrop) },
                label = "Vlhkost",
                value = formatPercent(status.humidity),
                subtitle = when {
                    status.humidity != null -> null
                    METEO_FAULT in status.errors -> SENSOR_ERROR
                    else -> NOT_MEASURED
                },
            )
        },
        {
            SensorTile(
                metrics = metrics,
                icon = { TileIcon(Icons.Rounded.Speed) },
                label = "Tlak",
                value = formatPressure(status.pressure),
                subtitle = SENSOR_ERROR.takeIf { status.pressure == null },
            )
        },
        {
            SensorTile(
                metrics = metrics,
                icon = { TileIcon(Icons.Rounded.Brightness6) },
                label = "Osvětlení",
                value = formatLux(status.lux),
                subtitle = lightLevelNote(status.lux, state.number(SettingKey.LUX_THRESHOLD)),
            )
        },
        {
            SensorTile(
                metrics = metrics,
                icon = { TileIcon(Icons.AutoMirrored.Rounded.DirectionsWalk) },
                label = "Pohyb",
                value = if (status.motion) "Ano" else "Ne",
                subtitle = motionNote(state),
                highlighted = status.motion,
            )
        },
        { LightTile(state, onOpenLight, metrics) },
    )
    BoxWithConstraints(modifier) {
        val columns = tileColumns(maxWidth, metrics)
        Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
            tiles.chunked(columns).forEach { row ->
                TileRow { row.forEach { tile -> tile() } }
            }
        }
    }
}

@Composable
private fun tileColumns(available: Dp, metrics: TileMetrics): Int {
    val valueStyle = mockupText(metrics.valueSize, FontWeight.Medium)
    val tileWidth = widestTextWidth(WIDEST_VALUES, valueStyle) + metrics.sides * 2 + VALUE_SLACK
    return (MAX_COLUMNS downTo 2).firstOrNull { (available - TileGap * (it - 1)) / it >= tileWidth } ?: 1
}

// Šero začne pod prahem a skončí až nad DUSK_END_FACTOR násobkem prahu. Mezi tím záleží na předchozím stavu, který Raspberry
// neposílá, proto se tam ukáže jen práh. Bez Nastavení práh neznáme, tak se ukáže jen hodnota.

private fun lightLevelNote(lux: Double?, threshold: Double?): String? {
    if (lux == null) return SENSOR_MISSING
    if (threshold == null) return null
    val limit = "práh ${formatDecimal(threshold, 0)}"
    return when {
        lux < threshold -> "šero · $limit"
        lux > DUSK_END_FACTOR * threshold -> "světlo · $limit"
        else -> limit
    }
}

private fun motionNote(state: HouseUiState): String =
    state.lastMotionAt?.let { formatAgo(ChronoUnit.MILLIS.between(it, state.phoneNow)) } ?: "zatím ne"

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        content = content,
    )
}

// Dlaždice světla ukazuje stav i s rozpracovanou barvou a jasem a klepnutí otevře záložku Světlo.
@Composable
private fun RowScope.LightTile(state: HouseUiState, onOpenLight: () -> Unit, metrics: TileMetrics) {
    val light = state.shownLight ?: return
    val status = state.status ?: return
    // „Platí do“ (manualUntil) je jen v podtitulu na obrazovce Světlo, dlaždice ho nepoužívá.
    val texts = lightTexts(light, status.dayPart, manualUntil = null)
    SensorTile(
        metrics = metrics,
        icon = { LightIcon(if (light.on) light.rgb.let { Color(it.r, it.g, it.b) } else null) },
        label = "Světlo",
        value = texts.tileValue,
        subtitle = texts.tileDetail,
        onClick = onOpenLight,
        onClickLabel = "Otevřít záložku Světlo",
    )
}

// Žárovka vyplněná skutečnou barvou světla, nad ní obrys v barvě ostatních ikon. Obrys drží tvar i u bílého světla na bílé
// dlaždici. Zhasnuté světlo má jen šedý obrys, takže se od bílého světla liší.
@Composable
private fun LightIcon(color: Color?) {
    Box(Modifier.size(IconSlot)) {
        if (color == null) {
            val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(IconSlot), tint = grey)
        } else {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, modifier = Modifier.size(IconSlot), tint = color)
            TileIcon(Icons.Outlined.Lightbulb)
        }
    }
}

@Composable
private fun TileIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(IconSlot), tint = MaterialTheme.colorScheme.primary)
}

// Dlaždice z náhledu (.tl): ikona, popisek, hodnota a podtitul. Zvýrazněná je v barvě primaryContainer, s onClick se dá
// klepnout. Texty se slučují do jednoho prvku pro čtečku obrazovky.
@Composable
private fun RowScope.SensorTile(
    metrics: TileMetrics,
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    subtitle: String?,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (highlighted) scheme.primaryContainer else scheme.surface
    val content = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface
    val secondary = if (highlighted) content.copy(alpha = 0.75f) else scheme.onSurfaceVariant
    Surface(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        shape = TileShape,
        color = container,
        contentColor = content,
        shadowElevation = 1.dp,
    ) {
        // Klepnutí je na obsahu uvnitř karty, aby vlna dostala její zaoblení.
        val semantics = if (onClick == null) {
            Modifier.semantics(mergeDescendants = true) {}
        } else {
            Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        }
        Column(semantics.padding(start = metrics.sides, top = metrics.top, end = metrics.sides, bottom = metrics.bottom)) {
            icon()
            Text(
                text = label,
                modifier = Modifier.padding(top = 4.dp),
                color = secondary,
                style = mockupText(12f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 1.dp),
                style = mockupText(metrics.valueSize, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 1.dp),
                    color = secondary,
                    style = mockupText(11f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
