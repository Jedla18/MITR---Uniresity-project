"""
Simulovaný hardware pro běh na PC a pro testy.

Každá třída splňuje rozhraní z `rozhrani.py`. Veřejné atributy slouží k tomu, aby šly měnit
"naměřené" hodnoty (`teplota`, `lux`, `pohyb`) a číst, co domek na výstupy poslal (`historie`,
`posledni`). Atribut `selhava` simuluje poruchu: čtení nebo výstup pak vyhodí OSError, stejně
jako skutečný hardware, a nic se nezapíše.
"""
from __future__ import annotations

from datetime import datetime

from domek.hardware.rozhrani import MereniMeteo
from domek.svetlo import StavSvetla


class _SimulaceSPoruchou:
    """Základ simulací, které umějí selhat: dokud je `selhava` zapnuté, volání vyhazují OSError."""

    def __init__(self) -> None:
        self.selhava = False

    def _zkontroluj_poruchu(self) -> None:
        if self.selhava:
            raise OSError(f"Simulovany vypadek hardwaru ({type(self).__name__}).")


class SimMeteo(_SimulaceSPoruchou):
    """Simulované BME280. Naměřené hodnoty jsou atributy, které test nebo uživatel mění.

    Vlhkost None simuluje BMP280, který vlhkost neměří.
    """

    def __init__(self, teplota: float = 22.0, vlhkost: float | None = 45.0,
                 tlak: float = 1013.25) -> None:
        super().__init__()
        self.teplota = teplota
        self.vlhkost = vlhkost
        self.tlak = tlak

    def precti(self) -> MereniMeteo:
        self._zkontroluj_poruchu()
        return MereniMeteo(self.teplota, self.vlhkost, self.tlak)


class SimSvetelnost(_SimulaceSPoruchou):
    """Simulované TSL2561."""

    def __init__(self, lux: float = 200.0) -> None:
        super().__init__()
        self.lux = lux

    def precti_lux(self) -> float:
        self._zkontroluj_poruchu()
        return self.lux


class SimPohyb:
    """Simulované PIR. Atribut `pohyb` říká, jestli právě vidí pohyb."""

    def __init__(self) -> None:
        self.pohyb = False

    def je_pohyb(self) -> bool:
        return self.pohyb


class SimIndikatory:
    """Simulované kontrolky. Zapisují se všechna nastavení, takže je vidět i to, co už zhaslo."""

    def __init__(self) -> None:
        self.historie: list[tuple[bool, bool]] = []  # dvojice (topi, chladi), nejnovější na konci
        self.historie_pohybu: list[bool] = []        # kontrolka pohybu: svítí / nesvítí

    def nastav(self, topi: bool, chladi: bool) -> None:
        self.historie.append((topi, chladi))

    def ukaz_pohyb(self, sviti: bool) -> None:
        self.historie_pohybu.append(sviti)


class SimDisplej(_SimulaceSPoruchou):
    """Simulovaný displej. Pamatuje si poslední zobrazený stav a to, jestli byl připojený tablet."""

    def __init__(self) -> None:
        super().__init__()
        self.posledni: tuple[dict, bool] | None = None

    def zobraz(self, stav: dict, pripojeno: bool) -> None:
        self._zkontroluj_poruchu()
        self.posledni = (stav, pripojeno)


class SimVystupSvetla(_SimulaceSPoruchou):
    """Simulované světlo. Pamatuje si stav, který dostalo naposledy."""

    def __init__(self) -> None:
        super().__init__()
        self.posledni: StavSvetla | None = None

    def nastav(self, stav: StavSvetla) -> None:
        self._zkontroluj_poruchu()
        self.posledni = stav


class SimNastavovacCasu(_SimulaceSPoruchou):
    """Simulované nastavení času. Pamatuje si poslední čas, který se měl nastavit."""

    def __init__(self) -> None:
        super().__init__()
        self.posledni: datetime | None = None

    def nastav(self, cas: datetime) -> None:
        self._zkontroluj_poruchu()
        self.posledni = cas
