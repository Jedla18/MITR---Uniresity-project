package cz.vsb.mitr.domek.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.ModeStandby
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.ui.theme.LocalDomekColors

// Co termostat právě dělá. Vypnutý termostat netopí ani nechladí, i kdyby Stav ještě nesl příznak topení.
internal enum class ThermostatActivity { HEATING, COOLING, IDLE, OFF }

internal fun HouseStatus.activity(): ThermostatActivity = when {
    mode == ThermostatMode.OFF -> ThermostatActivity.OFF
    heating -> ThermostatActivity.HEATING
    cooling -> ThermostatActivity.COOLING
    else -> ThermostatActivity.IDLE
}

// Štítek stavu termostatu, stejný na kartě termostatu na Přehledu a v souhrnu na obrazovce Termostat.
@Composable
internal fun ThermostatChip(activity: ThermostatActivity, modifier: Modifier = Modifier) {
    val colors = LocalDomekColors.current
    val container = when (activity) {
        ThermostatActivity.HEATING -> colors.heat
        ThermostatActivity.COOLING -> colors.cool
        ThermostatActivity.IDLE, ThermostatActivity.OFF -> colors.neutralContainer
    }
    val content = when (activity) {
        ThermostatActivity.HEATING, ThermostatActivity.COOLING -> Color.White
        ThermostatActivity.IDLE, ThermostatActivity.OFF -> colors.onNeutralContainer
    }
    val (icon, text) = when (activity) {
        ThermostatActivity.HEATING -> Icons.Rounded.LocalFireDepartment to "Topí"
        ThermostatActivity.COOLING -> Icons.Rounded.AcUnit to "Chladí"
        // Nezlomitelná mezera za „V“, aby jednopísmenná předložka nezůstala na konci řádku.
        ThermostatActivity.IDLE -> Icons.Rounded.ModeStandby to "V${NBSP}klidu"
        ThermostatActivity.OFF -> Icons.Rounded.PowerSettingsNew to "Vypnuto"
    }
    Row(
        modifier = modifier
            .background(container, CircleShape)
            .padding(start = 10.dp, top = 7.dp, end = 14.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        Text(text = text, color = content, style = mockupText(14f, FontWeight.Medium))
    }
}
