package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val Gap = 12.dp

// Obsah dvou sloupců je nejvýš tak široký a na širší obrazovce (tablet na šířku) stojí uprostřed, aby karty nebyly natažené přes
// celou šířku. Telefon na šířku má obsah užší, takže ho omezení nezasáhne.
private val MaxWideWidth = 960.dp

// Široká obrazovka (od 600 dp) má dva sloupce, které se posouvají každý zvlášť, v poměru 1 : 1,15 jako na náhledu
// „Na šířku“. Úzká má jeden sloupec, ve kterém je levý obsah nad pravým.
@Composable
fun TwoPane(
    wide: Boolean,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    if (wide) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.widthIn(max = MaxWideWidth).fillMaxSize().padding(horizontal = Gap),
                horizontalArrangement = Arrangement.spacedBy(Gap),
            ) {
                ScrollingColumn(Modifier.weight(1f), PaddingValues(vertical = Gap), left)
                ScrollingColumn(Modifier.weight(1.15f), PaddingValues(vertical = Gap), right)
            }
        }
    } else {
        ScrollingColumn(modifier, PaddingValues(Gap)) {
            left()
            right()
        }
    }
}

// Svislé odsazení je uvnitř posouvaného obsahu, aby karty při posouvání dojely až k okraji a poslední se dala
// odsunout od spodního okraje.
@Composable
private fun ScrollingColumn(modifier: Modifier, padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Gap),
        content = content,
    )
}
