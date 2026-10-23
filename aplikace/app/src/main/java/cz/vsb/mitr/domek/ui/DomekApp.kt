package cz.vsb.mitr.domek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.ui.components.PillModel
import cz.vsb.mitr.domek.ui.components.StatusPill
import cz.vsb.mitr.domek.ui.components.pillFor
import cz.vsb.mitr.domek.ui.connection.ConnectionScreen
import cz.vsb.mitr.domek.ui.connection.DevicePickerSheet
import cz.vsb.mitr.domek.ui.light.LightScreen
import cz.vsb.mitr.domek.ui.log.LogScreen
import cz.vsb.mitr.domek.ui.overview.OverviewScreen
import cz.vsb.mitr.domek.ui.settings.SettingsScreen
import cz.vsb.mitr.domek.ui.thermostat.ThermostatScreen
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import kotlinx.coroutines.flow.Flow

enum class DomekTab(val title: String) {
    OVERVIEW("Přehled"),
    THERMOSTAT("Termostat"),
    LIGHT("Světlo"),
    LOG("Deník"),
    SETTINGS("Nastavení"),
}

// To, co obrazovky umí jen díky aktivitě: oprávnění a systémová nastavení. Obrazovky dostávají jen tyto funkce, takže
// jdou vykreslit i bez aktivity. permissionsPermanentlyDenied je true, když systém další žádost o oprávnění už nezobrazí.
data class SystemActions(
    val requestPermissions: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
    val permissionsPermanentlyDenied: Boolean,
)

// Od 600 dp (tablet, telefon na šířku) je boční lišta místo dolní (spec 6.0).
private val WideBreakpoint = 600.dp

// Široké okno nižší než 480 dp (hranice nízkého okna v Material 3) je telefon na šířku. Přehled tam má menší číslo a dlaždice
// jako na náhledu „Na šířku“, aby se na něj vešlo víc.
private val ShortBreakpoint = 480.dp

// Výška záhlaví z náhledů: na výšku 56 dp, na šířku 48 dp, aby zbylo víc místa pro obsah.
private val TopBarHeight = 56.dp
private val TopBarHeightWide = 48.dp

// Rozestup položek boční lišty, jaký jim dává Material 3.
private val RailItemSpacing = 4.dp

@Composable
fun DomekApp(state: HouseUiState, actions: HouseActions, system: SystemActions, messages: Flow<String>) {
    var tab by rememberSaveable { mutableStateOf(DomekTab.OVERVIEW) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hlášky se ukazují jedna po druhé, další přijde, až předchozí zmizí nebo ji uživatel zavře tlačítkem OK.
    LaunchedEffect(messages) {
        messages.collect { message ->
            snackbarHostState.showSnackbar(message, actionLabel = "OK", duration = SnackbarDuration.Long)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideBreakpoint
        val compact = wide && maxHeight < ShortBreakpoint
        // Boční lišta už odsadila okraj, na kterém stojí, takže ho obsah vedle ní nesmí odsadit podruhé.
        val railInsets = NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        Row(Modifier.fillMaxSize()) {
            if (wide) DomekNavigationRail(selected = tab, onSelect = { tab = it })
            Scaffold(
                modifier = Modifier.weight(1f).then(if (wide) Modifier.consumeWindowInsets(railInsets) else Modifier),
                topBar = {
                    DomekTopBar(
                        title = if (tab == DomekTab.OVERVIEW) "Chytrý dům" else tab.title,
                        pill = pillFor(state),
                        wide = wide,
                        onPillClick = { tab = DomekTab.SETTINGS },
                    )
                },
                bottomBar = { if (!wide) DomekNavigationBar(selected = tab, onSelect = { tab = it }) },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                TabContent(
                    tab = tab,
                    state = state,
                    actions = actions,
                    system = system,
                    wide = wide,
                    compact = compact,
                    onOpenLight = { tab = DomekTab.LIGHT },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
    // Výběr domku otevírá aplikace sama, kdykoli zdroj čeká na volbu, ať je vybraná jakákoli záložka a ať už jsou nějaká
    // data, nebo ne. Po „Připojit“ totiž můžou zůstat stará data (spec 5.3).
    val connection = state.connection
    if (connection is ConnectionState.ChoosingDevice) DevicePickerSheet(connection.devices, actions::chooseDevice)
}

// Záhlaví leží na pozadí stránky jako na náhledech, Material 3 by mu dal barvu ploch karet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DomekTopBar(title: String, pill: PillModel, wide: Boolean, onPillClick: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            StatusPill(pill, onClick = onPillClick)
            // Spolu s odsazením lišty je štítek 14 dp od kraje jako na náhledech.
            Spacer(Modifier.width(10.dp))
        },
        expandedHeight = if (wide) TopBarHeightWide else TopBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun DomekNavigationBar(selected: DomekTab, onSelect: (DomekTab) -> Unit) {
    NavigationBar {
        DomekTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { TabLabel(tab.title) },
            )
        }
    }
}

// Lišta má stejnou barvu jako dolní lišta; Material 3 jí jinak dá barvu ploch. Telefon na šířku je nízký, při velkém písmu se
// pět záložek nevejde pod sebe, proto se posouvají. Rozestup je stejný jako mezi položkami samotné lišty.
@Composable
private fun DomekNavigationRail(selected: DomekTab, onSelect: (DomekTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DomekTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { TabLabel(tab.title) },
                )
            }
        }
    }
}

// Pět záložek je na telefonu užších a při velkém písmu by se popisek zalomil uprostřed slova („Termos-tat“). Písmo se proto
// zmenší jen tolik, aby se popisek vešel na řádek, a nikdy pod velikost, kterou má při běžném písmu.
@Composable
private fun TabLabel(title: String) {
    val size = LocalTextStyle.current.fontSize
    Text(
        text = title,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = tabLabelMinFontSize(size, LocalDensity.current.fontScale), maxFontSize = size),
    )
}

// Při menším písmu je velikost při běžném písmu větší než nastavená; nejmenší velikost pak nesmí přerůst největší.
internal fun tabLabelMinFontSize(size: TextUnit, fontScale: Float): TextUnit = if (fontScale > 1f) size / fontScale else size

// Před prvními daty ukazují Přehled, Termostat i Světlo stav spojení. Nastavení je přístupné vždy (spec 6.0) a Deník také, protože
// zapisuje i spojení.
@Composable
private fun TabContent(
    tab: DomekTab,
    state: HouseUiState,
    actions: HouseActions,
    system: SystemActions,
    wide: Boolean,
    compact: Boolean,
    onOpenLight: () -> Unit,
    modifier: Modifier,
) {
    if (tab != DomekTab.SETTINGS && tab != DomekTab.LOG && state.received == null) {
        ConnectionScreen(state, actions, system, modifier)
    } else if (tab == DomekTab.OVERVIEW) {
        OverviewScreen(state, actions, wide, onOpenLight, modifier, compact = compact)
    } else if (tab == DomekTab.THERMOSTAT) {
        ThermostatScreen(state, actions, wide, modifier)
    } else if (tab == DomekTab.LIGHT) {
        LightScreen(state, actions, wide, modifier)
    } else if (tab == DomekTab.LOG) {
        LogScreen(state.log, modifier)
    } else if (tab == DomekTab.SETTINGS) {
        SettingsScreen(state, actions, wide, modifier)
    }
}

private val DomekTab.icon: ImageVector
    get() = when (this) {
        DomekTab.OVERVIEW -> Icons.Rounded.SpaceDashboard
        DomekTab.THERMOSTAT -> Icons.Rounded.Thermostat
        DomekTab.LIGHT -> Icons.Rounded.Lightbulb
        DomekTab.LOG -> Icons.AutoMirrored.Rounded.ListAlt
        DomekTab.SETTINGS -> Icons.Rounded.Settings
    }
