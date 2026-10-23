package cz.vsb.mitr.domek.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.components.icon
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.components.tint
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.viewmodel.LogCategory
import cz.vsb.mitr.domek.viewmodel.LogEntry
import cz.vsb.mitr.domek.viewmodel.LogEvent
import cz.vsb.mitr.domek.viewmodel.LogKind
import cz.vsb.mitr.domek.viewmodel.formatLogTime

// Na tabletu a na šířku stojí seznam uprostřed a je nejvýš takhle široký (spec 2.1).
private val MaxListWidth = 720.dp

// Kruh ikony a tečka barvy světla z náhledu Deníku (.ic, .dot).
private val IconCircle = 30.dp
private val IconGlyph = 18.dp
private val LightDotSize = 9.dp

// Čára mezi řádky je jako v Nastavení světlejší než outlineVariant.
private const val DIVIDER_STRENGTH = 0.6f

// Kruh ikony bez vlastní barvy kontejneru je odstín její barvy na ploše, zhruba jako kontejner topení ve světlém motivu.
private const val ICON_CONTAINER_STRENGTH = 0.18f

// Obrys tečky z náhledu, aby byla vidět i bílá tečka na světlém pozadí.
private const val LIGHT_DOT_OUTLINE_ALPHA = 0.15f

// Vybraný je vždy právě jeden filtr. Ostatní události (simulace, hodiny, začátky úseků) vlastní filtr nemají, ukazuje je jen Vše.
private enum class LogFilter(val label: String, val category: LogCategory?) {
    ALL("Vše", null),
    THERMOSTAT("Termostat", LogCategory.THERMOSTAT),
    LIGHT("Světlo", LogCategory.LIGHT),
    MOTION("Pohyb", LogCategory.MOTION),
    CONNECTION("Spojení a chyby", LogCategory.CONNECTION),
}

// Deník (spec 2.1): řádek filtrů a pod ním události, nejnovější nahoře. Je přístupný vždy, i před prvními daty.
@Composable
fun LogScreen(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val shown = remember(entries, filter) { entries.filter { filter.category == null || it.event.category == filter.category } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MaxListWidth).fillMaxSize()) {
            FilterRow(filter, onSelect = { filter = it })
            when {
                entries.isEmpty() -> EmptyLog()
                shown.isEmpty() -> EmptyGroup()
                else -> EntryList(shown)
            }
        }
    }
}

// Štítky, které se na šířku nevejdou, se posouvají vodorovně.
@Composable
private fun FilterRow(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(filter.label) })
        }
    }
}

@Composable
private fun EntryList(entries: List<LogEntry>) {
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // Nová událost přibude nad dosavadní první řádek a seznam by ho jinak držel nahoře. Kdo je na začátku, má ji vidět;
    // posunutý seznam zůstane, kde je.
    if (atTop) listState.requestScrollToItem(0)
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp)) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DIVIDER_STRENGTH))
            EntryRow(entry)
        }
    }
}

// Řádek z náhledu (.ev): čas telefonu, ikona v barevném kruhu, nadpis a podrobnost. Nadpis se zalamuje, nic se neořezává.
// Čtečka obrazovky přečte řádek jako jeden celek.
@Composable
private fun EntryRow(entry: LogEntry) {
    val event = entry.event
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Tabulkové číslice, aby měly všechny časy stejnou šířku a ikony stály pod sebou.
        Box(Modifier.heightIn(min = IconCircle), contentAlignment = Alignment.CenterStart) {
            Text(
                text = formatLogTime(entry.at),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = mockupText(12.5f).copy(fontFeatureSettings = "tnum"),
            )
        }
        KindIcon(event.kind)
        // Krátký řádek stojí uprostřed vedle ikony, delší od jejího horního okraje.
        Column(Modifier.weight(1f).heightIn(min = IconCircle), verticalArrangement = Arrangement.Center) {
            Text(text = event.title, style = mockupText(13.5f, FontWeight.Medium))
            if (event.detail.isNotEmpty() || event.lightColors.isNotEmpty()) Detail(event)
        }
    }
}

// Tečky barvy světla sedí na účaří podrobnosti, takže zůstanou na řádku i při větším písmu. Dvě tečky jsou stará a nová barva.
@Composable
private fun Detail(event: LogEvent) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = mockupText(12f)
    Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        event.lightColors.forEachIndexed { index, rgb ->
            if (index > 0) Text(text = "→", modifier = Modifier.alignByBaseline(), color = color, style = style)
            LightDot(rgb, Modifier.alignBy { it.measuredHeight })
        }
        if (event.detail.isNotEmpty()) {
            Text(text = event.detail, modifier = Modifier.alignByBaseline().weight(1f, fill = false), color = color, style = style)
        }
    }
}

@Composable
private fun LightDot(rgb: Rgb, modifier: Modifier) {
    Box(
        modifier
            .size(LightDotSize)
            .background(Color(rgb.r, rgb.g, rgb.b), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = LIGHT_DOT_OUTLINE_ALPHA), CircleShape),
    )
}

@Composable
private fun KindIcon(kind: LogKind) {
    val look = lookOf(kind)
    Box(Modifier.size(IconCircle).background(look.container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(look.icon, contentDescription = null, modifier = Modifier.size(IconGlyph), tint = look.accent)
    }
}

private class KindLook(val icon: ImageVector, val accent: Color, val container: Color)

// Barvy aplikace: topení a chlazení, úseky dne, štítky stavu spojení a chyby. Světlo má barvu slunce z ikony dne.
@Composable
private fun lookOf(kind: LogKind): KindLook {
    val colors = LocalDomekColors.current
    val scheme = MaterialTheme.colorScheme
    fun tinted(icon: ImageVector, accent: Color) = KindLook(icon, accent, lerp(scheme.surface, accent, ICON_CONTAINER_STRENGTH))
    val partTints = DayPart.entries.associateWith { it.tint() }
    fun dayPart(part: DayPart) = tinted(part.icon, partTints.getValue(part))
    val neutral = { icon: ImageVector -> KindLook(icon, colors.onNeutralContainer, colors.neutralContainer) }
    val ok = { icon: ImageVector -> KindLook(icon, colors.onOkContainer, colors.okContainer) }
    val error = { icon: ImageVector -> KindLook(icon, scheme.onErrorContainer, scheme.errorContainer) }
    val motion = { icon: ImageVector -> KindLook(icon, scheme.primary, scheme.primaryContainer) }
    return when (kind) {
        LogKind.HEATING -> KindLook(Icons.Rounded.LocalFireDepartment, colors.heat, colors.heatContainer)
        LogKind.COOLING -> KindLook(Icons.Rounded.AcUnit, colors.cool, colors.coolContainer)
        LogKind.DAY -> dayPart(DayPart.DAY)
        LogKind.EVENING -> dayPart(DayPart.EVENING)
        LogKind.NIGHT -> dayPart(DayPart.NIGHT)
        LogKind.OTHER_DAY_PART -> neutral(Icons.Rounded.Schedule)
        LogKind.LIGHT -> tinted(Icons.Rounded.Lightbulb, partTints.getValue(DayPart.DAY))
        LogKind.MOTION -> motion(Icons.AutoMirrored.Rounded.DirectionsWalk)
        LogKind.NOBODY_HOME -> motion(Icons.Rounded.PersonOff)
        LogKind.SOMEBODY_HOME -> motion(Icons.Rounded.Person)
        LogKind.SETTING -> neutral(Icons.Rounded.Tune)
        LogKind.CONNECTED -> ok(Icons.Rounded.BluetoothConnected)
        LogKind.CONNECTION_LOST -> error(Icons.Rounded.BluetoothDisabled)
        LogKind.DISCONNECTED -> neutral(Icons.Rounded.LinkOff)
        LogKind.SIMULATION_STARTED -> KindLook(Icons.Rounded.Science, colors.onSimulationContainer, colors.simulationContainer)
        LogKind.DATA_STALE -> KindLook(Icons.Rounded.History, colors.onStaleContainer, colors.staleContainer)
        LogKind.DATA_FRESH -> ok(Icons.Rounded.CheckCircle)
        LogKind.FAULT -> error(Icons.Rounded.Warning)
        LogKind.FAULT_ENDED -> ok(Icons.Rounded.CheckCircle)
        LogKind.MESSAGE -> error(Icons.Rounded.ErrorOutline)
        LogKind.SIMULATION -> neutral(Icons.Rounded.Science)
        LogKind.CLOCK -> neutral(Icons.Rounded.Schedule)
    }
}

@Composable
private fun EmptyLog() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ListAlt,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Zatím se nic nestalo.",
            modifier = Modifier.padding(top = 12.dp),
            style = mockupText(16f, FontWeight.Medium),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Deník zapisuje změny od chvíle, kdy aplikace dostane první data.",
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = mockupText(13.5f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyGroup() {
    Text(
        text = "V${NBSP}této skupině zatím nic není.",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = mockupText(13.5f),
        textAlign = TextAlign.Center,
    )
}
