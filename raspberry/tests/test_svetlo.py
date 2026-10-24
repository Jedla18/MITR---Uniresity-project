from dataclasses import FrozenInstanceError

import pytest

from domek.nastaveni import Nastaveni, slouc
from domek.svetlo import HYSTEREZE_LUX, JAS_NOC, VYPNUTO, StavSvetla, Svetlo

N = Nastaveni()
BILA = (255, 255, 255)
# vlastní barva a jas z aplikace: dobře se liší od výchozí bílé na 100 %
NB = slouc(N, {"barva_svetla": (10, 20, 30), "jas_svetla": 40})


def test_ve_dne_sviti_jen_pri_seru():
    s = Svetlo()
    assert s.krok("den", 200.0, False, 0.0, N) == VYPNUTO
    assert s.krok("den", 30.0, False, 1.0, N) == StavSvetla(True, BILA, 100, False)


def test_sero_ma_hysterezi():
    s = Svetlo()
    s.krok("den", 30.0, False, 0.0, N)
    assert s.krok("den", 60.0, False, 1.0, N).zapnuto
    assert not s.krok("den", 70.0, False, 2.0, N).zapnuto


def test_bez_cidla_osvetleni_ve_dne_nesviti():
    assert Svetlo().krok("den", None, False, 0.0, N) == VYPNUTO


def test_vecer_sviti_vychozi_bila_na_plny_jas():
    assert Svetlo().krok("vecer", 500.0, False, 0.0, N) == StavSvetla(True, BILA, 100, False)


def test_v_noci_pohyb_rozsviti_nocni_svetlo_na_nastavenou_dobu():
    s = Svetlo()
    assert s.krok("noc", 0.0, False, 100.0, N) == VYPNUTO
    assert s.krok("noc", 0.0, True, 101.0, N) == StavSvetla(True, BILA, JAS_NOC, False)
    assert s.krok("noc", 0.0, False, 130.0, N).zapnuto
    assert s.krok("noc", 0.0, False, 131.5, N) == VYPNUTO


def test_bez_svetla_pri_pohybu_pohyb_v_noci_nocni_svetlo_nerozsviti():
    n = slouc(N, {"nocni_svetlo_pri_pohybu": False})
    s = Svetlo()
    assert s.krok("noc", 0.0, True, 101.0, n) == VYPNUTO
    assert s.krok("noc", 0.0, False, 102.0, n) == VYPNUTO


def test_vypnuti_svetla_pri_pohybu_zastavi_i_uz_bezici_nocni_svetlo_jen_kdyz_neprijde_pohyb():
    s = Svetlo()
    s.krok("noc", 0.0, True, 100.0, N)  # svítí do 130 s
    n = slouc(N, {"nocni_svetlo_pri_pohybu": False})
    assert s.krok("noc", 0.0, True, 110.0, n).zapnuto  # rozběhlý odpočet běží dál, ale neprodlouží se
    assert s.krok("noc", 0.0, False, 130.0, n) == VYPNUTO


def test_svetlo_pri_pohybu_se_vyhodnocuje_v_kazdem_kroku_a_po_zapnuti_zase_funguje():
    s = Svetlo()
    vyp, zap = slouc(N, {"nocni_svetlo_pri_pohybu": False}), N
    assert s.krok("noc", 0.0, True, 0.0, vyp) == VYPNUTO
    assert s.krok("noc", 0.0, True, 1.0, zap).zapnuto


def test_pohyb_ve_dne_nocni_svetlo_nezapne():
    s = Svetlo()
    s.krok("den", 500.0, True, 0.0, N)
    assert s.krok("noc", 0.0, False, 1.0, N) == VYPNUTO


def test_rucni_volba_plati_do_zmeny_useku():
    s = Svetlo()
    s.rucne(True, "den")
    assert s.krok("den", 500.0, False, 0.0, NB) == StavSvetla(True, (10, 20, 30), 40, True)
    assert s.krok("vecer", 500.0, False, 1.0, N) == StavSvetla(True, BILA, 100, False)


def test_auto_zrusi_rucni_volbu():
    s = Svetlo()
    s.rucne(False, "vecer")
    assert s.krok("vecer", 0.0, False, 0.0, N) == StavSvetla(False, (0, 0, 0), 0, True)
    s.auto()
    assert s.krok("vecer", 0.0, False, 1.0, N) == StavSvetla(True, BILA, 100, False)


def test_prepni_prepne_na_opak_a_zapnute_svetlo_bere_barvu_z_nastaveni():
    s = Svetlo()
    s.prepni(StavSvetla(True, BILA, 100, False), "vecer")
    assert not s.krok("vecer", 0.0, False, 0.0, NB).zapnuto
    s.prepni(VYPNUTO, "vecer")
    assert s.krok("vecer", 0.0, False, 1.0, NB) == StavSvetla(True, (10, 20, 30), 40, True)


# --- Doplňující testy: pravidla ze zadání, která úvodní testy neověřují ---


def test_konstanty_maji_hodnoty_ze_zadani():
    # Podle těchto hodnot světlo opravdu svítí, takže jsou zapsané pevně.
    assert VYPNUTO == StavSvetla(False, (0, 0, 0), 0, False)
    assert JAS_NOC == 10
    assert HYSTEREZE_LUX == 1.3


def test_barva_se_bere_jen_z_nastaveni_a_konstanty_barev_zmizely():
    import domek.svetlo as modul
    for nazev in ("BARVA_DEN", "JAS_DEN", "BARVA_VECER", "JAS_VECER", "BARVA_NOC"):
        assert not hasattr(modul, nazev)


def test_stav_svetla_je_nemenny():
    # vlastní instance, ne sdílené VYPNUTO: kdyby změna prošla, nerozbila by ostatní testy
    stav = StavSvetla(True, BILA, 100, False)
    with pytest.raises(FrozenInstanceError):
        stav.zapnuto = False


@pytest.mark.parametrize("predtim, lux, sviti", [
    (200.0, 49.9, True),   # těsně pod prahem 50 lx se rozsvítí
    (200.0, 50.0, False),  # přesně na prahu ještě ne
    (200.0, 60.0, False),  # uvnitř pásma 50 až 65 lx se nic nemění: zhasnuté zůstává zhasnuté
    (30.0, 50.0, True),    # svítící zůstává svítit na prahu
    (30.0, 65.0, True),    # i přesně na horní hranici 65 lx (50 * 1,3)
    (30.0, 65.1, False),   # a zhasne až nad ní
])
def test_hranice_hystereze_sera(predtim, lux, sviti):
    s = Svetlo()
    s.krok("den", predtim, False, 0.0, N)
    assert s.krok("den", lux, False, 1.0, N).zapnuto == sviti


def test_prvni_mereni_uvnitr_pasma_hystereze_nerozsviti():
    # po startu se šero nepředpokládá
    assert Svetlo().krok("den", 60.0, False, 0.0, N) == VYPNUTO


def test_prah_sera_se_bere_z_nastaveni():
    n = slouc(N, {"prah_lux": 200.0})  # horní hranice šera je 200 * 1,3 = 260 lx
    s = Svetlo()
    assert s.krok("den", 150.0, False, 0.0, n).zapnuto      # pod prahem (při 50 lx by šero nebylo)
    assert s.krok("den", 250.0, False, 1.0, n).zapnuto      # uvnitř pásma hystereze šero trvá
    assert not s.krok("den", 270.0, False, 2.0, n).zapnuto  # nad horní hranicí šero končí


def test_vypadek_cidla_zhasne_denni_svetlo_a_zapomene_sero():
    s = Svetlo()
    assert s.krok("den", 30.0, False, 0.0, N).zapnuto
    assert s.krok("den", None, False, 1.0, N) == VYPNUTO
    # čidlo se vrátilo s hodnotou uvnitř pásma hystereze: staré šero už neplatí
    assert s.krok("den", 60.0, False, 2.0, N) == VYPNUTO


def test_sero_se_sleduje_i_mimo_den():
    s = Svetlo()
    s.krok("noc", 30.0, False, 0.0, N)
    # 60 lx je uvnitř pásma hystereze, takže šero z noci trvá i ve dne
    assert s.krok("den", 60.0, False, 1.0, N).zapnuto


def test_nocni_svetlo_se_ridi_nastavenou_dobou_a_v_case_konce_uz_nesviti():
    n = slouc(N, {"nocni_svetlo_s": 10})
    s = Svetlo()
    assert s.krok("noc", 0.0, True, 50.0, n).zapnuto
    assert s.krok("noc", 0.0, False, 59.9, n).zapnuto
    assert s.krok("noc", 0.0, False, 60.0, n) == VYPNUTO


def test_dalsi_pohyb_v_noci_spusti_odpocet_znovu():
    s = Svetlo()
    s.krok("noc", 0.0, True, 100.0, N)  # svítí do 130 s
    s.krok("noc", 0.0, True, 120.0, N)  # nový pohyb: svítí do 150 s
    assert s.krok("noc", 0.0, False, 149.0, N).zapnuto
    assert s.krok("noc", 0.0, False, 150.0, N) == VYPNUTO


def test_pohyb_vecer_nocni_svetlo_nezapne():
    s = Svetlo()
    s.krok("vecer", 500.0, True, 0.0, N)
    assert s.krok("noc", 0.0, False, 1.0, N) == VYPNUTO


def test_nocni_svetlo_konci_s_noci():
    s = Svetlo()
    s.krok("noc", 500.0, True, 0.0, N)  # odpočet by běžel do 30 s
    # úsek se změnil dřív, než odpočet doběhl: platí pravidla nového úseku
    assert s.krok("den", 500.0, False, 1.0, N) == VYPNUTO
    assert s.krok("vecer", 500.0, False, 2.0, N) == StavSvetla(True, BILA, 100, False)


def test_neznamy_usek_nesviti():
    s = Svetlo()
    s.krok("noc", 0.0, True, 0.0, N)  # odpočet nočního světla by běžel do 30 s
    assert s.krok("odpoledne", 0.0, True, 1.0, N) == VYPNUTO


@pytest.mark.parametrize("mono", [-1e9, -1.0])
def test_po_startu_nesviti_nocni_svetlo_ani_pri_zapornem_case(mono):
    # počátek monotonních hodin není pevně daný, takže `mono` může být i záporné
    assert Svetlo().krok("noc", 0.0, False, mono, N) == VYPNUTO


def test_pohyb_se_zaznamena_i_pod_rucni_volbou():
    s = Svetlo()
    s.rucne(False, "noc")
    # ruční volba má přednost, pohyb noční světlo nerozsvítí ...
    assert s.krok("noc", 0.0, True, 100.0, N) == StavSvetla(False, (0, 0, 0), 0, True)
    # ... ale odpočet běží, takže po návratu do automatiky světlo hned svítí
    s.auto()
    assert s.krok("noc", 0.0, False, 110.0, N) == StavSvetla(True, BILA, JAS_NOC, False)


def test_sero_se_zaznamena_i_pod_rucni_volbou():
    s = Svetlo()
    s.rucne(True, "den")
    s.krok("den", 30.0, False, 0.0, N)  # ruční volba platí, šero se i tak zapamatuje
    s.auto()
    # 60 lx je uvnitř pásma hystereze, takže světlo svítí jen díky šeru z doby ruční volby
    assert s.krok("den", 60.0, False, 1.0, N).zapnuto


def test_rucni_volba_se_po_zmene_useku_neobnovi():
    s = Svetlo()
    s.rucne(True, "den")
    s.krok("vecer", 500.0, False, 0.0, N)
    # ruční volba zanikla změnou úseku, po návratu do dne už se neobjeví
    assert s.krok("den", 500.0, False, 1.0, N) == VYPNUTO


# --- Barva a jas z nastavení ---


def test_den_pri_seru_sviti_barvou_a_jasem_z_nastaveni():
    assert Svetlo().krok("den", 30.0, False, 0.0, NB) == StavSvetla(True, (10, 20, 30), 40, False)


def test_vecer_sviti_barvou_a_jasem_z_nastaveni():
    stav = Svetlo().krok("vecer", 500.0, False, 0.0, NB)
    assert stav == StavSvetla(True, (10, 20, 30), 40, False)


def test_nocni_svetlo_sviti_barvou_z_nastaveni_a_jasem_nejvys_10():
    n = slouc(N, {"barva_svetla": (10, 20, 30), "jas_svetla": 100})
    assert Svetlo().krok("noc", 0.0, True, 0.0, n) == StavSvetla(True, (10, 20, 30), 10, False)


@pytest.mark.parametrize("jas, ocekavany", [(0, 0), (5, 5), (10, 10), (11, 10), (100, 10)])
def test_nocni_jas_je_nejvys_jas_svetla_a_10(jas, ocekavany):
    n = slouc(N, {"jas_svetla": jas})
    assert Svetlo().krok("noc", 0.0, True, 0.0, n).jas == ocekavany


def test_rucni_zapnuti_bere_barvu_z_nastaveni_a_zmenu_nastaveni_prevezme_v_dalsim_kroku():
    s = Svetlo()
    s.rucne(True, "den")
    assert s.krok("den", 500.0, False, 0.0, N) == StavSvetla(True, BILA, 100, True)
    assert s.krok("den", 500.0, False, 1.0, NB) == StavSvetla(True, (10, 20, 30), 40, True)


def test_rucni_vypnuti_a_auto_funguji_dal():
    s = Svetlo()
    s.rucne(False, "den")
    assert s.krok("den", 30.0, False, 0.0, NB) == StavSvetla(False, (0, 0, 0), 0, True)
    s.auto()
    assert s.krok("den", 30.0, False, 1.0, NB) == StavSvetla(True, (10, 20, 30), 40, False)
