"""
Světlo: automatika podle denní doby, osvětlení a pohybu a ruční ovládání.

Automatika rozhoduje, kdy světlo svítí: ve dne jen při šeru, večer vždy a v noci je zhasnuto,
jen při pohybu se na nastavenou dobu rozsvítí slabé noční světlo. Vždy svítí barvou a jasem
z nastavení (`barva_svetla`, `jas_svetla`), které tablet naposledy nastavil. Noční světlo je
tlumené: jas je nejvýš `JAS_NOC`. Ruční volba z tabletu (zapnuto, nebo vypnuto) automatiku
přebije, ale jen do konce úseku dne, ve kterém vznikla. Modul nezná hardware: jen rozhoduje,
jaký stav má světlo mít, a rozsvítit ho je věc jiné vrstvy.
"""
from __future__ import annotations

from dataclasses import dataclass

from domek.nastaveni import Nastaveni


@dataclass(frozen=True)
class StavSvetla:
    """Stav, v jakém má světlo být.

    Je neměnný, protože `Svetlo.krok` vrací i sdílenou konstantu `VYPNUTO` a zvenku se nesmí
    dát změnit.
    """

    zapnuto: bool
    barva: tuple[int, int, int]  # RGB, každá složka 0 až 255
    jas: int                     # 0 až 100 %
    rucne: bool                  # True, když stav vybral člověk, a ne automatika


VYPNUTO = StavSvetla(False, (0, 0, 0), 0, False)
JAS_NOC = 10  # noční světlo je slabé, aby v noci nerušilo: jas je nejvýš tolik [%]
HYSTEREZE_LUX = 1.3  # šero končí až nad prahem krát toto číslo, aby světlo na hraně neblikalo


class Svetlo:
    """Rozhoduje, jak má světlo svítit. Pamatuje si ruční volbu, šero a konec nočního světla."""

    def __init__(self) -> None:
        self._rucni: bool | None = None        # ruční volba (zapnuto?); None, když řídí automatika
        self._rucni_usek: str | None = None    # úsek dne, ve kterém ruční volba vznikla
        self._sero = False                     # je venku šero (s hysterezí)
        # Do kdy [s] svítí noční světlo, měřeno na stejných hodinách jako `mono`. Mínus
        # nekonečno znamená, že po startu nesvítí, ať je `mono` jakékoli.
        self._nocni_do = float("-inf")

    def krok(self, usek: str, lux: float | None, pohyb: bool, mono: float,
             nastaveni: Nastaveni) -> StavSvetla:
        """Vrátí stav, v jakém má světlo právě být.

        `usek` je "den", "vecer" nebo "noc". `lux` je osvětlení venku, None když ho čidlo
        nepřečetlo. `pohyb` říká, jestli je právě vidět pohyb, a `mono` je monotonní čas
        v sekundách. Barva a jas svítícího světla se berou z `nastaveni`, i u ruční volby,
        takže se změna nastavení projeví v dalším kroku.

        Na pořadí kroků záleží. Šero a pohyb se zaznamenají vždy, i když ruční volba
        automatiku přebíjí, a teprve potom se vybere výsledek. Po návratu do automatiky
        tak světlo hned odpovídá tomu, co se mezitím stalo.
        """
        # 1. Ruční volba platí jen do konce úseku dne, ve kterém vznikla.
        if self._rucni is not None and usek != self._rucni_usek:
            self.auto()
        # 2. Šero začíná pod prahem a končí až nad prahem krát HYSTEREZE_LUX, mezi tím se
        #    nemění. Bez čidla se šero nepředpokládá.
        if lux is None:
            self._sero = False
        elif lux < nastaveni.prah_lux:
            self._sero = True
        elif lux > nastaveni.prah_lux * HYSTEREZE_LUX:
            self._sero = False
        # 3. Každý pohyb v noci spustí odpočet nočního světla znovu od začátku, pokud to
        #    nastavení povoluje. Rozběhnutý odpočet se vypnutím nastavení neruší.
        if usek == "noc" and pohyb and nastaveni.nocni_svetlo_pri_pohybu:
            self._nocni_do = mono + nastaveni.nocni_svetlo_s
        # 4. Ruční volba má přednost, jinak rozhoduje úsek dne. Neznámý úsek nesvítí.
        barva, jas = nastaveni.barva_svetla, nastaveni.jas_svetla
        if self._rucni is not None:
            if self._rucni:
                return StavSvetla(True, barva, jas, True)
            return StavSvetla(False, (0, 0, 0), 0, True)
        if usek == "den":
            return StavSvetla(True, barva, jas, False) if self._sero else VYPNUTO
        if usek == "vecer":
            return StavSvetla(True, barva, jas, False)
        if usek == "noc" and mono < self._nocni_do:
            return StavSvetla(True, barva, min(jas, JAS_NOC), False)
        return VYPNUTO

    def rucne(self, zapnuto: bool, usek: str) -> None:
        """Zapne nebo vypne světlo ručně. Volba platí do konce úseku dne `usek`.

        Pamatuje se jen, jestli má světlo svítit. Barva a jas zapnutého světla se při každém
        kroku berou z nastavení, takže ručně zapnuté světlo svítí tak, jak je tablet nastavil.
        """
        self._rucni = zapnuto
        self._rucni_usek = usek

    def prepni(self, aktualni: StavSvetla, usek: str) -> None:
        """Přepne světlo na opak toho, co právě svítí (`aktualni`), jako ruční volbu."""
        self.rucne(not aktualni.zapnuto, usek)

    def auto(self) -> None:
        """Zruší ruční volbu, takže se světlo zase řídí automatikou."""
        self._rucni = None
        self._rucni_usek = None
