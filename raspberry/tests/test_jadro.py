import json
import logging
import os
from datetime import datetime, timedelta

import pytest

from domek.hardware.simulace import (SimDisplej, SimIndikatory, SimMeteo, SimNastavovacCasu, SimPohyb,
                                     SimSvetelnost, SimVystupSvetla)
from domek.jadro import ChybaPrikazu, Domek
from domek.nastaveni import ChybaNastaveni, Nastaveni, nacti, slouc
from domek.svetlo import JAS_NOC, VYPNUTO, StavSvetla

DEN = datetime(2026, 10, 20, 10, 0, 0)
NOC = datetime(2026, 10, 20, 23, 0, 0)


def vytvor(nastaveni=None, **kw):
    hw = {"meteo": SimMeteo(), "cidlo_pohybu": SimPohyb(), "indikatory": SimIndikatory()}
    hw.update(kw)
    return Domek(nastaveni or Nastaveni(), **hw), hw


def test_prvni_krok_naplni_stav():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    s = d.stav()
    assert s["cas"] == "2026-10-20T10:00:00" and s["usek"] == "den"
    assert (s["t_venku"], s["vlhkost"], s["tlak"], s["lux"]) == (22.0, 45.0, 1013.25, None)
    assert (s["t_uvnitr"], s["cil"], s["topi"], s["chladi"]) == (22.0, 23.0, True, False)
    assert (s["rezim"], s["nikdo_doma"], s["pohyb"]) == ("auto", False, False)
    assert s["svetlo"] == {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": False}
    assert s["chyby"] == []
    assert hw["indikatory"].historie[-1] == (True, False)


def test_topeni_se_po_dosazeni_cile_vypne():
    d, hw = vytvor()
    for i in range(80):
        d.krok(DEN + timedelta(seconds=i), float(i))
    assert hw["indikatory"].historie[-1] == (False, False)
    assert 22.7 <= d.stav()["t_uvnitr"] <= 23.1


def test_chyba_meteo_zastavi_regulaci_a_po_navratu_se_obnovi():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["meteo"].selhava = True
    d.krok(DEN, 1.0)
    s = d.stav()
    assert (s["topi"], s["chladi"], s["t_venku"], s["chyby"]) == (False, False, None, ["meteo"])
    assert hw["indikatory"].historie[-1] == (False, False)
    hw["meteo"].selhava = False
    d.krok(DEN, 2.0)
    assert d.stav()["chyby"] == [] and d.stav()["topi"]


def test_meteo_chybi_uz_od_startu():
    meteo = SimMeteo()
    meteo.selhava = True
    d, _ = vytvor(meteo=meteo)
    d.krok(DEN, 0.0)
    assert (d.stav()["t_uvnitr"], d.stav()["cil"], d.stav()["topi"]) == (None, 23.0, False)


def test_nikdo_doma_snizi_cil_a_pohyb_ho_zrusi():
    d, hw = vytvor(slouc(Nastaveni(), {"nikdo_doma_po_min": 1}))
    d.krok(DEN, 0.0)
    d.krok(DEN, 61.0)
    assert d.stav()["nikdo_doma"] and d.stav()["cil"] == 20.0
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(61.5) is True
    d.krok(DEN, 62.0)
    assert not d.stav()["nikdo_doma"] and d.stav()["cil"] == 23.0


def test_skok_hodin_neovlivni_odpocet_nikdo_doma():
    d, _ = vytvor(slouc(Nastaveni(), {"nikdo_doma_po_min": 1}))
    d.krok(DEN, 0.0)
    d.krok(DEN + timedelta(hours=2), 1.0)
    assert d.stav()["nikdo_doma"] is False


def test_kontroluj_pohyb_hlasi_jen_zmenu():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    assert d.kontroluj_pohyb(0.1) is False
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(0.2) is True
    assert d.kontroluj_pohyb(0.3) is False
    hw["cidlo_pohybu"].pohyb = False
    assert d.kontroluj_pohyb(0.4) is True


def test_v_noci_pohyb_hned_rozsviti_nocni_svetlo():
    vystup = SimVystupSvetla()
    d, hw = vytvor(vystup_svetla=vystup)
    d.krok(NOC, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(0.1)
    assert d.stav()["svetlo"]["zap"] and d.stav()["svetlo"]["jas"] == JAS_NOC
    assert vystup.posledni.zapnuto


def test_osvetleni_z_cidla_je_ve_stavu():
    d, _ = vytvor(svetelnost=SimSvetelnost(lux=30.0))
    d.krok(DEN, 0.0)
    assert d.stav()["lux"] == 30.0 and d.stav()["svetlo"]["zap"]


def test_zmena_nastaveni_se_ulozi_a_neplatna_se_odmitne(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.zmen_nastaveni({"cil_den": 24.0})
    assert d.nastaveni.cil_den == 24.0 and nacti(cesta).cil_den == 24.0
    with pytest.raises(ChybaNastaveni):
        d.zmen_nastaveni({"cil_den": 99})
    assert d.nastaveni.cil_den == 24.0


def test_prikazy_svetla():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": "zapnout"}, 0.1)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [255, 255, 255], "jas": 100, "rucne": True}
    d.proved_prikaz({"svetlo": {"barva": [0, 0, 255], "jas": 50}}, 0.2)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [0, 0, 255], "jas": 50, "rucne": True}
    d.proved_prikaz({"svetlo": "prepnout"}, 0.3)
    assert d.stav()["svetlo"]["zap"] is False
    d.proved_prikaz({"svetlo": "auto"}, 0.4)
    assert d.stav()["svetlo"]["rucne"] is False


@pytest.mark.parametrize("prikaz", [
    {}, [], "svetlo", {"svetlo": "blikat"}, {"neco": 1},
    {"svetlo": "zapnout", "cas": "2026-10-20T10:00:00"},
    {"svetlo": {}}, {"svetlo": {"barva": [0, 0, 256]}}, {"svetlo": {"barva": [0, 0]}},
    {"svetlo": {"barva": [True, 0, 0]}}, {"svetlo": {"jas": True}}, {"svetlo": {"jas": 101}},
    {"svetlo": {"bila": 1}},
    {"cas": "zitra"}, {"cas": 5}, {"cas": "2026-10-20T14:03:00+02:00"},
])
def test_neplatne_prikazy_se_odmitnou(prikaz):
    d, _ = vytvor(nastavovac_casu=SimNastavovacCasu())
    d.krok(DEN, 0.0)
    with pytest.raises(ChybaPrikazu):
        d.proved_prikaz(prikaz, 0.1)


def test_prikaz_cas():
    hodiny = SimNastavovacCasu()
    d, _ = vytvor(nastavovac_casu=hodiny)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"cas": "2026-10-20T14:03:00"}, 0.1)
    assert hodiny.posledni == datetime(2026, 10, 20, 14, 3, 0)


def test_prikaz_cas_bez_nastavovace_nebo_pri_selhani():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    with pytest.raises(ChybaPrikazu):
        d.proved_prikaz({"cas": "2026-10-20T14:03:00"}, 0.1)
    hodiny = SimNastavovacCasu()
    hodiny.selhava = True
    d, _ = vytvor(nastavovac_casu=hodiny)
    d.krok(DEN, 0.0)
    with pytest.raises(ChybaPrikazu):
        d.proved_prikaz({"cas": "2026-10-20T14:03:00"}, 0.1)


def test_chyba_displeje_nezastavi_domek():
    displej = SimDisplej()
    displej.selhava = True
    d, _ = vytvor(displej=displej)
    d.krok(DEN, 0.0)
    assert d.stav()["chyby"] == ["displej"]


# --- Doplňující testy: rozhodnutí kontrolora a pravidla ze zadání, která úvodní testy neověřují ---

VECER = datetime(2026, 10, 20, 20, 0, 0)
ZHASNUTO = {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": False}
SVETLO_VECER = {"zap": True, "barva": [255, 255, 255], "jas": 100, "rucne": False}
NEKONECNA = [float("nan"), float("inf"), float("-inf")]


# Stav

def test_stav_ma_presne_dohodnute_klice():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    assert set(d.stav()) == {
        "cas", "usek", "t_venku", "vlhkost", "tlak", "lux", "t_uvnitr", "cil", "topi", "chladi",
        "rezim", "nikdo_doma", "pohyb", "svetlo", "chyby"}


def test_stav_pred_prvnim_krokem_nic_neukazuje_ale_nespadne():
    d, _ = vytvor()
    s = d.stav()
    assert (s["cas"], s["usek"], s["t_venku"], s["vlhkost"], s["tlak"], s["lux"]) == (None,) * 6
    assert (s["t_uvnitr"], s["cil"], s["topi"], s["chladi"]) == (None, None, False, False)
    assert (s["rezim"], s["nikdo_doma"], s["pohyb"]) == ("auto", False, False)
    assert s["svetlo"] == ZHASNUTO and s["chyby"] == []


def test_stav_je_obycejny_json():
    d, _ = vytvor(svetelnost=SimSvetelnost(lux=30.0))
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [1, 2, 3]}}, 0.1)
    s = d.stav()
    # po cestě přes JSON se stav musí vrátit beze změny: n-tice by se vrátila jako seznam
    assert json.loads(json.dumps(s)) == s


def test_zmena_vraceneho_stavu_nezmeni_domek():
    displej = SimDisplej()
    displej.selhava = True
    d, _ = vytvor(displej=displej)
    d.krok(DEN, 0.0)
    s = d.stav()
    s["chyby"].append("zbytecna")
    s["svetlo"]["barva"][0] = 99
    s["svetlo"]["zap"] = True
    assert d.stav()["chyby"] == ["displej"] and d.stav()["svetlo"] == ZHASNUTO


def test_stav_zaokrouhluje_podle_zadani():
    d, _ = vytvor(slouc(Nastaveni(), {"cil_den": 23.456}),
                  meteo=SimMeteo(teplota=21.456, vlhkost=45.26, tlak=1013.256),
                  svetelnost=SimSvetelnost(lux=123.456))
    d.krok(DEN.replace(microsecond=999999), 0.0)
    s = d.stav()
    assert (s["t_venku"], s["t_uvnitr"], s["tlak"], s["cil"]) == (21.46, 21.46, 1013.26, 23.46)
    assert (s["vlhkost"], s["lux"]) == (45.3, 123.5)
    assert s["cas"] == "2026-10-20T10:00:00"  # zlomky sekundy se zahodí


# Úsek dne, cíl a režimy termostatu

@pytest.mark.parametrize("ted, usek, cil", [
    (DEN, "den", 23.0),
    (VECER, "vecer", 23.0),
    (NOC, "noc", 20.0),
    (datetime(2026, 10, 20, 3, 0, 0), "noc", 20.0),  # noc trvá přes půlnoc
])
def test_usek_a_cil_podle_denni_doby(ted, usek, cil):
    d, _ = vytvor()
    d.krok(ted, 0.0)
    assert (d.stav()["usek"], d.stav()["cil"]) == (usek, cil)


def test_posunuty_zacatek_noci_se_projevi_v_dalsim_kroku():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    d.zmen_nastaveni({"zacatek_noc": "09:00"})
    d.krok(DEN, 1.0)
    assert (d.stav()["usek"], d.stav()["cil"]) == ("noc", 20.0)


def test_rucni_rezim_ma_vlastni_cil():
    d, _ = vytvor(slouc(Nastaveni(), {"rezim_termostatu": "rucni", "cil_rucni": 25.0}))
    d.krok(DEN, 0.0)
    s = d.stav()
    assert (s["rezim"], s["cil"], s["topi"]) == ("rucni", 25.0, True)


def test_vypnuty_termostat_netopi_a_nechladi():
    d, hw = vytvor(slouc(Nastaveni(), {"rezim_termostatu": "vypnuto"}),
                   meteo=SimMeteo(teplota=10.0))
    d.krok(DEN, 0.0)
    s = d.stav()
    assert (s["rezim"], s["cil"], s["t_uvnitr"]) == ("vypnuto", None, 10.0)
    assert (s["topi"], s["chladi"]) == (False, False)
    assert hw["indikatory"].historie[-1] == (False, False)


def test_nad_cilem_se_chladi():
    d, hw = vytvor(meteo=SimMeteo(teplota=30.0))
    d.krok(DEN, 0.0)
    assert (d.stav()["topi"], d.stav()["chladi"]) == (False, True)
    assert hw["indikatory"].historie[-1] == (False, True)


def test_vnitrni_teplota_se_pocita_z_monotonnich_hodin():
    d, hw = vytvor()
    d.krok(DEN, 10.0)
    d.krok(DEN + timedelta(hours=1), 12.0)  # hodiny skočily o hodinu, monotonní čas o 2 s
    assert d.stav()["t_uvnitr"] == 22.12
    assert len(hw["indikatory"].historie) == 2


def test_indikatory_se_nastavuji_jen_v_kroku():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(0.5)
    d.proved_prikaz({"svetlo": "zapnout"}, 0.6)
    assert len(hw["indikatory"].historie) == 1


# Nikdo doma a pohyb

def test_nikdo_doma_se_pocita_od_prvniho_kroku_i_pri_velkych_hodinach():
    # monotonní hodiny nezačínají u nuly (na Linuxu měří čas od startu systému)
    d, _ = vytvor(slouc(Nastaveni(), {"nikdo_doma_po_min": 1}))
    d.krok(DEN, 100000.0)
    assert d.stav()["nikdo_doma"] is False
    d.krok(DEN, 100059.9)
    assert d.stav()["nikdo_doma"] is False
    d.krok(DEN, 100060.0)
    assert d.stav()["nikdo_doma"] is True


def test_trvajici_pohyb_odsouva_nikdo_doma():
    # PIR drží výstup nahoře, dokud se někdo hýbe: i čtení beze změny musí posunout poslední pohyb
    d, hw = vytvor(slouc(Nastaveni(), {"nikdo_doma_po_min": 1}))
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(1.0) is True
    assert d.kontroluj_pohyb(59.0) is False
    hw["cidlo_pohybu"].pohyb = False
    assert d.kontroluj_pohyb(59.1) is True
    d.krok(DEN, 118.9)  # od posledního pohybu v 59 s uplynulo 59,9 s
    assert d.stav()["nikdo_doma"] is False
    d.krok(DEN, 119.0)
    assert d.stav()["nikdo_doma"] is True


def test_pohyb_zrusi_nikdo_doma_hned_a_ne_az_dalsim_krokem():
    d, hw = vytvor(slouc(Nastaveni(), {"nikdo_doma_po_min": 1}))
    d.krok(DEN, 0.0)
    d.krok(DEN, 61.0)
    assert d.stav()["nikdo_doma"] is True
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(61.5)
    assert (d.stav()["nikdo_doma"], d.stav()["pohyb"]) == (False, True)


def test_kontroluj_pohyb_pred_prvnim_krokem_svetlo_nechava_byt():
    vystup = SimVystupSvetla()
    d, hw = vytvor(vystup_svetla=vystup)
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(0.0) is True
    assert d.stav()["pohyb"] is True and vystup.posledni is None  # úsek dne ještě není znám
    d.krok(NOC, 0.1)
    assert d.stav()["svetlo"]["zap"]  # krok počítá s naposledy přečteným pohybem


def test_bez_svetla_pri_pohybu_noc_nerozsviti_ale_pohyb_se_hlasi_a_rusi_nikdo_doma():
    vystup = SimVystupSvetla()
    d, hw = vytvor(slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": False, "nikdo_doma_po_min": 1}),
                   vystup_svetla=vystup)
    d.krok(NOC, 0.0)
    d.krok(NOC, 61.0)
    assert d.stav()["nikdo_doma"] is True
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(61.5) is True  # změna pohybu se hlásí
    d.krok(NOC, 62.0)
    stav = d.stav()
    assert stav["pohyb"] is True and stav["nikdo_doma"] is False  # pohyb rusi "nikdo doma"
    assert stav["svetlo"]["zap"] is False and vystup.posledni == VYPNUTO


def test_zapnuti_svetla_pri_pohybu_za_behu_se_projevi_pri_dalsim_pohybu():
    d, hw = vytvor()
    d.krok(NOC, 0.0)
    d.zmen_nastaveni({"nocni_svetlo_pri_pohybu": False})
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(1.0)
    assert d.stav()["svetlo"]["zap"] is False
    d.zmen_nastaveni({"nocni_svetlo_pri_pohybu": True})
    d.krok(NOC, 2.0)  # pohyb trvá
    assert d.stav()["svetlo"]["zap"] is True


# Světlo

def test_nocni_svetlo_zhasne_po_nastavene_dobe():
    d, hw = vytvor(slouc(Nastaveni(), {"nocni_svetlo_s": 10}))
    d.krok(NOC, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(5.0)  # svítí do 15 s
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(5.1)
    d.krok(NOC, 14.9)
    assert d.stav()["svetlo"]["zap"] is True
    d.krok(NOC, 15.0)
    assert d.stav()["svetlo"]["zap"] is False


def test_zmena_prahu_osvetleni_se_pouzije_v_dalsim_kroku():
    d, _ = vytvor(svetelnost=SimSvetelnost(lux=30.0))
    d.krok(DEN, 0.0)
    assert d.stav()["svetlo"]["zap"] is True
    d.zmen_nastaveni({"prah_lux": 10.0})  # 30 lx už není šero
    d.krok(DEN, 1.0)
    assert d.stav()["svetlo"]["zap"] is False


def test_vecer_sviti_automaticky_a_vystup_dostane_stav_z_kroku():
    vystup = SimVystupSvetla()
    d, _ = vytvor(vystup_svetla=vystup)
    d.krok(VECER, 0.0)
    assert d.stav()["svetlo"] == SVETLO_VECER
    assert vystup.posledni == StavSvetla(True, (255, 255, 255), 100, False)
    d.krok(DEN, 1.0)
    assert vystup.posledni == VYPNUTO


# Chyby čidel, výstupů a displeje

def test_chyba_cidla_osvetleni_vynuluje_lux_a_po_navratu_se_obnovi():
    cidlo = SimSvetelnost(lux=30.0)
    d, _ = vytvor(svetelnost=cidlo)
    d.krok(DEN, 0.0)
    cidlo.selhava = True
    d.krok(DEN, 1.0)
    s = d.stav()
    assert (s["lux"], s["chyby"], s["svetlo"]["zap"]) == (None, ["osvetleni"], False)
    cidlo.selhava = False
    d.krok(DEN, 2.0)
    s = d.stav()
    assert (s["lux"], s["chyby"], s["svetlo"]["zap"]) == (30.0, [], True)


@pytest.mark.parametrize("lux", NEKONECNA)
def test_nekonecne_osvetleni_je_porucha_cidla(lux):
    d, _ = vytvor(svetelnost=SimSvetelnost(lux=lux))
    d.krok(DEN, 0.0)
    assert (d.stav()["lux"], d.stav()["chyby"]) == (None, ["osvetleni"])


@pytest.mark.parametrize("pole", ["teplota", "vlhkost", "tlak"])
@pytest.mark.parametrize("hodnota", NEKONECNA)
def test_nekonecne_meteo_je_porucha_cidla(pole, hodnota):
    d, hw = vytvor(meteo=SimMeteo(**{pole: hodnota}))
    d.krok(DEN, 0.0)
    s = d.stav()
    assert (s["t_venku"], s["vlhkost"], s["tlak"], s["chyby"]) == (None, None, None, ["meteo"])
    assert (s["t_uvnitr"], s["topi"], s["chladi"]) == (None, False, False)
    assert hw["indikatory"].historie[-1] == (False, False)


def test_vlhkost_none_neni_porucha_cidla_bmp280_vlhkost_nemeri():
    # BMP280 vlhkost neměří, takže ji ovladač hlásí jako None. Teplota a tlak platí a termostat
    # běží dál, jen vlhkost zůstane neznámá.
    d, hw = vytvor(meteo=SimMeteo(vlhkost=None))
    d.krok(DEN, 0.0)
    d.krok(DEN, 1.0)
    s = d.stav()
    assert (s["t_venku"], s["vlhkost"], s["tlak"], s["chyby"]) == (22.0, None, 1013.25, [])
    assert (s["t_uvnitr"], s["topi"]) == (22.06, True)
    assert hw["indikatory"].historie[-1] == (True, False)
    assert json.loads(json.dumps(s)) == s  # vlhkost je v JSON null


def test_vlhkost_none_se_nezaloguje_jako_porucha(caplog):
    d, _ = vytvor(meteo=SimMeteo(vlhkost=None))
    with caplog.at_level(logging.INFO):
        d.krok(DEN, 0.0)
        d.krok(DEN, 1.0)
    assert caplog.records == []


@pytest.mark.parametrize("pole", ["teplota", "tlak"])
@pytest.mark.parametrize("hodnota", NEKONECNA)
def test_vlhkost_none_nezakryje_nekonecnou_teplotu_ani_tlak(pole, hodnota):
    # Kontrola konečných čísel platí dál pro všechno, co není None
    d, _ = vytvor(meteo=SimMeteo(vlhkost=None, **{pole: hodnota}))
    d.krok(DEN, 0.0)
    s = d.stav()
    assert (s["t_venku"], s["vlhkost"], s["tlak"], s["chyby"]) == (None, None, None, ["meteo"])
    assert (s["t_uvnitr"], s["topi"], s["chladi"]) == (None, False, False)


def test_vlhkost_se_po_vymene_bmp280_za_bme280_zase_ukaze():
    meteo = SimMeteo(vlhkost=None)
    d, _ = vytvor(meteo=meteo)
    d.krok(DEN, 0.0)
    assert d.stav()["vlhkost"] is None
    meteo.vlhkost = 45.0
    d.krok(DEN, 1.0)
    assert (d.stav()["vlhkost"], d.stav()["chyby"]) == (45.0, [])


def test_jedno_nekonecne_cteni_vnitrni_teplotu_netrvale_nepokazi():
    # NaN by se do modelu dostal a zůstal v něm: každé srovnání s NaN je nepravda, takže by se
    # topení nikdy nevypnulo a vnitřní teplota by už nikdy nebyla číslo
    meteo = SimMeteo()
    d, _ = vytvor(meteo=meteo)
    d.krok(DEN, 0.0)
    meteo.teplota = float("nan")
    d.krok(DEN, 1.0)
    assert (d.stav()["t_uvnitr"], d.stav()["chyby"]) == (22.0, ["meteo"])
    meteo.teplota = 22.0
    d.krok(DEN, 2.0)
    assert (d.stav()["t_uvnitr"], d.stav()["chyby"], d.stav()["topi"]) == (22.0, [], True)
    d.krok(DEN, 3.0)
    assert d.stav()["t_uvnitr"] == 22.06


def test_chyba_vystupu_svetla_se_ohlasi_a_po_navratu_zmizi():
    vystup = SimVystupSvetla()
    d, hw = vytvor(vystup_svetla=vystup, svetelnost=SimSvetelnost(lux=30.0))
    vystup.selhava = True
    d.krok(DEN, 0.0)
    s = d.stav()
    assert s["chyby"] == ["svetlo"] and s["svetlo"]["zap"] is True  # stav je to, co si světlo přeje
    assert hw["indikatory"].historie[-1] == (True, False)  # ostatní běží dál
    vystup.selhava = False
    d.krok(DEN, 1.0)
    assert d.stav()["chyby"] == []
    assert vystup.posledni == StavSvetla(True, (255, 255, 255), 100, False)


def test_chyba_vystupu_svetla_pri_pohybu():
    vystup = SimVystupSvetla()
    d, hw = vytvor(vystup_svetla=vystup)
    d.krok(NOC, 0.0)
    vystup.selhava = True
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(0.1) is True
    assert d.stav()["chyby"] == ["svetlo"]


def test_chyba_displeje_zmizi_az_se_displej_vrati():
    displej = SimDisplej()
    displej.selhava = True
    d, _ = vytvor(displej=displej)
    d.krok(DEN, 0.0)
    displej.selhava = False
    d.krok(DEN, 1.0)
    assert d.stav()["chyby"] == []


def test_displej_dostane_stav_a_zda_je_pripojen_tablet():
    displej = SimDisplej()
    d, _ = vytvor(displej=displej)
    d.krok(DEN, 0.0)
    assert displej.posledni == (d.stav(), False)
    d.pripojeno = True
    d.krok(DEN, 1.0)
    assert displej.posledni == (d.stav(), True)


def test_chyby_jsou_serazene(tmp_path):
    blok = tmp_path / "blok"
    blok.write_text("soubor, ne slozka")
    sim = {"meteo": SimMeteo(), "svetelnost": SimSvetelnost(), "displej": SimDisplej(),
           "vystup_svetla": SimVystupSvetla()}
    for porucha in sim.values():
        porucha.selhava = True
    d, _ = vytvor(cesta_nastaveni=str(blok / "nastaveni.json"), **sim)
    d.krok(DEN, 0.0)
    d.zmen_nastaveni({"cil_den": 24.0})
    assert d.stav()["chyby"] == ["displej", "meteo", "nastaveni", "osvetleni", "svetlo"]


@pytest.mark.parametrize("nazev, soucastka", [
    ("meteo", "meteo"), ("osvetleni", "svetelnost"), ("displej", "displej"),
    ("svetlo", "vystup_svetla")])
def test_porucha_se_zaloguje_jednou_i_s_duvodem_a_jeji_konec_taky(caplog, nazev, soucastka):
    sim = {"meteo": SimMeteo(), "svetelnost": SimSvetelnost(), "displej": SimDisplej(),
           "vystup_svetla": SimVystupSvetla()}
    d, _ = vytvor(**sim)
    sim[soucastka].selhava = True
    with caplog.at_level(logging.INFO):
        d.krok(DEN, 0.0)
        d.krok(DEN, 1.0)  # porucha trvá: do logu se znovu nezapíše, jinak by řádky přibývaly
        sim[soucastka].selhava = False
        d.krok(DEN, 2.0)
    duvod = f"Simulovany vypadek hardwaru ({type(sim[soucastka]).__name__})."
    assert [(z.levelno, z.getMessage()) for z in caplog.records] == [
        (logging.WARNING, f"Porucha {nazev}: {duvod}"),
        (logging.INFO, f"Porucha {nazev} skoncila.")]


def test_neplatne_mereni_se_zaloguje_jako_porucha(caplog):
    d, _ = vytvor(meteo=SimMeteo(teplota=float("nan")), svetelnost=SimSvetelnost(lux=float("inf")))
    with caplog.at_level(logging.WARNING):
        d.krok(DEN, 0.0)
    assert [z.getMessage() for z in caplog.records] == [
        "Porucha meteo: cidlo vratilo hodnotu, ktera neni konecne cislo: (nan, 45.0, 1013.25)",
        "Porucha osvetleni: cidlo vratilo hodnotu, ktera neni konecne cislo: inf"]


def test_selhani_ulozeni_nastaveni_se_zaloguje_s_duvodem(tmp_path, caplog):
    blok = tmp_path / "blok"
    blok.write_text("soubor, ne slozka")
    d, _ = vytvor(cesta_nastaveni=str(blok / "nastaveni.json"))
    with caplog.at_level(logging.INFO):
        d.zmen_nastaveni({"cil_den": 24.0})
        d.zmen_nastaveni({"cil_den": 25.0})  # porucha trvá
        blok.unlink()
        d.zmen_nastaveni({"cil_den": 26.0})
    [(uroven, zprava), konec] = [(z.levelno, z.getMessage()) for z in caplog.records]
    assert uroven == logging.WARNING and zprava.startswith("Porucha nastaveni: ")
    assert "blok" in zprava  # důvod je text chyby OSError, který jmenuje soubor
    assert konec == (logging.INFO, "Porucha nastaveni skoncila.")


# Nastavení

def test_zmena_nastaveni_bez_cesty_plati_a_nehlasi_chybu():
    d, _ = vytvor()
    d.zmen_nastaveni({"cil_den": 24.0})
    d.krok(DEN, 0.0)
    assert d.nastaveni.cil_den == 24.0
    assert (d.stav()["cil"], d.stav()["chyby"]) == (24.0, [])


def test_neplatna_zmena_nastaveni_nezmeni_ani_soubor(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.zmen_nastaveni({"cil_den": 24.0})
    ulozene = d.nastaveni
    for zmeny in ({"cil_den": 99}, {"neznamy": 1}, [], {"cil_den": 25.0, "cil_noc": 99}):
        with pytest.raises(ChybaNastaveni):
            d.zmen_nastaveni(zmeny)
    assert d.nastaveni == ulozene and nacti(cesta) == ulozene
    assert d.stav()["chyby"] == []


def test_selhani_ulozeni_se_ohlasi_ale_zmena_plati(tmp_path):
    blok = tmp_path / "blok"
    blok.write_text("soubor, ne slozka")  # složka nastavení by se musela jmenovat jako tento soubor
    cesta = str(blok / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.zmen_nastaveni({"cil_den": 24.0})  # nesmí vyhodit výjimku
    assert d.nastaveni.cil_den == 24.0
    assert d.stav()["chyby"] == ["nastaveni"]
    blok.unlink()  # překážka zmizí, další uložení už projde a chyba s ním
    d.zmen_nastaveni({"cil_den": 25.0})
    assert d.stav()["chyby"] == [] and nacti(cesta).cil_den == 25.0


# Příkazy

def test_prikaz_pred_prvnim_krokem_se_odmitne():
    hodiny = SimNastavovacCasu()
    d, _ = vytvor(nastavovac_casu=hodiny)
    for prikaz in ({"svetlo": "zapnout"}, {"svetlo": "auto"}, {"cas": "2026-10-20T14:03:00"}):
        with pytest.raises(ChybaPrikazu):
            d.proved_prikaz(prikaz, 0.0)
    assert hodiny.posledni is None


def test_prikaz_s_barvou_a_jasem_se_ulozi_do_nastaveni_a_platne_dal(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [10, 20, 30], "jas": 30}}, 0.1)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [10, 20, 30], "jas": 30, "rucne": True}
    assert (d.nastaveni.barva_svetla, d.nastaveni.jas_svetla) == ((10, 20, 30), 30)
    ulozeno = nacti(cesta)
    assert (ulozeno.barva_svetla, ulozeno.jas_svetla) == ((10, 20, 30), 30)
    d.proved_prikaz({"svetlo": "vypnout"}, 0.2)
    assert d.stav()["svetlo"] == {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": True}
    d.proved_prikaz({"svetlo": "zapnout"}, 0.3)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [10, 20, 30], "jas": 30, "rucne": True}


def test_prikaz_jen_s_jasem_zachova_ulozenou_barvu(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [1, 2, 3]}}, 0.1)  # chybějící jas zůstane 100
    assert d.stav()["svetlo"] == {"zap": True, "barva": [1, 2, 3], "jas": 100, "rucne": True}
    d.proved_prikaz({"svetlo": {"jas": 30}}, 0.2)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [1, 2, 3], "jas": 30, "rucne": True}
    ulozeno = nacti(cesta)
    assert (ulozeno.barva_svetla, ulozeno.jas_svetla) == ((1, 2, 3), 30)


def test_prikaz_jen_s_barvou_zachova_ulozeny_jas(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"jas": 30}}, 0.1)
    d.proved_prikaz({"svetlo": {"barva": [1, 2, 3]}}, 0.2)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [1, 2, 3], "jas": 30, "rucne": True}
    ulozeno = nacti(cesta)
    assert (ulozeno.barva_svetla, ulozeno.jas_svetla) == ((1, 2, 3), 30)


def test_po_restartu_sviti_vecer_barvou_z_prikazu(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [10, 20, 30], "jas": 40}}, 0.1)
    novy = Domek(nacti(cesta), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    novy.krok(VECER, 0.0)
    assert novy.stav()["svetlo"] == {"zap": True, "barva": [10, 20, 30], "jas": 40, "rucne": False}


def test_nocni_svetlo_po_restartu_sviti_v_ulozene_barve_ztlumene(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [10, 20, 30], "jas": 80}}, 0.1)
    novy, hw = vytvor(nacti(cesta))
    novy.krok(NOC, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    novy.kontroluj_pohyb(0.1)
    assert novy.stav()["svetlo"] == {"zap": True, "barva": [10, 20, 30], "jas": 10, "rucne": False}


def test_zmena_barvy_pres_nastaveni_zmeni_i_rucne_zapnute_svetlo():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": "zapnout"}, 0.1)
    d.zmen_nastaveni({"barva_svetla": [5, 6, 7], "jas_svetla": 20})
    d.krok(DEN, 1.0)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [5, 6, 7], "jas": 20, "rucne": True}


def test_odmitnuty_prikaz_barvy_nezmeni_ani_neulozi_nastaveni(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), cesta_nastaveni=cesta)
    d.krok(DEN, 0.0)
    for prikaz in ({"svetlo": {"barva": [10, 20, 30], "jas": 200}},
                   {"svetlo": {"barva": [10, 20], "jas": 30}}):
        with pytest.raises(ChybaPrikazu):
            d.proved_prikaz(prikaz, 0.1)
    assert d.nastaveni == Nastaveni()
    assert not os.path.exists(cesta)
    assert d.stav()["svetlo"]["rucne"] is False


@pytest.mark.parametrize("prikaz, zprava", [
    ({"svetlo": {"barva": [0, 0]}}, "Barva musi byt seznam tri celych cisel 0 az 255."),
    ({"svetlo": {"jas": 101}}, "Jas musi byt cele cislo 0 az 100."),
])
def test_chybove_zpravy_prikazu_barvy_a_jasu_zustaly(prikaz, zprava):
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    with pytest.raises(ChybaPrikazu) as chyba:
        d.proved_prikaz(prikaz, 0.1)
    assert str(chyba.value) == zprava


def test_chyba_ulozeni_barvy_ohlasi_nastaveni_ale_svetlo_svici(tmp_path):
    (tmp_path / "soubor").write_text("neni slozka", encoding="utf-8")
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(),
              cesta_nastaveni=str(tmp_path / "soubor" / "nastaveni.json"))
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [10, 20, 30], "jas": 30}}, 0.1)
    s = d.stav()
    assert s["chyby"] == ["nastaveni"]
    assert s["svetlo"] == {"zap": True, "barva": [10, 20, 30], "jas": 30, "rucne": True}


def test_prepnout_bere_to_co_prave_sviti():
    d, _ = vytvor(svetelnost=SimSvetelnost(lux=30.0))  # ve dne za šera svítí automatika
    d.krok(DEN, 0.0)
    assert d.stav()["svetlo"]["zap"] is True
    d.proved_prikaz({"svetlo": "prepnout"}, 0.1)
    assert d.stav()["svetlo"] == {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": True}
    d.proved_prikaz({"svetlo": "prepnout"}, 0.2)
    assert d.stav()["svetlo"] == {"zap": True, "barva": [255, 255, 255], "jas": 100, "rucne": True}


def test_prikaz_svetla_jde_hned_na_vystup():
    vystup = SimVystupSvetla()
    d, _ = vytvor(vystup_svetla=vystup)
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": {"barva": [0, 0, 255], "jas": 50}}, 0.1)
    assert vystup.posledni == StavSvetla(True, (0, 0, 255), 50, True)


def test_barva_z_prikazu_se_zkopiruje():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    barva = [0, 0, 255]
    d.proved_prikaz({"svetlo": {"barva": barva}}, 0.1)
    barva[2] = 0  # příkaz zvenku se dál používá a mění
    barva.append(7)
    assert d.stav()["svetlo"]["barva"] == [0, 0, 255]
    d.krok(DEN, 1.0)
    assert d.stav()["svetlo"]["barva"] == [0, 0, 255]


def test_rucni_svetlo_konci_se_zmenou_useku():
    d, _ = vytvor()
    d.krok(DEN, 0.0)
    d.proved_prikaz({"svetlo": "zapnout"}, 0.1)
    d.krok(DEN, 1.0)
    assert d.stav()["svetlo"]["rucne"] is True  # v úseku, ve kterém vznikla, platí
    d.krok(VECER, 2.0)
    assert d.stav()["svetlo"] == SVETLO_VECER


@pytest.mark.parametrize("prikaz, svetlo", [
    ({"svetlo": "zapnout"}, {"zap": True, "barva": [255, 255, 255], "jas": 100, "rucne": True}),
    ({"svetlo": "vypnout"}, {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": True}),
    # večer svítí automatika, takže přepnutí světlo zhasne
    ({"svetlo": "prepnout"}, {"zap": False, "barva": [0, 0, 0], "jas": 0, "rucne": True}),
    ({"svetlo": {"barva": [0, 0, 255], "jas": 50}},
     {"zap": True, "barva": [0, 0, 255], "jas": 50, "rucne": True}),
])
def test_rucni_svetlo_vznikle_vecer_plati_do_noci(prikaz, svetlo):
    d, _ = vytvor()
    d.krok(VECER, 0.0)
    d.proved_prikaz(prikaz, 0.1)
    d.krok(VECER, 1.0)
    assert d.stav()["svetlo"] == svetlo  # v úseku, ve kterém vznikla, platí
    d.krok(NOC, 2.0)
    assert d.stav()["svetlo"] == ZHASNUTO  # s koncem úseku zaniká


@pytest.mark.parametrize("prikaz", [
    None, 5, "s", ["svetlo"],  # jen objekt je příkaz, i když má jeden prvek
    {"svetlo": True}, {"svetlo": None}, {"svetlo": 1}, {"svetlo": []}, {"svetlo": ["zapnout"]},
    {"svetlo": "ZAPNOUT"}, {"svetlo": ""},
    {"svetlo": {"barva": [-1, 0, 0]}}, {"svetlo": {"barva": [0, 0, 0, 0]}},
    {"svetlo": {"barva": []}}, {"svetlo": {"barva": [0.0, 0, 0]}},
    {"svetlo": {"barva": ["0", 0, 0]}}, {"svetlo": {"barva": "cervena"}},
    {"svetlo": {"barva": None}},
    {"svetlo": {"jas": -1}}, {"svetlo": {"jas": 50.0}},
    {"svetlo": {"jas": "50"}}, {"svetlo": {"jas": None}},
    {"svetlo": {"barva": [0, 0, 0], "jas": 101}}, {"svetlo": {"barva": [0, 0, 0], "bila": 1}},
    {"svetlo": "zapnout", "jas": 5}, {"neco": "zapnout"},
    {"cas": None}, {"cas": ""}, {"cas": "2026-10-20T25:00:00"}, {"cas": "2026-10-20T14:03:00Z"},
    {1: "x"},
])
def test_dalsi_neplatne_prikazy_se_odmitnou(prikaz):
    d, _ = vytvor(nastavovac_casu=SimNastavovacCasu())
    d.krok(DEN, 0.0)
    with pytest.raises(ChybaPrikazu):
        d.proved_prikaz(prikaz, 0.1)


def test_odmitnuty_prikaz_svetla_nic_nezmeni():
    vystup = SimVystupSvetla()
    d, _ = vytvor(vystup_svetla=vystup)
    d.krok(DEN, 0.0)
    pred = d.stav()
    with pytest.raises(ChybaPrikazu):
        d.proved_prikaz({"svetlo": {"barva": [10, 20, 30], "jas": 200}}, 0.1)  # jen jas je špatně
    assert d.stav() == pred and vystup.posledni is VYPNUTO
    d.proved_prikaz({"svetlo": "zapnout"}, 0.2)
    assert d.stav()["svetlo"]["barva"] == [255, 255, 255]  # barva z odmítnutého příkazu se zahodila


# Kontrolka pohybu (oranžová LED semaforu)

def test_pohyb_rozsviti_kontrolku_hned_ve_stejnem_volani():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    assert hw["indikatory"].historie_pohybu == []
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(0.1)
    assert hw["indikatory"].historie_pohybu == [True]


def test_trvajici_pohyb_drzi_kontrolku_a_led_se_nastavi_jen_jednou():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    for i in range(1, 31):  # PIR drží výstup nahoře asi 3 s, čtení po 0,1 s
        d.kontroluj_pohyb(i / 10)
    assert hw["indikatory"].historie_pohybu == [True]


def test_trvajici_pohyb_prodluzuje_odpocet_az_do_posledniho_cteni():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    for i in range(1, 151):  # PIR drží pohyb 15 s, čtení po 0,1 s
        d.kontroluj_pohyb(i / 10)
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(15.1)
    d.kontroluj_pohyb(24.5)  # poslední čtení s pohybem bylo v 15,0 s, odpočet končí v 25,0 s
    assert hw["indikatory"].historie_pohybu == [True]
    d.kontroluj_pohyb(25.0)
    assert hw["indikatory"].historie_pohybu == [True, False]


def test_po_poklesu_pir_sviti_kontrolka_do_posledniho_pohybu_plus_10_s():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(100.0)
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(100.5)
    d.kontroluj_pohyb(109.5)
    assert hw["indikatory"].historie_pohybu == [True]
    d.kontroluj_pohyb(110.0)  # na hranici už zhasne
    assert hw["indikatory"].historie_pohybu == [True, False]
    d.kontroluj_pohyb(110.1)
    d.kontroluj_pohyb(200.0)
    assert hw["indikatory"].historie_pohybu == [True, False]


def test_kontrolka_zhasne_i_kdyz_se_hodnota_pir_nemeni():
    # PIR zůstane dole a `kontroluj_pohyb` vrací False, ale zhasnutí se musí vyhodnotit
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(100.0)
    hw["cidlo_pohybu"].pohyb = False
    assert d.kontroluj_pohyb(100.1) is True
    assert d.kontroluj_pohyb(110.0) is False
    assert hw["indikatory"].historie_pohybu == [True, False]


def test_kratky_impuls_pir_sviti_kontrolka_10_s():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(50.0)  # jediné čtení s pohybem
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(50.1)
    d.kontroluj_pohyb(59.5)
    assert hw["indikatory"].historie_pohybu == [True]
    d.kontroluj_pohyb(60.0)
    assert hw["indikatory"].historie_pohybu == [True, False]


def test_novy_pohyb_behem_odpoctu_kontrolku_prodlouzi():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(100.0)
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(100.5)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(105.0)  # odpočet se posune na 115 s
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(105.5)
    d.kontroluj_pohyb(110.0)
    d.kontroluj_pohyb(114.5)
    assert hw["indikatory"].historie_pohybu == [True]
    d.kontroluj_pohyb(115.0)
    assert hw["indikatory"].historie_pohybu == [True, False]


def test_kontrolka_pohybu_funguje_pred_prvnim_krokem():
    d, hw = vytvor()
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(0.0)
    assert hw["indikatory"].historie_pohybu == [True]
    hw["cidlo_pohybu"].pohyb = False
    d.kontroluj_pohyb(10.0)
    assert hw["indikatory"].historie_pohybu == [True, False]


@pytest.mark.parametrize("zmeny", [{}, {"nocni_svetlo_pri_pohybu": False}])
@pytest.mark.parametrize("cas", [DEN, NOC])
def test_kontrolka_pohybu_nezavisi_na_useku_dne_ani_svetle_pri_pohybu(cas, zmeny):
    d, hw = vytvor(slouc(Nastaveni(), zmeny))
    d.krok(cas, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(0.1)
    assert hw["indikatory"].historie_pohybu == [True]


def test_krok_kontrolku_pohybu_nemeni_a_stav_ji_neukazuje():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    hw["cidlo_pohybu"].pohyb = True
    d.kontroluj_pohyb(1.0)
    for i in range(2, 30):  # i po 10 s od pohybu krok na kontrolku nesahá
        d.krok(DEN, float(i))
    assert hw["indikatory"].historie_pohybu == [True]


def test_navratova_hodnota_kontroluj_pohyb_je_i_s_kontrolkou_beze_zmeny():
    d, hw = vytvor()
    d.krok(DEN, 0.0)
    assert d.kontroluj_pohyb(0.1) is False
    hw["cidlo_pohybu"].pohyb = True
    assert d.kontroluj_pohyb(0.2) is True
    assert d.kontroluj_pohyb(0.3) is False
    hw["cidlo_pohybu"].pohyb = False
    assert d.kontroluj_pohyb(0.4) is True
    assert d.kontroluj_pohyb(20.0) is False  # kontrolka zhasne, hodnota PIR se nemění
