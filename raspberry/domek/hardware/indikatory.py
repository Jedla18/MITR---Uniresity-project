"""
Ovladač kontrolek topení, chlazení a pohybu: tři LED na výstupech GPIO.

Používá knihovnu gpiozero, která existuje jen na Raspberry, proto se importuje až v konstruktoru
a modul jde načíst i na PC.
"""
from __future__ import annotations


class LedIndikatory:
    """Tři LED na pinech `gpio_topeni`, `gpio_chlazeni` a `gpio_pohybu` (číslování BCM), splňuje
    protokol `Indikatory`. Výchozí piny odpovídají modulu LED semafor: červená (topení), zelená
    (chlazení) a oranžová (pohyb).

    Každá LED je připojená přes rezistor, takže svítí při logické 1. Hned po vytvoření jsou všechny
    zhasnuté. Chyby GPIO se tu nezachycují: `Domek` je nezachytává taky, takže chyba ukončí
    program a systemd ho spustí znovu.
    """

    def __init__(self, gpio_topeni: int = 17, gpio_chlazeni: int = 27,
                 gpio_pohybu: int = 22) -> None:
        from gpiozero import LED

        self._topeni = LED(gpio_topeni)
        self._chlazeni = LED(gpio_chlazeni)
        self._pohyb = LED(gpio_pohybu)

    def nastav(self, topi: bool, chladi: bool) -> None:
        """Rozsvítí nebo zhasne LED topení a chlazení.

        Domek volá `nastav` v každém kroku, i beze změny.
        """
        self._topeni.value = topi
        self._chlazeni.value = chladi

    def ukaz_pohyb(self, sviti: bool) -> None:
        """Rozsvítí nebo zhasne oranžovou LED pohybu. Domek ji volá jen při změně."""
        self._pohyb.value = sviti
