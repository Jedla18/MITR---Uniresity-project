package cz.vsb.mitr.domek.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

private const val STALE_ALPHA = 0.45f
private const val STALE_SATURATION = 0.25f

// Zašedlé ovládací prvky mají všude stejnou průhlednost, jakou Material 3 dává zakázanému obsahu.
internal const val DISABLED_ALPHA = 0.38f

// Výchozí hodnota jsou světlé barvy, aby šla komponenta vykreslit i mimo DomekTheme.
val LocalDomekColors: ProvidableCompositionLocal<DomekColors> = staticCompositionLocalOf { LightDomekColors }

// Motiv se řídí nastavením telefonu. Dynamické barvy (Material You) jsou vypnuté, aby aplikace vypadala jako na
// náhledech (spec 9).
@Composable
fun DomekTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDomekColors provides if (darkTheme) DarkDomekColors else LightDomekColors) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme, content = content)
    }
}

// Stará data (spec 6.5): hodnoty zeslábnou a ztratí barvu jako na náhledu, ale zůstanou čitelné. Kreslí se přes jednu
// vrstvu, aby se průhlednost nesčítala u překrývajících se částí karty.
fun Modifier.staleDim(stale: Boolean): Modifier =
    if (!stale) {
        this
    } else {
        drawWithCache {
            val paint = Paint().apply {
                alpha = STALE_ALPHA
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(STALE_SATURATION) })
            }
            onDrawWithContent {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                    drawContent()
                    canvas.restore()
                }
            }
        }
    }
