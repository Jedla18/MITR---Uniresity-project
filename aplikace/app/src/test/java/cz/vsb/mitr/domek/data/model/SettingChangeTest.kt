package cz.vsb.mitr.domek.data.model

import cz.vsb.mitr.domek.DEFAULTS
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingChangeTest {
    @Test
    fun decimalKeepsFraction() {
        assertEquals("""{"cil_den":23.5}""", listOf(SettingKey.TARGET_DAY.change(23.5)).toJsonObject().toString())
    }

    @Test
    fun wholeKeyIsWrittenAsRoundedInteger() {
        assertEquals("""{"nocni_svetlo_s":30}""", listOf(SettingKey.NIGHT_LIGHT_SECONDS.change(30.0)).toJsonObject().toString())
        assertEquals("""{"nocni_svetlo_s":30}""", listOf(SettingKey.NIGHT_LIGHT_SECONDS.change(29.6)).toJsonObject().toString())
        assertEquals("""{"nikdo_doma_po_min":30}""", listOf(SettingKey.NOBODY_HOME_AFTER.change(30.4)).toJsonObject().toString())
    }

    @Test
    fun modeIsWrittenByJsonName() {
        assertEquals("""{"rezim_termostatu":"rucni"}""", listOf(SettingKey.MODE.change(ThermostatMode.MANUAL.json)).toJsonObject().toString())
    }

    @Test
    fun flagIsWrittenAsBoolean() {
        assertEquals("""{"nocni_svetlo_pri_pohybu":false}""", listOf(SettingKey.LIGHT_ON_MOTION.change(false)).toJsonObject().toString())
    }

    @Test
    fun severalChangesKeepOrder() {
        assertEquals(
            """{"zacatek_den":"07:00","zacatek_vecer":"19:00","zacatek_noc":"22:00"}""",
            listOf(
                SettingKey.DAY_START.change("07:00"),
                SettingKey.EVENING_START.change("19:00"),
                SettingKey.NIGHT_START.change("22:00"),
            ).toJsonObject().toString(),
        )
    }

    @Test
    fun valueOfReadsStoredValues() {
        assertEquals(JsonPrimitive(23.0), DEFAULTS.valueOf(SettingKey.TARGET_DAY))
        assertEquals(JsonPrimitive(30), DEFAULTS.valueOf(SettingKey.NOBODY_HOME_AFTER))
        assertEquals(JsonPrimitive("auto"), DEFAULTS.valueOf(SettingKey.MODE))
        assertEquals(JsonPrimitive("07:00"), DEFAULTS.valueOf(SettingKey.DAY_START))
        assertEquals(JsonPrimitive(true), DEFAULTS.valueOf(SettingKey.LIGHT_ON_MOTION))
    }

    @Test
    fun valueOfIsNullOnlyForMissingLightOnMotion() {
        val withoutFlag = DEFAULTS.copy(lightOnMotion = null)

        assertNull(withoutFlag.valueOf(SettingKey.LIGHT_ON_MOTION))
        SettingKey.entries.filter { it != SettingKey.LIGHT_ON_MOTION }.forEach { key ->
            assertNotNull(key.name, withoutFlag.valueOf(key))
        }
    }

    @Test
    fun valueOfEqualsWhatTheSameChangeWrites() {
        // Návrh se po zápisu porovnává s Nastavením z Raspberry, takže obě cesty musí dát stejný JsonPrimitive.
        assertEquals(SettingKey.TARGET_DAY.change(DEFAULTS.targetDay).value, DEFAULTS.valueOf(SettingKey.TARGET_DAY))
        assertEquals(SettingKey.NIGHT_LIGHT_SECONDS.change(30.0).value, DEFAULTS.valueOf(SettingKey.NIGHT_LIGHT_SECONDS))
        assertEquals(SettingKey.MODE.change("auto").value, DEFAULTS.valueOf(SettingKey.MODE))
        assertEquals(SettingKey.LIGHT_ON_MOTION.change(true).value, DEFAULTS.valueOf(SettingKey.LIGHT_ON_MOTION))
    }

    @Test
    fun valueOfMatchesEveryEncodedSettingsField() {
        // Samostatné hodnoty u každého pole, aby se nezaměnilo třeba nocni_svetlo_s a nikdo_doma_po_min.
        val settings = HouseSettings(
            ThermostatMode.MANUAL, 24.5, 18.0, 21.5, "06:30", "18:15", "23:45", 120.0, 45, 60, 2.5, lightOnMotion = false,
        )
        val encoded = HouseJson.encodeToJsonElement(settings).jsonObject

        SettingKey.entries.forEach { key ->
            assertEquals(key.name, encoded[key.json], settings.valueOf(key))
        }
    }

    @Test
    fun keysMatchSerializedSettingsFields() {
        // DEFAULTS má vyplněná i nepovinná pole, takže se serializují všechna. Barvu a jas světla zapisuje storedLookPatch
        // (nejsou to čísla s rozsahem jako ostatní klíče), proto pro ně SettingKey není.
        val serializedKeys = HouseJson.encodeToJsonElement(DEFAULTS).jsonObject.keys

        assertEquals(serializedKeys - setOf("barva_svetla", "jas_svetla"), SettingKey.entries.map { it.json }.toSet())
    }

    // Uložená barva a jas světla (spec 3 v 2026-10-23-denik-a-vylepseni-design.md)

    // docs/ble-rozhrani.md (Nastavení, Pravidla zápisu): jen části, které se mění, a celá čísla bez desetinné tečky.
    @Test
    fun storedLookPatchHasOnlyTheChangedPartsAsWholeNumbers() {
        assertEquals("""{"barva_svetla":[255,147,41],"jas_svetla":60}""", storedLookPatch(Rgb(255, 147, 41), 60).toString())
        assertEquals("""{"barva_svetla":[0,0,255]}""", storedLookPatch(Rgb(0, 0, 255), null).toString())
        assertEquals("""{"jas_svetla":1}""", storedLookPatch(null, 1).toString())
    }

    // Klíče zápisu jsou názvy polí Nastavení a hodnoty mají tvar, v jakém Nastavení posílá Raspberry. Kdyby se klíč v patchi
    // a v HouseSettings rozešel, aplikace by zapisovala něco, co pak v Nastavení nepozná (a Raspberry by zápis odmítlo).
    @Test
    fun storedLookPatchUsesTheSerialNamesOfTheSettings() {
        val settings = DEFAULTS.copy(lightColor = listOf(255, 147, 41), lightBrightness = 60)
        val encoded = HouseJson.encodeToJsonElement(settings).jsonObject
        val patch = storedLookPatch(Rgb(255, 147, 41), 60)

        assertEquals(2, patch.size)
        patch.forEach { (key, value) -> assertEquals(key, encoded[key], value) }
    }

    @Test
    fun changeRejectsKeyOfWrongKind() {
        assertThrows(IllegalArgumentException::class.java) { SettingKey.MODE.change(1.0) }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.DAY_START.change(1.0) }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.LIGHT_ON_MOTION.change(1.0) }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.TARGET_DAY.change("23.0") }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.NIGHT_LIGHT_SECONDS.change("30") }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.LIGHT_ON_MOTION.change("true") }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.TARGET_DAY.change(true) }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.DAY_START.change(true) }
        assertThrows(IllegalArgumentException::class.java) { SettingKey.MODE.change(true) }
    }
}
