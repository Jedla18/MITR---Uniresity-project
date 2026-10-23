package cz.vsb.mitr.domek.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val MOCKUP_LINE_HEIGHT = 1.35f

// Náhledy mají řádkování 1,35 a písmo bez rozestupů, Material 3 má u každého stylu vlastní. Velikost, řádkování i rozestupy
// se proto nastavují společně, aby se výšky karet shodovaly s náhledem.
@Composable
internal fun mockupText(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = (size * MOCKUP_LINE_HEIGHT).sp,
        letterSpacing = 0.sp,
        fontWeight = weight,
    )
