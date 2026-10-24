"""
Ovladač čidla pohybu PIR (HC-SR501) na vstupu GPIO.

Používá knihovnu gpiozero, která existuje jen na Raspberry, proto se importuje až v konstruktoru
a modul jde načíst i na PC.
"""
from __future__ import annotations


class Pir:
    """HC-SR501 na pinu `gpio` (číslování BCM), splňuje protokol `CidloPohybu`.

    Výstup čidla má 3,3 V, takže jde připojit přímo na GPIO. Vnitřní pull-down drží vstup
    v klidu, i když čidlo není zapojené. Chyby GPIO se tu nezachycují: `Domek` je nezachytává
    taky, takže chyba ukončí program a systemd ho spustí znovu.
    """

    def __init__(self, gpio: int = 24) -> None:
        from gpiozero import DigitalInputDevice

        self._vstup = DigitalInputDevice(gpio, pull_up=False)

    def je_pohyb(self) -> bool:
        """Vidí čidlo právě teď pohyb? Jen přečte stav vstupu, takže jde volat každých 100 ms."""
        return self._vstup.is_active
