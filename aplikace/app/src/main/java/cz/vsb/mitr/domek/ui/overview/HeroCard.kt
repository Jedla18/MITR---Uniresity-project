package cz.vsb.mitr.domek.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.domain.NO_VALUE
import cz.vsb.mitr.domek.domain.activeTargetSlot
import cz.vsb.mitr.domek.domain.dayPartTitle
import cz.vsb.mitr.domek.domain.formatDecimal
import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.domain.formatTemp
import cz.vsb.mitr.domek.domain.title
import cz.vsb.mitr.domek.ui.components.DomekCard
import cz.vsb.mitr.domek.ui.components.StepperSize
import cz.vsb.mitr.domek.ui.components.ThermostatActivity
import cz.vsb.mitr.domek.ui.components.ThermostatChip
import cz.vsb.mitr.domek.ui.components.ValueStepper
import cz.vsb.mitr.domek.ui.components.activity
import cz.vsb.mitr.domek.ui.components.icon
import cz.vsb.mitr.domek.ui.components.mockupText
import cz.vsb.mitr.domek.ui.components.tint
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.controlsEnabled
import cz.vsb.mitr.domek.viewmodel.number
import cz.vsb.mitr.domek.viewmodel.piTime
import cz.vsb.mitr.domek.viewmodel.status
import java.time.LocalDateTime

// Zaoblení DomekCard: tónování se kreslí vedle karty, takže musí mít stejný tvar.
private val CardShape = RoundedCornerShape(22.dp)

// Přechod karty z náhledu (#FFE1D5 → #FFF4EF) končí 62 % cesty od barvy kontejneru k ploše. Až do plochy by spodek karty
// zbělal a bílý pruh se stepperem by v něm zmizel.
private const val TINT_FADE = 0.62f

// Okraj karty z náhledu (#FFD2C2) je o osminu blíž k barvě topení než kontejner.
private const val TINT_BORDER_MIX = 0.12f

// Štítek má v náhledu odsazení 10 a 4 dp a okraj 1 dp, který se do rozměru počítá. Tady se okraj kreslí zvenku a rozměr
// nezvětšuje, proto je odsazení o okraj větší.
private val TagPaddingHorizontal = 11.dp
private val TagPaddingVertical = 5.dp

// Výška velkého krokového tlačítka (ValueStepper LARGE) při výchozím písmu.
private val LargeStepperHeight = 68.dp

private val BigTemperatureSpacing = (-1.5).sp

// Velikost velké teploty: obvyklá a menší z náhledu „Na šířku“ (.cmp), kde je číslo 46 px, jednotka 20 px a menší odsazení nad
// číslem a pod ním. Jednotka je vyzvednutá tak, aby její horní okraj ladil s horním okrajem číslic (náhled: top −24 px, v menší
// verzi −18 px). Posun se kreslí jen vrstvou: s offset by se posunulo i účaří a řádek by o 24 dp povyrostl.
private class TemperatureSize(val number: TextUnit, val unit: TextUnit, val unitRaise: Dp, val above: Dp, val below: Dp)

private val RegularTemperature = TemperatureSize(60.sp, 26.sp, unitRaise = 24.dp, above = 10.dp, below = 14.dp)
private val CompactTemperature = TemperatureSize(46.sp, 20.sp, unitRaise = 18.dp, above = 6.dp, below = 10.dp)

// Karta termostatu (spec 6.1, bod 3): denní doba, vnitřní teplota, štítek stavu a −/+ platného cíle.
@Composable
fun HeroCard(state: HouseUiState, actions: HouseActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val status = state.status ?: return
    val activity = status.activity()
    val tint = tintOf(activity)
    val temperatureSize = if (compact) CompactTemperature else RegularTemperature
    // Nádech topení nebo chlazení se kreslí zvenku a karta je průhledná, aby přechod pokryl celou kartu včetně odsazení.
    DomekCard(
        modifier = if (tint == null) modifier else modifier.tinted(tint),
        container = if (tint == null) MaterialTheme.colorScheme.surface else Color.Transparent,
    ) {
        TopRow(status, state.piTime)
        TemperatureRow(status.insideTemp, activity, temperatureSize)
        val target = status.target
        if (status.nobodyHome && target != null) {
            // Raspberry snižuje cíl jen v Auto, v ručním režimu štítek žádné snížení netvrdí.
            NobodyHomeTag(state.settings?.nobodyHomeSetback?.takeIf { status.mode == ThermostatMode.AUTO }, target)
        }
        TargetSection(state, actions, status)
    }
}

// Barva a kontejner nádechu, nebo null, když karta zůstává bílá.
private class Tint(val accent: Color, val container: Color)

@Composable
private fun tintOf(activity: ThermostatActivity): Tint? {
    val colors = LocalDomekColors.current
    return when (activity) {
        ThermostatActivity.HEATING -> Tint(colors.heat, colors.heatContainer)
        ThermostatActivity.COOLING -> Tint(colors.cool, colors.coolContainer)
        ThermostatActivity.IDLE, ThermostatActivity.OFF -> null
    }
}

@Composable
private fun Modifier.tinted(tint: Tint): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val gradient = Brush.verticalGradient(listOf(tint.container, lerp(tint.container, surface, TINT_FADE)))
    return background(gradient, CardShape).border(1.dp, lerp(tint.container, tint.accent, TINT_BORDER_MIX), CardShape)
}

@Composable
private fun TopRow(status: HouseStatus, piTime: LocalDateTime?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayPartLabel(status, piTime)
        HeroTag("Režim ${status.mode.label}")
    }
}

// Čas je čas Raspberry. Když ho Raspberry pošle nečitelně, zůstane jen název úseku, bez úseku jen čas. Ikonu má jen
// známý úsek, nový se ukáže, jak přišel.
@Composable
private fun DayPartLabel(status: HouseStatus, piTime: LocalDateTime?) {
    val part = status.dayPart
    val text = listOfNotNull(status.dayPartTitle, piTime?.let { formatHhMm(it.toLocalTime()) }).joinToString(" · ")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (part != null) Icon(part.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = part.tint())
        Text(
            text = text.ifEmpty { NO_VALUE },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = mockupText(13.5f, FontWeight.Medium),
        )
    }
}

// Štítek s obrysem na bílém podkladu (.tag): režim termostatu a „nikdo doma“.
@Composable
private fun HeroTag(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        modifier = modifier
            .background(scheme.surface.copy(alpha = 0.8f), shape)
            .border(1.dp, scheme.outline, shape)
            .padding(horizontal = TagPaddingHorizontal, vertical = TagPaddingVertical),
        color = scheme.onSurfaceVariant,
        style = mockupText(12f, FontWeight.Medium),
    )
}

// Při velkém písmu se štítek stavu zalomí pod teplotu místo toho, aby ji stlačil.
@Composable
private fun TemperatureRow(insideTemp: Double?, activity: ThermostatActivity, size: TemperatureSize) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = size.above, bottom = size.below),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        // Popisek, číslo a jednotka se čtečce obrazovky čtou jako jedna hodnota.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = if (insideTemp == null) "Uvnitř · chyba čidla" else "Uvnitř",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = mockupText(12.5f),
            )
            BigTemperature(insideTemp, size)
        }
        ThermostatChip(activity)
    }
}

// Číslo a jednotka jsou dva texty na společné účaří, jednotka menší a vyzvednutá jako horní index. Řádek čísla má výšku 1 em
// jako v náhledu (line-height: 1). Compose řádek pod výšku písma nezkrátí, proto se zkrátí box textu a text se v něm
// vycentruje; účaří se přitom posune s ním.
@Composable
private fun BigTemperature(value: Double?, size: TemperatureSize) {
    val lineHeight = with(LocalDensity.current) { size.number.toDp() }
    Row {
        Text(
            text = if (value == null) formatTemp(null) else formatDecimal(value, 1),
            modifier = Modifier
                .alignByBaseline()
                .height(lineHeight)
                .wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            style = displayStyle(size.number, BigTemperatureSpacing),
            maxLines = 1,
        )
        if (value != null) {
            Text(
                text = "°C",
                modifier = Modifier
                    .alignByBaseline()
                    .padding(start = 4.dp)
                    .graphicsLayer { translationY = -size.unitRaise.toPx() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = displayStyle(size.unit, 0.sp),
                maxLines = 1,
            )
        }
    }
}

// Písmo čísla bez řádkování z Material 3 (64 sp), které by jednotku z menšího písma natáhlo na výšku.
@Composable
private fun displayStyle(size: TextUnit, letterSpacing: TextUnit): TextStyle =
    MaterialTheme.typography.displayLarge.copy(fontSize = size, lineHeight = TextUnit.Unspecified, letterSpacing = letterSpacing)

// Štítek říká, o kolik se cíl při „nikdo doma“ snižuje (z Nastavení) a jaký cíl teď opravdu platí (ze Stavu). Bez Nastavení
// se ukáže jen ten, který platí.
@Composable
private fun NobodyHomeTag(setback: Double?, target: Double) {
    val change = setback?.let { -it }
    val text = if (change == null) {
        "Nikdo doma · platí ${formatTemp(target)}"
    } else {
        "Nikdo doma · cíl ${formatTemp(change)}, platí ${formatTemp(target)}"
    }
    HeroTag(text, Modifier.padding(bottom = 10.dp))
}

// Krok −/+ mění cíl, který právě platí (spec 6.1). Ve vypnutém režimu žádný neplatí, tam je tlačítko pro zapnutí.
@Composable
private fun TargetSection(state: HouseUiState, actions: HouseActions, status: HouseStatus) {
    val slot = activeTargetSlot(status.mode, status.dayPart)
    if (slot == null) {
        TurnOnBar(state, actions)
        return
    }
    // Rozpracovaná hodnota a Nastavení mají přednost, Stav slouží, dokud se Nastavení nenačte.
    val shown = state.number(slot.key) ?: status.target
    ValueStepper(
        value = shown ?: 0.0,
        key = slot.key,
        // Bez hodnoty se ukáže „—“ a tlačítka jsou zašedlá.
        format = { formatTemp(if (shown == null) null else it) },
        onChange = { actions.changeSetting(slot.key, it) },
        enabled = state.controlsEnabled && shown != null,
        pending = slot.key in state.pendingKeys,
        size = StepperSize.LARGE,
        label = slot.label,
    )
}

// Bílá lišta jako u velkého krokového tlačítka a stejně vysoká, aby karta při přepnutí režimu neposkočila.
@Composable
private fun TurnOnBar(state: HouseUiState, actions: HouseActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LargeStepperHeight)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Termostat je vypnutý",
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = mockupText(14f, FontWeight.Medium),
        )
        Button(
            onClick = { actions.setMode(ThermostatMode.AUTO) },
            modifier = Modifier.height(48.dp),
            // Zápis režimu je ještě na cestě, další klepnutí by poslalo totéž znovu.
            enabled = state.controlsEnabled && SettingKey.MODE !in state.pendingKeys,
            // Užší odsazení než výchozí, aby se věta vedle tlačítka vešla na jeden řádek.
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text("Zapnout (Auto)")
        }
    }
}
