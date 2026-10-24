"""Text na displeji: řádky ze stavu domku, bez hardwaru."""
import copy
from datetime import datetime

import pytest

from domek.hardware.simulace import SimIndikatory, SimMeteo, SimPohyb
from domek.jadro import Domek
from domek.nastaveni import Nastaveni
from domek.text_displeje import radky_displeje

STAV = {"cas": "2026-10-20T10:05:09", "usek": "den", "t_venku": 22.04, "vlhkost": 45.2,
        "tlak": 1013.25, "lux": None, "t_uvnitr": 22.46, "cil": 23.0, "topi": True, "chladi": False,
        "rezim": "auto", "nikdo_doma": False, "pohyb": False,
        "svetlo": {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": False}, "chyby": []}


def test_bezny_stav():
    assert radky_displeje(STAV, True) == [
        "10:05 den         BLE",
        "Uvnitr 22.5 cil 23.0",
        "Venku  22.0C 1013hPa",
        "TOPI   auto",
        "Svetlo vyp",
    ]


def test_chybejici_hodnoty_a_chyby():
    stav = dict(STAV, t_venku=None, vlhkost=None, tlak=None, t_uvnitr=None, cil=None, topi=False,
                rezim="vypnuto", nikdo_doma=True,
                svetlo={"zap": True, "barva": [0, 0, 255], "jas": 50, "rucne": True},
                chyby=["meteo", "displej"])
    assert radky_displeje(stav, False) == [
        "10:05 den",
        "Uvnitr --.- cil  vyp",
        "Venku  --.-C   --hPa",
        "klid   vypnuto pryc",
        "Svetlo zap rucne",
        "CHYBA meteo,displej",
    ]


def test_radky_nejsou_delsi_nez_21_znaku():
    stav = dict(STAV, t_uvnitr=-12.34, rezim="vypnuto", nikdo_doma=True, topi=False, chladi=True,
                chyby=["displej", "meteo", "nastaveni", "osvetleni", "svetlo"])
    assert all(len(r) <= 21 for r in radky_displeje(stav, True))


# --- Další případy ---

def test_ble_zacina_v_osmnactem_sloupci_a_bez_pripojeni_neni_nic():
    pripojeno, nepripojeno = radky_displeje(STAV, True)[0], radky_displeje(STAV, False)[0]
    assert pripojeno == "10:05 den" + " " * 9 + "BLE" and len(pripojeno) == 21
    assert nepripojeno == "10:05 den"  # bez mezer na konci


def test_chybi_cas_nebo_usek():
    assert radky_displeje(dict(STAV, cas=None), False)[0] == "--:-- den"
    assert radky_displeje(dict(STAV, cas=None), True)[0] == "--:-- den" + " " * 9 + "BLE"
    assert radky_displeje(dict(STAV, cas=None, usek=None), False)[0] == "--:--"
    assert radky_displeje(dict(STAV, usek=None), True)[0] == "10:05" + " " * 13 + "BLE"


@pytest.mark.parametrize("hodnota, text", [
    (7.04, " 7.0"), (-5.0, "-5.0"), (-12.34, "-12.3"), (100.0, "100.0")])
def test_teplota_ma_jedno_desetinne_misto_a_nejmene_ctyri_znaky(hodnota, text):
    assert radky_displeje(dict(STAV, t_uvnitr=hodnota), False)[1] == f"Uvnitr {text} cil 23.0"


@pytest.mark.parametrize("tlak, text", [
    (300.4, " 300"), (987.9, " 988"), (1013.25, "1013"), (1100.0, "1100")])
def test_tlak_je_cele_cislo_v_hpa_na_ctyri_znaky(tlak, text):
    assert radky_displeje(dict(STAV, tlak=tlak), False)[2] == f"Venku  22.0C {text}hPa"


def test_vlhkost_se_na_displeji_neukazuje():
    # Displej ukazuje tlak, který měří BME280 i BMP280; vlhkost je jen ve Stavu pro tablet
    for vlhkost in (None, 5.4, 100.0):
        assert radky_displeje(dict(STAV, vlhkost=vlhkost), False) == radky_displeje(STAV, False)


def test_nejsirsi_radek_venku_se_vejde():
    assert radky_displeje(dict(STAV, t_venku=-12.34, tlak=1013.25), False)[2] == (
        "Venku  -12.3C 1013hPa")


def test_chybi_jen_jedna_hodnota():
    assert radky_displeje(dict(STAV, t_uvnitr=None), False)[1] == "Uvnitr --.- cil 23.0"
    assert radky_displeje(dict(STAV, cil=None), False)[1] == "Uvnitr 22.5 cil  vyp"
    assert radky_displeje(dict(STAV, t_venku=None), False)[2] == "Venku  --.-C 1013hPa"
    assert radky_displeje(dict(STAV, tlak=None), False)[2] == "Venku  22.0C   --hPa"


@pytest.mark.parametrize("topi, chladi, akce", [
    (True, False, "TOPI   "), (False, True, "CHLADI "), (False, False, "klid   ")])
def test_akce_je_zarovnana_na_sedm_znaku(topi, chladi, akce):
    assert radky_displeje(dict(STAV, topi=topi, chladi=chladi), False)[3] == akce + "auto"


def test_pryc_se_pise_za_rezim():
    stav = dict(STAV, rezim="rucni", nikdo_doma=True)
    assert radky_displeje(stav, False)[3] == "TOPI   rucni pryc"


@pytest.mark.parametrize("zap, rucne, text", [
    (False, False, "Svetlo vyp"), (True, False, "Svetlo zap"),
    (True, True, "Svetlo zap rucne"), (False, True, "Svetlo vyp rucne")])
def test_radek_svetla_ukazuje_stav_a_jestli_je_rucni(zap, rucne, text):
    svetlo = {"zap": zap, "barva": [255, 255, 255], "jas": 100, "rucne": rucne}
    assert radky_displeje(dict(STAV, svetlo=svetlo), False)[4] == text


def test_chybovy_radek_je_jen_kdyz_nejaka_chyba_je_a_je_posledni():
    assert len(radky_displeje(STAV, False)) == 5
    radky = radky_displeje(dict(STAV, chyby=["svetlo"]), False)
    assert len(radky) == 6 and radky[5] == "CHYBA svetlo"


def test_dlouhy_seznam_chyb_se_zkrati_na_21_znaku():
    stav = dict(STAV, chyby=["displej", "meteo", "nastaveni", "osvetleni", "svetlo"])
    assert radky_displeje(stav, False)[5] == "CHYBA displej,meteo,n"


def test_prilis_siroke_hodnoty_se_oriznou_na_21_znaku():
    stav = dict(STAV, t_uvnitr=-12.34, cil=-12.34)  # "Uvnitr -12.3 cil -12.3" by mělo 22 znaků
    radek = radky_displeje(stav, False)[1]
    assert radek.startswith("Uvnitr -12.3 cil -12") and len(radek) == 21


@pytest.mark.parametrize("zmeny", [
    {},
    {"cas": None, "usek": None, "t_venku": None, "vlhkost": None, "tlak": None, "t_uvnitr": None,
     "cil": None},
    {"rezim": "vypnuto", "nikdo_doma": True, "topi": False, "chladi": True,
     "svetlo": {"zap": True, "barva": [1, 2, 3], "jas": 50, "rucne": True},
     "chyby": ["displej", "meteo", "nastaveni", "osvetleni", "svetlo"]},
])
@pytest.mark.parametrize("pripojeno", [False, True])
def test_nejvys_sest_radku_po_21_znacich_ascii(zmeny, pripojeno):
    radky = radky_displeje(dict(STAV, **zmeny), pripojeno)
    assert 5 <= len(radky) <= 6
    assert all(len(radek) <= 21 and radek.isascii() for radek in radky)


def test_stav_se_nezmeni():
    stav = dict(STAV, chyby=["meteo"])
    kopie = copy.deepcopy(stav)
    radky_displeje(stav, True)
    assert stav == kopie


# --- Skutečný stav domku: pozná se tu, kdyby Domek.stav() změnil tvar ---

def vytvor_domek(meteo=None):
    return Domek(Nastaveni(), meteo or SimMeteo(), SimPohyb(), SimIndikatory())


def test_radky_ze_stavu_domku_po_prvnim_kroku():
    domek = vytvor_domek()
    domek.krok(datetime(2026, 10, 20, 10, 5, 9), 0.0)
    assert radky_displeje(domek.stav(), True) == [
        "10:05 den         BLE",
        "Uvnitr 22.0 cil 23.0",
        "Venku  22.0C 1013hPa",
        "TOPI   auto",
        "Svetlo vyp",
    ]


def test_radky_ze_stavu_domku_pred_prvnim_krokem():
    # Čas, úsek, měření i cíl ještě nejsou, ale stav se musí dát ukázat
    assert radky_displeje(vytvor_domek().stav(), False) == [
        "--:--",
        "Uvnitr --.- cil  vyp",
        "Venku  --.-C   --hPa",
        "klid   auto",
        "Svetlo vyp",
    ]


def test_radky_ze_stavu_domku_s_bmp280_bez_vlhkosti():
    # BMP280 vlhkost neměří, ale porucha to není: displej ukazuje teplotu a tlak jako s BME280,
    # není tu řádek CHYBA a termostat funguje
    domek = vytvor_domek(SimMeteo(vlhkost=None))
    domek.krok(datetime(2026, 10, 20, 10, 5, 9), 0.0)
    assert radky_displeje(domek.stav(), False) == [
        "10:05 den",
        "Uvnitr 22.0 cil 23.0",
        "Venku  22.0C 1013hPa",
        "TOPI   auto",
        "Svetlo vyp",
    ]


def test_radky_ze_stavu_domku_s_poruchou_meteo_v_noci():
    meteo = SimMeteo()
    meteo.selhava = True
    domek = vytvor_domek(meteo)
    domek.krok(datetime(2026, 10, 20, 23, 0, 0), 0.0)
    assert radky_displeje(domek.stav(), False) == [
        "23:00 noc",
        "Uvnitr --.- cil 20.0",
        "Venku  --.-C   --hPa",
        "klid   auto",
        "Svetlo vyp",
        "CHYBA meteo",
    ]
