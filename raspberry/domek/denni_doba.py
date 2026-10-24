"""
Denní doba: který z úseků dne (den, večer, noc) právě platí.

Začátky úseků jsou v nastavení jako text HH:MM. Nastavení hlídá, aby byly navzájem
různé, takže platný úsek je vždy jednoznačný.
"""
from __future__ import annotations

from datetime import time

from domek.nastaveni import Nastaveni


def usek_dne(cas: time, nastaveni: Nastaveni) -> str:
    """Vrátí "den", "vecer" nebo "noc": úsek, který platí v čase `cas`.

    `cas` je čas dne bez časového pásma. Platí úsek, jehož začátek je poslední před
    `cas` nebo přesně v `cas`. Před prvním začátkem dne platí úsek s nejpozdějším
    začátkem, protože trvá přes půlnoc.
    """
    zacatky = sorted([
        (time.fromisoformat(nastaveni.zacatek_den), "den"),
        (time.fromisoformat(nastaveni.zacatek_vecer), "vecer"),
        (time.fromisoformat(nastaveni.zacatek_noc), "noc"),
    ])
    usek = zacatky[-1][1]  # před prvním začátkem dne platí úsek přes půlnoc
    for zacatek, nazev in zacatky:
        if zacatek <= cas:
            usek = nazev  # začátky jdou vzestupně, takže vyhraje poslední z nich do `cas`
    return usek
