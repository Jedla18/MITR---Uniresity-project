"""
Text na displeji: ze stavu domku udělá řádky, které se vejdou na OLED displej.

Modul jen formátuje text a o hardwaru neví nic, takže se zkouší na PC. Řádky na displej kreslí
ovladač `domek.hardware.displej_oled`. Texty jsou jen ASCII, aby je zobrazilo jakékoli písmo.
"""
from __future__ import annotations

from datetime import datetime

ZNAKU_NA_RADEK = 21  # displej je široký 128 bodů a jeden znak 6 bodů
SLOUPEC_BLE = 18     # BLE zabírá poslední tři sloupce prvního řádku
CHYBEJICI_CAS = "--:--"


def _teplota(hodnota: float | None) -> str:
    return "--.-" if hodnota is None else f"{hodnota:4.1f}"


def _tlak(hodnota: float | None) -> str:
    return "  --" if hodnota is None else f"{hodnota:4.0f}"


def radky_displeje(stav: dict, pripojeno: bool) -> list[str]:
    """Řádky textu pro displej: nejvýš 6 řádků po nejvýš 21 znacích ASCII.

    `stav` je slovník z `Domek.stav()`, `pripojeno` říká, jestli je připojený tablet (pak první
    řádek končí na BLE). Venku se ukazuje teplota a tlak, který měří BME280 i BMP280. Vlhkost
    BMP280 neměří, proto na displeji není a tablet ji má ve Stavu. Chybějící hodnota se ukáže
    jako pomlčky a chybějící cíl, tedy vypnutý termostat, jako "vyp". Řádek s chybami je jen
    tehdy, když nějaká chyba je, a je poslední.
    Řádek, který by byl delší než displej, se zkrátí: bez toho by ho displej uřízl sám.
    """
    cas = stav["cas"]
    hodina = CHYBEJICI_CAS if cas is None else datetime.fromisoformat(cas).strftime("%H:%M")
    usek = stav["usek"]
    cas_a_usek = hodina if usek is None else f"{hodina} {usek}"
    if pripojeno:
        cas_a_usek = cas_a_usek.ljust(SLOUPEC_BLE) + "BLE"

    cil = " vyp" if stav["cil"] is None else _teplota(stav["cil"])
    uvnitr = f"Uvnitr {_teplota(stav['t_uvnitr'])} cil {cil}"
    venku = f"Venku  {_teplota(stav['t_venku'])}C {_tlak(stav['tlak'])}hPa"

    # Topení a chlazení spolu nejedou, takže pořadí podmínek nemá vliv na výsledek
    akce = "TOPI" if stav["topi"] else "CHLADI" if stav["chladi"] else "klid"
    termostat = f"{akce:<7}{stav['rezim']}" + (" pryc" if stav["nikdo_doma"] else "")

    svetlo = stav["svetlo"]
    svetlo_text = ("Svetlo " + ("zap" if svetlo["zap"] else "vyp")
                   + (" rucne" if svetlo["rucne"] else ""))

    radky = [cas_a_usek, uvnitr, venku, termostat, svetlo_text]
    if stav["chyby"]:
        radky.append("CHYBA " + ",".join(stav["chyby"]))
    return [radek[:ZNAKU_NA_RADEK] for radek in radky]
