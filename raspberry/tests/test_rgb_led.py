"""RGB LED (společná katoda) jako světlo domku: ovladač zkoušený s falešnou knihovnou gpiozero
(viz conftest.py)."""
import sys
from datetime import datetime

import pytest

from domek.hardware.rgb_led import RgbLed
from domek.hardware.simulace import SimIndikatory, SimMeteo, SimPohyb
from domek.jadro import Domek
from domek.nastaveni import Nastaveni, slouc
from domek.svetlo import VYPNUTO, StavSvetla

TEPLA_ZLUTA = (255, 147, 41)  # na ní se zkouší gama korekce a prolínání
JAS_TEPLE = 60

VECER = datetime(2026, 10, 20, 20, 0, 0)
BILA = StavSvetla(True, (255, 255, 255), 100, True)
SVETLO_VECER = StavSvetla(True, TEPLA_ZLUTA, JAS_TEPLE, False)  # (255, 147, 41) na 60 %
HODNOTA_VECER = ((255 / 255) ** 2.2 * 0.6, (147 / 255) ** 2.2 * 0.6, (41 / 255) ** 2.2 * 0.6)


def led(hw):
    """Jediná RGB LED, kterou ovladač vytvořil."""
    (rgb,) = hw.rgb_ledky
    return rgb


# --- Vytvoření ---

def test_rgb_led_pri_vytvoreni_nenacita_knihovnu_a_nic_nevytvari(falesny_hardware, monkeypatch):
    RgbLed()
    RgbLed(1, 2, 3)
    assert falesny_hardware.rgb_ledky == []
    monkeypatch.setitem(sys.modules, "gpiozero", None)  # import by teď selhal s ImportError
    RgbLed()


@pytest.mark.parametrize("argumenty, piny", [((), (16, 20, 21)), ((5, 6, 7), (5, 6, 7))])
def test_prvni_nastav_vytvori_rgbled_na_zadanych_pinech_a_led_je_zhasnuta(
        falesny_hardware, argumenty, piny):
    RgbLed(*argumenty).nastav(VYPNUTO)
    (rgb,) = falesny_hardware.rgb_ledky
    assert (rgb.piny, rgb.historie, rgb.active_high, rgb.pwm) == (
        piny, [(0.0, 0.0, 0.0)], True, True)


def test_rgbled_se_vytvori_jen_jednou(falesny_hardware):
    svetlo = RgbLed()
    for stav in (VYPNUTO, BILA, SVETLO_VECER, VYPNUTO, VYPNUTO):
        svetlo.nastav(stav)
    assert len(falesny_hardware.rgb_ledky) == 1


# --- Barva ---

@pytest.mark.parametrize("stav, hodnota", [
    (BILA, (1.0, 1.0, 1.0)),
    (StavSvetla(True, (255, 0, 0), 100, True), (1.0, 0.0, 0.0)),
    (StavSvetla(True, (0, 0, 255), 50, True), (0.0, 0.0, 0.5)),
    (StavSvetla(True, (255, 255, 255), 0, True), (0.0, 0.0, 0.0)),   # zapnuté světlo s jasem 0
    (SVETLO_VECER, HODNOTA_VECER),
    (StavSvetla(True, (128, 0, 0), 100, True), ((128 / 255) ** 2.2, 0.0, 0.0)),  # asi 0,22, ne 0,5
    (StavSvetla(False, (255, 255, 255), 100, True), (0.0, 0.0, 0.0)),  # vypnuté, i s barvou a jasem
    (VYPNUTO, (0.0, 0.0, 0.0)),
])
def test_slozka_je_barva_s_gama_korekci_krat_jas_a_vypnute_svetlo_je_cerne(
        falesny_hardware, stav, hodnota):
    RgbLed().nastav(stav)
    assert led(falesny_hardware).value == pytest.approx(hodnota)


def test_kazda_barva_a_kazdy_jas_jsou_platne_pro_gpiozero(falesny_hardware):
    # Falešná RGBLED stejně jako skutečná odmítne složku mimo 0 až 1 (OutputDeviceBadValue)
    svetlo = RgbLed()
    for jas in range(101):
        for barva in ((0, 0, 0), (255, 255, 255), (1, 128, 254), (255, 147, 41)):
            svetlo.nastav(StavSvetla(True, barva, jas, True))
            assert all(0 <= slozka <= 1 for slozka in led(falesny_hardware).value)


# --- Zapisuje se jen při změně ---

def test_stejny_stav_se_nezapisuje_znovu(falesny_hardware):
    svetlo = RgbLed()
    modra = StavSvetla(True, (0, 0, 255), 100, True)
    for stav in (VYPNUTO, VYPNUTO, modra, modra, modra, StavSvetla(True, (0, 0, 255), 100, False),
                 VYPNUTO, VYPNUTO, modra):
        svetlo.nastav(stav)
    assert led(falesny_hardware).historie == [
        (0.0, 0.0, 0.0), (0.0, 0.0, 1.0), (0.0, 0.0, 0.0), (0.0, 0.0, 1.0)]


def test_jina_barva_se_stejnym_jasem_se_zapise(falesny_hardware):
    svetlo = RgbLed()
    svetlo.nastav(StavSvetla(True, (255, 0, 0), 100, True))
    svetlo.nastav(StavSvetla(True, (0, 255, 0), 100, True))
    assert led(falesny_hardware).historie == [
        (0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0)]


# --- Chyby ---

def test_chyba_gpio_se_nezachyti_a_po_navratu_se_barva_zapise_znovu(falesny_hardware):
    svetlo = RgbLed()
    svetlo.nastav(VYPNUTO)
    falesny_hardware.porucha = {"gpio"}
    for _ in range(2):
        with pytest.raises(OSError):
            svetlo.nastav(BILA)
    assert led(falesny_hardware).historie == [(0.0, 0.0, 0.0)]
    falesny_hardware.porucha = set()
    svetlo.nastav(BILA)
    svetlo.nastav(BILA)
    assert led(falesny_hardware).historie == [(0.0, 0.0, 0.0), (1.0, 1.0, 1.0)]


# --- S jádrem domku ---

def vytvor_domek(svetlo):
    # večer svítí barvou a jasem z nastavení: tady teplá žlutá na 60 %
    nastaveni = slouc(Nastaveni(), {"barva_svetla": TEPLA_ZLUTA, "jas_svetla": JAS_TEPLE})
    return Domek(nastaveni, SimMeteo(), SimPohyb(), SimIndikatory(), vystup_svetla=svetlo)


def test_domek_rozsviti_led_podle_stavu_svetla_a_zapisuje_jen_zmeny(falesny_hardware):
    d = vytvor_domek(RgbLed())
    for mono in range(4):  # večer svítí automatika na 60 %
        d.krok(VECER, float(mono))
    assert len(led(falesny_hardware).historie) == 2
    assert led(falesny_hardware).value == pytest.approx(HODNOTA_VECER)
    d.proved_prikaz({"svetlo": {"barva": [0, 0, 255], "jas": 50}}, 4.0)
    assert led(falesny_hardware).value == (0.0, 0.0, 0.5)
    d.proved_prikaz({"svetlo": "vypnout"}, 5.0)
    assert led(falesny_hardware).value == (0.0, 0.0, 0.0)
    d.proved_prikaz({"svetlo": "auto"}, 6.0)
    # příkaz s barvou uložil modrou na 50 % do nastavení, takže automatika svítí jí
    assert led(falesny_hardware).value == (0.0, 0.0, 0.5)
    assert d.stav()["chyby"] == []


def test_porucha_gpio_se_ve_stavu_ohlasi_jako_svetlo_a_po_navratu_zmizi(falesny_hardware):
    d = vytvor_domek(RgbLed())
    d.krok(VECER, 0.0)
    falesny_hardware.porucha = {"gpio"}
    d.proved_prikaz({"svetlo": "vypnout"}, 1.0)
    assert d.stav()["chyby"] == ["svetlo"]
    assert led(falesny_hardware).value == pytest.approx(HODNOTA_VECER)
    falesny_hardware.porucha = set()
    d.krok(VECER, 2.0)
    assert d.stav()["chyby"] == [] and led(falesny_hardware).value == (0.0, 0.0, 0.0)
