package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.viewmodel.LogCategory.CONNECTION
import cz.vsb.mitr.domek.viewmodel.LogCategory.LIGHT
import cz.vsb.mitr.domek.viewmodel.LogCategory.MOTION
import cz.vsb.mitr.domek.viewmodel.LogCategory.OTHER
import cz.vsb.mitr.domek.viewmodel.LogCategory.THERMOSTAT
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

// Události Deníku podle tabulky ve spec 2.3 (docs/navrh/2026-10-23-denik-a-vylepseni-design.md).
class LogEventsTest {
    // Klidný den v Auto: netopí, nechladí, světlo je zhasnuté v automatice a nikdo nechybí.
    private val calm = EXAMPLE.copy(heating = false, insideTemp = 22.6, target = 23.0)

    private val off = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = false)

    private fun lit(r: Int, g: Int, b: Int, brightness: Int, manual: Boolean) =
        LightState(on = true, color = listOf(r, g, b), brightness = brightness, manual = manual)

    private fun events(previous: HouseStatus, current: HouseStatus) = statusEvents(previous, current, DEFAULTS)

    // Termostat

    @Test
    fun heatingStartAndStopShowTheNewInsideTemperatureAndTarget() {
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.HEATING, "Začalo topit", "Uvnitř 22,6${NBSP}°C, cíl 23,0${NBSP}°C")),
            events(calm.copy(insideTemp = 22.8), calm.copy(heating = true)),
        )
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.HEATING, "Přestalo topit", "Uvnitř 23,0${NBSP}°C, cíl 23,0${NBSP}°C")),
            events(calm.copy(heating = true), calm.copy(insideTemp = 23.0)),
        )
    }

    @Test
    fun coolingStartAndStop() {
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.COOLING, "Začalo chladit", "Uvnitř 24,1${NBSP}°C, cíl 23,0${NBSP}°C")),
            events(calm.copy(insideTemp = 24.1), calm.copy(insideTemp = 24.1, cooling = true)),
        )
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.COOLING, "Přestalo chladit", "Uvnitř 23,0${NBSP}°C, cíl 23,0${NBSP}°C")),
            events(calm.copy(insideTemp = 23.1, cooling = true), calm.copy(insideTemp = 23.0)),
        )
    }

    // V Auto říká podrobnost, který cíl z Nastavení teď platí (noční jen v noci).
    @Test
    fun newDayPartInAutoSaysWhichTargetApplies() {
        val evening = calm.copy(dayPartText = "vecer")
        val night = calm.copy(dayPartText = "noc", target = 20.0)
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.EVENING, "Začal večer", "platí denní cíl 23,0${NBSP}°C")),
            events(calm, evening),
        )
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.NIGHT, "Začala noc", "platí noční cíl 20,0${NBSP}°C")),
            events(evening, night),
        )
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.DAY, "Začal den", "platí denní cíl 23,0${NBSP}°C")),
            events(night, calm),
        )
    }

    // Cíl ve Stavu je snížený o „nikdo doma“, podrobnost ale jmenuje cíl, jak je nastavený.
    @Test
    fun theTargetNamedAtANewDayPartIsTheOneFromTheSettings() {
        val evening = calm.copy(dayPartText = "vecer", nobodyHome = true, target = 20.0)
        assertEquals(
            "platí noční cíl 19,5${NBSP}°C",
            statusEvents(evening, evening.copy(dayPartText = "noc", target = 16.5), DEFAULTS.copy(targetNight = 19.5)).single().detail,
        )
    }

    @Test
    fun withoutSettingsTheNewDayPartNamesTheTargetFromTheStatus() {
        val evening = calm.copy(dayPartText = "vecer")
        assertEquals(
            "platí noční cíl 19,5${NBSP}°C",
            statusEvents(evening, evening.copy(dayPartText = "noc", target = 19.5), settings = null).single().detail,
        )
    }

    @Test
    fun newDayPartOutsideAutoHasNoDetail() {
        listOf(calm.copy(mode = ThermostatMode.MANUAL, target = 22.0), calm.copy(mode = ThermostatMode.OFF, target = null)).forEach { day ->
            assertEquals(
                listOf(LogEvent(THERMOSTAT, LogKind.EVENING, "Začal večer", "")),
                events(day, day.copy(dayPartText = "vecer")),
            )
        }
    }

    // Nový úsek se ukáže tak, jak ho Raspberry poslalo. Noční cíl drží Raspberry jen v noci, takže platí denní.
    @Test
    fun unknownDayPartIsShownAsItCame() {
        assertEquals(
            listOf(LogEvent(THERMOSTAT, LogKind.OTHER_DAY_PART, "Úsek dne: svitani", "platí denní cíl 23,0${NBSP}°C")),
            events(calm, calm.copy(dayPartText = "svitani")),
        )
    }

    @Test
    fun aMissingDayPartStartsNothing() {
        assertEquals(emptyList<LogEvent>(), events(calm, calm.copy(dayPartText = null)))
    }

    // Změnu cíle vysvětlí jiné události a režim zapisuje Nastavení, proto ze Stavu nejsou (spec 2.3, Pravidla).
    @Test
    fun targetAndModeChangesInTheStatusAreNotEvents() {
        assertEquals(emptyList<LogEvent>(), events(calm, calm.copy(target = 25.0, mode = ThermostatMode.MANUAL)))
        assertEquals(emptyList<LogEvent>(), events(calm, calm.copy(mode = ThermostatMode.OFF, target = null)))
    }

    @Test
    fun measuredValuesAndTimeAreNotEvents() {
        val later =
            calm.copy(time = "2026-10-20T10:00:01", insideTemp = 22.7, outsideTemp = 18.0, humidity = 50.0, pressure = 990.0, lux = 4.0)
        assertEquals(emptyList<LogEvent>(), events(calm, later))
    }

    // Světlo

    @Test
    fun lightSwitchedOnByTheAutomationSaysWhyAndHowBright() {
        fun switchedOn(dayPart: String, light: LightState) =
            events(calm.copy(dayPartText = dayPart), calm.copy(dayPartText = dayPart, light = light)).single()

        assertEquals(
            LogEvent(LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "auto · venku je šero · 100${NBSP}%", listOf(Rgb(255, 255, 255))),
            switchedOn("den", lit(255, 255, 255, 100, manual = false)),
        )
        assertEquals(
            LogEvent(LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "auto · večerní světlo · 80${NBSP}%", listOf(Rgb(255, 180, 120))),
            switchedOn("vecer", lit(255, 180, 120, 80, manual = false)),
        )
        assertEquals(
            LogEvent(LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "auto · noční světlo · 10${NBSP}%", listOf(Rgb(255, 255, 255))),
            switchedOn("noc", lit(255, 255, 255, 10, manual = false)),
        )
        // Bez známého úseku dne se důvod automatiky neví.
        assertEquals(
            LogEvent(LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "auto · 100${NBSP}%", listOf(Rgb(255, 255, 255))),
            switchedOn("svitani", lit(255, 255, 255, 100, manual = false)),
        )
    }

    @Test
    fun lightSwitchedOnByHand() {
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Světlo se rozsvítilo", "ručně · 60${NBSP}%", listOf(Rgb(100, 110, 255)))),
            events(calm, calm.copy(light = lit(100, 110, 255, 60, manual = true))),
        )
    }

    @Test
    fun lightSwitchedOffSaysWhetherByHandOrByTheAutomation() {
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Světlo zhaslo", "auto")),
            events(calm.copy(light = lit(255, 255, 255, 100, manual = false)), calm),
        )
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Světlo zhaslo", "ručně")),
            events(calm.copy(light = lit(255, 255, 255, 100, manual = true)), calm.copy(light = off.copy(manual = true))),
        )
    }

    // Tečka ukazuje barvu světla, jen když svítí.
    @Test
    fun manualChoiceWithoutSwitchingOnOrOff() {
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Ruční volba světla", "", listOf(Rgb(255, 0, 0)))),
            events(calm.copy(light = lit(255, 0, 0, 50, manual = false)), calm.copy(light = lit(255, 0, 0, 50, manual = true))),
        )
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Světlo zpět v${NBSP}automatice", "", listOf(Rgb(255, 255, 255)))),
            events(calm.copy(light = lit(255, 0, 0, 50, manual = true)), calm.copy(light = lit(255, 255, 255, 100, manual = false))),
        )
        assertEquals(
            listOf(LogEvent(LIGHT, LogKind.LIGHT, "Světlo zpět v${NBSP}automatice", "")),
            events(calm.copy(light = off.copy(manual = true)), calm),
        )
    }

    // Zapnutí rukou mění zároveň `zap` i `rucne`; zapíše se jen rozsvícení.
    @Test
    fun switchingOnByHandIsOnlyTheSwitchingOn() {
        assertEquals(
            listOf("Světlo se rozsvítilo"),
            events(calm, calm.copy(light = lit(255, 255, 255, 100, manual = true))).map { it.title },
        )
    }

    // Barvu a jas zapisuje Nastavení, ze Stavu by byly dvakrát.
    @Test
    fun newColorOrBrightnessOfAShiningLightIsNoStatusEvent() {
        assertEquals(
            emptyList<LogEvent>(),
            events(calm.copy(light = lit(255, 255, 255, 100, manual = true)), calm.copy(light = lit(255, 0, 0, 40, manual = true))),
        )
    }

    // Pohyb a nikdo doma

    @Test
    fun motionStartIsAnEventItsEndIsNot() {
        assertEquals(listOf(LogEvent(MOTION, LogKind.MOTION, "Pohyb")), events(calm, calm.copy(motion = true)))
        assertEquals(emptyList<LogEvent>(), events(calm.copy(motion = true), calm))
    }

    @Test
    fun nobodyHomeSaysByHowMuchTheSettingsLowerTheTarget() {
        assertEquals(
            listOf(LogEvent(MOTION, LogKind.NOBODY_HOME, "Nikdo doma", "cíl snížen o${NBSP}2,5${NBSP}°C")),
            statusEvents(calm, calm.copy(nobodyHome = true, target = 20.5), DEFAULTS.copy(nobodyHomeSetback = 2.5)),
        )
    }

    // Vypnutý termostat žádný cíl nemá a bez Nastavení se snížení neví.
    @Test
    fun nobodyHomeWithoutATargetOrSettingsHasNoDetail() {
        val off = calm.copy(mode = ThermostatMode.OFF, target = null)
        assertEquals(listOf(LogEvent(MOTION, LogKind.NOBODY_HOME, "Nikdo doma")), events(off, off.copy(nobodyHome = true)))
        assertEquals(
            listOf(LogEvent(MOTION, LogKind.NOBODY_HOME, "Nikdo doma")),
            statusEvents(calm, calm.copy(nobodyHome = true, target = 20.0), settings = null),
        )
    }

    @Test
    fun nobodyHomeInManualModeDoesNotLowerTheTarget() {
        val manual = calm.copy(mode = ThermostatMode.MANUAL, target = 22.0)
        assertEquals(
            listOf(LogEvent(MOTION, LogKind.NOBODY_HOME, "Nikdo doma", "v${NBSP}ručním režimu se cíl nesnižuje")),
            statusEvents(manual, manual.copy(nobodyHome = true), DEFAULTS),
        )
    }

    @Test
    fun somebodyHomeAgain() {
        assertEquals(
            listOf(LogEvent(MOTION, LogKind.SOMEBODY_HOME, "Někdo je zase doma")),
            events(calm.copy(nobodyHome = true, target = 20.0), calm),
        )
    }

    // Poruchy z pole `chyby`

    @Test
    fun faultStartUsesTheTextsOfTheErrorCard() {
        assertEquals(
            LogEvent(
                CONNECTION,
                LogKind.FAULT,
                "Porucha: čidlo osvětlení (TSL2561) neodpovídá",
                "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí.",
            ),
            faultStarted("osvetleni"),
        )
    }

    // Text karty pokračuje za dvojtečkou malým písmenem, jen vlastní jméno Raspberry zůstává velké.
    @Test
    fun knownFaultTitlesContinueTheCardTextInLowercase() {
        assertEquals(
            listOf(
                "Porucha: čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
                "Porucha: čidlo osvětlení (TSL2561) neodpovídá",
                "Porucha: displej domu nefunguje",
                "Porucha: světlo (RGB LED) nejde ovládat",
                "Porucha: Raspberry nemůže uložit nastavení",
            ),
            listOf("meteo", "osvetleni", "displej", "svetlo", "nastaveni").map { faultStarted(it).title },
        )
    }

    @Test
    fun unknownFaultCodeIsAnUnknownFault() {
        assertEquals(LogEvent(CONNECTION, LogKind.FAULT, "Neznámá porucha: kamera"), faultStarted("kamera"))
    }

    @Test
    fun faultEndNamesWhatWorksAgainAndHowLongTheFaultLasted() {
        listOf(
            "meteo" to "Čidlo BME280 zase funguje",
            "osvetleni" to "Čidlo osvětlení zase funguje",
            "displej" to "Displej zase funguje",
            "svetlo" to "Světlo (RGB LED) zase funguje",
            "nastaveni" to "Ukládání nastavení zase funguje",
        ).forEach { (code, title) ->
            assertEquals(LogEvent(CONNECTION, LogKind.FAULT_ENDED, title, "porucha trvala 43${NBSP}s"), faultEnded(code, 43))
        }
        assertEquals(
            LogEvent(CONNECTION, LogKind.FAULT_ENDED, "Chyba kamera skončila", "porucha trvala 1${NBSP}min 15${NBSP}s"),
            faultEnded("kamera", 75),
        )
    }

    @Test
    fun faultOfUnknownLengthEndsWithoutDetail() {
        assertEquals(LogEvent(CONNECTION, LogKind.FAULT_ENDED, "Čidlo BME280 zase funguje"), faultEnded("meteo", null))
    }

    // Nastavení

    @Test
    fun changedFieldsAreTheKeysInTheirOrderThenColorAndBrightnessOfTheLight() {
        val changed = DEFAULTS.copy(
            targetDay = 26.0,
            lightOnMotion = false,
            nightStart = "23:00",
            lightColor = listOf(255, 0, 0),
            lightBrightness = 60,
        )
        assertEquals(
            listOf(
                SettingsField.Key(SettingKey.TARGET_DAY),
                SettingsField.Key(SettingKey.NIGHT_START),
                SettingsField.Key(SettingKey.LIGHT_ON_MOTION),
                SettingsField.LightColor,
                SettingsField.LightBrightness,
            ),
            changedFields(DEFAULTS, changed),
        )
        assertEquals(emptyList<SettingsField>(), changedFields(DEFAULTS, DEFAULTS.copy()))
    }

    // Starší program na Raspberry poslední tři klíče neposílá; když jeden z nich chybí, není s čím srovnat.
    @Test
    fun aKeyMissingOnOneSideIsNoChange() {
        val older = DEFAULTS.copy(lightOnMotion = null, lightColor = null, lightBrightness = null)
        assertEquals(emptyList<SettingsField>(), changedFields(older, DEFAULTS))
        assertEquals(emptyList<SettingsField>(), changedFields(DEFAULTS, older))
    }

    @Test
    fun settingTitleIsTheCapitalizedLabelWithOldAndNewValueAndTheUnitOnce() {
        fun title(key: SettingKey, new: (HouseSettings) -> HouseSettings) =
            settingsEvent(SettingsField.Key(key), DEFAULTS, new(DEFAULTS), fromApp = true).title

        assertEquals("Denní cíl: 23,0 → 26,0${NBSP}°C", title(SettingKey.TARGET_DAY) { it.copy(targetDay = 26.0) })
        assertEquals("Režim termostatu: Auto → Ruční", title(SettingKey.MODE) { it.copy(mode = ThermostatMode.MANUAL) })
        assertEquals("Začátek dne: 07:00 → 06:30", title(SettingKey.DAY_START) { it.copy(dayStart = "06:30") })
        assertEquals("Práh šera: 50 → 80${NBSP}lx", title(SettingKey.LUX_THRESHOLD) { it.copy(luxThreshold = 80.0) })
        assertEquals("Délka nočního světla: 30 → 10${NBSP}s", title(SettingKey.NIGHT_LIGHT_SECONDS) { it.copy(nightLightSeconds = 10) })
        assertEquals("Snížení cíle po: 30 → 45${NBSP}min", title(SettingKey.NOBODY_HOME_AFTER) { it.copy(nobodyHomeAfterMin = 45) })
        assertEquals("Rozsvěcení při pohybu: zapnuto → vypnuto", title(SettingKey.LIGHT_ON_MOTION) { it.copy(lightOnMotion = false) })
    }

    @Test
    fun changeFromTheAppOrOnTheRaspberry() {
        val field = SettingsField.Key(SettingKey.TARGET_NIGHT)
        val changed = DEFAULTS.copy(targetNight = 18.5)
        assertEquals(
            LogEvent(THERMOSTAT, LogKind.SETTING, "Noční cíl: 20,0 → 18,5${NBSP}°C", "změna z${NBSP}aplikace"),
            settingsEvent(field, DEFAULTS, changed, fromApp = true),
        )
        assertEquals(
            LogEvent(THERMOSTAT, LogKind.SETTING, "Noční cíl: 20,0 → 18,5${NBSP}°C", "změna na Raspberry"),
            settingsEvent(field, DEFAULTS, changed, fromApp = false),
        )
    }

    // Režim, cíle a útlum → Termostat; začátky úseků → ostatní; práh šera, noční světlo, rozsvěcení při pohybu, barva a jas
    // → Světlo; „nikdo doma po“ → Pohyb (spec 2.3, Pravidla).
    @Test
    fun categoryOfASettingFollowsItsKey() {
        val expected = mapOf(
            SettingKey.MODE to THERMOSTAT,
            SettingKey.TARGET_DAY to THERMOSTAT,
            SettingKey.TARGET_NIGHT to THERMOSTAT,
            SettingKey.TARGET_MANUAL to THERMOSTAT,
            SettingKey.NOBODY_HOME_SETBACK to THERMOSTAT,
            SettingKey.DAY_START to OTHER,
            SettingKey.EVENING_START to OTHER,
            SettingKey.NIGHT_START to OTHER,
            SettingKey.LUX_THRESHOLD to LIGHT,
            SettingKey.NIGHT_LIGHT_SECONDS to LIGHT,
            SettingKey.LIGHT_ON_MOTION to LIGHT,
            SettingKey.NOBODY_HOME_AFTER to MOTION,
        )
        val changed = DEFAULTS.copy(
            mode = ThermostatMode.OFF,
            targetDay = 24.0,
            targetNight = 19.0,
            targetManual = 21.0,
            dayStart = "06:00",
            eveningStart = "18:00",
            nightStart = "23:00",
            luxThreshold = 60.0,
            nightLightSeconds = 45,
            nobodyHomeAfterMin = 10,
            nobodyHomeSetback = 2.0,
            lightOnMotion = false,
        )
        assertEquals(SettingKey.entries.toSet(), expected.keys)
        expected.forEach { (key, category) ->
            assertEquals(key.json, category, settingsEvent(SettingsField.Key(key), DEFAULTS, changed, fromApp = false).category)
        }
    }

    @Test
    fun lightColorChangeShowsBothColors() {
        assertEquals(
            LogEvent(LIGHT, LogKind.SETTING, "Barva světla změněna", "změna z${NBSP}aplikace", listOf(Rgb(255, 255, 255), Rgb(255, 0, 0))),
            settingsEvent(SettingsField.LightColor, DEFAULTS, DEFAULTS.copy(lightColor = listOf(255, 0, 0)), fromApp = true),
        )
    }

    @Test
    fun lightBrightnessChange() {
        assertEquals(
            LogEvent(LIGHT, LogKind.SETTING, "Jas světla: 100 → 60${NBSP}%", "změna na Raspberry"),
            settingsEvent(SettingsField.LightBrightness, DEFAULTS, DEFAULTS.copy(lightBrightness = 60), fromApp = false),
        )
    }

    // Ovládání simulace a hodiny

    @Test
    fun simulationButtonsSayWhatTheyDo() {
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: zahřáté čidlo", "venku o${NBSP}8${NBSP}°C víc na 30${NBSP}s"),
            simulationEvent(SimulationAction.HEAT_SENSOR, faultReported = false),
        )
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: zakryté čidlo světla", "osvětlení 5${NBSP}lx na 30${NBSP}s"),
            simulationEvent(SimulationAction.COVER_LIGHT_SENSOR, faultReported = false),
        )
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: pohyb"),
            simulationEvent(SimulationAction.MOTION, faultReported = false),
        )
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: výpadek dat", "10${NBSP}s bez Stavu"),
            simulationEvent(SimulationAction.PAUSE_DATA, faultReported = false),
        )
    }

    // Tlačítko poruchu přepíná: zapne ji, když ji Stav nehlásí, jinak ji vypne.
    @Test
    fun sensorFaultButtonSwitchesTheFaultOnOrOff() {
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: porucha BME280 zapnutá"),
            simulationEvent(SimulationAction.TOGGLE_SENSOR_FAULT, faultReported = false),
        )
        assertEquals(
            LogEvent(OTHER, LogKind.SIMULATION, "Simulace: porucha BME280 vypnutá"),
            simulationEvent(SimulationAction.TOGGLE_SENSOR_FAULT, faultReported = true),
        )
    }

    // Řádek je akce, zapíše se před odesláním; výsledek ukážou následné události, chybu hláška.
    @Test
    fun clockSyncSaysTheTimeTakenFromThePhone() {
        assertEquals(
            LogEvent(OTHER, LogKind.CLOCK, "Srovnání hodin", "podle telefonu 14:03:00"),
            clockSyncEvent(LocalDateTime.of(2026, 10, 20, 14, 3, 0)),
        )
    }
}
