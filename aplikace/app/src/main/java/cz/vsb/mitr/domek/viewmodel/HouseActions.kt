package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import java.time.LocalTime

// Všechno, co obrazovky od ViewModelu chtějí. Obrazovky znají jen toto rozhraní, takže v náhledech a testech UI
// stačí podstrčit prázdnou implementaci.
interface HouseActions {
    fun selectSource(kind: SourceKind)

    // Zápisy Nastavení a příkazy
    fun changeSetting(key: SettingKey, value: Double)
    fun setMode(mode: ThermostatMode)
    fun setLightOnMotion(enabled: Boolean)
    fun setDayStart(part: DayPart, time: LocalTime)
    fun setDayStartNow(part: DayPart)
    fun restoreDayStarts()
    fun lightAction(action: LightAction)
    fun setLightColor(color: Rgb)
    fun setLightBrightness(percent: Int)
    fun syncClock()

    fun setChartWindow(window: ChartWindow)

    // Spojení s Raspberry a ovládání simulace
    fun chooseDevice(address: String)
    fun disconnect()
    fun connect()
    fun retryConnection()
    fun simulation(action: SimulationAction)
}
