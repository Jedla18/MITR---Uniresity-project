package cz.vsb.mitr.domek.domain

private class PiError(val text: String, val detail: String)

// Porucha BME280. Podle ní se pozná i BMP280, které vlhkost neměří, a simulace ji hlásí stejně jako Raspberry.
const val METEO_FAULT = "meteo"

// Kódy z pole `chyby` ve Stavu a jejich důsledky podle docs/ble-rozhrani.md (Hodnoty v poli chyby).
private val knownErrors = mapOf(
    METEO_FAULT to PiError(
        "Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
        "Venkovní teplota, vlhkost a tlak chybí. Dokud čidlo nejde přečíst, termostat netopí ani nechladí.",
    ),
    "osvetleni" to PiError("Čidlo osvětlení (TSL2561) neodpovídá", "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí."),
    "displej" to PiError("Displej domu nefunguje", "Dům běží dál, jen bez displeje."),
    "svetlo" to PiError("Světlo (RGB LED) nejde ovládat", "Aplikace ukazuje, jak má světlo svítit, ne jak opravdu svítí."),
    "nastaveni" to PiError("Raspberry nemůže uložit nastavení", "Změny platí, ale po restartu Raspberry se vrátí dřívější nastavení."),
)

fun piErrorText(code: String): String = knownErrors[code]?.text ?: "Raspberry hlásí chybu: $code"

fun piErrorDetail(code: String): String = knownErrors[code]?.detail ?: ""
