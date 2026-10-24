"""
Spuštění domku. Na Raspberry ho `python -m domek` rozjede se skutečnými součástkami a Bluetooth
serverem. Příkaz `python -m domek --simulace` ho rozjede na PC se simulovaným hardwarem, takže se
bez Raspberry dá zkoušet logika i celý stav.

Na Raspberry běží všechno v jedné smyčce GLib: časovač 1 s dělá krok domku a posílá stav,
časovač 100 ms kontroluje pohyb a Bluetooth server ve stejné smyčce obsluhuje tablet. Sestava
je pevná: čidlo osvětlení TSL2561, světlo RGB LED a oranžová LED modulu semafor jako kontrolka
pohybu. Žádné volby součástek nejsou.

Simulace běží ve smyčce po 100 ms: v každé pauze se zkontroluje pohyb a jednou za sekundu
proběhne krok domku a jeho stav se vypíše jako jeden řádek JSON.
"""
from __future__ import annotations

import argparse
import itertools
import json
import logging
import time
from datetime import datetime

from domek.ble_server import BleServer
from domek.casovac import opakuj
from domek.hardware.cidlo_bme280 import Bme280
from domek.hardware.cidlo_pohybu import Pir
from domek.hardware.cidlo_tsl2561 import Tsl2561
from domek.hardware.displej_oled import OledDisplej
from domek.hardware.indikatory import LedIndikatory
from domek.hardware.rgb_led import RgbLed
from domek.hardware.simulace import (SimDisplej, SimIndikatory, SimMeteo, SimNastavovacCasu,
                                     SimPohyb, SimSvetelnost, SimVystupSvetla)
from domek.hardware.systemovy_cas import SystemovyCas
from domek.jadro import Domek
from domek.nastaveni import nacti

CESTA_NASTAVENI = "/var/lib/domek/nastaveni.json"
CESTA_NASTAVENI_SIMULACE = "nastaveni-simulace.json"
TIK_S = 0.1           # jak často se kontroluje pohyb [s]
TIKU_NA_KROK = 10     # kolik ticků dá jednu sekundu, tedy jeden krok domku


def _cele_cislo(text: str) -> int:
    """Celé číslo i s předponou, třeba 0x76, jako `int(text, 0)`."""
    try:
        return int(text, 0)
    except ValueError:
        zprava = f"'{text}' neni cele cislo (napr. 118 nebo 0x76)"
        raise argparse.ArgumentTypeError(zprava) from None


def _simuluj(domek: Domek, sekund: int | None) -> None:
    """Točí simulaci `sekund` sekund, bez omezení do Ctrl+C. Každá sekunda je jeden krok domku."""
    sekundy = itertools.count() if sekund is None else range(sekund)
    for _ in sekundy:
        domek.krok(datetime.now(), time.monotonic())
        print(json.dumps(domek.stav(), ensure_ascii=False), flush=True)
        for _ in range(TIKU_NA_KROK):
            time.sleep(TIK_S)
            domek.kontroluj_pohyb(time.monotonic())


def _spust_na_raspberry(bme280_adresa: int, cesta: str) -> None:
    """Rozjede domek na Raspberry: součástky, Bluetooth server a časovače v jedné smyčce GLib.

    Sestava je pevná: čidlo osvětlení TSL2561, světlo RGB LED na GPIO16, 20 a 21 a oranžová LED
    modulu semafor (GPIO22) jako kontrolka pohybu, ta patří k `LedIndikatory`. RGB LED žádný
    časovač nepotřebuje.

    Blokuje, dokud smyčka běží. Knihovna gi existuje jen na Raspberry, proto se importuje až tady.
    Výjimka v časovači ukončí celý program (`opakuj`), aby ho systemd spustil znovu. Porucha
    světla výjimka není: ohlásí se jako chyba "svetlo" a domek běží dál.
    """
    from gi.repository import GLib

    logging.basicConfig(level=logging.INFO)
    domek = Domek(nacti(cesta), Bme280(bme280_adresa), Pir(), LedIndikatory(),
                  cesta_nastaveni=cesta, displej=OledDisplej(),
                  svetelnost=Tsl2561(), vystup_svetla=RgbLed(),
                  nastavovac_casu=SystemovyCas())
    # První krok proběhne dřív, než se domek začne inzerovat, takže tablet nikdy nedostane stav
    # bez času a měření
    domek.krok(datetime.now(), time.monotonic())
    server = BleServer(domek)

    def tik() -> None:
        domek.krok(datetime.now(), time.monotonic())
        server.posli_stav()

    def kontrola_pohybu() -> None:
        if domek.kontroluj_pohyb(time.monotonic()):
            server.posli_stav()

    GLib.timeout_add(1000, opakuj(tik))
    GLib.timeout_add(100, opakuj(kontrola_pohybu))
    server.spust()


def _parser(*, s_napovedou: bool = True) -> argparse.ArgumentParser:
    """Parser voleb programu. `s_napovedou=False` vynechá volbu --help, viz `over_volby`."""
    # allow_abbrev=False: zkratka (třeba --sek místo --sekund) je chyba, ne celá volba
    parser = argparse.ArgumentParser(
        prog="python -m domek", allow_abbrev=False, add_help=s_napovedou,
        description="Chytry dum: termostat, svetlo a cidla ovladane pres Bluetooth LE.")
    parser.add_argument("--simulace", action="store_true",
                        help="bez hardwaru, na PC se simulovanymi cidly")
    parser.add_argument("--sekund", type=int, metavar="N",
                        help="jen se --simulace: skonci po N sekundach, tedy po N krocich "
                             "(bez volby bezi do Ctrl+C)")
    parser.add_argument("--nastaveni", metavar="CESTA",
                        help=f"soubor s nastavenim (vychozi {CESTA_NASTAVENI}, "
                             f"se --simulace {CESTA_NASTAVENI_SIMULACE})")
    parser.add_argument("--bme280-adresa", type=_cele_cislo, default=0x76, metavar="ADRESA",
                        help="I2C adresa BME280, pouziva se jen na Raspberry (vychozi 0x76)")
    return parser


def over_volby(argv: list[str]) -> None:
    """Jen zkontroluje volby pro službu na Raspberry. S platnými nic nespustí, nenačte ani nevypíše.

    Volá ji `deploy/instaluj.sh` dřív, než volby zapíše do služby: s překlepem (třeba `--sekundy`)
    by služba končila chybou voleb a systemd by ji spouštěl každé 2 s znovu, pořád dokola.
    Neplatná volba skončí stejně jako v `main` chybou argparse s kódem 2. Odmítne se i `--help`
    (služba by jen vypsala nápovědu a skončila) a `--simulace` (služba by nespustila Bluetooth).
    """
    parser = _parser(s_napovedou=False)
    if parser.parse_args(argv).simulace:
        parser.error("volba --simulace patri jen na PC, sluzba na Raspberry ji mit nesmi")


def main(argv: list[str] | None = None) -> None:
    """Zpracuje volby a spustí domek. `argv` je seznam voleb, bez něj se čtou z příkazové řádky."""
    args = _parser().parse_args(argv)

    cesta = args.nastaveni
    if cesta is None:
        cesta = CESTA_NASTAVENI_SIMULACE if args.simulace else CESTA_NASTAVENI
    if not args.simulace:
        _spust_na_raspberry(args.bme280_adresa, cesta)
        return

    domek = Domek(
        nacti(cesta), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta,
        displej=SimDisplej(), svetelnost=SimSvetelnost(), vystup_svetla=SimVystupSvetla(),
        nastavovac_casu=SimNastavovacCasu())
    try:
        _simuluj(domek, args.sekund)
    except KeyboardInterrupt:
        pass  # Ctrl+C je normální konec běhu bez --sekund


if __name__ == "__main__":
    main()
