"""Čidlo osvětlení TSL2561: přepočet kanálů na lux a ovladač zkoušený s falešnou knihovnou smbus2
(viz conftest.py)."""
import math
import sys
from datetime import datetime

import pytest

from domek.hardware import cidlo_tsl2561
from domek.hardware.cidlo_tsl2561 import Tsl2561, lux_z_kanalu
from domek.hardware.simulace import SimIndikatory, SimMeteo, SimPohyb
from domek.jadro import Domek
from domek.nastaveni import Nastaveni

DEN = datetime(2026, 10, 20, 10, 5, 9)
PRVNI_MERENI_S = 0.402  # doba integrace, první měření po zapnutí trvá aspoň tak dlouho
ZAPNUTI = [("write_byte_data", 0x39, 0x80, 0x03), ("write_byte_data", 0x39, 0x81, 0x02)]
CTENI = [("read_word_data", 0x39, 0xAC), ("read_word_data", 0x39, 0xAE)]


def jmena_volani(hw):
    return [volani[0] for volani in hw.volani]


class FalesneHodiny:
    """Nahrazuje modul time v ovladači: sleep nečeká. Pauzu zapíše do záznamu falešného hardwaru
    jako ("sleep",), takže je vidět, co se stalo před ní a co po ní."""

    def __init__(self, hw):
        self.pauzy = []
        self._hw = hw

    def sleep(self, sekund):
        self.pauzy.append(sekund)
        self._hw.volani.append(("sleep",))


@pytest.fixture
def hodiny(falesny_hardware, monkeypatch):
    falesne = FalesneHodiny(falesny_hardware)
    monkeypatch.setattr(cidlo_tsl2561, "time", falesne)
    return falesne


# --- lux_z_kanalu ---

@pytest.mark.parametrize("ch0, ch1, lux", [
    (1000, 100, 446.91),    # poměr 0,1
    (1000, 550, 85.60),     # poměr 0,55
    (1000, 700, 33.44),     # poměr 0,7
    (1000, 1000, 5.44),     # poměr 1,0
    (1000, 1400, 0.0),      # poměr nad 1,3
    (0, 0, 0.0),
    (0, 50, 0.0),
])
def test_prevod_kanalu_na_lux(ch0, ch1, lux):
    assert lux_z_kanalu(ch0, ch1) == pytest.approx(lux, abs=0.01)


@pytest.mark.parametrize("ch0, ch1", [
    (0xFFFF, 0), (0xFFFF, 0xFFFF), (1, 1), (100, 80), (100, 61), (100, 50), (65535, 32768),
    (1000, 1300), (1000, 1301)])
def test_prevod_je_vzdy_konecny_a_nezaporny(ch0, ch1):
    lux = lux_z_kanalu(ch0, ch1)
    assert math.isfinite(lux) and lux >= 0.0


# --- Tsl2561 ---

def test_tsl2561_pri_vytvoreni_nenacita_knihovnu_a_nesaha_na_hardware(monkeypatch):
    monkeypatch.setitem(sys.modules, "smbus2", None)  # import by teď selhal s ImportError
    Tsl2561()
    Tsl2561(0x29, 0)


@pytest.mark.parametrize("argumenty, sbernice, adresa", [
    ((), 1, 0x39),
    ((0x29, 0), 0, 0x29),
])
def test_tsl2561_zapne_cidlo_pocka_na_prvni_mereni_a_precte_oba_kanaly(
        falesny_hardware, hodiny, argumenty, sbernice, adresa):
    assert Tsl2561(*argumenty).precti_lux() == pytest.approx(446.91, abs=0.01)  # kanály 1000, 100
    assert falesny_hardware.volani == [
        ("SMBus", sbernice),
        ("write_byte_data", adresa, 0x80, 0x03),  # řídicí registr: zapnuto
        ("write_byte_data", adresa, 0x81, 0x02),  # časování: 402 ms, zesílení 1x
        ("sleep",),                               # první měření se nejdřív dokončí
        ("read_word_data", adresa, 0xAC),         # CH0
        ("read_word_data", adresa, 0xAE)]         # CH1
    assert len(hodiny.pauzy) == 1 and hodiny.pauzy[0] >= PRVNI_MERENI_S


@pytest.mark.parametrize("kanaly, lux", [
    ((1000, 100), 446.91),
    ((1000, 550), 85.60),
    ((1000, 1000), 5.44),
    ((100, 1000), 0.0),
])
def test_tsl2561_vraci_lux_z_prectenych_kanalu(falesny_hardware, hodiny, kanaly, lux):
    falesny_hardware.kanaly = kanaly
    assert Tsl2561().precti_lux() == pytest.approx(lux, abs=0.01)


def test_tsl2561_otevre_sbernici_a_zapne_cidlo_jen_jednou(falesny_hardware, hodiny):
    cidlo = Tsl2561()
    vysledky = []
    for kanaly in [(1000, 100), (1000, 550), (1000, 700)]:  # čidlo měří dál, čtení vezme poslední
        falesny_hardware.kanaly = kanaly
        vysledky.append(cidlo.precti_lux())
    assert vysledky == pytest.approx([446.91, 85.60, 33.44], abs=0.01)
    assert jmena_volani(falesny_hardware) == (
        ["SMBus", "write_byte_data", "write_byte_data", "sleep"] + ["read_word_data"] * 6)
    assert len(hodiny.pauzy) == 1  # čeká se jen na první měření


def test_tsl2561_po_nulovych_kanalech_zapne_cidlo_znovu_bez_cekani(falesny_hardware, hodiny):
    # Obě nuly jsou úplná tma, nebo čidlo, které na chvíli ztratilo napájení: to je pak vypnuté
    # a odpovídá nulami. Zapnutí se proto pošle znovu, ale bez čekání: čtení běží ve smyčce GLib
    # a další čtení za sekundu už dostane nové měření.
    cidlo = Tsl2561()
    cidlo.precti_lux()  # první čtení: zapne čidlo a počká na první měření
    del falesny_hardware.volani[:]
    falesny_hardware.kanaly = (0, 0)
    assert cidlo.precti_lux() == 0.0
    assert falesny_hardware.volani == CTENI + ZAPNUTI
    falesny_hardware.kanaly = (1000, 100)  # čidlo zase měří
    assert cidlo.precti_lux() == pytest.approx(446.91, abs=0.01)
    assert falesny_hardware.volani[4:] == CTENI  # už bez zapnutí
    assert len(hodiny.pauzy) == 1  # čekalo se jen při prvním čtení


@pytest.mark.parametrize("kanaly", [(0, 1), (1, 0), (0xFFFF, 0xFFFF), (0, 50)])
def test_tsl2561_kdyz_neni_nula_v_obou_kanalech_zapnuti_znovu_neposila(
        falesny_hardware, hodiny, kanaly):
    cidlo = Tsl2561()
    cidlo.precti_lux()
    del falesny_hardware.volani[:]
    falesny_hardware.kanaly = kanaly
    cidlo.precti_lux()
    assert jmena_volani(falesny_hardware) == ["read_word_data"] * 2


def test_tsl2561_v_uplne_tme_nikdy_neceka(falesny_hardware, hodiny):
    # Ve tmě čte nuly každou sekundu: zapnutí jde pokaždé znovu, ale smyčka GLib nikdy nečeká
    cidlo = Tsl2561()
    falesny_hardware.kanaly = (0, 0)
    assert [cidlo.precti_lux() for _ in range(3)] == [0.0, 0.0, 0.0]
    assert jmena_volani(falesny_hardware) == (
        ["SMBus", "write_byte_data", "write_byte_data", "sleep"]
        + (["read_word_data"] * 2 + ["write_byte_data"] * 2) * 3)
    assert len(hodiny.pauzy) == 1  # jen první čtení


def test_tsl2561_kdyz_zapnuti_po_nulach_selze_je_to_porucha(falesny_hardware, hodiny):
    # Stejně jako u každé jiné chyby sběrnice: OSError, a další čtení zapne čidlo znovu
    # i s čekáním na první měření
    cidlo = Tsl2561()
    cidlo.precti_lux()
    falesny_hardware.kanaly = (0, 0)
    falesny_hardware.porucha = {"zapnuti"}
    with pytest.raises(OSError):
        cidlo.precti_lux()
    falesny_hardware.porucha = set()
    falesny_hardware.kanaly = (1000, 100)
    assert cidlo.precti_lux() == pytest.approx(446.91, abs=0.01)
    assert jmena_volani(falesny_hardware) == (
        ["SMBus", "write_byte_data", "write_byte_data", "sleep"] + ["read_word_data"] * 2  # první
        + ["read_word_data"] * 2 + ["write_byte_data"]  # nuly a zapnutí, které selhalo
        + ["write_byte_data", "write_byte_data", "sleep"] + ["read_word_data"] * 2)  # znovu


@pytest.mark.parametrize("porucha, volani", [
    # sběrnice se neotevře: nic dalšího se nezkouší, a když se otevře, zůstane otevřená
    ("sbernice", ["SMBus", "SMBus", "SMBus"] + ["write_byte_data"] * 2 + ["sleep"]
     + ["read_word_data"] * 2),
    # čidlo se nepodaří zapnout: nečeká se ani nečte a sběrnice se neotevírá znovu
    ("zapnuti", ["SMBus"] + ["write_byte_data"] * 2 + ["write_byte_data"] * 2 + ["sleep"]
     + ["read_word_data"] * 2),
])
def test_tsl2561_porucha_je_oserror_a_pozdejsi_cteni_dokonci_jen_co_chybi(
        falesny_hardware, hodiny, porucha, volani):
    cidlo = Tsl2561()
    falesny_hardware.porucha = {porucha}
    for _ in range(2):  # čidlo, které se zatím neozývá, hlásí chybu při každém čtení
        with pytest.raises(OSError):
            cidlo.precti_lux()
    falesny_hardware.porucha = set()
    assert cidlo.precti_lux() == pytest.approx(446.91, abs=0.01)
    assert jmena_volani(falesny_hardware) == volani


def test_tsl2561_porucha_cteni_kanalu_je_oserror_a_cidlo_se_zapne_znovu(falesny_hardware, hodiny):
    cidlo = Tsl2561()
    falesny_hardware.porucha = {"kanaly"}
    for _ in range(2):
        with pytest.raises(OSError):
            cidlo.precti_lux()
    falesny_hardware.porucha = set()
    assert cidlo.precti_lux() == pytest.approx(446.91, abs=0.01)
    assert jmena_volani(falesny_hardware) == (
        ["SMBus"] + (["write_byte_data"] * 2 + ["sleep", "read_word_data"]) * 2
        + ["write_byte_data"] * 2 + ["sleep"] + ["read_word_data"] * 2)
    assert len(hodiny.pauzy) == 3 and min(hodiny.pauzy) >= PRVNI_MERENI_S


def test_tsl2561_nepotrebuje_knihovnu_bme280(falesny_hardware, hodiny, monkeypatch):
    monkeypatch.setitem(sys.modules, "bme280", None)
    assert Tsl2561().precti_lux() == pytest.approx(446.91, abs=0.01)


# --- Domek s čidlem TSL2561 ---

@pytest.mark.parametrize("porucha", ["sbernice", "zapnuti", "kanaly"])
def test_domek_ohlasi_poruchu_cidla_jako_osvetleni_a_po_navratu_ji_smaze(
        falesny_hardware, hodiny, porucha):
    # Kód chyby je "osvetleni"
    domek = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), svetelnost=Tsl2561())
    falesny_hardware.porucha = {porucha}
    domek.krok(DEN, 0.0)
    assert (domek.stav()["lux"], domek.stav()["chyby"]) == (None, ["osvetleni"])
    falesny_hardware.porucha = set()
    domek.krok(DEN, 1.0)
    assert (domek.stav()["lux"], domek.stav()["chyby"]) == (446.9, [])
