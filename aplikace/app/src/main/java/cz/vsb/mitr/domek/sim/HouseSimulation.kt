package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.formatPiTime
import cz.vsb.mitr.domek.domain.DUSK_END_FACTOR
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.NIGHT_LIGHT_MAX_BRIGHTNESS
import cz.vsb.mitr.domek.domain.TargetSlot
import cz.vsb.mitr.domek.domain.activeTargetSlot
import cz.vsb.mitr.domek.domain.dayPartAt
import cz.vsb.mitr.domek.domain.dayStartsOf
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject

enum class SimEvent { MOTION, LIGHT, DAY_PART }

// Výchozí hodnoty Nastavení z docs/ble-rozhrani.md.
val SIM_DEFAULT_SETTINGS = HouseSettings(
    mode = ThermostatMode.AUTO,
    targetDay = 23.0,
    targetNight = 20.0,
    targetManual = 22.0,
    dayStart = "07:00",
    eveningStart = "19:00",
    nightStart = "22:00",
    luxThreshold = 50.0,
    nightLightSeconds = 30,
    nobodyHomeAfterMin = 30,
    nobodyHomeSetback = 3.0,
    lightOnMotion = true,
    lightColor = listOf(255, 255, 255),
    lightBrightness = 100,
)

// O kolik se smí vnitřní teplota vzdálit od cíle, než se zapne topení nebo chlazení; vypne je až cíl
// (docs/ble-rozhrani.md, Stav → Jak domek hodnoty určuje).
private const val HEATING_STARTS_BELOW = 0.3 // °C pod cílem
private const val COOLING_STARTS_ABOVE = 1.0 // °C nad cílem

// Konstanty modelu nechává spec (kap. 12) na implementaci.
private const val HEATING_RATE = 0.05 // °C za sekundu, stejně rychle se chladí

// Jak rychle se bez topení a chlazení vnitřní teplota blíží venkovní.
private const val DRIFT_PER_SECOND = 0.01

// Zahřáté čidlo (scénář 3) musí za svých 30 s dostat vnitřní teplotu o víc než 1 °C nad cíl, jinak se chladit nezačne.
// S +5 °C to nestihne, s +8 °C ano z každé chvíle cyklu topení.
internal const val HEAT_SENSOR_BOOST = 8.0 // °C
internal const val COVERED_LUX_LIMIT = 5.0
internal const val SENSOR_EFFECT_SECONDS = 30.0
private const val MOTION_HOLD_SECONDS = 3.0
private const val SECONDS_PER_MINUTE = 60.0
private const val NANOS_PER_SECOND = 1_000_000_000.0

// Časovač, který nikdy neběžel: jeho konec leží v minulosti.
private const val NEVER = Double.NEGATIVE_INFINITY

// Nastavení staršího programu barvu a jas světla nemá, domek pak svítí bíle na plný jas.
private val WHITE = listOf(255, 255, 255)
private const val FULL_BRIGHTNESS = 100

private fun lightOff(manual: Boolean) = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = manual)

// Stav nese čísla na dvě desetinná místa, aby se do JSON nedostal šum dvojkové aritmetiky.
private fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0

private data class Observed(val motion: Boolean, val light: LightState, val dayPart: DayPart)

// Deterministický model domku (spec 5.4): bez náhody, aby šla ukázka i testy opakovat. Časovače běží na vnitřním čase
// elapsed, ne na clock: příkaz cas hodiny jen přestaví, zatímco pohyb, světlo i čidla běží dál.
class HouseSimulation(
    start: LocalDateTime,
    initialInside: Double = 21.5,
    settings: HouseSettings = SIM_DEFAULT_SETTINGS,
) {
    init {
        // Prázdný zápis projde jen u platných začátků úseků, tedy podle stejných pravidel jako na Raspberry.
        require(applySettingsPatch(settings, JsonObject(emptyMap())) != null) {
            "Začátky úseků dne musí být platné a různé"
        }
    }

    var settings: HouseSettings = settings
        private set

    var clock: LocalDateTime = start
        private set

    // Porucha BME280 (meteo) zastaví termostat hned, aby už první Stav s poruchou netopil ani nechladil.
    var sensorFault = false
        set(value) {
            field = value
            refresh()
        }

    // t: sekundy od startu simulace.
    private var elapsed = 0.0
    private var inside = initialInside
    private var heating = false
    private var cooling = false
    private var dayPart = currentDayPart()

    // Ruční volba si pamatuje jen zapnuto (true) nebo vypnuto (false), null = světlo řídí automatika. Barvu a jas bere
    // vždy z Nastavení (docs/ble-rozhrani.md, Příkaz → Světlo).
    private var manualOn: Boolean? = null

    // Šero ve dne má hysterezi, proto si ho model pamatuje.
    private var dusk = false

    // Za poslední pohyb se na začátku bere start simulace.
    private var lastMotionAt = 0.0
    private var motionUntil = NEVER
    private var nightLightUntil = NEVER
    private var heatedUntil = NEVER
    private var coveredUntil = NEVER
    private var seen: Observed

    init {
        refresh()
        seen = observe()
    }

    // Události jsou rozdíl proti stavu na konci minulého advance (nebo konstruktoru), takže zachytí
    // i změny, které nastaly mezi kroky.
    fun advance(seconds: Double): Set<SimEvent> {
        require(seconds >= 0.0) { "Simulace se nedá vrátit zpět" }
        elapsed += seconds
        clock = clock.plusNanos((seconds * NANOS_PER_SECOND).roundToLong())
        // Teplota se posune s topením z minulého kroku, teprve pak se rozhodne znovu. Pohyb, „nikdo doma“
        // i světlo se odvozují z elapsed až při čtení, takže po posunu času už platí.
        stepInsideTemperature(seconds)
        refresh()
        return takeEvents()
    }

    fun status(): HouseStatus = HouseStatus(
        time = formatPiTime(clock),
        dayPartText = dayPart.json,
        outsideTemp = reportedByBme280 { outsideTemp() },
        humidity = reportedByBme280 { humidity() },
        pressure = reportedByBme280 { pressure() },
        lux = round2(lux()),
        insideTemp = round2(inside),
        target = target()?.let(::round2),
        heating = heating,
        cooling = cooling,
        mode = settings.mode,
        nobodyHome = nobodyHome,
        motion = motion,
        light = light,
        errors = if (sensorFault) listOf(METEO_FAULT) else emptyList(),
    )

    // false = zápis je neplatný a nic se nezměnilo.
    fun writeSettings(patch: JsonObject): Boolean {
        settings = applySettingsPatch(settings, patch) ?: return false
        // Vypnuté rozsvěcení pohybem zhasne noční světlo hned a nevrátí ho, ani kdyby se zase zapnulo.
        if (!lightOnMotion) nightLightUntil = NEVER
        refresh()
        return true
    }

    // false = příkaz je neplatný a nic se nezměnilo.
    fun command(json: JsonObject): Boolean {
        when (val parsed = parseCommand(json) ?: return false) {
            is HouseCommand.Light -> applyLightAction(parsed.action)
            is HouseCommand.LightLook -> applyLightLook(parsed)
            is HouseCommand.SetClock -> clock = parsed.time
        }
        refresh()
        return true
    }

    fun heatSensor() {
        heatedUntil = elapsed + SENSOR_EFFECT_SECONDS
    }

    fun coverLightSensor() {
        coveredUntil = elapsed + SENSOR_EFFECT_SECONDS
        // Osvětlení klesne hned, tak ať hned platí i šero.
        refresh()
    }

    fun triggerMotion() {
        lastMotionAt = elapsed
        motionUntil = elapsed + MOTION_HOLD_SECONDS
        // Noční světlo patří noci (spec 4.1); ve dne a večer pohyb světlo neřídí.
        if (dayPart == DayPart.NIGHT && lightOnMotion) nightLightUntil = elapsed + settings.nightLightSeconds
        refresh()
    }

    // Raspberry bez klíče nocni_svetlo_pri_pohybu se chová, jako by byl zapnutý.
    private val lightOnMotion: Boolean get() = settings.lightOnMotion != false

    private val motion: Boolean get() = elapsed < motionUntil

    private val nobodyHome: Boolean get() = elapsed - lastMotionAt >= settings.nobodyHomeAfterMin * SECONDS_PER_MINUTE

    // Přepočítá jen to, co si model pamatuje: úsek dne, rozhodnutí termostatu a šero (obojí s hysterezí). Zbytek se
    // odvozuje při čtení, takže změny mezi kroky (příkaz, zápis, pohyb) platí hned; události z nich ohlásí až příští
    // advance. Úsek jde první, protože na něm závisí cíl i osvětlení.
    private fun refresh() {
        updateDayPart()
        decideThermostat()
        updateDusk()
    }

    private fun currentDayPart(): DayPart = dayPartAt(clock.toLocalTime(), checkNotNull(dayStartsOf(settings)))

    // Ruční volba světla platí jen do změny úseku (docs/ble-rozhrani.md, Příkaz → Světlo).
    private fun updateDayPart() {
        val now = currentDayPart()
        if (now == dayPart) return
        dayPart = now
        manualOn = null
    }

    // Šero začne pod prahem a skončí až nad DUSK_END_FACTOR násobkem prahu, aby světlo kolem prahu neblikalo.
    private fun updateDusk() {
        val lux = lux()
        dusk = if (dusk) lux <= DUSK_END_FACTOR * settings.luxThreshold else lux < settings.luxThreshold
    }

    // Střed + amplituda · sin(2π · t / perioda).
    private fun wave(center: Double, amplitude: Double, periodSeconds: Double): Double =
        center + amplitude * sin(2 * PI * elapsed / periodSeconds)

    // Zahřáté čidlo hlásí o HEAT_SENSOR_BOOST víc celých 30 s, potom zase skutečnou teplotu.
    private fun outsideTemp(): Double {
        val boost = if (elapsed < heatedUntil) HEAT_SENSOR_BOOST else 0.0
        return wave(21.5, 0.4, 600.0) + boost
    }

    private fun humidity(): Double = wave(45.0, 2.0, 900.0)

    private fun pressure(): Double = wave(1013.2, 0.3, 1200.0)

    private fun lux(): Double {
        val natural = when (dayPart) {
            DayPart.DAY -> wave(300.0, 40.0, 300.0)
            DayPart.EVENING -> 20.0
            DayPart.NIGHT -> 0.5
        }
        return if (elapsed < coveredUntil) minOf(natural, COVERED_LUX_LIMIT) else natural
    }

    private inline fun reportedByBme280(value: () -> Double): Double? = if (sensorFault) null else round2(value())

    private fun stepInsideTemperature(seconds: Double) {
        // Bez venkovní teploty domek vnitřní teplotu nepočítá a drží poslední hodnotu
        // (docs/ble-rozhrani.md, Hodnoty v poli chyby).
        if (sensorFault) return
        inside += when {
            heating -> HEATING_RATE * seconds
            cooling -> -HEATING_RATE * seconds
            else -> (outsideTemp() - inside) * (1 - exp(-DRIFT_PER_SECOND * seconds))
        }
    }

    // null = termostat je vypnutý, žádný cíl neplatí.
    private fun target(): Double? {
        val base = when (activeTargetSlot(settings.mode, dayPart)) {
            TargetSlot.DAY -> settings.targetDay
            TargetSlot.NIGHT -> settings.targetNight
            TargetSlot.MANUAL -> settings.targetManual
            null -> return null
        }
        // Útlum „nikdo doma“ platí jen v Auto, ruční cíl se nesnižuje (docs/ble-rozhrani.md, Jak domek hodnoty určuje).
        return if (nobodyHome && settings.mode == ThermostatMode.AUTO) base - settings.nobodyHomeSetback else base
    }

    private fun decideThermostat() {
        val target = target()
        // Při poruše BME280 termostat stojí (docs/ble-rozhrani.md, Hodnoty v poli chyby).
        if (target == null || sensorFault) {
            heating = false
            cooling = false
            return
        }
        if (heating && inside >= target) heating = false
        if (cooling && inside <= target) cooling = false
        if (!heating && !cooling) {
            heating = inside < target - HEATING_STARTS_BELOW
            cooling = inside > target + COOLING_STARTS_ABOVE
        }
    }

    private val light: LightState
        get() = when (manualOn) {
            true -> shining(storedBrightness, manual = true)
            false -> lightOff(manual = true)
            null -> automaticBrightness()?.let { shining(it, manual = false) } ?: lightOff(manual = false)
        }

    // Automatika rozhoduje jen o tom, kdy světlo svítí; null = zhasnuto (docs/ble-rozhrani.md, Světlo v automatice).
    private fun automaticBrightness(): Int? = when (dayPart) {
        DayPart.DAY -> if (dusk) storedBrightness else null
        DayPart.EVENING -> storedBrightness
        DayPart.NIGHT -> if (elapsed < nightLightUntil) minOf(storedBrightness, NIGHT_LIGHT_MAX_BRIGHTNESS) else null
    }

    private val storedBrightness: Int get() = settings.lightBrightness ?: FULL_BRIGHTNESS

    // Svítí se vždy barvou z Nastavení, takže změna barva_svetla změní hned i světlo, které už svítí.
    private fun shining(brightness: Int, manual: Boolean) =
        LightState(on = true, color = settings.lightColor ?: WHITE, brightness = brightness, manual = manual)

    private fun applyLightAction(action: LightAction) {
        manualOn = when (action) {
            LightAction.ON -> true
            LightAction.OFF -> false
            // Opak podle zap, takže světlo s jasem 0 přepnutí zhasne.
            LightAction.TOGGLE -> !light.on
            LightAction.AUTO -> null
        }
    }

    // Objekt je zároveň změna Nastavení: co přišlo, se uloží, chybějící část zůstane, jak byla, a světlo se ručně zapne
    // (docs/ble-rozhrani.md, Příkaz → Světlo).
    private fun applyLightLook(look: HouseCommand.LightLook) {
        settings = settings.copy(
            lightColor = look.color?.let { listOf(it.r, it.g, it.b) } ?: settings.lightColor,
            lightBrightness = look.brightness ?: settings.lightBrightness,
        )
        manualOn = true
    }

    private fun observe() = Observed(motion, light, dayPart)

    private fun takeEvents(): Set<SimEvent> {
        val now = observe()
        val events = buildSet {
            if (now.motion && !seen.motion) add(SimEvent.MOTION)
            if (now.light != seen.light) add(SimEvent.LIGHT)
            if (now.dayPart != seen.dayPart) add(SimEvent.DAY_PART)
        }
        seen = now
        return events
    }
}
