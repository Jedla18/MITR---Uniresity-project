package cz.vsb.mitr.domek.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.ui.chart.TemperatureChartCard
import cz.vsb.mitr.domek.ui.components.TwoPane
import cz.vsb.mitr.domek.ui.theme.staleDim
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.status

// Všechno pro předvádění na jedné obrazovce (spec 6.1). Obrazovku spojení místo ní ukazuje DomekApp, dokud nepřijde první
// Stav, takže bez Stavu tu není co kreslit. Na nízké široké obrazovce (telefon na šířku) je compact: menší číslo a dlaždice jako
// na náhledu „Na šířku“, aby se do sloupce vešlo víc.
@Composable
fun OverviewScreen(
    state: HouseUiState,
    actions: HouseActions,
    wide: Boolean,
    onOpenLight: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (state.status == null) return
    // Stará data: hodnoty zešednou, bannery ne (spec 6.5).
    val dim = Modifier.staleDim(state.isStale)
    val chart: @Composable () -> Unit = {
        TemperatureChartCard(state.chart, state.chartWindow, state.nowMs, actions::setChartWindow, dim)
    }
    // Na úzké obrazovce je graf mezi kartou termostatu a dlaždicemi jako na náhledu, TwoPane by ho dal až na konec.
    // Na široké má vlastní sloupec vpravo.
    TwoPane(
        wide = wide,
        modifier = modifier,
        left = {
            OverviewBanners(state, actions)
            HeroCard(state, actions, dim, compact = compact)
            if (!wide) chart()
            SensorTiles(state, onOpenLight, dim, compact = compact)
            if (state.sourceKind == SourceKind.SIMULATION) SimulationCard(state, actions)
        },
        right = { if (wide) chart() },
    )
}
