package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseJson
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.ThermostatMode
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseSimulationTest {
    private fun at(hour: Int, minute: Int = 0, second: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 10, 20, hour, minute, second)

    private val tenAm = at(10)
    private val elevenPm = at(23)

    private fun HouseSimulation.run(s: Int) = repeat(s) { advance(1.0) }

    private fun json(text: String): JsonObject = HouseJson.parseToJsonElement(text).jsonObject

    private fun HouseSimulation.send(text: String): Boolean = command(json(text))

    private fun HouseSimulation.write(text: String): Boolean = writeSettings(json(text))

    private val HouseSimulation.insideTemp: Double get() = status().insideTemp!!

    private val HouseSimulation.light: LightState get() = status().light

    private fun lit(r: Int, g: Int, b: Int, brightness: Int, manual: Boolean = false) =
        LightState(true, listOf(r, g, b), brightness, manual)

    private val off = LightState(false, listOf(0, 0, 0), 0, false)

    private val offManually = LightState(false, listOf(0, 0, 0), 0, true)

    // Teplota a termostat

    @Test
    fun heatingRaisesInsideAboutThreeDegreesPerMinute() {
        val sim = HouseSimulation(tenAm, 22.0, SIM_DEFAULT_SETTINGS.copy(targetDay = 25.0))
        sim.run(1)
        assertTrue(sim.status().heating)
        sim.run(59)
        assertTrue(sim.insideTemp >= 24.8)
    }

    @Test
    fun heatingUsesHysteresis() {
        val inBand = HouseSimulation(tenAm, 22.8)
        inBand.advance(1.0)
        assertFalse(inBand.status().heating)

        val below = HouseSimulation(tenAm, 22.6)
        below.advance(1.0)
        assertTrue(below.status().heating)
        below.run(12)
        assertFalse(below.status().heating)
        assertTrue(below.insideTemp.toString(), below.insideTemp in 22.9..23.2)

        // Totéž od začátku, bez prvního samostatného kroku.
        val fresh = HouseSimulation(tenAm, 22.6)
        fresh.run(12)
        assertFalse(fresh.status().heating)
        assertTrue(fresh.insideTemp.toString(), fresh.insideTemp in 22.9..23.2)
    }

    @Test
    fun coolingUsesHysteresis() {
        val inBand = HouseSimulation(tenAm, 23.9)
        inBand.advance(1.0)
        assertFalse(inBand.status().cooling)

        val above = HouseSimulation(tenAm, 24.1)
        above.advance(1.0)
        assertTrue(above.status().cooling)
        above.run(24)
        // Chlazení skončilo na cíli a uvnitř pásma se topení ani chlazení znovu nezapne.
        assertFalse(above.status().cooling)
        assertFalse(above.status().heating)
        assertTrue(above.insideTemp.toString(), above.insideTemp in 22.8..23.1)
    }

    @Test
    fun thermostatStartsOnlyBeyondTheContractThresholds() {
        // docs/ble-rozhrani.md (Stav, Jak domek hodnoty určuje): topí se pod cílem o víc než 0,3 °C, chladí nad cílem
        // o víc než 1,0 °C. Stav se čte hned po vytvoření, aby teplotu nic neposunulo.
        fun statusAt(inside: Double) = HouseSimulation(tenAm, inside).status().also { assertEquals(23.0, it.target) }

        assertFalse("−0,29 °C", statusAt(22.71).heating)
        assertTrue("−0,31 °C", statusAt(22.69).heating)
        assertFalse("přesně −0,3 °C", statusAt(22.7).heating)
        assertFalse("+0,99 °C", statusAt(23.99).cooling)
        assertTrue("+1,01 °C", statusAt(24.01).cooling)
        assertFalse("přesně +1,0 °C", statusAt(24.0).cooling)
        listOf(22.7, 23.0, 23.5, 24.0).forEach {
            val status = statusAt(it)
            assertFalse("$it °C", status.heating || status.cooling)
        }
    }

    @Test
    fun heatingStopsExactlyAtTheTarget() {
        // Topí se 0,05 °C/s, z 22,5 °C je to na cíl 23,0 °C přesně 10 s.
        val almost = HouseSimulation(tenAm, 22.5)
        almost.advance(9.8)
        assertTrue(almost.insideTemp.toString(), almost.status().heating)

        val reached = HouseSimulation(tenAm, 22.5)
        reached.advance(10.0)
        assertEquals(23.0, reached.insideTemp, 0.0)
        assertFalse(reached.status().heating)
        assertFalse(reached.status().cooling)
    }

    @Test
    fun coolingStopsAtTheTarget() {
        // Chladí se 0,05 °C/s, z 24,5 °C je to na cíl 23,0 °C přesně 30 s.
        val almost = HouseSimulation(tenAm, 24.5)
        assertTrue(almost.status().cooling)
        almost.advance(29.8)
        assertTrue(almost.insideTemp.toString(), almost.status().cooling)

        val reached = HouseSimulation(tenAm, 24.5)
        reached.advance(30.0)
        assertEquals(23.0, reached.insideTemp, 0.0)
        assertFalse(reached.status().cooling)
        assertFalse(reached.status().heating)
    }

    @Test
    fun coolingLowersInside() {
        val sim = HouseSimulation(tenAm, 25.0, SIM_DEFAULT_SETTINGS.copy(targetDay = 22.0))
        sim.run(30)
        assertTrue(sim.status().cooling)
        assertFalse(sim.status().heating)
        assertTrue(sim.insideTemp <= 23.6)
    }

    @Test
    fun offModeDriftsTowardOutside() {
        val sim = HouseSimulation(tenAm, 25.0, SIM_DEFAULT_SETTINGS.copy(mode = ThermostatMode.OFF))
        sim.run(600)
        val status = sim.status()
        assertTrue(status.insideTemp.toString(), status.insideTemp!! < 24.0)
        // Hodnota 21,31 je spočtená zvlášť ze vzorce v zadání, takže hlídá i rychlost přibližování.
        assertEquals(21.31, status.insideTemp!!, 0.05)
        assertNull(status.target)
        assertFalse(status.heating)
        assertFalse(status.cooling)
        assertEquals(ThermostatMode.OFF, status.mode)
    }

    @Test
    fun nightUsesNightTarget() {
        val status = HouseSimulation(elevenPm).status()
        assertEquals(DayPart.NIGHT, status.dayPart)
        assertEquals(20.0, status.target)
    }

    @Test
    fun eveningUsesDayTarget() {
        val status = HouseSimulation(at(20)).status()
        assertEquals(DayPart.EVENING, status.dayPart)
        assertEquals(23.0, status.target)
    }

    // V ručním režimu platí cil_rucni bez útlumu, i když nikdo není doma (docs/ble-rozhrani.md, Jak domek hodnoty určuje).
    @Test
    fun manualModeUsesManualTargetWithoutSetback() {
        val sim = HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(mode = ThermostatMode.MANUAL, nobodyHomeAfterMin = 1))
        assertEquals(22.0, sim.status().target)
        sim.run(60)
        assertTrue(sim.status().nobodyHome)
        assertEquals(22.0, sim.status().target)
    }

    @Test
    fun nobodyHomeLowersTarget() {
        val sim = HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(nobodyHomeAfterMin = 1))
        assertFalse(sim.status().nobodyHome)
        sim.run(59)
        assertFalse(sim.status().nobodyHome)
        // „Aspoň“ minuta: platí už v šedesáté vteřině.
        sim.run(1)
        assertTrue(sim.status().nobodyHome)
        sim.run(1)
        assertTrue(sim.status().nobodyHome)
        assertEquals(20.0, sim.status().target)

        sim.triggerMotion()
        sim.advance(1.0)
        assertFalse(sim.status().nobodyHome)
        assertEquals(23.0, sim.status().target)
    }

    @Test
    fun motionRestoresTargetAndThermostatAtOnce() {
        // Jeden velký krok dá jistý stav: teplota 22,05 °C a cíl snížený na 20 °C, tedy chlazení.
        val sim = HouseSimulation(tenAm, 19.0, SIM_DEFAULT_SETTINGS.copy(nobodyHomeAfterMin = 1))
        sim.advance(61.0)
        assertTrue(sim.status().nobodyHome)
        assertTrue(sim.status().cooling)
        assertFalse(sim.status().heating)

        sim.triggerMotion()
        assertEquals(23.0, sim.status().target)
        assertTrue(sim.status().heating)
        assertFalse(sim.status().cooling)
    }

    @Test
    fun shorteningNobodyHomeDelayAppliesAtOnce() {
        val sim = HouseSimulation(tenAm)
        sim.run(100)
        assertFalse(sim.status().nobodyHome)
        assertTrue(sim.write("""{"nikdo_doma_po_min":1}"""))
        assertTrue(sim.status().nobodyHome)
        assertEquals(20.0, sim.status().target)
    }

    @Test
    fun thermostatDecidesAfterTheTemperatureStep() {
        // 24,63 je pod spodní mezí pásma, takže se topí; po sedmi krocích je 24,98 a po osmém 25,03.
        val sim = HouseSimulation(tenAm, 24.63, SIM_DEFAULT_SETTINGS.copy(targetDay = 25.0))
        sim.run(7)
        assertTrue(sim.status().heating)
        sim.advance(1.0)
        assertTrue(sim.insideTemp >= 25.0)
        assertFalse(sim.status().heating)
    }

    @Test
    fun stepLengthScalesHeatingCoolingAndDrift() {
        val heating = HouseSimulation(tenAm, 22.0, SIM_DEFAULT_SETTINGS.copy(targetDay = 25.0))
        heating.advance(10.0)
        assertEquals(22.5, heating.insideTemp, 1e-9)
        val cooling = HouseSimulation(tenAm, 25.0, SIM_DEFAULT_SETTINGS.copy(targetDay = 22.0))
        cooling.advance(10.0)
        assertEquals(24.5, cooling.insideTemp, 1e-9)

        // Bez topení a chlazení je jedno, zda se čas posune po vteřinách, nebo najednou.
        val offMode = SIM_DEFAULT_SETTINGS.copy(mode = ThermostatMode.OFF)
        val whole = HouseSimulation(tenAm, 25.0, offMode)
        val parts = HouseSimulation(tenAm, 25.0, offMode)
        whole.advance(10.0)
        parts.run(10)
        assertEquals(parts.insideTemp, whole.insideTemp, 0.011)
    }

    @Test
    fun thermostatHoldsInsideAroundTarget() {
        // „Nikdo doma“ až po 240 minutách, aby se cíl za hodinu nesnížil.
        val sim = HouseSimulation(tenAm, 22.0, SIM_DEFAULT_SETTINGS.copy(nobodyHomeAfterMin = 240))
        sim.run(300)
        val temps = (1..3600).map { sim.advance(1.0); sim.insideTemp }
        assertTrue("${temps.min()}..${temps.max()}", temps.all { it in 22.6..23.1 })
        // Teplota opravdu kolísá mezi spodní a horní mezí pásma, topení tedy cyklicky zapíná a vypíná.
        assertTrue(temps.min() < 22.75)
        assertTrue(temps.max() > 23.0)
    }

    @Test
    fun thermostatNeverHeatsAndCoolsAtOnce() {
        for (startTenths in 150..300 step 5) {
            val sim = HouseSimulation(tenAm, startTenths / 10.0)
            repeat(300) { second ->
                // Cíl se uprostřed běhu mění nahoru i dolů, aby se zkusily všechny přechody topení a chlazení.
                if (second == 100) sim.write("""{"cil_den":15.0}""")
                if (second == 200) sim.write("""{"cil_den":30.0}""")
                sim.advance(1.0)
                val status = sim.status()
                assertFalse("start $startTenths, second $second", status.heating && status.cooling)
            }
        }
    }

    @Test
    fun changingModeAppliesAtOnce() {
        val sim = HouseSimulation(tenAm)
        assertTrue(sim.status().heating)

        assertTrue(sim.write("""{"rezim_termostatu":"vypnuto"}"""))
        assertFalse(sim.status().heating)
        assertFalse(sim.status().cooling)
        assertNull(sim.status().target)

        assertTrue(sim.write("""{"rezim_termostatu":"rucni"}"""))
        assertEquals(22.0, sim.status().target)
        assertTrue(sim.status().heating)
    }

    @Test
    fun changingTargetAppliesAtOnce() {
        val sim = HouseSimulation(tenAm)
        assertTrue(sim.status().heating)
        assertTrue(sim.write("""{"cil_den":20.0}"""))
        assertEquals(20.0, sim.status().target)
        assertFalse(sim.status().heating)
        assertTrue(sim.status().cooling)
    }

    // Světlo

    // Uložená barva a jas, jiné než výchozí bílá na 100 %, aby bylo vidět, odkud světlo bere vzhled.
    private val warmLook = SIM_DEFAULT_SETTINGS.copy(lightColor = listOf(255, 147, 41), lightBrightness = 60)

    @Test
    fun darkDayTurnsWhiteLightOn() {
        val sim = HouseSimulation(tenAm)
        sim.coverLightSensor()
        assertEquals(setOf(SimEvent.LIGHT), sim.advance(1.0))
        assertEquals(lit(255, 255, 255, 100), sim.light)
    }

    @Test
    fun duskLightUsesTheStoredLookAtOnce() {
        val sim = HouseSimulation(tenAm, settings = warmLook)
        sim.coverLightSensor()
        // Zakryté čidlo hlásí 5 lx hned, světlo tedy svítí hned také.
        assertEquals(lit(255, 147, 41, 60), sim.light)
    }

    // docs/ble-rozhrani.md (Světlo v automatice): šero začne pod prahem a skončí až nad 1,3násobkem prahu.
    @Test
    fun duskStartsBelowTheThresholdAndEndsOnlyAboveOnePointThreeTimesIt() {
        // Ve dne je na startu přesně 300 lx; mění se jen práh.
        val sim = HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 300.0))
        assertFalse("300 lx není pod prahem 300 lx", sim.light.on)
        assertTrue(sim.write("""{"prah_lux":301}"""))
        assertTrue("300 lx je pod prahem 301 lx", sim.light.on)
        assertTrue(sim.write("""{"prah_lux":240}"""))
        assertTrue("300 lx není nad 312 lx, šero trvá", sim.light.on)
        assertTrue(sim.write("""{"prah_lux":230}"""))
        assertFalse("300 lx je nad 299 lx, šero skončilo", sim.light.on)
        assertTrue(sim.write("""{"prah_lux":240}"""))
        assertFalse("nad prahem šero nezačne, i když je pod 312 lx", sim.light.on)
    }

    @Test
    fun duskOutlastsTheCoveredSensorUntilLuxRisesAboveTheEndOfDusk() {
        // Při prahu 250 lx končí šero nad 325 lx. Po odkrytí čidla ve 30. vteřině je 323,5 lx, ve 32. 324,8 lx
        // a ve 33. 325,5 lx.
        val sim = HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 250.0))
        sim.coverLightSensor()
        assertTrue(sim.light.on)
        sim.run(30)
        assertTrue(sim.status().lux.toString(), sim.status().lux!! > 250.0)
        assertTrue(sim.light.on)
        sim.run(2)
        assertTrue(sim.light.on)
        sim.run(1)
        assertFalse(sim.light.on)
    }

    @Test
    fun brightDayKeepsLightOff() {
        val sim = HouseSimulation(tenAm)
        sim.advance(1.0)
        assertFalse(sim.light.on)
    }

    @Test
    fun switchedOffLightLooksLikeTheExampleStatus() {
        assertEquals(EXAMPLE.light, HouseSimulation(tenAm).light)
    }

    @Test
    fun lightFollowsConfiguredLuxThreshold() {
        // Ve dne svítí osvětlení mezi 260 a 340 lx, na startu přesně 300 lx.
        assertEquals(lit(255, 255, 255, 100), HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 400.0)).light)
        assertEquals(off, HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 250.0)).light)
        // Světlo svítí jen pod prahem, při rovnosti ne.
        assertEquals(off, HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 300.0)).light)
    }

    @Test
    fun eveningLightUsesTheStoredLook() {
        assertEquals(lit(255, 255, 255, 100), HouseSimulation(at(20)).light)

        val sim = HouseSimulation(at(20), settings = warmLook)
        assertEquals(lit(255, 147, 41, 60), sim.light)
        sim.advance(1.0)
        assertEquals(lit(255, 147, 41, 60), sim.light)
    }

    @Test
    fun eveningLightDoesNotDependOnLux() {
        // Večerní osvětlení je 20 lx, přesto při prahu 10 lx světlo svítí.
        val sim = HouseSimulation(at(20), settings = SIM_DEFAULT_SETTINGS.copy(luxThreshold = 10.0))
        assertEquals(lit(255, 255, 255, 100), sim.light)
    }

    @Test
    fun settingsWithoutStoredLookLightWhiteAtFullBrightness() {
        val older = SIM_DEFAULT_SETTINGS.copy(lightColor = null, lightBrightness = null)
        assertEquals(lit(255, 255, 255, 100), HouseSimulation(at(20), settings = older).light)

        val night = HouseSimulation(elevenPm, settings = older)
        night.triggerMotion()
        assertEquals(lit(255, 255, 255, 10), night.light)
    }

    @Test
    fun nightMotionLightsNightLightForConfiguredSeconds() {
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(nightLightSeconds = 5))
        assertFalse(sim.light.on)
        sim.triggerMotion()
        sim.advance(1.0)
        assertEquals(lit(255, 255, 255, 10), sim.light)
        sim.run(5)
        assertFalse(sim.light.on)
    }

    @Test
    fun nightLightUsesTheStoredColourAtMostTenPercent() {
        val sim = HouseSimulation(elevenPm, settings = warmLook)
        sim.triggerMotion()
        assertEquals(lit(255, 147, 41, 10), sim.light)

        val dim = HouseSimulation(elevenPm, settings = warmLook.copy(lightBrightness = 5))
        dim.triggerMotion()
        assertEquals(lit(255, 147, 41, 5), dim.light)
    }

    @Test
    fun nightLightBurnsExactlyConfiguredSecondsAndFurtherMotionExtendsIt() {
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(nightLightSeconds = 5))
        sim.triggerMotion()
        sim.run(4)
        assertTrue(sim.light.on)
        sim.run(1)
        assertFalse(sim.light.on)

        sim.triggerMotion()
        sim.run(3)
        sim.triggerMotion()
        sim.run(4)
        assertTrue(sim.light.on)
        sim.run(1)
        assertFalse(sim.light.on)
    }

    @Test
    fun noNightLightWhenDisabled() {
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(lightOnMotion = false))
        sim.triggerMotion()
        sim.advance(1.0)
        assertFalse(sim.light.on)
    }

    @Test
    fun missingLightOnMotionSettingBehavesAsEnabled() {
        // Raspberry bez klíče nocni_svetlo_pri_pohybu rozsvěcí jako dřív (spec 4.2).
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(lightOnMotion = null))
        sim.triggerMotion()
        sim.advance(1.0)
        assertEquals(lit(255, 255, 255, 10), sim.light)
    }

    @Test
    fun motionIsStillReportedWhenLightOnMotionIsOff() {
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(lightOnMotion = false, nobodyHomeAfterMin = 1))
        sim.run(61)
        assertTrue(sim.status().nobodyHome)

        sim.triggerMotion()
        val status = sim.status()
        assertTrue(status.motion)
        assertFalse(status.nobodyHome)
        assertFalse(status.light.on)
    }

    @Test
    fun motionBeforeNightDoesNotStartNightLight() {
        // Noční světlo patří noci (spec 4.1), pohyb o vteřiny před jejím začátkem ho nespustí.
        val sim = HouseSimulation(at(21, 59, 50))
        sim.triggerMotion()
        sim.run(11)
        assertEquals(DayPart.NIGHT, sim.status().dayPart)
        assertFalse(sim.light.on)
    }

    @Test
    fun disablingLightOnMotionTurnsNightLightOff() {
        val sim = HouseSimulation(elevenPm)
        sim.triggerMotion()
        sim.advance(1.0)
        assertTrue(sim.light.on)
        assertTrue(sim.write("""{"nocni_svetlo_pri_pohybu":false}"""))
        assertFalse(sim.light.on)
    }

    @Test
    fun reEnablingLightOnMotionDoesNotBringNightLightBack() {
        val sim = HouseSimulation(elevenPm)
        sim.triggerMotion()
        sim.advance(1.0)
        sim.write("""{"nocni_svetlo_pri_pohybu":false}""")
        assertTrue(sim.write("""{"nocni_svetlo_pri_pohybu":true}"""))
        assertFalse(sim.light.on)
        sim.advance(1.0)
        assertFalse(sim.light.on)
    }

    @Test
    fun disablingLightOnMotionLeavesManualLightAlone() {
        val sim = HouseSimulation(tenAm)
        sim.send("""{"svetlo":"zapnout"}""")
        sim.write("""{"nocni_svetlo_pri_pohybu":false}""")
        assertEquals(lit(255, 255, 255, 100, manual = true), sim.light)
    }

    @Test
    fun manualChoiceLastsUntilNextDayPart() {
        val sim = HouseSimulation(at(18, 59, 50))
        assertTrue(sim.send("""{"svetlo":"zapnout"}"""))
        assertEquals(lit(255, 255, 255, 100, manual = true), sim.light)
        sim.run(9)
        assertTrue(sim.light.manual)
        sim.run(6)
        assertFalse(sim.light.manual)
        assertEquals(lit(255, 255, 255, 100), sim.light)
    }

    @Test
    fun manualOffEndsWithTheDayPartToo() {
        val sim = HouseSimulation(at(18, 59, 50))
        assertTrue(sim.send("""{"svetlo":"vypnout"}"""))
        assertEquals(offManually, sim.light)
        sim.run(15)
        assertEquals(lit(255, 255, 255, 100), sim.light)
    }

    @Test
    fun manualOnUsesTheStoredLook() {
        val sim = HouseSimulation(tenAm, settings = warmLook)
        assertTrue(sim.send("""{"svetlo":"zapnout"}"""))
        assertEquals(lit(255, 147, 41, 60, manual = true), sim.light)
    }

    // docs/ble-rozhrani.md (Světlo v automatice): změna barva_svetla nebo jas_svetla změní i ručně zapnuté světlo.
    @Test
    fun writingTheStoredLookChangesManualAndAutomaticLightAtOnce() {
        val manual = HouseSimulation(tenAm)
        manual.send("""{"svetlo":"zapnout"}""")
        assertTrue(manual.write("""{"barva_svetla":[0,0,255],"jas_svetla":40}"""))
        assertEquals(lit(0, 0, 255, 40, manual = true), manual.light)

        val evening = HouseSimulation(at(20))
        assertTrue(evening.write("""{"jas_svetla":30}"""))
        assertEquals(lit(255, 255, 255, 30), evening.light)
    }

    @Test
    fun toggleFlipsWhatIsShown() {
        val sim = HouseSimulation(tenAm)
        sim.advance(1.0)
        assertFalse(sim.light.on)

        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertTrue(sim.light.on)
        assertTrue(sim.light.manual)

        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertFalse(sim.light.on)
        assertTrue(sim.light.manual)
    }

    @Test
    fun toggleTurnsShownAutomaticLightOff() {
        val sim = HouseSimulation(at(20))
        assertTrue(sim.light.on)
        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertEquals(offManually, sim.light)
    }

    @Test
    fun toggleLightsWithTheStoredLook() {
        val sim = HouseSimulation(tenAm, settings = warmLook)
        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertEquals(lit(255, 147, 41, 60, manual = true), sim.light)
    }

    @Test
    fun colorCommandTurnsLightOnManually() {
        val sim = HouseSimulation(tenAm)
        assertTrue(sim.send("""{"svetlo":{"barva":[0,0,255]}}"""))
        assertEquals(lit(0, 0, 255, 100, manual = true), sim.light)
    }

    // Ruční volba si pamatuje jen zapnuto a vypnuto, barva a jas z příkazu zůstanou v Nastavení.
    @Test
    fun onAndToggleLightWithTheLookStoredByTheLastCommand() {
        val sim = HouseSimulation(tenAm)
        sim.send("""{"svetlo":{"barva":[0,0,255],"jas":40}}""")
        assertEquals(lit(0, 0, 255, 40, manual = true), sim.light)

        sim.send("""{"svetlo":"vypnout"}""")
        assertEquals(offManually, sim.light)
        sim.send("""{"svetlo":"zapnout"}""")
        assertEquals(lit(0, 0, 255, 40, manual = true), sim.light)

        sim.send("""{"svetlo":"prepnout"}""")
        assertEquals(offManually, sim.light)
        sim.send("""{"svetlo":"prepnout"}""")
        assertEquals(lit(0, 0, 255, 40, manual = true), sim.light)
    }

    // docs/ble-rozhrani.md (Příkaz → Světlo): objekt uloží, co přišlo, do Nastavení; chybějící část zůstane
    // z Nastavení.
    @Test
    fun colourOnlyAndBrightnessOnlyCommandsStoreIntoSettingsAndTheLight() {
        val sim = HouseSimulation(at(20))
        assertTrue(sim.send("""{"svetlo":{"jas":30}}"""))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightBrightness = 30), sim.settings)
        assertEquals(lit(255, 255, 255, 30, manual = true), sim.light)

        assertTrue(sim.send("""{"svetlo":{"barva":[0,0,255]}}"""))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(lightColor = listOf(0, 0, 255), lightBrightness = 30), sim.settings)
        assertEquals(lit(0, 0, 255, 30, manual = true), sim.light)
    }

    @Test
    fun lookCommandTakesTheMissingPartFromSettingsEvenWhenTheLightIsOff() {
        val sim = HouseSimulation(tenAm, settings = warmLook)
        sim.send("""{"svetlo":"vypnout"}""")
        sim.send("""{"svetlo":{"barva":[1,2,3]}}""")
        assertEquals(lit(1, 2, 3, 60, manual = true), sim.light)

        sim.send("""{"svetlo":"vypnout"}""")
        sim.send("""{"svetlo":{"jas":50}}""")
        assertEquals(lit(1, 2, 3, 50, manual = true), sim.light)
    }

    // Uložený vzhled platí i pro automatiku: po konci ruční volby svítí večer barvou a jasem z příkazu.
    @Test
    fun lookFromACommandOutlivesTheManualChoice() {
        val sim = HouseSimulation(at(18, 59, 50))
        assertTrue(sim.send("""{"svetlo":{"barva":[0,0,255],"jas":50}}"""))
        sim.run(15)
        assertEquals(lit(0, 0, 255, 50), sim.light)
    }

    // docs/ble-rozhrani.md (Příkaz → Světlo): jas 0 je platný a dá zap true při jasu 0, i v automatice.
    @Test
    fun brightnessZeroIsOnWithZeroBrightness() {
        val sim = HouseSimulation(at(18, 59, 50))
        assertTrue(sim.send("""{"svetlo":{"jas":0}}"""))
        assertEquals(lit(255, 255, 255, 0, manual = true), sim.light)
        assertEquals(0, sim.settings.lightBrightness)
        sim.run(15)
        assertEquals(lit(255, 255, 255, 0), sim.light)
    }

    // Přepnout se řídí podle zap, ne podle jasu: světlo s jasem 0 zhasne.
    @Test
    fun toggleTurnsOffALightWithZeroBrightness() {
        val sim = HouseSimulation(tenAm)
        sim.send("""{"svetlo":{"jas":0}}""")
        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertEquals(offManually, sim.light)
        assertTrue(sim.send("""{"svetlo":"prepnout"}"""))
        assertEquals(lit(255, 255, 255, 0, manual = true), sim.light)
    }

    @Test
    fun autoCommandReturnsLightToAutomation() {
        val day = HouseSimulation(tenAm)
        day.send("""{"svetlo":"zapnout"}""")
        assertTrue(day.send("""{"svetlo":"auto"}"""))
        assertEquals(off, day.light)

        val evening = HouseSimulation(at(20))
        evening.send("""{"svetlo":"vypnout"}""")
        assertTrue(evening.send("""{"svetlo":"auto"}"""))
        assertEquals(lit(255, 255, 255, 100), evening.light)
    }

    // Hodiny, poruchy a odmítnutí

    @Test
    fun clockCommandMovesClock() {
        val sim = HouseSimulation(tenAm)
        assertTrue(sim.send("""{"cas":"2026-10-20T23:30:00"}"""))
        sim.advance(1.0)
        assertEquals(DayPart.NIGHT, sim.status().dayPart)
        assertTrue(sim.status().time!!.startsWith("2026-10-20T23:30:0"))
        assertEquals("2026-10-20T23:30:01", sim.status().time)
    }

    @Test
    fun clockRunsWithTheSimulation() {
        val sim = HouseSimulation(tenAm)
        assertEquals("2026-10-20T10:00:00", sim.status().time)
        sim.run(65)
        assertEquals("2026-10-20T10:01:05", sim.status().time)
        sim.advance(2.5)
        assertEquals(tenAm.plusSeconds(67).plusNanos(500_000_000), sim.clock)
        assertEquals("2026-10-20T10:01:07", sim.status().time)
    }

    @Test
    fun clockCommandMovesDayPartAtOnce() {
        val sim = HouseSimulation(tenAm)
        sim.send("""{"svetlo":"zapnout"}""")
        assertTrue(sim.send("""{"cas":"2026-10-20T23:30:00"}"""))
        val status = sim.status()
        assertEquals("2026-10-20T23:30:00", status.time)
        assertEquals(DayPart.NIGHT, status.dayPart)
        assertEquals(20.0, status.target)
        // Změna úseku zrušila ruční volbu světla.
        assertEquals(off, status.light)
    }

    // Cíl patří úseku, který právě platí, a termostat se podle něj rozhodne hned: ve 23:30 je cíl 20 °C
    // a 21,5 °C je nad ním o víc než 1 °C, takže se chladí, i když se v 10:00 topilo.
    @Test
    fun clockCommandDecidesTheThermostatWithTheNewTarget() {
        val sim = HouseSimulation(tenAm)
        assertTrue(sim.status().heating)
        assertTrue(sim.send("""{"cas":"2026-10-20T23:30:00"}"""))
        val status = sim.status()
        assertEquals(20.0, status.target)
        assertFalse(status.heating)
        assertTrue(status.cooling)
    }

    @Test
    fun clockCommandDoesNotTriggerNobodyHome() {
        val sim = HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(nobodyHomeAfterMin = 1))
        sim.send("""{"cas":"2026-10-20T12:00:00"}""")
        sim.advance(1.0)
        assertFalse(sim.status().nobodyHome)
    }

    @Test
    fun clockCommandNeitherCancelsNorExtendsMotionAndNightLight() {
        val sim = HouseSimulation(elevenPm, settings = SIM_DEFAULT_SETTINGS.copy(nightLightSeconds = 10))
        sim.triggerMotion()
        sim.run(2)
        sim.send("""{"cas":"2026-10-20T23:50:00"}""")
        assertTrue(sim.status().motion)
        assertTrue(sim.light.on)

        // Skok hodin zpátky světlo neprodlouží: svítí dál jen do desáté vteřiny od pohybu.
        sim.send("""{"cas":"2026-10-20T23:00:00"}""")
        sim.run(1)
        assertFalse(sim.status().motion)
        sim.run(6)
        assertTrue(sim.light.on)
        sim.run(1)
        assertFalse(sim.light.on)
    }

    @Test
    fun clockCommandNeitherCancelsNorExtendsSensorEffects() {
        val sim = HouseSimulation(tenAm)
        sim.coverLightSensor()
        sim.run(10)
        sim.send("""{"cas":"2026-10-20T08:00:00"}""")
        assertEquals(5.0, sim.status().lux)
        sim.send("""{"cas":"2026-10-20T14:00:00"}""")
        sim.run(19)
        assertEquals(5.0, sim.status().lux)
        sim.run(1)
        assertTrue(sim.status().lux!! > 250.0)
    }

    @Test
    fun sensorFaultNullsBmeValues() {
        val sim = HouseSimulation(tenAm)
        sim.sensorFault = true
        val status = sim.status()
        assertNull(status.outsideTemp)
        assertNull(status.humidity)
        assertNull(status.pressure)
        assertEquals(listOf("meteo"), status.errors)
        assertNotNull(status.insideTemp)
    }

    // docs/ble-rozhrani.md (Hodnoty v poli chyby, meteo): termostat stojí a t_uvnitr drží poslední hodnotu.
    @Test
    fun meteoFaultStopsTheThermostatAndHoldsTheInsideTemperature() {
        val heated = HouseSimulation(tenAm, 21.5)
        assertTrue(heated.status().heating)
        heated.sensorFault = true
        // Termostat stojí hned, ne až v dalším kroku.
        assertFalse(heated.status().heating)
        assertFalse(heated.status().cooling)
        heated.run(120)
        assertEquals(21.5, heated.insideTemp, 0.0)
        assertFalse(heated.status().heating)
        assertFalse(heated.status().cooling)

        val cooled = HouseSimulation(tenAm, 25.0)
        assertTrue(cooled.status().cooling)
        cooled.sensorFault = true
        cooled.run(120)
        assertEquals(25.0, cooled.insideTemp, 0.0)
        assertFalse(cooled.status().cooling)
        assertFalse(cooled.status().heating)
    }

    @Test
    fun meteoFaultHoldsTheInsideTemperatureEvenWithTheThermostatOff() {
        val sim = HouseSimulation(tenAm, 25.0, SIM_DEFAULT_SETTINGS.copy(mode = ThermostatMode.OFF))
        sim.sensorFault = true
        sim.run(120)
        assertEquals(25.0, sim.insideTemp, 0.0)
    }

    @Test
    fun thermostatResumesWhenTheMeteoFaultEnds() {
        val sim = HouseSimulation(tenAm, 21.5)
        sim.sensorFault = true
        sim.run(60)
        sim.sensorFault = false
        assertTrue(sim.status().heating)
        sim.advance(1.0)
        assertEquals(21.55, sim.insideTemp, 1e-9)
    }

    @Test
    fun meteoFaultKeepsLuxReported() {
        val healthy = HouseSimulation(tenAm)
        val faulty = HouseSimulation(tenAm)
        faulty.sensorFault = true
        healthy.run(120)
        faulty.run(120)
        assertNotNull(faulty.status().lux)
        assertEquals(healthy.status().lux, faulty.status().lux)
    }

    @Test
    fun clearingSensorFaultBringsBackBmeValues() {
        val sim = HouseSimulation(tenAm)
        sim.sensorFault = true
        sim.sensorFault = false
        val status = sim.status()
        assertEquals(21.5, status.outsideTemp)
        assertEquals(45.0, status.humidity)
        assertEquals(1013.2, status.pressure)
        assertEquals(emptyList<String>(), status.errors)
    }

    @Test
    fun invalidWritesAndCommandsChangeNothing() {
        val sim = HouseSimulation(tenAm)
        assertFalse(sim.write("""{"zacatek_noc":"19:00"}"""))
        assertEquals(SIM_DEFAULT_SETTINGS, sim.settings)
        assertFalse(sim.send("""{"svetlo":"blikat"}"""))
    }

    @Test
    fun rejectedInputLeavesTheWholeStatusUntouched() {
        val sim = HouseSimulation(tenAm)
        sim.run(5)
        val before = sim.status()
        listOf(
            """{"neznamy":1}""", """{"cil_den":99.0}""", """{"cil_den":24.0,"zacatek_noc":"19:00"}""",
            """{"nocni_svetlo_pri_pohybu":1}""", """{"barva_svetla":[0,0]}""", """{"jas_svetla":30.0}""",
            """{"jas_svetla":50,"barva_svetla":[256,0,0]}""",
        ).forEach { assertFalse(it, sim.write(it)) }
        listOf(
            """{"svetlo":"blikat"}""", """{"cas":"vcera"}""", """{"svetlo":"zapnout","cas":"2026-10-20T23:30:00"}""",
            "{}", """{"svetlo":{"barva":[0,0,255],"jas":101}}""",
        ).forEach { assertFalse(it, sim.send(it)) }
        assertEquals(before, sim.status())
        assertEquals(SIM_DEFAULT_SETTINGS, sim.settings)
    }

    @Test
    fun validWriteMergesIntoSettingsAndAppliesAtOnce() {
        val sim = HouseSimulation(at(10, 30))
        assertTrue(sim.write("""{"cil_den":24.0,"zacatek_den":"06:30"}"""))
        assertEquals(SIM_DEFAULT_SETTINGS.copy(targetDay = 24.0, dayStart = "06:30"), sim.settings)
        assertEquals(24.0, sim.status().target)
    }

    @Test
    fun constructorRejectsEqualDayStarts() {
        assertThrows(IllegalArgumentException::class.java) {
            HouseSimulation(tenAm, settings = SIM_DEFAULT_SETTINGS.copy(nightStart = "19:00"))
        }
    }

    @Test
    fun advanceRejectsNegativeSeconds() {
        val sim = HouseSimulation(tenAm)
        assertThrows(IllegalArgumentException::class.java) { sim.advance(-1.0) }
        assertEquals(tenAm, sim.clock)
    }

    // Konstanty modelu a tvar Stavu

    @Test
    fun simDefaultSettingsMatchSpecDefaults() {
        assertEquals(DEFAULTS, SIM_DEFAULT_SETTINGS)
        assertEquals(SIM_DEFAULT_SETTINGS, HouseSimulation(tenAm).settings)
    }

    @Test
    fun statusIsMeaningfulRightAfterConstruction() {
        val day = HouseSimulation(tenAm).status()
        assertEquals("2026-10-20T10:00:00", day.time)
        assertEquals(DayPart.DAY, day.dayPart)
        assertEquals(21.5, day.insideTemp)
        assertEquals(23.0, day.target)
        assertTrue(day.heating)
        assertFalse(day.cooling)
        assertEquals(ThermostatMode.AUTO, day.mode)
        assertFalse(day.nobodyHome)
        assertFalse(day.motion)
        assertEquals(emptyList<String>(), day.errors)

        val night = HouseSimulation(elevenPm).status()
        assertEquals(DayPart.NIGHT, night.dayPart)
        assertTrue(night.cooling)
        assertFalse(night.heating)
    }

    @Test
    fun outsideTemperatureFollowsItsWave() {
        val sim = HouseSimulation(tenAm)
        assertEquals(21.5, sim.status().outsideTemp)
        sim.run(150)
        assertEquals(21.9, sim.status().outsideTemp!!, 1e-9)
        sim.run(300)
        assertEquals(21.1, sim.status().outsideTemp!!, 1e-9)
        sim.run(150)
        assertEquals(21.5, sim.status().outsideTemp!!, 1e-9)
    }

    @Test
    fun humidityAndPressureFollowTheirWaves() {
        val sim = HouseSimulation(tenAm)
        assertEquals(45.0, sim.status().humidity)
        assertEquals(1013.2, sim.status().pressure)
        // Vlhkost má periodu 900 s, tlak 1200 s; čtvrtiny period jsou vrcholy a dna.
        sim.run(225)
        assertEquals(47.0, sim.status().humidity!!, 1e-9)
        sim.run(75)
        assertEquals(1013.5, sim.status().pressure!!, 1e-9)
        sim.run(375)
        assertEquals(43.0, sim.status().humidity!!, 1e-9)
        sim.run(225)
        assertEquals(1012.9, sim.status().pressure!!, 1e-9)
    }

    @Test
    fun luxDependsOnDayPart() {
        val sim = HouseSimulation(tenAm)
        assertEquals(300.0, sim.status().lux)
        sim.run(75)
        assertEquals(340.0, sim.status().lux!!, 1e-9)
        sim.run(150)
        assertEquals(260.0, sim.status().lux!!, 1e-9)
        assertEquals(20.0, HouseSimulation(at(20)).status().lux)
        assertEquals(0.5, HouseSimulation(elevenPm).status().lux)
    }

    // „Zahřát čidlo“ je venkovní teplota +8 °C na 30 s. Přídavek drží celou dobu a potom naráz zmizí.
    @Test
    fun heatSensorBoostHoldsEightDegreesForThirtySeconds() {
        val plain = HouseSimulation(tenAm)
        val heated = HouseSimulation(tenAm)
        heated.heatSensor()
        fun boost() = heated.status().outsideTemp!! - plain.status().outsideTemp!!
        fun runBoth(seconds: Int) = repeat(seconds) { plain.advance(1.0); heated.advance(1.0) }

        assertEquals(8.0, boost(), 0.011)
        runBoth(15)
        assertEquals(8.0, boost(), 0.011)
        runBoth(14)
        assertEquals(8.0, boost(), 0.011)
        runBoth(1)
        assertEquals(0.0, boost(), 0.011)
        runBoth(30)
        assertEquals(0.0, boost(), 0.011)
    }

    @Test
    fun heatingTheSensorAgainRestartsTheBoost() {
        val plain = HouseSimulation(tenAm)
        val heated = HouseSimulation(tenAm)
        heated.heatSensor()
        fun boost() = heated.status().outsideTemp!! - plain.status().outsideTemp!!
        fun runBoth(seconds: Int) = repeat(seconds) { plain.advance(1.0); heated.advance(1.0) }

        runBoth(15)
        heated.heatSensor()
        // Druhé zahřátí se nepřičítá (nebylo by 16 °C), jen znovu odměří 30 s.
        assertEquals(8.0, boost(), 0.011)
        runBoth(29)
        assertEquals("prvních 30 s už uběhlo, druhé zahřátí ještě platí", 8.0, boost(), 0.011)
        runBoth(1)
        assertEquals(0.0, boost(), 0.011)
    }

    // Za kolik sekund od zahřátí čidla se začne chladit, nebo null, když ne do 30 s.
    private fun HouseSimulation.secondsToCoolingAfterHeatingTheSensor(): Int? {
        heatSensor()
        return (1..30).firstOrNull { advance(1.0); status().cooling }
    }

    // Termostat po rozběhu drží 23 °C a vnitřní teplota cyklí mezi 22,7 a 23,0 °C (cyklus má asi 28 s). Vrátí simulaci
    // v prvním kroku, ve kterém platí podmínka nad Stavem z minulého a z tohoto kroku.
    private fun steadyCycleAt(phase: (previous: HouseStatus, now: HouseStatus) -> Boolean): HouseSimulation {
        val sim = HouseSimulation(tenAm, 22.0)
        sim.run(300)
        var previous = sim.status()
        repeat(60) {
            sim.advance(1.0)
            val now = sim.status()
            if (phase(previous, now)) return sim
            previous = now
        }
        throw AssertionError("cyklus termostatu tu chvíli do minuty nenabídl")
    }

    // Scénář 3 ze spec (kap. 1): termostat drží 23 °C a zahřáté čidlo táhne vnitřní teplotu nahoru, až se začne
    // chladit. Chladí se až nad cílem o víc než 1,0 °C (docs/ble-rozhrani.md, Stav), proto čidlo hlásí +8 °C. Zkouší
    // se z různých chvil cyklu topení i vlny venkovní teploty, aby na okamžiku klepnutí nezáleželo.
    @Test
    fun heatingTheSensorMakesTheThermostatCoolWithinThirtySeconds() {
        for (warmUp in 300 until 900 step 7) {
            val sim = HouseSimulation(tenAm, 22.0)
            sim.run(warmUp)
            val before = sim.status()
            assertTrue("po $warmUp s je uvnitř ${before.insideTemp}", before.insideTemp!! in 22.6..23.1)
            assertFalse("po $warmUp s", before.cooling)

            val seconds = sim.secondsToCoolingAfterHeatingTheSensor()
            assertNotNull("po $warmUp s zahřáté čidlo nerozběhlo chlazení do 30 s", seconds)
        }
    }

    // Dvě krajní chvíle cyklu: hned po dotopení je uvnitř nejtepleji, na začátku topení nejchladněji a ještě se
    // musí dotopit.
    @Test
    fun heatingTheSensorCoolsWithinThirtySecondsRightAfterHeatingStoppedAndWhileHeating() {
        val heatingJustStopped = steadyCycleAt { previous, now -> previous.heating && !now.heating }
        assertNotNull("hned po dotopení", heatingJustStopped.secondsToCoolingAfterHeatingTheSensor())

        val heatingJustStarted = steadyCycleAt { previous, now -> !previous.heating && now.heating }
        assertNotNull("na začátku topení", heatingJustStarted.secondsToCoolingAfterHeatingTheSensor())
    }

    @Test
    fun coveringLightSensorAgainRestartsThirtySeconds() {
        val sim = HouseSimulation(tenAm)
        sim.coverLightSensor()
        sim.run(20)
        sim.coverLightSensor()
        sim.run(29)
        assertEquals(5.0, sim.status().lux)
        sim.run(1)
        assertTrue(sim.status().lux!! > 250.0)
    }

    @Test
    fun coveringLightSensorCapsLuxForThirtySeconds() {
        val sim = HouseSimulation(tenAm)
        sim.coverLightSensor()
        assertEquals(5.0, sim.status().lux)
        sim.run(29)
        assertEquals(5.0, sim.status().lux)
        assertTrue(sim.light.on)
        sim.run(1)
        assertTrue(sim.status().lux!! > 250.0)
        assertFalse(sim.light.on)

        // „Nejvýš 5“: tmavší hodnoty zůstávají, jasnější se srazí.
        val evening = HouseSimulation(at(20))
        evening.coverLightSensor()
        assertEquals(5.0, evening.status().lux)
        val night = HouseSimulation(elevenPm)
        night.coverLightSensor()
        assertEquals(0.5, night.status().lux)
    }

    @Test
    fun statusNumbersAreRoundedToTwoDecimals() {
        val sim = HouseSimulation(tenAm)
        sim.run(37)
        val status = sim.status()
        // Hodnoty ve 37. vteřině jsou spočtené zvlášť ze vzorců v zadání (surově 21,651136; 45,510892 a tak dál).
        assertEquals(21.65, status.outsideTemp)
        assertEquals(45.51, status.humidity)
        assertEquals(1013.26, status.pressure)
        assertEquals(327.99, status.lux)
        // Vnitřní teplota a cíl procházejí stejným zaokrouhlením.
        listOf(status.insideTemp, status.target).forEach { assertTrue("$it", BigDecimal.valueOf(it!!).scale() <= 2) }
    }

    @Test
    fun simulationIsDeterministic() {
        fun script(): List<Any> {
            val sim = HouseSimulation(at(21, 58))
            val seen = mutableListOf<Any>()
            repeat(300) { second ->
                if (second == 20) sim.triggerMotion()
                if (second == 60) sim.send("""{"svetlo":{"barva":[0,0,255]}}""")
                if (second == 90) sim.coverLightSensor()
                if (second == 120) sim.heatSensor()
                seen.add(sim.advance(1.0))
                seen.add(sim.status())
            }
            return seen
        }
        assertEquals(script(), script())
    }

    // Události

    @Test
    fun quietStepReportsNoEvents() {
        val sim = HouseSimulation(tenAm)
        assertEquals(emptySet<SimEvent>(), sim.advance(1.0))
        assertEquals(emptySet<SimEvent>(), sim.advance(1.0))
    }

    @Test
    fun motionIsHeldForThreeSeconds() {
        val sim = HouseSimulation(tenAm)
        assertFalse(sim.status().motion)
        sim.triggerMotion()
        assertTrue(sim.status().motion)
        sim.run(2)
        assertTrue(sim.status().motion)
        sim.run(1)
        assertFalse(sim.status().motion)
    }

    @Test
    fun motionRetriggeredWhileHeldIsNotANewStart() {
        val sim = HouseSimulation(tenAm)
        sim.triggerMotion()
        assertEquals(setOf(SimEvent.MOTION), sim.advance(1.0))
        sim.triggerMotion()
        assertEquals(emptySet<SimEvent>(), sim.advance(1.0))
        // Druhý pohyb držení prodloužil do čtvrté vteřiny, po jeho konci je další pohyb zase nový začátek.
        sim.run(4)
        assertFalse(sim.status().motion)
        sim.triggerMotion()
        assertEquals(setOf(SimEvent.MOTION), sim.advance(1.0))
    }

    @Test
    fun nightMotionReportsMotionAndLightOnce() {
        val sim = HouseSimulation(elevenPm)
        sim.triggerMotion()
        assertEquals(setOf(SimEvent.MOTION, SimEvent.LIGHT), sim.advance(1.0))
        // Pohyb dál trvá a konec pohybu ani nezměněné světlo událost nejsou.
        (2..29).forEach { assertEquals("second $it", emptySet<SimEvent>(), sim.advance(1.0)) }
        assertEquals(setOf(SimEvent.LIGHT), sim.advance(1.0))
    }

    @Test
    fun dayPartChangeIsReported() {
        val sim = HouseSimulation(at(18, 59, 55))
        sim.run(4)
        assertEquals(setOf(SimEvent.DAY_PART, SimEvent.LIGHT), sim.advance(1.0))
        assertEquals(DayPart.EVENING, sim.status().dayPart)
        assertEquals(emptySet<SimEvent>(), sim.advance(1.0))
    }

    @Test
    fun changesBetweenStepsAreReportedByTheNextAdvance() {
        val sim = HouseSimulation(tenAm)
        sim.advance(1.0)

        sim.send("""{"svetlo":"zapnout"}""")
        assertEquals(setOf(SimEvent.LIGHT), sim.advance(1.0))
        assertEquals(emptySet<SimEvent>(), sim.advance(1.0))

        sim.triggerMotion()
        assertEquals(setOf(SimEvent.MOTION), sim.advance(1.0))
    }

    @Test
    fun writeMovingTheDayPartTakesEffectAtOnceAndIsReportedByTheNextAdvance() {
        // Spec 4.3: při začátku noci 10:05 je v 10:30 noc.
        val sim = HouseSimulation(at(10, 30))
        sim.send("""{"svetlo":"zapnout"}""")
        sim.advance(1.0)

        assertTrue(sim.write("""{"zacatek_noc":"10:05"}"""))
        assertEquals(DayPart.NIGHT, sim.status().dayPart)
        assertEquals(20.0, sim.status().target)
        assertEquals(off, sim.light)
        assertEquals(setOf(SimEvent.DAY_PART, SimEvent.LIGHT), sim.advance(1.0))
    }

    @Test
    fun dayPartEventsComeAtTheConfiguredStarts() {
        val sim = HouseSimulation(at(0))
        val changes = mutableListOf<Pair<Int, DayPart?>>()
        for (second in 1 until 86_400) {
            if (SimEvent.DAY_PART in sim.advance(1.0)) changes += second to sim.status().dayPart
        }
        assertEquals(
            listOf(7 * 3600 to DayPart.DAY, 19 * 3600 to DayPart.EVENING, 22 * 3600 to DayPart.NIGHT),
            changes,
        )
    }
}
