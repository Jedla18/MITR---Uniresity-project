package cz.vsb.mitr.domek

import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.decodeStatus

// Sdílená testovací data (docs/ble-rozhrani.md). EXAMPLE_SETTINGS je Nastavení staršího programu bez posledních tří
// klíčů, DEFAULTS je totéž s nimi doplněnými na výchozí hodnoty.
const val EXAMPLE_STATUS = """{"cas":"2026-10-20T10:00:00","usek":"den","t_venku":22.0,"vlhkost":45.0,"tlak":1013.25,"lux":null,"t_uvnitr":22.0,"cil":23.0,"topi":true,"chladi":false,"rezim":"auto","nikdo_doma":false,"pohyb":false,"svetlo":{"zap":false,"barva":[0,0,0],"jas":0,"rucne":false},"chyby":[]}"""
const val EXAMPLE_SETTINGS = """{"rezim_termostatu":"auto","cil_den":23.0,"cil_noc":20.0,"cil_rucni":22.0,"zacatek_den":"07:00","zacatek_vecer":"19:00","zacatek_noc":"22:00","prah_lux":50.0,"nocni_svetlo_s":30,"nikdo_doma_po_min":30,"utlum_nikdo_doma":3.0}"""
val EXAMPLE: HouseStatus get() = decodeStatus(EXAMPLE_STATUS.toByteArray())!!
val DEFAULTS = HouseSettings(
    ThermostatMode.AUTO, 23.0, 20.0, 22.0, "07:00", "19:00", "22:00", 50.0, 30, 30, 3.0,
    lightOnMotion = true, lightColor = listOf(255, 255, 255), lightBrightness = 100,
)

// Nastavení programu, který uloženou barvu a jas světla nezná: barvu a jas pak mění jen příkaz světla, který světlo zapne.
val WITHOUT_STORED_LOOK = DEFAULTS.copy(lightColor = null, lightBrightness = null)
