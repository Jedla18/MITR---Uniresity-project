package cz.vsb.mitr.domek.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors

// Ikona úseku dne (slunce, soumrak, měsíc) a její barva, stejné na kartě termostatu na Přehledu i v Nastavení.

// Ikona slunce z náhledu (.sun) je tmavší než barva dne na pruhu, aby byla vidět na světlé kartě.
private val SunColor = Color(0xFFE0A100)

internal val DayPart.icon: ImageVector
    get() = when (this) {
        DayPart.DAY -> Icons.Rounded.LightMode
        DayPart.EVENING -> Icons.Rounded.WbTwilight
        DayPart.NIGHT -> Icons.Rounded.Bedtime
    }

@Composable
internal fun DayPart.tint(): Color {
    val colors = LocalDomekColors.current
    return when (this) {
        DayPart.DAY -> SunColor
        DayPart.EVENING -> colors.evening
        DayPart.NIGHT -> colors.night
    }
}
