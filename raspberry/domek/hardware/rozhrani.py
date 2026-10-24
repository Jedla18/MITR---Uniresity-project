"""
Rozhraní hardwaru: co `Domek` potřebuje od čidel, výstupů a displeje.

Domek používá hardware jen přes tato rozhraní, takže na PC běží s náhradami ze `simulace.py`
a na Raspberry s ovladači skutečných součástek. Čtení i výstupy při chybě hardwaru vyhazují
OSError. Domek ji zachytí a ohlásí jako chybu ve stavu, místo aby se zastavil.
"""
from __future__ import annotations

from datetime import datetime
from typing import NamedTuple, Protocol

from domek.svetlo import StavSvetla


class MereniMeteo(NamedTuple):
    """Jedno měření z čidla teploty, vlhkosti a tlaku.

    `vlhkost` je None, když čidlo vlhkost neměří (BMP280). To není porucha: teplota a tlak
    platí a vlhkost je jen neznámá.
    """

    teplota: float         # °C
    vlhkost: float | None  # %
    tlak: float            # hPa


class Meteo(Protocol):
    """Čidlo teploty, vlhkosti a tlaku (BME280, nebo BMP280, které vlhkost neměří)."""

    def precti(self) -> MereniMeteo: ...


class Svetelnost(Protocol):
    """Čidlo osvětlení (TSL2561)."""

    def precti_lux(self) -> float: ...


class CidloPohybu(Protocol):
    """Čidlo pohybu (PIR)."""

    def je_pohyb(self) -> bool: ...


class Indikatory(Protocol):
    """Kontrolky modulu LED semafor: topení (červená), chlazení (zelená) a pohyb (oranžová).

    `nastav` se volá v každém kroku, i když se nic nezměnilo. `ukaz_pohyb` se volá jen při změně,
    tedy když se kontrolka pohybu má rozsvítit nebo zhasnout.
    """

    def nastav(self, topi: bool, chladi: bool) -> None: ...

    def ukaz_pohyb(self, sviti: bool) -> None: ...


class Displej(Protocol):
    """Displej, na kterém je vidět stav domku.

    `stav` je slovník z `Domek.stav()` a `pripojeno` říká, jestli je připojený tablet.
    """

    def zobraz(self, stav: dict, pripojeno: bool) -> None: ...


class VystupSvetla(Protocol):
    """Světlo domku (RGB LED).

    `nastav` se volá v každém kroku a po každém pohybu nebo příkazu, i když se stav nezměnil,
    takže ovladač musí snést opakované volání se stejným stavem.
    """

    def nastav(self, stav: StavSvetla) -> None: ...


class NastavovacCasu(Protocol):
    """Nastavuje systémový čas."""

    def nastav(self, cas: datetime) -> None: ...
