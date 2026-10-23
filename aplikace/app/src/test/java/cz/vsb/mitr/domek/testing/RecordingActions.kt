package cz.vsb.mitr.domek.testing

import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.screenshots.NoActions
import cz.vsb.mitr.domek.ui.SystemActions
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import cz.vsb.mitr.domek.viewmodel.HouseActions

// Akce, které si testy UI pamatují, aby ověřily, co klepnutí na tlačítko zavolalo. Ostatní jsou prázdné jako v NoActions.
class RecordingActions : HouseActions by NoActions {
    val calls = mutableListOf<String>()

    override fun selectSource(kind: SourceKind) {
        calls += "selectSource($kind)"
    }

    override fun changeSetting(key: SettingKey, value: Double) {
        calls += "changeSetting(${key.json}, $value)"
    }

    override fun setMode(mode: ThermostatMode) {
        calls += "setMode($mode)"
    }

    override fun setLightOnMotion(enabled: Boolean) {
        calls += "setLightOnMotion($enabled)"
    }

    override fun lightAction(action: LightAction) {
        calls += "lightAction($action)"
    }

    override fun setLightColor(color: Rgb) {
        calls += "setLightColor(${color.r}, ${color.g}, ${color.b})"
    }

    override fun setLightBrightness(percent: Int) {
        calls += "setLightBrightness($percent)"
    }

    override fun syncClock() {
        calls += "syncClock"
    }

    override fun setChartWindow(window: ChartWindow) {
        calls += "setChartWindow($window)"
    }

    override fun simulation(action: SimulationAction) {
        calls += "simulation($action)"
    }

    override fun chooseDevice(address: String) {
        calls += "chooseDevice($address)"
    }

    override fun disconnect() {
        calls += "disconnect"
    }

    override fun connect() {
        calls += "connect"
    }

    override fun retryConnection() {
        calls += "retryConnection"
    }
}

// Totéž pro SystemActions: oprávnění a systémová nastavení, která jinak umí jen aktivita.
class RecordingSystem {
    val calls = mutableListOf<String>()

    fun actions(permissionsPermanentlyDenied: Boolean = false) = SystemActions(
        requestPermissions = { calls += "requestPermissions" },
        enableBluetooth = { calls += "enableBluetooth" },
        openAppSettings = { calls += "openAppSettings" },
        openLocationSettings = { calls += "openLocationSettings" },
        permissionsPermanentlyDenied = permissionsPermanentlyDenied,
    )
}
