"""
Ovladač čidla osvětlení TSL2561 na sběrnici I2C.

Používá knihovnu smbus2 (sběrnice a čtení registrů). Existuje jen na Raspberry, proto se
importuje až při čtení a modul jde načíst i na PC. Jádro Raspberry ovladač tsl2563 pro toto čidlo
nemá, proto se registry čtou přímo.
"""
from __future__ import annotations

import time

REGISTR_RIZENI = 0x80      # příkaz + řídicí registr 0x00
REGISTR_CASOVANI = 0x81    # příkaz + registr časování 0x01
REGISTR_ID_TSL2561 = 0x8A  # příkaz + registr ID čipu 0x0A, horní půlbajt 0x5 je TSL2561
REGISTR_KANAL_0 = 0xAC     # příkaz + slovo + registr dat CH0 (viditelné a infračervené světlo)
REGISTR_KANAL_1 = 0xAE     # příkaz + slovo + registr dat CH1 (infračervené světlo)
HODNOTA_ZAPNUTO = 0x03     # řídicí registr: čidlo zapnuto
HODNOTA_CASOVANI = 0x02    # časování: integrace 402 ms, zesílení 1x
DOBA_PRVNIHO_MERENI_S = 0.41  # první měření po zapnutí trvá 402 ms
PREPOCET_ZESILENI = 16     # vzorec z katalogového listu platí pro zesílení 16x, čidlo má 1x


def lux_z_kanalu(ch0: int, ch1: int) -> float:
    """Přepočítá hodnoty obou kanálů na lux podle vzorce z katalogového listu (balení T/FN/CL).

    Platí pro pevné nastavení ovladače: integrace 402 ms a zesílení 1x. Vzorec je pro zesílení
    16x, proto se obě hodnoty nejdřív vynásobí 16. Výsledek není nikdy záporný.
    """
    ch0 *= PREPOCET_ZESILENI
    ch1 *= PREPOCET_ZESILENI
    if ch0 == 0:
        return 0.0
    pomer = ch1 / ch0
    if pomer <= 0.50:
        lux = 0.0304 * ch0 - 0.062 * ch0 * pomer ** 1.4
    elif pomer <= 0.61:
        lux = 0.0224 * ch0 - 0.031 * ch1
    elif pomer <= 0.80:
        lux = 0.0128 * ch0 - 0.0153 * ch1
    elif pomer <= 1.30:
        lux = 0.00146 * ch0 - 0.00112 * ch1
    else:
        lux = 0.0
    return max(0.0, lux)


class Tsl2561:
    """TSL2561 na sběrnici I2C, splňuje protokol `Svetelnost`.

    Konstruktor na hardware nesahá: sběrnice se otevře a čidlo zapne až při prvním čtení. Čidlo,
    které chybí už při startu, se tak ohlásí jen jako OSError z `precti_lux` a domek ho zkouší
    každou sekundu znovu, dokud se neozve.

    Po zapnutí napájení je čidlo vypnuté. První čtení ho proto zapne (řídicí registr), nastaví
    časování (integrace 402 ms, zesílení 1x) a počká na první měření. Další čtení vezmou poslední
    výsledek, který čidlo samo průběžně obnovuje. Po každé chybě se čidlo zapne znovu, protože
    mohlo přijít o napájení a bez nového zapnutí by neměřilo.

    Výpadek napájení kratší než sekunda mezi čteními ale žádnou chybu nezpůsobí: čidlo jen
    vypnuté odpovídá nulami, takže by ve dne pořád hlásilo tmu. Po čtení s nulou v obou kanálech
    se proto zapnutí pošle znovu, tentokrát bez čekání: čtení běží ve smyčce GLib, která se nesmí
    zdržovat, a měření bude hotové do dalšího čtení za sekundu. V opravdové tmě se tak zapnutí
    posílá každou sekundu, což čidlu nevadí.
    """

    def __init__(self, adresa: int = 0x39, sbernice: int = 1) -> None:
        self._adresa = adresa
        self._cislo_sbernice = sbernice
        self._sbernice = None     # smbus2.SMBus, otevře se při prvním čtení a zůstane otevřená
        self._zapnuto = False     # je čidlo zapnuté a první měření hotové?

    def _zapni(self) -> None:
        self._sbernice.write_byte_data(self._adresa, REGISTR_RIZENI, HODNOTA_ZAPNUTO)
        self._sbernice.write_byte_data(self._adresa, REGISTR_CASOVANI, HODNOTA_CASOVANI)

    def precti_lux(self) -> float:
        """Jedno měření osvětlení v luxech. Při poruše sběrnice nebo čidla vyhodí OSError."""
        return lux_z_kanalu(*self.precti_kanaly())

    def precti_kanaly(self) -> tuple[int, int]:
        """Jedno čtení kanálů CH0 a CH1. Při poruše sběrnice nebo čidla vyhodí OSError."""
        import smbus2

        # Sběrnice se otevře jen jednou a pak zůstane otevřená, aby opakované pokusy nehromadily
        # otevřené soubory (smbus2 0.6 ji při zániku objektu sám nezavírá).
        if self._sbernice is None:
            self._sbernice = smbus2.SMBus(self._cislo_sbernice)
        try:
            if not self._zapnuto:
                self._zapni()
                time.sleep(DOBA_PRVNIHO_MERENI_S)
                self._zapnuto = True
            ch0 = self._sbernice.read_word_data(self._adresa, REGISTR_KANAL_0)
            ch1 = self._sbernice.read_word_data(self._adresa, REGISTR_KANAL_1)
            if ch0 == 0 and ch1 == 0:  # tma, nebo vypnuté čidlo: zapnout znovu, bez čekání
                self._zapni()
        except OSError:
            # Čidlo mohlo přijít o napájení, takže ho příště znovu zapneme
            self._zapnuto = False
            raise
        return ch0, ch1
