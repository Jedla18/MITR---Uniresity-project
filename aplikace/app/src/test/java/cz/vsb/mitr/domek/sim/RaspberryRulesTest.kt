package cz.vsb.mitr.domek.sim

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseJson
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.model.toJsonObject
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RaspberryRulesTest {
    // Rozsahy ze spec 4.1: krajní hodnoty platí, nejbližší hodnoty za nimi ne.
    private class Bounds(val key: String, val min: String, val max: String, val below: String, val above: String)

    private val numericBounds = listOf(
        Bounds("cil_den", "5.0", "35.0", "4.9", "35.1"),
        Bounds("cil_noc", "5.0", "35.0", "4.9", "35.1"),
        Bounds("cil_rucni", "5.0", "35.0", "4.9", "35.1"),
        Bounds("prah_lux", "1.0", "10000.0", "0.9", "10000.1"),
        Bounds("utlum_nikdo_doma", "0.0", "10.0", "-0.1", "10.1"),
        Bounds("nocni_svetlo_s", "5", "600", "4", "601"),
        Bounds("nikdo_doma_po_min", "1", "240", "0", "241"),
    )

    private fun patch(s: String): JsonObject = HouseJson.parseToJsonElement(s).jsonObject

    // Zápisy se zkoušejí proti výchozím hodnotám z TestData.
    private fun settingsAfter(s: String) = applySettingsPatch(DEFAULTS, patch(s))

    private fun commandOf(s: String) = parseCommand(patch(s))

    private fun assertRejected(key: String, vararg values: String) {
        values.forEach { assertNull("$key=$it", settingsAfter("""{"$key":$it}""")) }
    }

    @Test
    fun patchChangesOneDecimalKey() {
        assertEquals(DEFAULTS.copy(targetDay = 24.5), settingsAfter("""{"cil_den":24.5}"""))
    }

    @Test
    fun patchChangesSeveralKeysAtOnce() {
        assertEquals(
            DEFAULTS.copy(nightLightSeconds = 60, mode = ThermostatMode.MANUAL),
            settingsAfter("""{"nocni_svetlo_s":60,"rezim_termostatu":"rucni"}"""),
        )
    }

    @Test
    fun patchChangesStartTime() {
        assertEquals(DEFAULTS.copy(nightStart = "10:05"), settingsAfter("""{"zacatek_noc":"10:05"}"""))
    }

    @Test
    fun patchChangesFlag() {
        assertEquals(DEFAULTS.copy(lightOnMotion = false), settingsAfter("""{"nocni_svetlo_pri_pohybu":false}"""))
    }

    @Test
    fun emptyPatchKeepsSettings() {
        assertEquals(DEFAULTS, settingsAfter("{}"))
    }

    @Test
    fun everyKeyPatchesItsOwnField() {
        // Zápisy se skládají stejně jako v aplikaci (change a toJsonObject); každý musí skončit ve svém poli.
        val writes = listOf(
            SettingKey.MODE.change("vypnuto") to DEFAULTS.copy(mode = ThermostatMode.OFF),
            SettingKey.TARGET_DAY.change(24.5) to DEFAULTS.copy(targetDay = 24.5),
            SettingKey.TARGET_NIGHT.change(18.5) to DEFAULTS.copy(targetNight = 18.5),
            SettingKey.TARGET_MANUAL.change(21.5) to DEFAULTS.copy(targetManual = 21.5),
            SettingKey.DAY_START.change("06:30") to DEFAULTS.copy(dayStart = "06:30"),
            SettingKey.EVENING_START.change("18:15") to DEFAULTS.copy(eveningStart = "18:15"),
            SettingKey.NIGHT_START.change("23:45") to DEFAULTS.copy(nightStart = "23:45"),
            SettingKey.LUX_THRESHOLD.change(120.5) to DEFAULTS.copy(luxThreshold = 120.5),
            SettingKey.NIGHT_LIGHT_SECONDS.change(45.0) to DEFAULTS.copy(nightLightSeconds = 45),
            SettingKey.NOBODY_HOME_AFTER.change(60.0) to DEFAULTS.copy(nobodyHomeAfterMin = 60),
            SettingKey.NOBODY_HOME_SETBACK.change(2.5) to DEFAULTS.copy(nobodyHomeSetback = 2.5),
            SettingKey.LIGHT_ON_MOTION.change(false) to DEFAULTS.copy(lightOnMotion = false),
        )

        assertEquals(SettingKey.entries.toSet(), writes.map { it.first.key }.toSet())
        writes.forEach { (change, expected) ->
            assertEquals(change.key.name, expected, applySettingsPatch(DEFAULTS, listOf(change).toJsonObject()))
        }
    }

    @Test
    fun invalidPatchesChangeNothing() {
        listOf(
            """{"neznamy":1}""", """{"cil_den":true}""", """{"cil_den":"23"}""", """{"cil_den":35.5}""", """{"nocni_svetlo_s":30.5}""",
            """{"nikdo_doma_po_min":0}""", """{"zacatek_noc":"19:00"}""", """{"zacatek_den":"7:00"}""", """{"rezim_termostatu":"turbo"}""",
            """{"nocni_svetlo_pri_pohybu":1}""",
        ).forEach { assertNull(it, settingsAfter(it)) }
    }

    @Test
    fun unknownKeysAreRejected() {
        // Klíč se shoduje přesně, včetně velikosti písmen.
        listOf("""{"neznamy":1}""", """{"CIL_DEN":24}""", """{"":1}""").forEach { assertNull(it, settingsAfter(it)) }
    }

    @Test
    fun oneInvalidKeyRejectsTheWholePatch() {
        assertNull(settingsAfter("""{"cil_den":24.5,"neznamy":1}"""))
        assertNull(settingsAfter("""{"cil_den":24.5,"cil_noc":99}"""))
    }

    @Test
    fun numericRangesIncludeTheirEnds() {
        numericBounds.forEach { b ->
            assertNotNull("${b.key} dolní kraj", settingsAfter("""{"${b.key}":${b.min}}"""))
            assertNotNull("${b.key} horní kraj", settingsAfter("""{"${b.key}":${b.max}}"""))
            assertNull("${b.key} pod rozsahem", settingsAfter("""{"${b.key}":${b.below}}"""))
            assertNull("${b.key} nad rozsahem", settingsAfter("""{"${b.key}":${b.above}}"""))
        }
    }

    @Test
    fun decimalAcceptsAnyJsonNumber() {
        assertEquals(DEFAULTS.copy(targetDay = 24.0), settingsAfter("""{"cil_den":24}"""))
        assertEquals(DEFAULTS.copy(targetDay = 23.5), settingsAfter("""{"cil_den":2.35e1}"""))
    }

    @Test
    fun decimalRejectsEverythingButNumbers() {
        assertRejected("cil_den", "\"23\"", "true", "false", "null", "[23]", """{"hodnota":23}""")
        // Python přečte NaN a Infinity jako čísla, rozsah je ale odmítne; 1e999 přeteče na nekonečno.
        assertRejected("cil_den", "NaN", "Infinity", "-Infinity", "1e999")
    }

    @Test
    fun wholeNumberMustBeWrittenWithoutFraction() {
        assertEquals(DEFAULTS.copy(nightLightSeconds = 60), settingsAfter("""{"nocni_svetlo_s":60}"""))
        assertRejected("nocni_svetlo_s", "30.0", "30.5", "3e1", "\"30\"", "true", "null", "99999999999999999999")
        assertRejected("nikdo_doma_po_min", "30.0", "30.5")
    }

    @Test
    fun flagMustBeJsonTrueOrFalse() {
        assertEquals(DEFAULTS.copy(lightOnMotion = true), settingsAfter("""{"nocni_svetlo_pri_pohybu":true}"""))
        assertRejected("nocni_svetlo_pri_pohybu", "1", "0", "\"true\"", "\"false\"", "null", "[true]")
    }

    // Barva a jas světla (docs/ble-rozhrani.md, Nastavení → Pravidla zápisu). SettingKey nemají, aplikace je jen čte.
    @Test
    fun patchStoresLightColorAndBrightness() {
        assertEquals(DEFAULTS.copy(lightColor = listOf(255, 147, 41)), settingsAfter("""{"barva_svetla":[255,147,41]}"""))
        assertEquals(DEFAULTS.copy(lightBrightness = 30), settingsAfter("""{"jas_svetla":30}"""))
        // Krajní hodnoty platí.
        assertEquals(
            DEFAULTS.copy(lightColor = listOf(0, 0, 0), lightBrightness = 0),
            settingsAfter("""{"barva_svetla":[0,0,0],"jas_svetla":0}"""),
        )
        assertEquals(
            DEFAULTS.copy(lightColor = listOf(255, 255, 255), lightBrightness = 100, targetDay = 24.0),
            settingsAfter("""{"barva_svetla":[255,255,255],"jas_svetla":100,"cil_den":24}"""),
        )
    }

    @Test
    fun patchFillsLightValuesMissingFromOlderSettings() {
        val older = DEFAULTS.copy(lightColor = null, lightBrightness = null)
        assertEquals(older.copy(lightBrightness = 50), applySettingsPatch(older, patch("""{"jas_svetla":50}""")))
        assertEquals(
            older.copy(lightColor = listOf(1, 2, 3)),
            applySettingsPatch(older, patch("""{"barva_svetla":[1,2,3]}""")),
        )
    }

    @Test
    fun lightColorNeedsExactlyThreeWholeChannelsFromZeroTo255() {
        assertRejected(
            "barva_svetla",
            "[0,0]", "[0,0,0,0]", "[]", "[256,0,0]", "[-1,0,0]", "[1.0,0,0]", "[0,0,25.5]", "[1e2,0,0]", "[\"0\",0,0]",
            "[true,0,0]", "[null,0,0]", "[[0],0,0]", "\"x\"", "true", "false", "null", "5", """{"r":0,"g":0,"b":0}""",
        )
    }

    @Test
    fun lightBrightnessNeedsAWholeNumberFromZeroTo100() {
        assertRejected("jas_svetla", "30.0", "101", "-1", "30.5", "3e1", "\"30\"", "true", "null", "[30]")
    }

    @Test
    fun invalidLightValueRejectsTheWholePatch() {
        assertNull(settingsAfter("""{"cil_den":24.5,"jas_svetla":101}"""))
        assertNull(settingsAfter("""{"barva_svetla":[0,0,255],"jas_svetla":30.0}"""))
        assertNull(settingsAfter("""{"jas_svetla":30,"barva_svetla":[0,0]}"""))
    }

    @Test
    fun timeMustBeExactHhMm() {
        assertEquals(DEFAULTS.copy(dayStart = "00:00"), settingsAfter("""{"zacatek_den":"00:00"}"""))
        assertEquals(DEFAULTS.copy(dayStart = "23:59"), settingsAfter("""{"zacatek_den":"23:59"}"""))
        assertRejected(
            "zacatek_den",
            "\"24:00\"", "\"07:60\"", "\"7:00\"", "\"07:00:00\"", "\" 07:00\"", "\"07:00\\n\"", "700", "null", "[\"07:00\"]",
        )
    }

    @Test
    fun modeMustBeOneOfTheKnownTexts() {
        listOf("auto" to ThermostatMode.AUTO, "rucni" to ThermostatMode.MANUAL, "vypnuto" to ThermostatMode.OFF)
            .forEach { (text, mode) ->
                assertEquals(text, DEFAULTS.copy(mode = mode), settingsAfter("""{"rezim_termostatu":"$text"}"""))
            }
        // Holé auto bez uvozovek není text, ale JSON ho jako literál přečte.
        assertRejected("rezim_termostatu", "\"turbo\"", "\"AUTO\"", "\"\"", "auto", "1", "true", "null")
    }

    @Test
    fun startsMayComeInAnyOrder() {
        // Spec 4.3: začátky se neřadí, noc může začít i před dnem.
        assertEquals(DEFAULTS.copy(nightStart = "03:00"), settingsAfter("""{"zacatek_noc":"03:00"}"""))
        // Prohození dvou začátků jedním zápisem je platné, protože se posuzuje až výsledek sloučení.
        val swapped = DEFAULTS.copy(dayStart = "19:00", eveningStart = "07:00")
        assertEquals(swapped, settingsAfter("""{"zacatek_den":"19:00","zacatek_vecer":"07:00"}"""))
        assertEquals(swapped, settingsAfter("""{"zacatek_vecer":"07:00","zacatek_den":"19:00"}"""))
    }

    @Test
    fun equalStartsAreRejectedAfterMerging() {
        listOf(
            """{"zacatek_noc":"19:00"}""",
            """{"zacatek_den":"19:00"}""",
            """{"zacatek_den":"22:00"}""",
            """{"zacatek_vecer":"07:00"}""",
            """{"zacatek_den":"10:00","zacatek_vecer":"10:00"}""",
        ).forEach { assertNull(it, settingsAfter(it)) }
        // Shoda se posuzuje se sloučeným nastavením, ne jen s výchozími hodnotami.
        assertNull(applySettingsPatch(DEFAULTS.copy(nightStart = "10:05"), patch("""{"zacatek_den":"10:05"}""")))
    }

    @Test
    fun lightActionsAreRecognised() {
        listOf(
            "zapnout" to LightAction.ON,
            "vypnout" to LightAction.OFF,
            "prepnout" to LightAction.TOGGLE,
            "auto" to LightAction.AUTO,
        ).forEach { (text, action) ->
            assertEquals(text, HouseCommand.Light(action), commandOf("""{"svetlo":"$text"}"""))
        }
    }

    @Test
    fun lightLookReadsColorAndBrightness() {
        assertEquals(HouseCommand.LightLook(Rgb(0, 0, 255), 50), commandOf("""{"svetlo":{"barva":[0,0,255],"jas":50}}"""))
        assertEquals(HouseCommand.LightLook(brightness = 20), commandOf("""{"svetlo":{"jas":20}}"""))
        assertEquals(HouseCommand.LightLook(color = Rgb(255, 128, 0)), commandOf("""{"svetlo":{"barva":[255,128,0]}}"""))
    }

    @Test
    fun lightLookRangesIncludeTheirEnds() {
        assertEquals(HouseCommand.LightLook(Rgb(0, 0, 0), 0), commandOf("""{"svetlo":{"barva":[0,0,0],"jas":0}}"""))
        assertEquals(
            HouseCommand.LightLook(Rgb(255, 255, 255), 100),
            commandOf("""{"svetlo":{"barva":[255,255,255],"jas":100}}"""),
        )
    }

    @Test
    fun clockIsReadFromPiTime() {
        assertEquals(
            HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)),
            commandOf("""{"cas":"2026-10-20T14:03:00"}"""),
        )
    }

    @Test
    fun commandsFromTheAppAreAccepted() {
        listOf(
            HouseCommand.Light(LightAction.ON),
            HouseCommand.Light(LightAction.OFF),
            HouseCommand.Light(LightAction.TOGGLE),
            HouseCommand.Light(LightAction.AUTO),
            HouseCommand.LightLook(Rgb(0, 0, 255), 50),
            HouseCommand.LightLook(color = Rgb(255, 128, 0)),
            HouseCommand.LightLook(brightness = 20),
            HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)),
        ).forEach { assertEquals(it.toString(), it, parseCommand(it.toJsonObject())) }
    }

    @Test
    fun invalidCommandsAreRejected() {
        listOf(
            """{"svetlo":"blikat"}""", """{"svetlo":{}}""", """{"svetlo":{"barva":[0,0,256]}}""", """{"svetlo":{"jas":101}}""",
            """{"svetlo":"auto","cas":"2026-10-20T14:03:00"}""", """{"cas":"zitra"}""",
        ).forEach { assertNull(it, commandOf(it)) }
    }

    @Test
    fun commandNeedsExactlyOneKnownKey() {
        listOf("{}", """{"topeni":"zapnout"}""", """{"svetlo":"auto","jas":50}""").forEach { assertNull(it, commandOf(it)) }
    }

    @Test
    fun lightMustBeKnownTextOrLook() {
        listOf("\"ZAPNOUT\"", "\"\"", "zapnout", "true", "1", "null", "[\"zapnout\"]").forEach {
            assertNull(it, commandOf("""{"svetlo":$it}"""))
        }
    }

    @Test
    fun lightLookRejectsForeignKeys() {
        listOf("""{"jas":50,"foo":1}""", """{"foo":1}""").forEach { assertNull(it, commandOf("""{"svetlo":$it}""")) }
    }

    @Test
    fun colorNeedsThreeWholeChannelsFromZeroTo255() {
        // 255.0 není celé číslo ze stejného důvodu jako 30.0 u celých čísel v Nastavení.
        listOf(
            "[]", "[0,0]", "[0,0,0,0]", "[0,0,-1]", "[0,0,256]", "[\"0\",\"0\",\"255\"]", "[0,0,25.5]", "[0,0,255.0]",
            "[0,0,null]", "[0,0,true]", "\"red\"", "5", """{"r":0,"g":0,"b":255}""", "null",
        ).forEach { assertNull(it, commandOf("""{"svetlo":{"barva":$it}}""")) }
    }

    @Test
    fun brightnessNeedsAWholeNumberFromZeroTo100() {
        listOf("-1", "101", "50.5", "50.0", "\"50\"", "true", "null", "[50]").forEach {
            assertNull(it, commandOf("""{"svetlo":{"jas":$it}}"""))
        }
    }

    @Test
    fun lightLookNeedsEveryGivenKeyValid() {
        assertNull(commandOf("""{"svetlo":{"barva":[0,0,255],"jas":101}}"""))
        assertNull(commandOf("""{"svetlo":{"barva":[0,0,256],"jas":50}}"""))
    }

    @Test
    fun clockNeedsPiTimeText() {
        listOf("\"zitra\"", "\"2026-10-20T14:03\"", "\"2026-02-30T10:00:00\"", "\"\"", "20261020", "null").forEach {
            assertNull(it, commandOf("""{"cas":$it}"""))
        }
    }
}
