package cz.vsb.mitr.domek.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.viewmodel.Band
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow

private val ChartHeight = 180.dp

// Okraje grafu z náhledu. Levý, horní a dolní jsou nejmenší: při větším písmu v systému se podle popisků zvětší.
private val MinPlotLeft = 26.dp
private val MinPlotTop = 6.dp
private val MinPlotBottom = 22.dp
private val PlotRight = 4.dp
private val LabelGap = 6.dp
private val TimeLabelGap = 4.dp

private val InsideWidth = 2.5.dp
private val OutsideWidth = 1.5.dp
private val TargetWidth = 1.5.dp
private val GridWidth = 1.dp
private val EndDotRadius = 3.5.dp

// Pruh kratší než tohle by zmizel, a krátké sepnutí topení je přitom na dlouhém okně vidět jen jako úzký proužek.
private val MinBandWidth = 1.dp

private val DashOn = 4.dp
private val DashOff = 3.dp

// Spec 8: zředit nanejvýš na dva body na pixel.
private const val POINTS_PER_PIXEL = 2

// Popisků je nejvýš devět na ose Y a tři na ose X, ať se při každém překreslení neměří znovu.
private const val TEXT_CACHE_SIZE = 16

// Pruhy v legendě jsou sytější než v grafu, jinak by na bílé kartě skoro nebyly vidět (stejně to dělá náhled).
private const val SWATCH_ALPHA = 0.3f

// Nezlomitelná mezera za „s“, aby jednopísmenná předložka nezůstala na konci řádku.
private const val EMPTY_TEXT = "Graf se začne kreslit s${NBSP}prvními daty."

@Composable
fun TemperatureChartCard(
    samples: List<ChartSample>,
    window: ChartWindow,
    nowMs: Long,
    onWindowChange: (ChartWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    DomekCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Teploty",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, letterSpacing = 0.sp),
            )
            WindowSelector(window, onWindowChange)
        }
        Spacer(Modifier.height(8.dp))
        TemperatureChart(samples, window, nowMs, colors)
        ChartLegend(colors)
    }
}

// Barvy grafu na jednom místě, aby legenda vždycky odpovídala čarám.
private class ChartColors(
    val inside: Color,
    val outside: Color,
    val target: Color,
    val heatBand: Color,
    val coolBand: Color,
    val heatSwatch: Color,
    val coolSwatch: Color,
    val grid: Color,
    val label: Color,
)

@Composable
private fun chartColors(): ChartColors {
    val scheme = MaterialTheme.colorScheme
    val domek = LocalDomekColors.current
    return ChartColors(
        inside = scheme.primary,
        outside = domek.chartOutside,
        target = domek.chartTarget,
        heatBand = domek.heatContainer,
        coolBand = domek.coolContainer,
        heatSwatch = domek.heat.copy(alpha = SWATCH_ALPHA),
        coolSwatch = domek.cool.copy(alpha = SWATCH_ALPHA),
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
    )
}

// Přepínač okna z náhledu: obrys, dělicí čáry a vybraný segment v barvě primaryContainer. Je nízký jako na náhledu, dotyková
// plocha se mu přesto sama zvětší na 48 dp.
@Composable
private fun WindowSelector(selected: ChartWindow, onSelect: (ChartWindow) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .clip(CircleShape)
            .border(1.dp, scheme.outline, CircleShape)
            .selectableGroup(),
    ) {
        ChartWindow.entries.forEachIndexed { index, window ->
            if (index > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(scheme.outline))
            val isSelected = window == selected
            Box(
                modifier = Modifier
                    .background(if (isSelected) scheme.primaryContainer else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(window) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = window.label,
                    color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

// Šířku Canvasu potřebuje plán (kolik bodů se vejde), proto BoxWithConstraints. Plán se počítá v kompozici, ne při
// kreslení, a znovu jen když se změní vzorky, okno nebo čas.
@Composable
private fun TemperatureChart(samples: List<ChartSample>, window: ChartWindow, nowMs: Long, colors: ChartColors) {
    val textMeasurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.label)
    BoxWithConstraints(Modifier.fillMaxWidth().height(ChartHeight)) {
        val maxPoints = POINTS_PER_PIXEL * constraints.maxWidth
        val plan = remember(samples, window, nowMs, maxPoints) { planChart(samples, window, nowMs, maxPoints) }
        if (plan == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = EMPTY_TEXT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Canvas(
                Modifier.fillMaxSize().semantics {
                    contentDescription = chartDescription(plan.latest)
                    role = Role.Image
                },
            ) {
                drawChart(plan, colors, textMeasurer, labelStyle)
            }
        }
    }
}

private fun chartDescription(latest: ChartSample): String {
    val values = listOfNotNull(
        latest.inside?.let { "uvnitř ${formatTemp(it)}" },
        latest.outside?.let { "venku ${formatTemp(it)}" },
        latest.target?.let { "cíl ${formatTemp(it)}" },
    )
    return if (values.isEmpty()) "Graf teplot" else "Graf teplot: ${values.joinToString(", ")}"
}

@Composable
private fun ChartLegend(colors: ChartColors) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem("Uvnitř") { LineSwatch(colors.inside) }
        LegendItem("Venku") { LineSwatch(colors.outside) }
        LegendItem("Cíl") { DashedSwatch(colors.target) }
        LegendItem("Topí") { SquareSwatch(colors.heatSwatch) }
        LegendItem("Chladí") { SquareSwatch(colors.coolSwatch) }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        swatch()
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, letterSpacing = 0.sp),
        )
    }
}

@Composable
private fun LineSwatch(color: Color) {
    Box(Modifier.size(14.dp, 3.dp).background(color, RoundedCornerShape(2.dp)))
}

@Composable
private fun SquareSwatch(color: Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
}

@Composable
private fun DashedSwatch(color: Color) {
    Canvas(Modifier.size(14.dp, 3.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(),
            pathEffect = dashEffect(),
        )
    }
}

// Čárkování v dp jako na náhledu (4 a 3), aby vypadalo stejně na každé hustotě displeje.
private fun DrawScope.dashEffect(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(DashOn.toPx(), DashOff.toPx()))

// Obdélník, do kterého se kreslí řady. x() a y() převádějí čas a teplotu na pixely Canvasu.
private class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float, private val plan: ChartPlan) {
    private val spanMs = (plan.toMs - plan.fromMs).coerceAtLeast(1)
    private val lowest = plan.range.start
    private val rangeSpan = plan.range.endInclusive - lowest

    fun x(atMs: Long): Float = left + (right - left) * ((atMs - plan.fromMs).toDouble() / spanMs).toFloat()

    fun y(value: Double): Float = bottom - (bottom - top) * ((value - lowest) / rangeSpan).toFloat()
}

private fun DrawScope.drawChart(plan: ChartPlan, colors: ChartColors, textMeasurer: TextMeasurer, labelStyle: TextStyle) {
    val gridLabels = plan.grid.map { textMeasurer.measure("$it°", labelStyle) }
    val timeLabels = plan.timeLabels.map { textMeasurer.measure(it, labelStyle) }
    val plot = PlotArea(
        left = maxOf(MinPlotLeft.toPx(), (gridLabels.maxOfOrNull { it.size.width } ?: 0) + LabelGap.toPx()),
        top = maxOf(MinPlotTop.toPx(), (gridLabels.maxOfOrNull { it.size.height } ?: 0) / 2f),
        right = size.width - PlotRight.toPx(),
        bottom = size.height - maxOf(MinPlotBottom.toPx(), TimeLabelGap.toPx() + (timeLabels.maxOfOrNull { it.size.height } ?: 0)),
        plan = plan,
    )

    drawBands(plan.bands, plot, colors)
    drawGrid(plan.grid, gridLabels, plot, colors)
    drawTimeLabels(timeLabels, plot)
    drawSeries(plan.outside, plot, colors.outside, Stroke(OutsideWidth.toPx()))
    drawSeries(plan.target, plot, colors.target, Stroke(TargetWidth.toPx(), pathEffect = dashEffect()), stepped = true)
    drawSeries(plan.inside, plot, colors.inside, Stroke(InsideWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    plan.inside.lastOrNull()?.lastOrNull()?.let { last ->
        drawCircle(colors.inside, EndDotRadius.toPx(), Offset(plot.x(last.atMs), plot.y(last.value)))
    }
}

private fun DrawScope.drawBands(bands: List<Band>, plot: PlotArea, colors: ChartColors) {
    val minWidth = MinBandWidth.toPx()
    for (band in bands) {
        val from = plot.x(band.fromMs)
        val width = maxOf(plot.x(band.toMs) - from, minWidth)
        drawRect(
            color = if (band.heating) colors.heatBand else colors.coolBand,
            topLeft = Offset(from, plot.top),
            size = Size(width, plot.bottom - plot.top),
        )
    }
}

private fun DrawScope.drawGrid(grid: List<Long>, labels: List<TextLayoutResult>, plot: PlotArea, colors: ChartColors) {
    grid.forEachIndexed { index, value ->
        val y = plot.y(value.toDouble())
        drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = GridWidth.toPx())
        val label = labels[index]
        // Popisek na okraji grafu se posune dovnitř, aby ho Canvas neuřízl.
        val labelTop = minOf(y - label.size.height / 2f, size.height - label.size.height).coerceAtLeast(0f)
        drawText(label, topLeft = Offset(plot.left - LabelGap.toPx() - label.size.width, labelTop))
    }
}

// Začátek je zarovnaný doleva, střed na střed a konec doprava, aby žádný popisek nepřetékal z grafu.
private fun DrawScope.drawTimeLabels(labels: List<TextLayoutResult>, plot: PlotArea) {
    val top = plot.bottom + TimeLabelGap.toPx()
    drawText(labels[0], topLeft = Offset(plot.left, top))
    drawText(labels[1], topLeft = Offset((plot.left + plot.right - labels[1].size.width) / 2, top))
    drawText(labels[2], topLeft = Offset(plot.right - labels[2].size.width, top))
}

// Jeden úsek je jedna Path. Úsek o jednom bodu se nakreslí jako tečka, jinak by osamocený vzorek nebyl vidět. Schody
// (cíl) jdou vodorovně k času dalšího vzorku a pak svisle, protože cíl platí, dokud se nezmění.
private fun DrawScope.drawSeries(
    segments: List<List<ChartPoint>>,
    plot: PlotArea,
    color: Color,
    stroke: Stroke,
    stepped: Boolean = false,
) {
    for (segment in segments) {
        val first = segment.first()
        if (segment.size == 1) {
            drawCircle(color, stroke.width / 2, Offset(plot.x(first.atMs), plot.y(first.value)))
            continue
        }
        val path = Path()
        var previousY = plot.y(first.value)
        path.moveTo(plot.x(first.atMs), previousY)
        for (index in 1 until segment.size) {
            val x = plot.x(segment[index].atMs)
            val y = plot.y(segment[index].value)
            if (stepped) path.lineTo(x, previousY)
            path.lineTo(x, y)
            previousY = y
        }
        drawPath(path, color, style = stroke)
    }
}
