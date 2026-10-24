import pytest

from domek.nastaveni import Nastaveni, slouc
from domek.termostat import Termostat, aktivni_cil, krok_simulace, regulace


def test_bez_topeni_se_teplota_blizi_venkovni():
    assert krok_simulace(25.0, 20.0, False, False, 1.0) == pytest.approx(25.0 - 5.0 / 300)


def test_topeni_a_chlazeni_meni_teplotu():
    assert krok_simulace(20.0, 20.0, True, False, 1.0) == pytest.approx(20.06)
    assert krok_simulace(20.0, 20.0, False, True, 1.0) == pytest.approx(19.94)


def test_dlouhy_krok_se_omezi_na_5_s():
    assert krok_simulace(20.0, 20.0, True, False, 60.0) == pytest.approx(20.3)


@pytest.mark.parametrize("t, topi, chladi, ocekavano", [
    (22.69, False, False, (True, False)),
    (22.71, False, False, (False, False)),
    (22.9, True, False, (True, False)),
    (23.0, True, False, (False, False)),
    (24.01, False, False, (False, True)),
    (23.99, False, False, (False, False)),
    (23.1, False, True, (False, True)),
    (23.0, False, True, (False, False)),
    (25.0, True, False, (False, True)),
])
def test_regulace_s_cilem_23(t, topi, chladi, ocekavano):
    assert regulace(t, 23.0, topi, chladi) == ocekavano


def test_aktivni_cil():
    n = Nastaveni()
    assert aktivni_cil(n, "den", False) == 23.0
    assert aktivni_cil(n, "vecer", False) == 23.0
    assert aktivni_cil(n, "noc", False) == 20.0
    assert aktivni_cil(n, "den", True) == 20.0
    rucni = slouc(n, {"rezim_termostatu": "rucni", "cil_rucni": 21.5})
    assert aktivni_cil(rucni, "noc", True) == 21.5
    assert aktivni_cil(slouc(n, {"rezim_termostatu": "vypnuto"}), "den", False) is None


def test_prvni_mereni_nastavi_vnitrni_teplotu():
    t = Termostat()
    t.krok(21.0, 23.0, 1.0)
    assert t.t_uvnitr == 21.0 and t.topi and not t.chladi


def test_bez_mereni_regulace_stoji():
    t = Termostat(t_uvnitr=21.0, topi=True)
    t.krok(None, 23.0, 1.0)
    assert (t.t_uvnitr, t.topi, t.chladi) == (21.0, False, False)


def test_vypnuto_netopi_ani_nechladi():
    t = Termostat(t_uvnitr=25.0, chladi=True)
    t.krok(20.0, None, 1.0)
    assert not t.topi and not t.chladi and t.t_uvnitr < 25.0


def test_zvyseni_cile_o_3_stupne_trva_zhruba_minutu():
    t = Termostat(t_uvnitr=20.0)
    t.krok(20.0, 23.0, 1.0)
    kroky = 0
    while t.topi and kroky < 1000:
        t.krok(20.0, 23.0, 1.0)
        kroky += 1
    assert 40 <= kroky <= 80


def test_vypnute_topeni_se_znovu_zapne_za_desitky_sekund():
    t = Termostat(t_uvnitr=23.0)
    t.krok(20.0, 23.0, 1.0)
    kroky = 0
    while not t.topi and kroky < 1000:
        t.krok(20.0, 23.0, 1.0)
        kroky += 1
    assert 10 <= kroky <= 90


# --- Doplňující testy: pravidla ze zadání, která úvodní testy neověřují ---


def test_zaporny_nebo_nulovy_krok_nemeni_teplotu():
    # záporné dt (hodiny skočily zpět) se bere jako 0, model nesmí běžet pozpátku
    assert krok_simulace(20.0, 25.0, True, False, -3.0) == 20.0
    assert krok_simulace(20.0, 25.0, True, False, 0.0) == 20.0


@pytest.mark.parametrize("t, topi, chladi, ocekavano", [
    (22.7, False, False, (False, False)),  # přesně 23 - 0,3: pod cílem o víc než 0,3 to ještě není
    (24.0, False, False, (False, False)),  # přesně 23 + 1,0: nad cílem o víc než 1,0 to ještě není
    (20.0, False, True, (True, False)),    # z chlazení se v jednom kroku přepne rovnou na topení
])
def test_regulace_hranice_hystereze_a_prepnuti(t, topi, chladi, ocekavano):
    assert regulace(t, 23.0, topi, chladi) == ocekavano


@pytest.mark.parametrize("usek, nikdo_doma, ocekavano", [
    ("den", False, 25.0), ("vecer", False, 25.0), ("noc", False, 18.0),
    ("den", True, 24.0), ("vecer", True, 24.0), ("noc", True, 17.0),
])
def test_aktivni_cil_auto_bere_hodnoty_z_nastaveni(usek, nikdo_doma, ocekavano):
    n = slouc(Nastaveni(), {"cil_den": 25.0, "cil_noc": 18.0, "utlum_nikdo_doma": 1.0})
    assert aktivni_cil(n, usek, nikdo_doma) == ocekavano


@pytest.mark.parametrize("usek", ["den", "vecer", "noc"])
@pytest.mark.parametrize("nikdo_doma", [False, True])
def test_aktivni_cil_rucni_a_vypnuto_nezavisi_na_useku_ani_utlumu(usek, nikdo_doma):
    rucni = slouc(Nastaveni(), {"rezim_termostatu": "rucni", "cil_rucni": 26.5})
    assert aktivni_cil(rucni, usek, nikdo_doma) == 26.5
    vypnuto = slouc(Nastaveni(), {"rezim_termostatu": "vypnuto"})
    assert aktivni_cil(vypnuto, usek, nikdo_doma) is None


@pytest.mark.parametrize("topi, chladi, ocekavano", [
    (True, False, 20.06),  # topilo se: tento krok se ještě počítá s topením
    (False, True, 19.94),  # chladilo se: tento krok se ještě počítá s chlazením
])
def test_simulace_pocita_se_stavem_z_minuleho_kroku(topi, chladi, ocekavano):
    # Cíl se rovná teplotě, takže regulace zařízení po kroku vypne. Teplota ale odráží,
    # že v kroku ještě běželo.
    t = Termostat(t_uvnitr=20.0, topi=topi, chladi=chladi)
    t.krok(20.0, 20.0, 1.0)
    assert t.t_uvnitr == pytest.approx(ocekavano)
    assert not t.topi and not t.chladi


@pytest.mark.parametrize("topi, chladi", [(True, False), (False, True)])
def test_bez_mereni_se_vypne_topeni_i_chlazeni(topi, chladi):
    t = Termostat(t_uvnitr=21.0, topi=topi, chladi=chladi)
    t.krok(None, 23.0, 1.0)
    assert (t.t_uvnitr, t.topi, t.chladi) == (21.0, False, False)


@pytest.mark.parametrize("topi, chladi", [(True, False), (False, True)])
def test_vypnuty_termostat_vypne_topeni_i_chlazeni(topi, chladi):
    t = Termostat(t_uvnitr=21.0, topi=topi, chladi=chladi)
    t.krok(20.0, None, 1.0)
    assert not t.topi and not t.chladi


def test_vypnuto_vnitrni_teplota_se_jen_vraci_k_venkovni():
    t = Termostat(t_uvnitr=25.0)
    for _ in range(1500):  # 25 minut, zhruba pětinásobek časové konstanty
        t.krok(20.0, None, 1.0)
        assert not t.topi and not t.chladi
        assert t.t_uvnitr > 20.0  # k venkovní teplotě se jen blíží, nikdy ji nepřekročí
    assert t.t_uvnitr == pytest.approx(20.0, abs=0.1)


def test_topeni_po_dosazeni_cile_stridave_zapina_a_teplota_zustane_u_cile():
    t = Termostat(t_uvnitr=20.0)
    topi, teploty = [], []
    for _ in range(1800):  # půl hodiny, venku 20 °C, cíl 23 °C
        t.krok(20.0, 23.0, 1.0)
        assert not t.chladi
        topi.append(t.topi)
        teploty.append(t.t_uvnitr)
    drzi = teploty[topi.index(False):]  # od prvního dosažení cíle
    assert 22.6 <= min(drzi) and max(drzi) <= 23.1
    assert sum(a != b for a, b in zip(topi, topi[1:])) >= 20  # topení se dál zapíná a vypíná


def test_chlazeni_po_dosazeni_cile_stridave_zapina_a_teplota_zustane_u_cile():
    t = Termostat(t_uvnitr=26.0)
    chladi, teploty = [], []
    for _ in range(1800):  # půl hodiny, venku 30 °C, cíl 23 °C
        t.krok(30.0, 23.0, 1.0)
        assert not t.topi
        chladi.append(t.chladi)
        teploty.append(t.t_uvnitr)
    drzi = teploty[chladi.index(False):]  # od prvního dosažení cíle
    assert 22.9 <= min(drzi) and max(drzi) <= 24.1
    assert sum(a != b for a, b in zip(chladi, chladi[1:])) >= 20  # chlazení se dál zapíná a vypíná
