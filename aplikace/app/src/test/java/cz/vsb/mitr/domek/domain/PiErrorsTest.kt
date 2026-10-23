package cz.vsb.mitr.domek.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PiErrorsTest {
    // Kódy z docs/ble-rozhrani.md (Hodnoty v poli chyby), text a vysvětlení podle toho, co porucha způsobí.
    private val known = listOf(
        Triple(
            "meteo",
            "Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
            "Venkovní teplota, vlhkost a tlak chybí. Dokud čidlo nejde přečíst, termostat netopí ani nechladí.",
        ),
        Triple("osvetleni", "Čidlo osvětlení (TSL2561) neodpovídá", "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí."),
        Triple("displej", "Displej domu nefunguje", "Dům běží dál, jen bez displeje."),
        Triple("svetlo", "Světlo (RGB LED) nejde ovládat", "Aplikace ukazuje, jak má světlo svítit, ne jak opravdu svítí."),
        Triple("nastaveni", "Raspberry nemůže uložit nastavení", "Změny platí, ale po restartu Raspberry se vrátí dřívější nastavení."),
    )

    @Test
    fun knownCodesHaveTextAndDetail() {
        known.forEach { (code, text, detail) ->
            assertEquals(code, text, piErrorText(code))
            assertEquals(code, detail, piErrorDetail(code))
        }
    }

    // Kódy z původního návrhu Raspberry neposílá; kdyby přišly, ukážou se jako každý neznámý kód.
    @Test
    fun oldSensorCodesAreUnknown() {
        assertEquals("Raspberry hlásí chybu: bme280", piErrorText("bme280"))
        assertEquals("Raspberry hlásí chybu: bh1750", piErrorText("bh1750"))
    }

    @Test
    fun unknownCodeIsReportedWithItsCodeAndNoDetail() {
        assertEquals("Raspberry hlásí chybu: xyz", piErrorText("xyz"))
        assertEquals("", piErrorDetail("xyz"))
    }
}
