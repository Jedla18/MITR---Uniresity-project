from datetime import time

import pytest

from domek.denni_doba import usek_dne
from domek.nastaveni import Nastaveni, slouc


@pytest.mark.parametrize("cas, usek", [
    (time(0, 30), "noc"), (time(6, 59), "noc"), (time(7, 0), "den"), (time(18, 59), "den"),
    (time(19, 0), "vecer"), (time(21, 59), "vecer"), (time(22, 0), "noc"), (time(23, 59), "noc"),
])
def test_vychozi_useky(cas, usek):
    assert usek_dne(cas, Nastaveni()) == usek


def test_noc_posunuta_pri_predvadeni():
    n = slouc(Nastaveni(), {"zacatek_noc": "14:00"})
    assert usek_dne(time(13, 59), n) == "den"
    assert usek_dne(time(14, 5), n) == "noc"
    assert usek_dne(time(19, 0), n) == "vecer"


# --- Doplňující testy: pravidlo ze zadání, které úvodní testy neověřují ---


def test_pred_prvnim_zacatkem_dne_plati_usek_s_nejpozdejsim_zacatkem():
    # noc začíná ve 3:00, takže přes půlnoc ještě trvá večer, který začal v 19:00
    n = slouc(Nastaveni(), {"zacatek_noc": "03:00"})
    assert usek_dne(time(0, 30), n) == "vecer"
    assert usek_dne(time(2, 59), n) == "vecer"
    assert usek_dne(time(3, 0), n) == "noc"
    assert usek_dne(time(6, 59), n) == "noc"
    assert usek_dne(time(7, 0), n) == "den"
    assert usek_dne(time(23, 59), n) == "vecer"
