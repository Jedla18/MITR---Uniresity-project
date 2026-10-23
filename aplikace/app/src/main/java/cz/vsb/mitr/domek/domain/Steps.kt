package cz.vsb.mitr.domek.domain

import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.SettingKind
import kotlin.math.roundToInt

fun stepValue(key: SettingKey, current: Double, up: Boolean): Double {
    require(key.kind == SettingKind.DECIMAL || key.kind == SettingKind.WHOLE) { "Nastavení ${key.json} nemá číselnou hodnotu" }
    val size = when (key) {
        SettingKey.TARGET_DAY, SettingKey.TARGET_NIGHT, SettingKey.TARGET_MANUAL, SettingKey.NOBODY_HOME_SETBACK -> 0.5
        SettingKey.NIGHT_LIGHT_SECONDS -> 5.0
        SettingKey.NOBODY_HOME_AFTER -> 1.0
        SettingKey.LUX_THRESHOLD -> luxStepSize(current, up)
        else -> error("Pro nastavení ${key.json} chybí velikost kroku")
    }
    val moved = if (up) current + size else current - size
    // Zaokrouhlení odstraní šum dvojkové aritmetiky (16,4 − 0,5 by dalo 15,899999999999999). Draft se pak
    // porovnává s Raspberry podle textu čísla, takže hodnota musí být přesně to desetinné číslo, které Raspberry zná.
    val rounded = if (key.kind == SettingKind.WHOLE) moved.roundToInt().toDouble() else (moved * 10).roundToInt() / 10.0
    return rounded.coerceIn(key.min, key.max)
}

// Spec 6.3: pod 10 je krok 1, pod 100 je 5, pod 1000 je 50, jinak 500. Dolů platí krok pásma pod hodnotou,
// takže 100 vede na 95 a 1000 na 950 a cesta nahoru i dolů prochází stejnými hodnotami.
private fun luxStepSize(current: Double, up: Boolean): Double {
    fun upTo(limit: Double) = if (up) current < limit else current <= limit
    return when {
        upTo(10.0) -> 1.0
        upTo(100.0) -> 5.0
        upTo(1000.0) -> 50.0
        else -> 500.0
    }
}
