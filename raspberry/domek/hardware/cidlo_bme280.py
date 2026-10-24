"""
Ovladač čidla BME280 (teplota, vlhkost, tlak) na sběrnici I2C.

Používá knihovny smbus2 (sběrnice) a RPi.bme280 (modul `bme280`, přepočet naměřených hodnot
na °C, % a hPa). Existují jen na Raspberry, proto se importují až při čtení a modul jde načíst
i na PC.

Na stejné adrese může být i BMP280, který vypadá stejně, ale vlhkost neměří. Pozná se podle ID
čipu v registru 0xD0, a protože knihovna u něj místo vlhkosti vrací nulu, ovladač ji nahradí
hodnotou None.
"""
from __future__ import annotations

from domek.hardware.rozhrani import MereniMeteo

REGISTR_ID = 0xD0  # registr, ve kterém je ID čipu
ID_BME280 = 0x60
ID_BMP280 = 0x58   # BMP280 vypadá stejně jako BME280, ale nemá čidlo vlhkosti


class Bme280:
    """BME280 na sběrnici I2C, splňuje protokol `Meteo`.

    Konstruktor na hardware nesahá: sběrnice se otevře a ID čipu s kalibrací čidla načtou až při
    prvním úspěšném čtení. Čidlo, které chybí už při startu, se tak ohlásí jen jako OSError
    z `precti` a domek ho zkouší každou sekundu znovu, dokud se neozve.

    Když je v registru ID čipu 0x58, je to BMP280 bez čidla vlhkosti a `precti` vrací vlhkost
    None, teplotu a tlak normálně. Jiné ID (0x60 u BME280, ale i cokoli neznámého) se bere jako
    BME280 a vlhkost se vrací, jak ji knihovna naměřila. Dokud se ID nepřečte, `precti` hlásí
    OSError: čidlo, které se neozvalo, se nesmí považovat za BME280.
    """

    def __init__(self, adresa: int = 0x76, sbernice: int = 1) -> None:
        self._adresa = adresa
        self._cislo_sbernice = sbernice
        self._sbernice = None   # smbus2.SMBus, otevře se při prvním čtení a pak zůstane otevřená
        self._id_cipu = None    # ID čipu z registru 0xD0, načte se při prvním úspěšném čtení
        self._kalibrace = None  # kalibrační parametry čidla, načtou se při prvním úspěšném čtení

    def precti(self) -> MereniMeteo:
        """Jedno měření. Při poruše sběrnice nebo čidla vyhodí OSError."""
        import bme280
        import smbus2

        # Co se nepovedlo, zůstane nenastavené a příští čtení to zkusí znovu. Sběrnice se ale otevře
        # jen jednou a pak zůstane otevřená, aby opakované pokusy nehromadily otevřené soubory
        # (smbus2 0.6 ji při zániku objektu sám nezavírá).
        if self._sbernice is None:
            self._sbernice = smbus2.SMBus(self._cislo_sbernice)
        if self._id_cipu is None:
            self._id_cipu = self._sbernice.read_byte_data(self._adresa, REGISTR_ID)
        if self._kalibrace is None:
            self._kalibrace = bme280.load_calibration_params(self._sbernice, self._adresa)
        data = bme280.sample(self._sbernice, self._adresa, self._kalibrace)
        vlhkost = None if self._id_cipu == ID_BMP280 else data.humidity
        return MereniMeteo(data.temperature, vlhkost, data.pressure)
