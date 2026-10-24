from datetime import datetime

import pytest

from domek.hardware.rozhrani import MereniMeteo
from domek.hardware.simulace import (SimDisplej, SimIndikatory, SimMeteo, SimNastavovacCasu,
                                     SimPohyb, SimSvetelnost, SimVystupSvetla)
from domek.svetlo import VYPNUTO, StavSvetla


def test_mereni_meteo_ma_pojmenovana_pole_ve_stanovenem_poradi():
    mereni = MereniMeteo(1.0, 2.0, 3.0)
    assert (mereni.teplota, mereni.vlhkost, mereni.tlak) == (1.0, 2.0, 3.0)
    assert MereniMeteo._fields == ("teplota", "vlhkost", "tlak")


def test_vychozi_hodnoty_simulace():
    assert SimMeteo().precti() == MereniMeteo(22.0, 45.0, 1013.25)
    assert SimSvetelnost().precti_lux() == 200.0
    assert SimPohyb().je_pohyb() is False
    assert SimIndikatory().historie == [] and SimIndikatory().historie_pohybu == []
    assert SimDisplej().posledni is None and SimVystupSvetla().posledni is None
    assert SimNastavovacCasu().posledni is None
    for sim in (SimMeteo(), SimSvetelnost(), SimDisplej(), SimVystupSvetla(), SimNastavovacCasu()):
        assert sim.selhava is False


def test_sim_meteo_umi_vlhkost_none_jako_bmp280_ktery_vlhkost_nemeri():
    meteo = SimMeteo(vlhkost=None)
    assert meteo.precti() == MereniMeteo(22.0, None, 1013.25)
    assert (meteo.teplota, meteo.tlak) == (22.0, 1013.25)  # ostatní hodnoty zůstávají
    meteo.vlhkost = 45.0  # i za běhu jde "vyměnit" čidlo za BME280, a naopak
    assert meteo.precti().vlhkost == 45.0
    meteo.vlhkost = None
    assert meteo.precti().vlhkost is None


def test_cidla_ctou_hodnoty_z_atributu():
    meteo = SimMeteo(teplota=-5.5, vlhkost=80.0, tlak=990.0)
    assert meteo.precti() == MereniMeteo(-5.5, 80.0, 990.0)
    meteo.teplota = 1.5
    assert meteo.precti().teplota == 1.5
    svetelnost = SimSvetelnost(lux=12.5)
    assert svetelnost.precti_lux() == 12.5
    svetelnost.lux = 70000.0
    assert svetelnost.precti_lux() == 70000.0
    pohyb = SimPohyb()
    pohyb.pohyb = True
    assert pohyb.je_pohyb() is True


def test_selhavajici_cidla_vyhodi_oserror_a_po_navratu_ctou_dal():
    meteo, svetelnost = SimMeteo(), SimSvetelnost()
    meteo.selhava = svetelnost.selhava = True
    with pytest.raises(OSError):
        meteo.precti()
    with pytest.raises(OSError):
        svetelnost.precti_lux()
    meteo.selhava = svetelnost.selhava = False
    assert meteo.precti().teplota == 22.0 and svetelnost.precti_lux() == 200.0


def test_indikatory_si_pamatuji_vsechna_nastaveni():
    indikatory = SimIndikatory()
    indikatory.nastav(True, False)
    indikatory.nastav(False, True)
    assert indikatory.historie == [(True, False), (False, True)]


def test_indikatory_si_pamatuji_kontrolku_pohybu_zvlast():
    indikatory = SimIndikatory()
    indikatory.nastav(True, False)
    indikatory.ukaz_pohyb(True)
    indikatory.ukaz_pohyb(False)
    assert indikatory.historie_pohybu == [True, False]
    assert indikatory.historie == [(True, False)]


def test_vystupy_si_pamatuji_posledni_hodnotu_a_pri_poruse_selzou():
    displej, vystup, hodiny = SimDisplej(), SimVystupSvetla(), SimNastavovacCasu()
    displej.zobraz({"a": 1}, True)
    vystup.nastav(VYPNUTO)
    hodiny.nastav(datetime(2026, 10, 20, 14, 3, 0))
    assert displej.posledni == ({"a": 1}, True)
    assert vystup.posledni == VYPNUTO
    assert hodiny.posledni == datetime(2026, 10, 20, 14, 3, 0)
    displej.selhava = vystup.selhava = hodiny.selhava = True
    with pytest.raises(OSError):
        displej.zobraz({"a": 2}, False)
    with pytest.raises(OSError):
        vystup.nastav(StavSvetla(True, (1, 2, 3), 4, True))
    with pytest.raises(OSError):
        hodiny.nastav(datetime(2027, 1, 1, 0, 0, 0))
    # selhání nic nezapíše
    assert displej.posledni == ({"a": 1}, True)
    assert vystup.posledni == VYPNUTO
    assert hodiny.posledni == datetime(2026, 10, 20, 14, 3, 0)
