package cz.vsb.mitr.domek.ui.light

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.NO_VALUE
import cz.vsb.mitr.domek.domain.formatLux
import cz.vsb.mitr.domek.domain.lightTexts
import cz.vsb.mitr.domek.domain.nextStart
import cz.vsb.mitr.domek.ui.components.CardTitle
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.HINT_LINE_HEIGHT
import cz.vsb.mitr.domek.ui.components.SettingRow
import cz.vsb.mitr.domek.ui.components.SettingRowShape
import cz.vsb.mitr.domek.ui.components.SettingStepper
import cz.vsb.mitr.domek.ui.components.TwoPane
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.components.widestTextWidth
import cz.vsb.mitr.domek.ui.theme.DISABLED_ALPHA
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.ui.theme.staleDim
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.chosenLook
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.dayStarts
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.piTime
import cz.vsb.mitr.domek.viewmodel.settingValue
import cz.vsb.mitr.domek.viewmodel.shownLight
import cz.vsb.mitr.domek.viewmodel.status
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.json.booleanOrNull

// Rozměry z náhledu 3: kruh (.glow) a jeho žárovka.
private val GlowSize = 150.dp
private val BulbSize = 44.dp

private val ButtonHeight = 44.dp
private val ButtonVerticalPadding = 8.dp
private val ButtonGap = 8.dp
private val ButtonIconSize = 20.dp
private val ButtonIconGap = 6.dp

// Úzké vodorovné odsazení, aby se tlačítka vešla vedle sebe i při velkém písmu. Tlačítka se stejně natahují na šířku řady.
private val ButtonSidePadding = 4.dp
private val OutlinedButtonSidePadding = 20.dp

private const val OFF_BULB_ALPHA = 0.6f

private val WHITE = Rgb(255, 255, 255)

// Záře kruhu z náhledu (radial-gradient): plná barva do 27 % poloměru, do 45 % slábne a do 70 % zmizí. Poloměr je vzdálenost středu
// od rohu čtverce jako v CSS. Střed má neprůhlednost podle jasu (viz glowStrength), zbytek záře je 0,44 násobek (0,42 : 0,95 z náhledu).
private const val CORE_END = 0.27f
private const val HALO_MID = 0.45f
private const val HALO_END = 0.70f
private const val HALO_RATIO = 0.44f

// Žárovka je na sytých barvách bílá a na světlých tmavá, protože bílá by na nich zanikla; tmavá je pevná, protože záře je světlá
// v obou motivech. Střed kruhu, který se od karty skoro neliší (bílé světlo na bílé kartě, slabý jas), by zanikl celý, proto má
// pod sebou šedý podklad jako zhasnuté světlo. Rozdíl se měří poměrem jasností jako kontrast ve WCAG, který počítá i s tím,
// že tmavé barvy se na tmavé kartě rozlišují snáz, než by řekl rozdíl jasností.
private const val PALE_LUMINANCE = 0.5f
private const val INVISIBLE_CONTRAST = 1.3f
private const val CONTRAST_OFFSET = 0.05f
private val DarkBulb = Color(0xFF161C1F)

// Okraj přepínače je v náhledu tmavší než čára karet (outline), stejně jako u tlačítka „Spustit simulaci“ na obrazovce spojení:
// mezi outline a barvou popisků.
private const val BUTTON_BORDER_STRENGTH = 0.42f

// Světlo (spec 6.3): náhled s tlačítky, barva a jas a automatika. Obrazovku spojení místo něj ukazuje DomekApp, dokud nepřijde
// první Stav, takže bez Stavu tu není co kreslit.
@Composable
fun LightScreen(state: HouseUiState, actions: HouseActions, wide: Boolean, modifier: Modifier = Modifier) {
    val status = state.status ?: return
    val light = state.shownLight ?: return
    val look = state.chosenLook ?: return
    // Stará data: hodnoty ze Stavu zešednou, ovládací prvky ne (spec 6.5).
    val dim = Modifier.staleDim(state.isStale)
    var choosingColor by rememberSaveable { mutableStateOf(false) }
    TwoPane(
        wide = wide,
        modifier = modifier,
        left = { PreviewCard(state, actions, light, status.dayPart, dim) },
        right = {
            LightColorCard(state, actions, look, onChooseCustom = { choosingColor = true })
            AutomationCard(state, actions, status.lux, dim)
        },
    )
    if (choosingColor) {
        ColorDialog(
            // Dialog začíná zvolenou barvou, bez ní (zhasnuté světlo staršího programu) bílou.
            initial = look.color ?: WHITE,
            onConfirm = {
                choosingColor = false
                actions.setLightColor(it)
            },
            onDismiss = { choosingColor = false },
        )
    }
}

// ----- Náhled -----

@Composable
private fun PreviewCard(state: HouseUiState, actions: HouseActions, light: LightState, dayPart: DayPart?, dim: Modifier) {
    val texts = lightTexts(light, dayPart, manualUntil(state, light))
    DomekCard {
        // Název a podtitul se čtečce obrazovky čtou jako jeden celek, kruh je jen obrázek.
        Column(
            modifier = Modifier.fillMaxWidth().then(dim).semantics(mergeDescendants = true) {}.padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightGlow(light)
            Text(text = texts.title, style = mockupText(20f, FontWeight.Medium))
            if (texts.detail.isNotEmpty()) {
                Text(
                    text = texts.detail,
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Popisek pod kruhem (.lamp-s) má řádkování jako poznámky pod ovladači.
                    style = mockupText(13f).copy(lineHeight = (13f * HINT_LINE_HEIGHT).sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Vrátit automatiku jde jen u ruční volby, jinak světlo už řídí automatika (rozhodl uživatel 23. 10. 2026). U starých dat
        // aplikace neví, jestli ruční volba pořád platí, tak tlačítko zůstane dostupné jako ostatní ovladače (spec 6.5).
        LightButtons(actions, enabled = state.controlsEnabled, canReturnToAutomatic = light.manual || state.isStale)
    }
}

// Ruční volba platí do začátku dalšího úseku dne podle času Raspberry a začátků úseků z Nastavení (spec 6.3). Bez nich není do kdy.
private fun manualUntil(state: HouseUiState, light: LightState): LocalTime? {
    if (!light.manual) return null
    val now = state.piTime ?: return null
    val starts = state.dayStarts ?: return null
    return nextStart(now.toLocalTime(), starts).second
}

// Jak silná je záře: 25 % při jasu 1 % až plná při jasu 100 %.
private fun glowStrength(brightness: Int): Float = 0.25f + 0.75f * brightness.coerceIn(0, MAX_BRIGHTNESS) / MAX_BRIGHTNESS

// Kruh ve skutečné barvě, jehož záře odpovídá jasu; zhasnuté světlo je šedé (spec 6.3).
@Composable
private fun LightGlow(light: LightState) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalDomekColors.current
    val glow = if (light.on) light.rgb.toColor() else null
    val strength = glowStrength(light.brightness)
    // Jasnost středu kruhu, tak jak ji uživatel vidí na kartě; podle ní se pozná, jestli je kruh světlý a jestli zaniká.
    val luminance = glow?.copy(alpha = strength)?.compositeOver(scheme.surface)?.luminance()
    val bulb = when {
        luminance == null -> colors.onNeutralContainer.copy(alpha = OFF_BULB_ALPHA)
        luminance > PALE_LUMINANCE -> DarkBulb
        else -> Color.White
    }
    val vanishes = luminance != null && contrastRatio(luminance, scheme.surface.luminance()) < INVISIBLE_CONTRAST
    Box(
        modifier = Modifier
            .size(GlowSize)
            .then(if (glow == null || vanishes) Modifier.background(colors.neutralContainer, CircleShape) else Modifier)
            .then(if (glow != null) Modifier.glow(glow, strength) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Lightbulb, contentDescription = null, modifier = Modifier.size(BulbSize), tint = bulb)
    }
}

private fun contrastRatio(first: Float, second: Float): Float =
    (max(first, second) + CONTRAST_OFFSET) / (min(first, second) + CONTRAST_OFFSET)

private fun Modifier.glow(color: Color, strength: Float): Modifier = drawWithCache {
    val brush = Brush.radialGradient(
        CORE_END to color.copy(alpha = strength),
        HALO_MID to color.copy(alpha = strength * HALO_RATIO),
        HALO_END to color.copy(alpha = 0f),
        center = size.center,
        radius = size.minDimension / 2f * sqrt(2f),
    )
    onDrawBehind { drawCircle(brush, radius = size.minDimension / 2f) }
}

// Tlačítka mají výšku 44 dp z náhledu. Material 3 kolem nich nechává dotykovou plochu 48 dp jako u přepínače režimu na Termostatu,
// takže řady jsou od sebe o 4 dp dál než na náhledu. Při velkém písmu tlačítka rostou do výšky.
@Composable
private fun LightButtons(actions: HouseActions, enabled: Boolean, canReturnToAutomatic: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tonal = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface)
    // Přepnout tu není: dělalo by totéž co Zapnout a Vypnout a stav světla je vidět v náhledu (rozhodl uživatel 23. 10. 2026).
    val buttons = listOf(
        Triple("Zapnout", Icons.Outlined.Lightbulb, LightAction.ON),
        Triple("Vypnout", Icons.Rounded.PowerSettingsNew, LightAction.OFF),
    )
    // Tlačítka mají stejnou šířku, takže každé musí unést delší popisek. FlowRow u tlačítek s váhou řadí podle nejmenší šířky
    // každého zvlášť, tak by nechal obě vedle sebe i tam, kde se nevejdou, a delší popisek by se zalomil uprostřed slova.
    val labels = buttons.map { it.first }
    val minButtonWidth = widestTextWidth(labels, MaterialTheme.typography.labelLarge) +
        ButtonIconSize + ButtonIconGap + ButtonSidePadding * 2
    // Při hodně velkém písmu se obě tlačítka nevejdou vedle sebe, tak se druhé přesune pod první.
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGap),
        verticalArrangement = Arrangement.spacedBy(ButtonGap),
        maxItemsInEachRow = buttons.size,
    ) {
        buttons.forEach { (label, icon, action) ->
            FilledTonalButton(
                onClick = { actions.lightAction(action) },
                modifier = Modifier.weight(1f).widthIn(min = minButtonWidth).heightIn(min = ButtonHeight),
                enabled = enabled,
                colors = tonal,
                contentPadding = PaddingValues(horizontal = ButtonSidePadding, vertical = ButtonVerticalPadding),
            ) {
                IconLabel(icon, label)
            }
        }
    }
    // Okraj se odvozuje z barev motivu, aby fungoval i v tmavém motivu. Zašedlé tlačítko vypadá jako aktivní, jen průhlednější,
    // stejně jako „Vrátit původní časy“ v Nastavení.
    val automaticEnabled = enabled && canReturnToAutomatic
    OutlinedButton(
        onClick = { actions.lightAction(LightAction.AUTO) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ButtonGap)
            .heightIn(min = ButtonHeight)
            .alpha(if (automaticEnabled) 1f else DISABLED_ALPHA),
        enabled = automaticEnabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary, disabledContentColor = scheme.primary),
        border = BorderStroke(1.dp, lerp(scheme.outline, scheme.onSurfaceVariant, BUTTON_BORDER_STRENGTH)),
        contentPadding = PaddingValues(horizontal = OutlinedButtonSidePadding, vertical = ButtonVerticalPadding),
    ) {
        IconLabel(Icons.Rounded.Autorenew, "Vrátit automatiku")
    }
}

@Composable
private fun RowScope.IconLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonIconSize))
    Spacer(Modifier.width(ButtonIconGap))
    Text(text = label)
}

// ----- Automatika -----

@Composable
private fun AutomationCard(state: HouseUiState, actions: HouseActions, lux: Double?, dim: Modifier) {
    DomekCard {
        CardTitle("Automatika")
        Column(Modifier.padding(top = 2.dp)) {
            MotionSwitchRow(state, actions)
            SettingRow(
                title = "Noční světlo svítí",
                note = "5–600${NBSP}s",
                control = { SettingStepper(state, actions, SettingKey.NIGHT_LIGHT_SECONDS, ::formatSeconds) },
            )
            SettingRow(
                title = "Práh šera",
                // Osvětlení venku je hodnota ze Stavu, takže při starých datech zešedne.
                note = lux?.let { "teď venku ${formatLux(it)}" },
                noteModifier = dim,
                control = { SettingStepper(state, actions, SettingKey.LUX_THRESHOLD, ::formatLux) },
            )
        }
    }
}

// Klepnout jde na celý řádek. Raspberry se starším programem klíč nezná a nepošle ho, tak je přepínač zašedlý a podtitul to říká
// (spec 4.2). Hodnota je rozpracovaná, nebo z Nastavení.
@Composable
private fun MotionSwitchRow(state: HouseUiState, actions: HouseActions) {
    val value = state.settingValue(SettingKey.LIGHT_ON_MOTION)?.booleanOrNull
    val enabled = state.controlsEnabled && value != null
    val on = value == true
    // O nepodpoře se dá mluvit, až když se Nastavení přečetlo a klíč v něm chybí. Dokud nepřišlo, přepínač jen čeká.
    val unsupported = value == null && state.settings != null
    SettingRow(
        title = "Rozsvítit při pohybu",
        note = if (unsupported) "Raspberry tuto volbu zatím nepodporuje" else "v${NBSP}noci na chvíli rozsvítí noční světlo",
        modifier = Modifier
            .clip(SettingRowShape)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = actions::setLightOnMotion),
        control = { Switch(checked = on, onCheckedChange = null, enabled = enabled) },
    )
}

// Nezlomitelná mezera mezi číslem a jednotkou jako u ostatních formátů.
private fun formatSeconds(value: Double?): String = if (value == null) NO_VALUE else "${value.roundToInt()}${NBSP}s"

internal fun Rgb.toColor(): Color = Color(r, g, b)
