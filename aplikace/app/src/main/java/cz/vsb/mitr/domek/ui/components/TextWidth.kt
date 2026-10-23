package cz.vsb.mitr.domek.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

// Šířka nejširšího z textů, jak ji naměří písmo zařízení při aktuálním zvětšení písma. Podle ní se vybírá rozvržení, ve kterém se
// texty nezkracují a nezalamují uprostřed slova. Odhad z počtu znaků by nestačil: Samsung má vlastní písmo a šířky se nemění
// stejně jako velikost písma.
@Composable
internal fun widestTextWidth(texts: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, style, measurer, density) {
        with(density) { texts.maxOf { measurer.measure(it, style).size.width }.toDp() }
    }
}
