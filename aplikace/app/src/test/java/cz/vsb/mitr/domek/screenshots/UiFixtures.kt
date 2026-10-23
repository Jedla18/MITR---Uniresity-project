package cz.vsb.mitr.domek.screenshots

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.parsePiTime
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.ui.SystemActions
import cz.vsb.mitr.domek.viewmodel.ChartSample
import cz.vsb.mitr.domek.viewmodel.ChartWindow
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.sin

// Společná data všech screenshotů: jeden příběh z náhledů (topí, uvnitř 22,4 °C, cíl 23,0 °C, graf za 5 min),
// aby se každá obrazovka dala porovnat s náhledem.

object NoActions : HouseActions {
    override fun selectSource(kind: SourceKind) {}
    override fun changeSetting(key: SettingKey, value: Double) {}
    override fun setMode(mode: ThermostatMode) {}
    override fun setLightOnMotion(enabled: Boolean) {}
    override fun setDayStart(part: DayPart, time: LocalTime) {}
    override fun setDayStartNow(part: DayPart) {}
    override fun restoreDayStarts() {}
    override fun lightAction(action: LightAction) {}
    override fun setLightColor(color: Rgb) {}
    override fun setLightBrightness(percent: Int) {}
    override fun syncClock() {}
    override fun setChartWindow(window: ChartWindow) {}
    override fun chooseDevice(address: String) {}
    override fun disconnect() {}
    override fun connect() {}
    override fun retryConnection() {}
    override fun simulation(action: SimulationAction) {}
}

val NoSystem = SystemActions({}, {}, {}, {}, false)

// Čas telefonu na náhledech (10:02:15).
val DEMO_WALL: LocalDateTime = LocalDateTime.of(2026, 10, 20, 10, 2, 15)

fun demoStatus(): HouseStatus = EXAMPLE.copy(
    time = "2026-10-20T10:00:03",
    insideTemp = 22.4,
    outsideTemp = 21.8,
    humidity = 45.0,
    pressure = 1013.2,
    lux = 320.0,
    target = 23.0,
    heating = true,
    light = LightState(true, listOf(100, 110, 255), 60, true),
)

private const val DEMO_SAMPLES = 300

// Graf z náhledu: 300 vzorků po 1 s, poslední v endMs. Sekunda příběhu 300 je poslední vzorek, takže graf končí
// hodnotami z demoStatus (uvnitř 22,4 °C, cíl 23 °C, topí).
fun demoChart(endMs: Long, endWall: LocalDateTime): List<ChartSample> = (1..DEMO_SAMPLES).map { second ->
    val secondsBeforeEnd = (DEMO_SAMPLES - second).toLong()
    ChartSample(
        atMs = endMs - secondsBeforeEnd * 1000,
        wallTime = endWall.minusSeconds(secondsBeforeEnd),
        inside = demoInside(second),
        outside = 21.8 + 0.05 * sin(2 * PI * second / DEMO_SAMPLES),
        target = demoTarget(second),
        heating = second in 60 until 120 || second >= 270,
        cooling = second in 180 until 260,
    )
}

private fun demoInside(second: Int): Double = when {
    second < 60 -> 22.0
    second < 120 -> ramp(second, 60, 120, 22.0, 25.0)
    second < 180 -> 25.0
    second < 260 -> ramp(second, 180, 260, 25.0, 21.0)
    second < 270 -> 21.0
    else -> ramp(second, 270, 300, 21.0, 22.4)
}

private fun demoTarget(second: Int): Double = when {
    second < 60 -> 22.0
    second < 180 -> 25.0
    second < 270 -> 21.0
    else -> 23.0
}

private fun ramp(second: Int, from: Int, to: Int, start: Double, end: Double): Double =
    start + (end - start) * (second - from) / (to - from)

// Ruční modrá na 60 % svítí uloženou barvou a jasem z Nastavení, jako každé světlo (docs/ble-rozhrani.md, Světlo v automatice).
fun demoState(): HouseUiState = HouseUiState(
    connection = ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, -61),
    received = ReceivedStatus(demoStatus(), 300_000, DEMO_WALL),
    settings = DEFAULTS.copy(lightColor = listOf(100, 110, 255), lightBrightness = 60),
    chart = demoChart(300_000, DEMO_WALL),
    lastMotionAt = DEMO_WALL.minusSeconds(40),
    nowMs = 300_400,
    phoneNow = DEMO_WALL,
)

// Stejný příběh s upraveným Stavem z Raspberry, aby se test nemusel starat o zbytek stavu.
fun HouseUiState.withStatus(change: (HouseStatus) -> HouseStatus): HouseUiState =
    copy(received = checkNotNull(received) { "Stav bez dat" }.let { it.copy(status = change(it.status)) })

// Telefon se v okamžiku přijetí Stavu liší od hodin Raspberry jen o 12 s, takže na Přehledu není upozornění na hodiny
// (to se ukazuje od 2 min). V demoState jdou hodiny Raspberry o 2 min 12 s pozadu jako na náhledu Nastavení, ale náhled
// Přehledu žádné upozornění nemá. Volá se až po úpravě času ve Stavu.
fun HouseUiState.withClocksInSync(): HouseUiState {
    val received = received ?: return this
    val piTime = received.status.time?.let(::parsePiTime) ?: return this
    return copy(received = received.copy(receivedAtWall = piTime.plusSeconds(12)))
}

fun demoStateWithoutClockWarning(): HouseUiState = demoState().withClocksInSync()
