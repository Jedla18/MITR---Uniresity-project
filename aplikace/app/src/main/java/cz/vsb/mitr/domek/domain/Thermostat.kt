package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode

enum class TargetSlot(val key: SettingKey, val label: String) {
    DAY(SettingKey.TARGET_DAY, "Denní cíl"),
    NIGHT(SettingKey.TARGET_NIGHT, "Noční cíl"),
    MANUAL(SettingKey.TARGET_MANUAL, "Ruční cíl"),
}

// Spec 6.1: v Auto platí ve dne a večer denní cíl, v noci noční; ve Vypnuto žádný cíl neplatí. Raspberry drží noční cíl
// jen v noci, takže u neznámého úseku platí denní (docs/ble-rozhrani.md, Cíl).
fun activeTargetSlot(mode: ThermostatMode, dayPart: DayPart?): TargetSlot? = when (mode) {
    ThermostatMode.OFF -> null
    ThermostatMode.MANUAL -> TargetSlot.MANUAL
    ThermostatMode.AUTO -> if (dayPart == DayPart.NIGHT) TargetSlot.NIGHT else TargetSlot.DAY
}

// Smlouva Raspberry (docs/ble-rozhrani.md, Topení a chlazení): topení naskočí, když je uvnitř pod cílem o víc než 0,3 °C,
// chlazení, když je nad cílem o víc než 1,0 °C.
private const val HEATING_MARGIN = 0.3
private const val COOLING_MARGIN = 1.0

data class ThermostatThresholds(val heatBelow: Double, val coolAbove: Double)

// Ve Vypnuto je cíl null: netopí se ani nechladí, takže prahy nejsou.
fun thermostatThresholds(target: Double?): ThermostatThresholds? =
    target?.let { ThermostatThresholds(heatBelow = it - HEATING_MARGIN, coolAbove = it + COOLING_MARGIN) }

// Topení po naskočení topí až do cíle (a chlazení chladí až na cíl), takže práh říká, kdy naskočí, ne kdy topí.
fun thresholdsText(thresholds: ThermostatThresholds): String =
    "Topení naskočí pod ${formatTemp(thresholds.heatBelow)}, chlazení nad ${formatTemp(thresholds.coolAbove)}"
