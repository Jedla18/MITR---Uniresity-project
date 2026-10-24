"""
Ověření součástek na Raspberry: každá se zkouší zvlášť, bez zbytku programu.

Spuštění na Raspberry z ~/domek (práce s I2C a GPIO chce práva roota):

    sudo venv/bin/python -m domek.over_soucastky bme280 [--adresa 0x76]
    sudo venv/bin/python -m domek.over_soucastky pir
    sudo venv/bin/python -m domek.over_soucastky led
    sudo venv/bin/python -m domek.over_soucastky displej
    sudo venv/bin/python -m domek.over_soucastky tsl2561
    sudo venv/bin/python -m domek.over_soucastky rgb

Zkouška používá stejné ovladače jako domek, takže ověří i je. Výpisy jsou česky bez diakritiky,
aby se četly v každém terminálu.
"""
from __future__ import annotations

import argparse
import time
from datetime import datetime

from domek.hardware.cidlo_bme280 import ID_BME280, ID_BMP280, REGISTR_ID, Bme280
from domek.hardware.cidlo_pohybu import Pir
from domek.hardware.cidlo_tsl2561 import REGISTR_ID_TSL2561, Tsl2561, lux_z_kanalu
from domek.hardware.displej_oled import OledDisplej
from domek.hardware.indikatory import LedIndikatory
from domek.hardware.rgb_led import RgbLed
from domek.svetlo import VYPNUTO, StavSvetla
from domek.text_displeje import radky_displeje

SBERNICE = 1          # sběrnice I2C na pinech GPIO2 a GPIO3
POCET_MERENI = 3
PAUZA_MEREN_S = 1.0
POHYB_SEKUND = 30
TIK_PIR_S = 0.1       # jak často se čte PIR, stejně jako v programu
POCET_BLIKNUTI = 3
BLIKNUTI_S = 0.5      # jak dlouho LED svítí a jak dlouho je pak zhasnutá
# Zkoušené LED modulu semafor: popis a stav (topí, chladí), při kterém svítí. Oranžová kontrolka
# pohybu se ovládá zvlášť, přes `ukaz_pohyb`.
LEDKY = (("LED topeni (cervena, pin 11)", True, False),
         ("LED chlazeni (zelena, pin 13)", False, True))
POPIS_LED_POHYBU = "Oranzova LED (pohyb, pin 15)"
ADRESA_DISPLEJE = 0x3C  # I2C adresa SSD1306, stejná jako výchozí u ovladače OledDisplej
DISPLEJ_SEKUND = 5      # jak dlouho zůstane na displeji testovací text
ADRESA_TSL2561 = 0x39   # I2C adresa TSL2561 s nezapojeným ADDR, stejná jako výchozí u Tsl2561
POCET_MERENI_LUX = 10   # kolik měření osvětlení se vypíše, mezi nimi je pauza PAUZA_MEREN_S
# Barvy, které zkouška ukáže na RGB LED, každou na RGB_BARVA_S
BARVY_SVETLA = (("cervena", (255, 0, 0)), ("zelena", (0, 255, 0)), ("modra", (0, 0, 255)),
                ("bila", (255, 255, 255)))
RGB_BARVA_S = 1.0  # jak dlouho zkouška drží každou barvu RGB LED


def _vypis(text: str) -> None:
    """Vypíše řádek hned, i když výstup nejde do terminálu (třeba přes `| tee`)."""
    print(text, flush=True)


def _cele_cislo(text: str) -> int:
    """Celé číslo i s předponou, třeba 0x76, jako `int(text, 0)`."""
    try:
        return int(text, 0)
    except ValueError:
        zprava = f"'{text}' neni cele cislo (napr. 118 nebo 0x76)"
        raise argparse.ArgumentTypeError(zprava) from None


def _precti_id_cipu(adresa: int, registr: int = REGISTR_ID) -> int:
    """Přečte ID čipu z registru `registr` (výchozí 0xD0 u BME280) přímo ze sběrnice, aby šel
    poznat i čip, který není ten očekávaný."""
    import smbus2

    with smbus2.SMBus(SBERNICE) as sbernice:
        return sbernice.read_byte_data(adresa, registr)


def over_bme280(args: argparse.Namespace) -> None:
    """Vypíše ID čipu a tři měření po sekundě. Při poruše čidla skončí srozumitelnou chybou.

    U BMP280, který vlhkost neměří, je vlhkost v měření `--`. Upozornění na BMP280 zůstává.
    """
    adresa = args.adresa
    try:
        id_cipu = _precti_id_cipu(adresa)
        _vypis(f"Registr 0xD0 (ID cipu) na adrese 0x{adresa:02X}: 0x{id_cipu:02X}")
        if id_cipu == ID_BME280:
            _vypis("Je to BME280, vse v poradku.")
        elif id_cipu == ID_BMP280:
            _vypis("POZOR: je to BMP280, ktery nema cidlo vlhkosti. Teplota a tlak plati, "
                   "dum posila vlhkost jako neznamou (null) a porucha to neni.")
        else:
            _vypis("POZOR: neznamy cip, ceka se BME280 (ID 0x60). Namerene hodnoty nemusi platit.")
        cidlo = Bme280(adresa, SBERNICE)
        for cislo in range(1, POCET_MERENI + 1):
            if cislo > 1:
                time.sleep(PAUZA_MEREN_S)
            mereni = cidlo.precti()
            vlhkost = "--" if mereni.vlhkost is None else f"{mereni.vlhkost:.1f} %"
            _vypis(f"Mereni {cislo}/{POCET_MERENI}: teplota {mereni.teplota:.2f} C, "
                   f"vlhkost {vlhkost}, tlak {mereni.tlak:.1f} hPa")
    except OSError as chyba:
        raise SystemExit(
            f"CHYBA: BME280 na adrese 0x{adresa:02X} nejde cist ({chyba}). Zkontroluj zapojeni, "
            f"napajeni 3,3 V, zapnute I2C a adresu cidla (i2cdetect -y {SBERNICE}).") from chyba


def over_pir(args: argparse.Namespace) -> None:
    """Třicet sekund vypisuje každou změnu čidla pohybu, na začátku i jeho aktuální stav."""
    pir = Pir()
    _vypis(f"PIR: {POHYB_SEKUND} s vypisuji kazdou zmenu. Mavni rukou pred cidlem.")
    _vypis("HC-SR501 se po zapnuti asi minutu ustaluje, na zacatku proto muze hlasit pohyb sam.")
    posledni = None
    for _ in range(round(POHYB_SEKUND / TIK_PIR_S)):
        pohyb = pir.je_pohyb()
        if pohyb != posledni:
            _vypis("pohyb" if pohyb else "klid")
            posledni = pohyb
        time.sleep(TIK_PIR_S)
    _vypis("Konec zkousky PIR.")


def over_led(args: argparse.Namespace) -> None:
    """Každá LED třikrát bliká, jedna po druhé: červená, zelená a oranžová. Nakonec jsou zhasnuté.

    Všechny tři jsou kontrolky z `LedIndikatory` (topení, chlazení a pohyb), takže se zkouší
    stejným ovladačem jako v programu.
    """
    indikatory = LedIndikatory()
    for popis, topi, chladi in LEDKY:
        _vypis(f"{popis}: {POCET_BLIKNUTI}x blikne")
        for _ in range(POCET_BLIKNUTI):
            indikatory.nastav(topi, chladi)
            time.sleep(BLIKNUTI_S)
            indikatory.nastav(False, False)
            time.sleep(BLIKNUTI_S)
    _vypis(f"{POPIS_LED_POHYBU}: {POCET_BLIKNUTI}x blikne")
    for _ in range(POCET_BLIKNUTI):
        indikatory.ukaz_pohyb(True)
        time.sleep(BLIKNUTI_S)
        indikatory.ukaz_pohyb(False)
        time.sleep(BLIKNUTI_S)


def _testovaci_stav(ted: datetime) -> dict:
    """Vymyšlený stav domku s aktuálním časem, na kterém je vidět všech šest řádků displeje."""
    return {
        "cas": ted.isoformat(timespec="seconds"), "usek": "test",
        "t_venku": 22.0, "vlhkost": 45.0, "tlak": 1013.0, "t_uvnitr": 22.5, "cil": 23.0,
        "topi": True, "chladi": False, "rezim": "auto", "nikdo_doma": False,
        "svetlo": {"zap": True, "barva": [255, 255, 255], "jas": 100, "rucne": True},
        "chyby": ["zkouska"],
    }


def over_displej(args: argparse.Namespace) -> None:
    """Na 5 s ukáže na displeji testovací text s aktuálním časem. Při poruše skončí chybou.

    Text se ukáže ovladačem domku a má všech šest řádků včetně chybového, takže je vidět, jestli
    jsou čitelné a jestli se nepřekrývají. Stejný text se vypíše i do terminálu.
    """
    stav = _testovaci_stav(datetime.now())
    try:
        OledDisplej(ADRESA_DISPLEJE, SBERNICE).zobraz(stav, True)
    except OSError as chyba:
        raise SystemExit(
            f"CHYBA: {chyba} Zkontroluj zapojeni, napajeni 3,3 V, zapnute I2C a adresu displeje "
            f"(i2cdetect -y {SBERNICE}).") from chyba
    _vypis(f"Displej na adrese 0x{ADRESA_DISPLEJE:02X}: {DISPLEJ_SEKUND} s ukazuje tento text:")
    for radek in radky_displeje(stav, True):
        _vypis(f"    {radek}")
    time.sleep(DISPLEJ_SEKUND)
    _vypis("Konec zkousky displeje.")


def over_tsl2561(args: argparse.Namespace) -> None:
    """Vypíše ID čipu a desetkrát po sekundě změří osvětlení (kanály CH0 a CH1 a lux).

    Při poruše čidla skončí srozumitelnou chybou. Zkouška běží i při běžící službě, sběrnice
    I2C se sdílí.
    """
    adresa = ADRESA_TSL2561
    try:
        id_cipu = _precti_id_cipu(adresa, REGISTR_ID_TSL2561)
        _vypis(f"Registr 0x{REGISTR_ID_TSL2561:02X} (ID cipu) na adrese 0x{adresa:02X}: "
               f"0x{id_cipu:02X}")
        if id_cipu >> 4 == 0x5:
            _vypis("Je to TSL2561, vse v poradku.")
        else:
            _vypis("POZOR: neznamy cip, ceka se TSL2561 (ID 0x5x). Namerene hodnoty nemusi platit.")
        _vypis(f"{POCET_MERENI_LUX} mereni po {PAUZA_MEREN_S:.0f} s. Zakryj cidlo rukou: hodnota "
               "ma klesnout pod 10 lx a po odkryti zase stoupnout.")
        cidlo = Tsl2561(adresa, SBERNICE)
        for cislo in range(1, POCET_MERENI_LUX + 1):
            if cislo > 1:
                time.sleep(PAUZA_MEREN_S)
            ch0, ch1 = cidlo.precti_kanaly()
            _vypis(f"Mereni {cislo}/{POCET_MERENI_LUX}: CH0 {ch0}, CH1 {ch1}, "
                   f"{lux_z_kanalu(ch0, ch1):.1f} lx")
    except OSError as chyba:
        zprava = (f"CHYBA: TSL2561 na adrese 0x{adresa:02X} nejde cist ({chyba}). "
                  "Zkontroluj zapojeni, napajeni 3,3 V, zapnute I2C a ADDR (nezapojeny = 0x39) "
                  f"(i2cdetect -y {SBERNICE}).")
        raise SystemExit(zprava) from chyba


def over_rgb(args: argparse.Namespace) -> None:
    """Ukáže na RGB LED postupně červenou, zelenou, modrou a bílou, každou na 1 s, a zhasne.

    Barvy jsou na plný jas a mění se hned. Používá ovladač `RgbLed`, tedy stejný jako program.
    Chyba GPIO (třeba obsazený pin, když běží služba) se nezachytává a skončí výpisem chyby.
    """
    svetlo = RgbLed()
    _vypis(f"RGB LED (R pin 36 GPIO16, G pin 38 GPIO20, B pin 40 GPIO21): postupne ctyri "
           f"barvy, kazda {RGB_BARVA_S:.0f} s.")
    for popis, barva in BARVY_SVETLA:
        svetlo.nastav(StavSvetla(True, barva, 100, True))
        _vypis(f"    {popis}")
        time.sleep(RGB_BARVA_S)
    svetlo.nastav(VYPNUTO)
    _vypis("Konec zkousky RGB LED.")


def _vytvor_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m domek.over_soucastky",
        description="Overi jednu soucastku zapojenou k Raspberry, bez zbytku programu.")
    soucastky = parser.add_subparsers(dest="soucastka", metavar="SOUCASTKA", required=True)

    bme280 = soucastky.add_parser(
        "bme280", help=f"cidlo teploty, vlhkosti a tlaku: ID cipu a {POCET_MERENI} mereni")
    bme280.add_argument("--adresa", type=_cele_cislo, default=0x76, metavar="ADRESA",
                        help="I2C adresa cidla (vychozi 0x76, nebo 0x77)")
    bme280.set_defaults(over=over_bme280)

    pir = soucastky.add_parser(
        "pir", help=f"cidlo pohybu: {POHYB_SEKUND} s vypisuje zmeny (pohyb / klid)")
    pir.set_defaults(over=over_pir)

    led = soucastky.add_parser(
        "led", help=f"LED modulu semafor (topeni, chlazeni a pohyb): kazda {POCET_BLIKNUTI}x "
                    f"blikne")
    led.set_defaults(over=over_led)

    displej = soucastky.add_parser(
        "displej", help=f"displej SSD1306: {DISPLEJ_SEKUND} s ukazuje testovaci text s casem")
    displej.set_defaults(over=over_displej)

    tsl2561 = soucastky.add_parser(
        "tsl2561", help=f"cidlo osvetleni TSL2561: ID cipu a {POCET_MERENI_LUX} mereni v luxech, "
                        "vzdy po sekunde")
    tsl2561.set_defaults(over=over_tsl2561)

    rgb = soucastky.add_parser(
        "rgb", help=f"RGB LED jako svetlo: postupne cervena, zelena, modra a bila, "
                    f"kazda {RGB_BARVA_S:.0f} s")
    rgb.set_defaults(over=over_rgb)
    return parser


def main(argv: list[str] | None = None) -> None:
    """Zpracuje volby a ověří vybranou součástku.

    `argv` je seznam voleb, bez něj se čtou z příkazové řádky. Každá zkouška dostane všechny volby,
    i když je nepotřebuje, aby se dala volat stejně.
    """
    args = _vytvor_parser().parse_args(argv)
    args.over(args)


if __name__ == "__main__":
    main()
