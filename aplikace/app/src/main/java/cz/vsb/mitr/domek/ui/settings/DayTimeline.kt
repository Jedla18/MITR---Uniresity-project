package cz.vsb.mitr.domek.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.domain.DayStarts
import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.domain.timeline
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.ui.theme.staleDim
import java.time.LocalTime
import kotlin.math.roundToInt

private const val MINUTES_PER_DAY = 24 * 60
private const val SECONDS_PER_DAY = 24 * 60 * 60f

// Rozměry z náhledu (.tline, .tmark, .hrs): pruh je 24 dp vysoký a značka přes něj přesahuje 4 dp nahoru a 3 dp dolů.
private val BarHeight = 24.dp
private val BarShape = RoundedCornerShape(8.dp)
private val MarkerWidth = 3.dp
private val MarkerShape = RoundedCornerShape(2.dp)
private val MarkerOverhangTop = 4.dp
private val MarkerOverhangBottom = 3.dp

// Značka končí 3 dp pod pruhem, popisky hodin začínají 4 dp pod ním.
private val ScaleGap = 1.dp

private const val SCALE_TEXT_SIZE = 10.5f

private val HOURS = listOf(0, 6, 12, 18, 24)

// Pruh přes 24 h obarvený podle úseků dne se značkou času Raspberry a popisky hodin pod ním (spec 6.4). Bez začátků (než přijde
// Nastavení) je pruh neutrální, bez času Raspberry chybí značka. stale zešedí značku: čas je ze Stavu, pruh z Nastavení.
@Composable
fun DayTimeline(starts: DayStarts?, now: LocalTime?, modifier: Modifier = Modifier, stale: Boolean = false) {
    val dim = Modifier.staleDim(stale)
    val position = now?.let { it.toSecondOfDay() / SECONDS_PER_DAY }
    val labelStyle = mockupText(SCALE_TEXT_SIZE, FontWeight.Bold)
    // Řádek popisku drží místo i bez značky, aby pruh po příchodu dat neposkočil.
    val labelHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = labelHeight)) {
            if (now != null && position != null) {
                Box(Modifier.centeredAt(position)) { MarkerLabel(now, labelStyle, dim) }
            }
        }
        Box(Modifier.fillMaxWidth().height(MarkerOverhangTop + BarHeight + MarkerOverhangBottom)) {
            DayBar(starts, Modifier.padding(top = MarkerOverhangTop).fillMaxWidth().height(BarHeight).clip(BarShape))
            if (position != null) {
                Box(
                    Modifier
                        .centeredAt(position)
                        .width(MarkerWidth)
                        .fillMaxHeight()
                        .then(dim)
                        .background(MaterialTheme.colorScheme.onSurface, MarkerShape),
                )
            }
        }
        HourScale(Modifier.padding(top = ScaleGap))
    }
}

@Composable
private fun DayBar(starts: DayStarts?, modifier: Modifier) {
    val colors = LocalDomekColors.current
    val segments = remember(starts) { starts?.let(::timeline) }
    val neutral = colors.neutralContainer
    Canvas(modifier) {
        if (segments == null) {
            drawRect(neutral)
            return@Canvas
        }
        segments.forEach { segment ->
            // Hranice na celé pixely, aby mezi sousedními úseky nezůstala světlá čára.
            val left = (segment.fromMinute * size.width / MINUTES_PER_DAY).roundToInt().toFloat()
            val right = (segment.toMinute * size.width / MINUTES_PER_DAY).roundToInt().toFloat()
            val color = when (segment.part) {
                DayPart.DAY -> colors.day
                DayPart.EVENING -> colors.evening
                DayPart.NIGHT -> colors.night
            }
            drawRect(color, Offset(left, 0f), Size(right - left, size.height))
        }
    }
}

@Composable
private fun MarkerLabel(now: LocalTime, style: TextStyle, modifier: Modifier) {
    val text = formatHhMm(now)
    Text(
        text = text,
        modifier = modifier.semantics { contentDescription = "Čas Raspberry $text" },
        style = style,
        maxLines = 1,
    )
}

// Hodiny jsou pod hodinami, které jmenují: 0 a 24 u okrajů pruhu, ostatní vystředěné pod svou polohou.
@Composable
private fun HourScale(modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        HOURS.forEach { hour ->
            Text(
                text = hour.toString(),
                modifier = Modifier.centeredAt(hour / 24f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = mockupText(SCALE_TEXT_SIZE),
                maxLines = 1,
            )
        }
    }
}

// Vystředí prvek na vodorovnou polohu `fraction` šířky rodiče, ale nenechá ho přesáhnout jeho okraje. Zabírá celou šířku,
// takže se prvky ve společném Boxu překrývají a každý stojí na svém místě. Nezrcadlí se (place, ne placeRelative): čas
// na pruhu teče zleva doprava, i když je rozložení zprava doleva.
private fun Modifier.centeredAt(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val parentWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(parentWidth, placeable.height) {
        val x = (parentWidth * fraction - placeable.width / 2f).roundToInt().coerceIn(0, (parentWidth - placeable.width).coerceAtLeast(0))
        placeable.place(x, 0)
    }
}
