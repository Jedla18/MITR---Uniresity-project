package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors

// Karta z náhledů: bílá plocha se zaoblením 22 dp. Vlastní barva pozadí (např. nádech topení) má text v barvě
// onSurface, protože k ní Material 3 vlastní barvu textu nezná.
@Composable
fun DomekCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = container,
        contentColor = scheme.contentColorFor(container).takeOrElse { scheme.onSurface },
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

enum class BannerTone { STALE, ERROR, WARNING }

@Composable
fun Banner(tone: BannerTone, title: String, text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val colors = LocalDomekColors.current
    val scheme = MaterialTheme.colorScheme
    // Varování i stará data jsou na náhledech oranžová (karta „Data jsou N s stará“ a upozornění na hodiny),
    // liší se ikonou. Jediné varování je posun hodin (spec 6.1), proto ikona hodin.
    val (container, content) = when (tone) {
        BannerTone.STALE, BannerTone.WARNING -> colors.staleContainer to colors.onStaleContainer
        BannerTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (tone) {
        BannerTone.STALE -> Icons.Rounded.History
        BannerTone.ERROR -> Icons.Rounded.ErrorOutline
        BannerTone.WARNING -> Icons.Rounded.Schedule
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = if (actionLabel != null) 4.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold))
                // Neznámá chyba z Raspberry žádné vysvětlení nemá.
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (actionLabel != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.textButtonColors(contentColor = content),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
