"""
Pojistka pro časovače smyčky GLib: neočekávaná chyba ukončí celý program.

Když funkce časovače GLib vyhodí výjimku, knihovna ji jen vypíše a časovač zruší. Bluetooth by
pak běžel dál, ale krok domku a notifikace by stály ("zombie"). Proto se každá funkce časovače
obalí funkcí `opakuj`: při chybě program skončí a systemd ho spustí znovu.
"""
from __future__ import annotations

import logging
import os
from typing import Callable


def opakuj(fn: Callable[[], object], ukonci: Callable[[int], object] = os._exit
           ) -> Callable[[], bool]:
    """Obal `fn` pro `GLib.timeout_add`: zavolá ji a vrátí True, takže se časovač opakuje.

    Když `fn` vyhodí výjimku, zaloguje ji i s výpisem volání, zavolá `ukonci(1)` a vrátí False.
    Výchozí `os._exit` ukončí proces hned a bez úklidu, i když běží uprostřed smyčky GLib
    (`sys.exit` jen vyhodí další výjimku). Testy místo něj předají vlastní funkci.
    """
    def obal() -> bool:
        try:
            fn()
        except Exception:
            logging.exception("Neocekavana chyba v casovaci, program konci a systemd ho "
                              "spusti znovu.")
            ukonci(1)
            return False
        return True

    return obal
