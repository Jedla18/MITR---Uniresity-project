package cz.vsb.mitr.domek.data.model

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.EXAMPLE_SETTINGS
import cz.vsb.mitr.domek.EXAMPLE_STATUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseJsonTest {
    @Test
    fun decodesExampleStatus() {
        val status = EXAMPLE

        assertEquals("2026-10-20T10:00:00", status.time)
        assertEquals(DayPart.DAY, status.dayPart)
        assertEquals("den", status.dayPartText)
        assertEquals(22.0, status.outsideTemp)
        assertNull(status.lux)
        assertEquals(23.0, status.target)
        assertTrue(status.heating)
        assertFalse(status.cooling)
        assertEquals(ThermostatMode.AUTO, status.mode)
        assertEquals(LightState(false, listOf(0, 0, 0), 0, false), status.light)
        assertTrue(status.errors.isEmpty())
    }

    @Test
    fun decodesEveryStatusField() {
        // U každého pole jiná hodnota než výchozí, aby překlep v @SerialName nebyl tiše nahrazen null.
        val json = """{"cas":"2026-10-20T21:15:30","usek":"vecer","t_venku":5.5,"vlhkost":81.0,"tlak":998.5,"lux":12.5,"t_uvnitr":21.5,"cil":22.0,"topi":false,"chladi":true,"rezim":"rucni","nikdo_doma":true,"pohyb":true,"svetlo":{"zap":true,"barva":[255,200,100],"jas":80,"rucne":true},"chyby":["meteo","svetlo"]}"""

        assertEquals(
            HouseStatus(
                time = "2026-10-20T21:15:30",
                dayPartText = "vecer",
                outsideTemp = 5.5,
                humidity = 81.0,
                pressure = 998.5,
                lux = 12.5,
                insideTemp = 21.5,
                target = 22.0,
                heating = false,
                cooling = true,
                mode = ThermostatMode.MANUAL,
                nobodyHome = true,
                motion = true,
                light = LightState(on = true, color = listOf(255, 200, 100), brightness = 80, manual = true),
                errors = listOf("meteo", "svetlo"),
            ),
            decodeStatus(json.toByteArray()),
        )
    }

    @Test
    fun acceptsFloatIntegersAndUnknownKeys() {
        val json = "{\"novy_klic\":1," + EXAMPLE_STATUS.removePrefix("{")
            .replace("\"jas\":0", "\"jas\":50.0")
            .replace("\"barva\":[0,0,0]", "\"barva\":[255.0,128,0]")

        val light = decodeStatus(json.toByteArray())!!.light

        assertEquals(50, light.brightness)
        assertEquals(listOf(255, 128, 0), light.color)
    }

    @Test
    fun brokenJsonGivesNull() {
        assertNull(decodeStatus(EXAMPLE_STATUS.take(40).toByteArray()))
        assertNull(decodeStatus("{}".toByteArray()))
        assertNull(decodeSettings(ByteArray(0)))
    }

    @Test
    fun invalidValuesGiveNull() {
        listOf(
            EXAMPLE_STATUS.replace("\"rezim\":\"auto\"", "\"rezim\":\"turbo\""),
            EXAMPLE_STATUS.replace("\"jas\":0", "\"jas\":null"),
            EXAMPLE_STATUS.replace("\"jas\":0", "\"jas\":\"vysoky\""),
            EXAMPLE_STATUS.replace("\"jas\":0", "\"jas\":{\"v\":1}"),
            EXAMPLE_STATUS.replace("\"barva\":[0,0,0]", "\"barva\":[0,\"x\",0]"),
        ).forEach { json -> assertNull(json, decodeStatus(json.toByteArray())) }
    }

    // Před prvním krokem domku jsou čas i úsek null (ble-rozhrani.md, Stav). Stav se kvůli tomu nezahodí.
    @Test
    fun nullTimeAndDayPartKeepTheStatus() {
        val json = EXAMPLE_STATUS
            .replace("\"cas\":\"2026-10-20T10:00:00\"", "\"cas\":null")
            .replace("\"usek\":\"den\"", "\"usek\":null")

        val status = decodeStatus(json.toByteArray())!!

        assertNull(status.time)
        assertNull(status.dayPartText)
        assertNull(status.dayPart)
    }

    // Nový úsek dne se má ukázat, jak přišel (ble-rozhrani.md, Formát JSON).
    @Test
    fun unknownDayPartKeepsItsText() {
        val json = EXAMPLE_STATUS.replace("\"usek\":\"den\"", "\"usek\":\"odpoledne\"")

        val status = decodeStatus(json.toByteArray())!!

        assertEquals("odpoledne", status.dayPartText)
        assertNull(status.dayPart)
    }

    @Test
    fun nullSensorValues() {
        val json = EXAMPLE_STATUS
            .replace("\"t_venku\":22.0", "\"t_venku\":null")
            .replace("\"vlhkost\":45.0", "\"vlhkost\":null")
            .replace("\"tlak\":1013.25", "\"tlak\":null")
            .replace("\"t_uvnitr\":22.0", "\"t_uvnitr\":null")

        val status = decodeStatus(json.toByteArray())!!

        assertNull(status.outsideTemp)
        assertNull(status.humidity)
        assertNull(status.pressure)
        assertNull(status.insideTemp)
    }

    @Test
    fun decodesErrorListAsUtf8() {
        val json = EXAMPLE_STATUS.replace("\"chyby\":[]", "\"chyby\":[\"meteo\",\"čidlo\"]")

        assertEquals(listOf("meteo", "čidlo"), decodeStatus(json.toByteArray())!!.errors)
    }

    // Starší program na Raspberry poslední tři klíče z ble-rozhrani.md neposílá; Nastavení se kvůli tomu nezahodí.
    @Test
    fun settingsWithoutLightKeys() {
        assertEquals(
            DEFAULTS.copy(lightOnMotion = null, lightColor = null, lightBrightness = null),
            decodeSettings(EXAMPLE_SETTINGS.toByteArray()),
        )
    }

    @Test
    fun settingsWithLightKeys() {
        val json = EXAMPLE_SETTINGS.removeSuffix("}") +
            ",\"nocni_svetlo_pri_pohybu\":false,\"barva_svetla\":[255,147,41],\"jas_svetla\":60}"

        val settings = decodeSettings(json.toByteArray())!!

        assertEquals(false, settings.lightOnMotion)
        assertEquals(listOf(255, 147, 41), settings.lightColor)
        assertEquals(60, settings.lightBrightness)
    }

    @Test
    fun settingsAcceptFloatIntegersForLightKeys() {
        val json = EXAMPLE_SETTINGS.removeSuffix("}") + ",\"barva_svetla\":[255.0,147,41],\"jas_svetla\":60.0}"

        val settings = decodeSettings(json.toByteArray())!!

        assertEquals(listOf(255, 147, 41), settings.lightColor)
        assertEquals(60, settings.lightBrightness)
    }

    // Klíč z původního návrhu Raspberry nezná; nesmí se plést s nocni_svetlo_pri_pohybu.
    @Test
    fun oldMotionKeyIsIgnored() {
        val json = EXAMPLE_SETTINGS.removeSuffix("}") + ",\"svetlo_pri_pohybu\":false}"

        assertNull(decodeSettings(json.toByteArray())!!.lightOnMotion)
    }

    @Test
    fun rgbUsesFirstThreeComponentsClampedAndPadded() {
        assertEquals(Rgb(1, 2, 3), LightState(true, listOf(1, 2, 3, 4), 10, false).rgb)
        assertEquals(Rgb(255, 0, 0), LightState(true, listOf(300, -5), 10, false).rgb)
        assertEquals(Rgb(0, 0, 0), LightState(false, emptyList(), 0, false).rgb)
    }

    @Test
    fun encodesLightStateWithIntegersAndWithoutRgb() {
        val light = LightState(true, listOf(255, 128, 0), 50, true)

        assertEquals("""{"zap":true,"barva":[255,128,0],"jas":50,"rucne":true}""", HouseJson.encodeToString(light))
    }

    @Test
    fun dayPartsUseJsonNames() {
        assertEquals(listOf("\"den\"", "\"vecer\"", "\"noc\""), DayPart.entries.map { HouseJson.encodeToString(it) })
        DayPart.entries.forEach { part -> assertEquals("\"${part.json}\"", HouseJson.encodeToString(part)) }
    }

    @Test
    fun thermostatModeJsonMatchesSerialName() {
        ThermostatMode.entries.forEach { mode ->
            assertEquals("\"${mode.json}\"", HouseJson.encodeToString(mode))
        }
    }
}
