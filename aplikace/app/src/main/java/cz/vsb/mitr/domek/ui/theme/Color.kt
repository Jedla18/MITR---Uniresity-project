package cz.vsb.mitr.domek.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Barvy, pro které Material 3 nemá roli: topení a chlazení, úseky dne, štítky stavu připojení a čáry grafu.
@Immutable
data class DomekColors(
    val heat: Color,
    val heatContainer: Color,
    val cool: Color,
    val coolContainer: Color,
    val night: Color,
    val day: Color,
    val evening: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    val staleContainer: Color,
    val onStaleContainer: Color,
    val connectingContainer: Color,
    val onConnectingContainer: Color,
    val simulationContainer: Color,
    val onSimulationContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val chartOutside: Color,
    val chartTarget: Color,
)

// Světlý motiv je podle schválených náhledů (spec 9). Sekundární kontejner má barvu hlavního, protože ho Material 3
// používá pro vybranou záložku, segment a dráhu posuvníku, které jsou na náhledech tyrkysové. Snackbar
// (inverseSurface, inverseOnSurface, inversePrimary) má barvy z náhledu odmítnutého zápisu.
internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0D6A70),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBEDED),
    onPrimaryContainer = Color(0xFF002F33),
    inversePrimary = Color(0xFF8FD3D6),
    secondary = Color(0xFF4A6366),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCBEDED),
    onSecondaryContainer = Color(0xFF002F33),
    background = Color(0xFFF3F6F7),
    onBackground = Color(0xFF161C1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161C1F),
    surfaceVariant = Color(0xFFE3E9EC),
    onSurfaceVariant = Color(0xFF59656B),
    surfaceTint = Color(0xFF0D6A70),
    inverseSurface = Color(0xFF2B3134),
    inverseOnSurface = Color(0xFFEEF2F3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF8C0009),
    outline = Color(0xFFD3DCE0),
    outlineVariant = Color(0xFFE1E7EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9E0E3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F5),
    surfaceContainer = Color(0xFFEAF0F2),
    surfaceContainerHigh = Color(0xFFE4EBED),
    surfaceContainerHighest = Color(0xFFDEE5E8),
)

internal val LightDomekColors = DomekColors(
    heat = Color(0xFFDF522E),
    heatContainer = Color(0xFFFFE3D9),
    cool = Color(0xFF2A77E2),
    coolContainer = Color(0xFFDCE9FF),
    night = Color(0xFF3E4090),
    day = Color(0xFFF5C451),
    evening = Color(0xFFEF8A4C),
    okContainer = Color(0xFFDCF2E4),
    onOkContainer = Color(0xFF116137),
    staleContainer = Color(0xFFFFE7C2),
    onStaleContainer = Color(0xFF7F4700),
    connectingContainer = Color(0xFFD9ECF7),
    onConnectingContainer = Color(0xFF0B4F75),
    simulationContainer = Color(0xFFECE0FF),
    onSimulationContainer = Color(0xFF4B3487),
    neutralContainer = Color(0xFFE3E9EC),
    onNeutralContainer = Color(0xFF4A565C),
    chartOutside = Color(0xFF9AA4AA),
    chartTarget = Color(0xFF2B3237),
)

// Tmavý motiv náhledy nemají (spec 12): plochy jsou tmavé, kontejnery sytě tmavé a barvy na nich světlé. Karty
// jsou o stupeň světlejší než pozadí, jako jsou ve světlém motivu bílé na šedém. Topení a chlazení zůstávají stejné,
// protože jsou pozadím štítků s bílým textem a zároveň musí být vidět na tmavém pozadí; noc je světlejší, jinak by
// její ikona na tmavé kartě zanikla.
internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FD3D6),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFFABEEF1),
    inversePrimary = Color(0xFF0D6A70),
    secondary = Color(0xFFB1CBCD),
    onSecondary = Color(0xFF1B3436),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFFABEEF1),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF192022),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484B),
    onSurfaceVariant = Color(0xFFBEC8CB),
    surfaceTint = Color(0xFF8FD3D6),
    inverseSurface = Color(0xFFDEE3E5),
    inverseOnSurface = Color(0xFF2B3134),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF414B4F),
    outlineVariant = Color(0xFF2C3538),
    surfaceBright = Color(0xFF353B3D),
    surfaceDim = Color(0xFF0F1416),
    surfaceContainerLowest = Color(0xFF0A0F10),
    surfaceContainerLow = Color(0xFF161C1E),
    surfaceContainer = Color(0xFF1D2527),
    surfaceContainerHigh = Color(0xFF273032),
    surfaceContainerHighest = Color(0xFF313A3D),
)

internal val DarkDomekColors = DomekColors(
    heat = Color(0xFFDF522E),
    heatContainer = Color(0xFF4E2417),
    cool = Color(0xFF2A77E2),
    coolContainer = Color(0xFF13305A),
    night = Color(0xFF7B7FD8),
    day = Color(0xFFF5C451),
    evening = Color(0xFFEF8A4C),
    okContainer = Color(0xFF1C4A2E),
    onOkContainer = Color(0xFFA6E8BE),
    staleContainer = Color(0xFF5A3A00),
    onStaleContainer = Color(0xFFFFDDAA),
    connectingContainer = Color(0xFF0D3F5E),
    onConnectingContainer = Color(0xFFBFE3F7),
    simulationContainer = Color(0xFF3B2C6E),
    onSimulationContainer = Color(0xFFE2D6FF),
    neutralContainer = Color(0xFF2E383C),
    onNeutralContainer = Color(0xFFC7D1D5),
    chartOutside = Color(0xFF7F8A90),
    chartTarget = Color(0xFFC9D1D5),
)
