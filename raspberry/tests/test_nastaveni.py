import errno
import json
import os

import pytest

from domek import nastaveni
from domek.nastaveni import ChybaNastaveni, Nastaveni, nacti, slouc, uloz


def test_vychozi_hodnoty():
    assert Nastaveni().jako_dict() == {
        "rezim_termostatu": "auto", "cil_den": 23.0, "cil_noc": 20.0, "cil_rucni": 22.0,
        "zacatek_den": "07:00", "zacatek_vecer": "19:00", "zacatek_noc": "22:00",
        "prah_lux": 50.0, "nocni_svetlo_s": 30, "nikdo_doma_po_min": 30, "utlum_nikdo_doma": 3.0,
        "nocni_svetlo_pri_pohybu": True, "barva_svetla": [255, 255, 255], "jas_svetla": 100,
    }


def test_slouc_zmeni_jen_zadane_klice():
    n = slouc(Nastaveni(), {"cil_den": 24.5})
    assert n.cil_den == 24.5 and n.cil_noc == 20.0


def test_slouc_cele_cislo_ulozi_jako_desetinne():
    n = slouc(Nastaveni(), {"cil_den": 24})
    assert n.cil_den == 24.0 and isinstance(n.cil_den, float)


def test_prazdna_zmena_nic_nemeni():
    assert slouc(Nastaveni(), {}) == Nastaveni()


def test_hranice_rozsahu_projdou():
    n = slouc(Nastaveni(), {"cil_den": 35, "cil_noc": 5, "nikdo_doma_po_min": 1, "nocni_svetlo_s": 600})
    assert (n.cil_den, n.cil_noc, n.nikdo_doma_po_min, n.nocni_svetlo_s) == (35.0, 5.0, 1, 600)


@pytest.mark.parametrize("zmeny", [
    ["cil_den"], {"neznamy": 1},
    {"cil_den": 35.5}, {"cil_den": 4.9}, {"cil_noc": "21"}, {"cil_rucni": True}, {"cil_den": None},
    {"rezim_termostatu": "turbo"},
    {"zacatek_den": "7:00"}, {"zacatek_den": "24:00"}, {"zacatek_vecer": "19:60"}, {"zacatek_noc": 2200},
    {"zacatek_noc": "19:00"},
    {"prah_lux": 0}, {"prah_lux": 10001},
    {"nocni_svetlo_s": 4}, {"nocni_svetlo_s": 601}, {"nocni_svetlo_s": 30.5},
    {"nikdo_doma_po_min": 0}, {"nikdo_doma_po_min": 241},
    {"utlum_nikdo_doma": -0.1}, {"utlum_nikdo_doma": 10.1},
])
def test_neplatna_zmena_se_odmitne(zmeny):
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), zmeny)


def test_uloz_a_nacti(tmp_path):
    cesta = str(tmp_path / "slozka" / "nastaveni.json")
    n = slouc(Nastaveni(), {"cil_den": 21.5, "zacatek_noc": "23:30"})
    uloz(n, cesta)
    assert nacti(cesta) == n


def test_chybejici_soubor_da_vychozi(tmp_path):
    assert nacti(str(tmp_path / "neni.json")) == Nastaveni()


@pytest.mark.parametrize("obsah", ['{"cil_den": 2', "[]", '{"cil_den": 99}', ""])
def test_poskozeny_soubor_da_vychozi(tmp_path, obsah):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text(obsah, encoding="utf-8")
    assert nacti(str(cesta)) == Nastaveni()


# --- Doplňující testy: okrajové případy, které zadání jen předpokládá ---


def test_cele_nastaveni_zpet_projde():
    # tablet může zapsat zpět celé nastavení, ne jen jeho část
    n = slouc(Nastaveni(), {"cil_den": 24.5, "zacatek_noc": "23:30"})
    assert slouc(Nastaveni(), n.jako_dict()) == n


@pytest.mark.parametrize("rezim", ["auto", "rucni", "vypnuto"])
def test_vsechny_rezimy_projdou(rezim):
    assert slouc(Nastaveni(), {"rezim_termostatu": rezim}).rezim_termostatu == rezim


@pytest.mark.parametrize("hodnota", [True, False])
def test_svetlo_pri_pohybu_prijme_bool(hodnota):
    n = slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": hodnota})
    assert n.nocni_svetlo_pri_pohybu is hodnota
    assert n.jako_dict()["nocni_svetlo_pri_pohybu"] is hodnota


@pytest.mark.parametrize("hodnota", [0, 1, 1.0, 0.0, "true", "false", "", None, [], [True], {}])
def test_svetlo_pri_pohybu_odmitne_cokoli_krome_bool(hodnota):
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": hodnota})


def test_neplatne_svetlo_pri_pohybu_shodi_cely_zapis():
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {"cil_den": 26, "nocni_svetlo_pri_pohybu": 1})


def test_stary_soubor_bez_svetla_pri_pohybu_se_nacte_s_vychozi_hodnotou(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"cil_den": 24.5, "nikdo_doma_po_min": 10}', encoding="utf-8")
    n = nacti(str(cesta))
    assert (n.cil_den, n.nikdo_doma_po_min, n.nocni_svetlo_pri_pohybu) == (24.5, 10, True)


def test_svetlo_pri_pohybu_se_ulozi_a_nacte(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    uloz(slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": False}), cesta)
    assert nacti(cesta).nocni_svetlo_pri_pohybu is False


@pytest.mark.parametrize("klic, hodnota", [
    ("prah_lux", True), ("utlum_nikdo_doma", True), ("utlum_nikdo_doma", False), ("nikdo_doma_po_min", True),
])
def test_bool_neni_cislo_ani_kdyz_se_vejde_do_rozsahu(klic, hodnota):
    # True a False jsou v Pythonu 1 a 0, takže by kontrolou rozsahu prošly
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {klic: hodnota})


@pytest.mark.parametrize("klic, hodnota", [
    ("cil_den", float("nan")), ("cil_den", float("inf")), ("cil_den", float("-inf")),
    ("cil_den", 10 ** 400), ("nikdo_doma_po_min", 10 ** 400),
], ids=["nan", "inf", "minus-inf", "obrovske-cislo", "obrovske-cele-cislo"])
def test_nan_nekonecno_a_obrovske_cislo_se_odmitne(klic, hodnota):
    # json.loads je pustí (NaN, 1e999, dlouhé číslo), proto musí selhat až slouc
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {klic: hodnota})


@pytest.mark.parametrize("cas", ["07:00\n", " 07:00", "07:00 ", "0٧:0٠", ""])
def test_cas_musi_byt_presne_hh_mm(cas):
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {"zacatek_den": cas})


def test_uloz_do_souboru_bez_slozky(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)  # v simulaci je cesta jen název souboru
    n = slouc(Nastaveni(), {"cil_den": 21.5})
    uloz(n, "nastaveni.json")
    assert nacti("nastaveni.json") == n


def test_uloz_prepise_stary_soubor_a_nenecha_pomocny(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    uloz(Nastaveni(), cesta)
    n = slouc(Nastaveni(), {"cil_noc": 18})
    uloz(n, cesta)
    assert nacti(cesta) == n
    assert not os.path.exists(cesta + ".tmp")


def test_neuspesny_zapis_nechava_puvodni_soubor_cely(tmp_path, monkeypatch):
    cesta = str(tmp_path / "nastaveni.json")
    puvodni = slouc(Nastaveni(), {"cil_den": 21.5})
    uloz(puvodni, cesta)

    def zapis_preruseny_vypadkem(data, soubor, **kwargs):
        soubor.write('{"cil_den": 2')
        raise OSError("vypadek proudu")

    monkeypatch.setattr(json, "dump", zapis_preruseny_vypadkem)
    with pytest.raises(OSError):
        uloz(slouc(puvodni, {"cil_den": 30}), cesta)
    assert nacti(cesta) == puvodni


class OsSeZaznamem:
    """Modul os pro nastaveni.py se záznamem fsync, přejmenování a práce se složkou.

    `name` určuje systém ("posix" jako Raspberry, "nt" jako Windows). Otevření, fsync a zavření
    složky jsou falešné (na Windows by otevření složky selhalo) a selžou s chybou z `chyby`
    ("open" nebo "fsync"); soubory a vše ostatní obsluhuje skutečný os.
    """

    O_RDONLY = os.O_RDONLY
    SLOZKA = -1000  # falešný popisovač otevřené složky

    def __init__(self, name, chyby=()):
        self.name = name
        self.chyby = dict(chyby)
        self.volani = []

    def __getattr__(self, nazev):
        return getattr(os, nazev)

    def open(self, cesta, priznaky):
        self.volani.append(("open", cesta, priznaky))
        if "open" in self.chyby:
            raise self.chyby["open"]
        return self.SLOZKA

    def fsync(self, popisovac):
        if popisovac != self.SLOZKA:
            self.volani.append(("fsync souboru",))
            return os.fsync(popisovac)
        self.volani.append(("fsync slozky",))
        if "fsync" in self.chyby:
            raise self.chyby["fsync"]

    def close(self, popisovac):
        assert popisovac == self.SLOZKA, "soubor zavírá `with`, ne os.close"
        self.volani.append(("close slozky",))

    def replace(self, zdroj, cil):
        self.volani.append(("replace",))
        os.replace(zdroj, cil)


def test_uloz_na_raspberry_zapise_na_disk_i_slozku_az_po_prejmenovani(tmp_path, monkeypatch):
    # Bez fsync složky by po výpadku proudu přejmenování nemuselo zůstat a vrátil by se starý
    # soubor, i když uložení skončilo úspěšně
    zaznam = OsSeZaznamem("posix")
    monkeypatch.setattr(nastaveni, "os", zaznam)
    cesta = str(tmp_path / "slozka" / "nastaveni.json")
    n = slouc(Nastaveni(), {"cil_den": 21.5})
    uloz(n, cesta)
    assert zaznam.volani == [("fsync souboru",), ("replace",),
                             ("open", str(tmp_path / "slozka"), os.O_RDONLY),
                             ("fsync slozky",), ("close slozky",)]
    assert nacti(cesta) == n


def test_uloz_bez_slozky_v_ceste_zapise_aktualni_slozku(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    zaznam = OsSeZaznamem("posix")
    monkeypatch.setattr(nastaveni, "os", zaznam)
    uloz(Nastaveni(), "nastaveni.json")
    assert ("open", os.curdir, os.O_RDONLY) in zaznam.volani


@pytest.mark.parametrize("chyby, zavreni", [
    ({"open": PermissionError(errno.EACCES, "Permission denied")}, False),
    ({"fsync": OSError(errno.EINVAL, "Invalid argument")}, True),
], ids=["slozka-nejde-otevrit", "fsync-slozky-nejde"])
def test_kdyz_slozku_nejde_zapsat_na_disk_ulozeni_presto_projde(tmp_path, monkeypatch, chyby,
                                                                 zavreni):
    # Soubor je v tu chvíli už uložený a přejmenovaný: domek nesmí hlásit chybu "nastaveni"
    zaznam = OsSeZaznamem("posix", chyby)
    monkeypatch.setattr(nastaveni, "os", zaznam)
    cesta = str(tmp_path / "nastaveni.json")
    n = slouc(Nastaveni(), {"cil_noc": 18})
    uloz(n, cesta)
    assert nacti(cesta) == n
    assert (("close slozky",) in zaznam.volani) is zavreni  # otevřená složka se vždy zavře


def test_na_windows_se_slozka_na_disk_nezapisuje(tmp_path, monkeypatch):
    # Na Windows (simulace na PC) složku otevřít nejde
    zaznam = OsSeZaznamem("nt")
    monkeypatch.setattr(nastaveni, "os", zaznam)
    cesta = str(tmp_path / "nastaveni.json")
    uloz(Nastaveni(), cesta)
    assert zaznam.volani == [("fsync souboru",), ("replace",)]
    assert nacti(cesta) == Nastaveni()


@pytest.mark.parametrize("obsah", [b"\x00" * 16, b"\xff\xfe\xfd"], ids=["same-nuly", "neplatne-utf8"])
def test_soubor_s_binarnimi_nesmysly_da_vychozi(tmp_path, obsah):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_bytes(obsah)
    assert nacti(str(cesta)) == Nastaveni()


# --- Barva a jas světla ---


def test_vychozi_barva_a_jas_svetla():
    n = Nastaveni()
    assert (n.barva_svetla, n.jas_svetla) == ((255, 255, 255), 100)


@pytest.mark.parametrize("barva", [[0, 0, 0], [255, 255, 255], (10, 20, 30), [1, 2, 3]])
def test_barva_svetla_prijme_seznam_i_n_tici_a_ulozi_n_tici(barva):
    n = slouc(Nastaveni(), {"barva_svetla": barva})
    assert n.barva_svetla == tuple(barva) and isinstance(n.barva_svetla, tuple)


def test_barva_svetla_se_ulozi_jako_kopie_n_tice():
    barva = [1, 2, 3]
    n = slouc(Nastaveni(), {"barva_svetla": barva})
    barva[0] = 99
    assert n.barva_svetla == (1, 2, 3)


@pytest.mark.parametrize("barva", [
    [], [1, 2], [1, 2, 3, 4], [-1, 0, 0], [0, 256, 0], [True, 0, 0], [0, 0, False],
    [1.0, 2, 3], [0, 0, 1.5], ["1", 2, 3], [None, 0, 0], "255,255,255", 255, None, {"r": 1},
])
def test_neplatna_barva_svetla_se_odmitne(barva):
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {"barva_svetla": barva})


@pytest.mark.parametrize("jas", [0, 1, 50, 100])
def test_jas_svetla_prijme_cele_cislo_0_az_100(jas):
    assert slouc(Nastaveni(), {"jas_svetla": jas}).jas_svetla == jas


@pytest.mark.parametrize("jas", [-1, 101, True, False, 50.0, 50.5, "50", None, [50]])
def test_neplatny_jas_svetla_se_odmitne(jas):
    with pytest.raises(ChybaNastaveni):
        slouc(Nastaveni(), {"jas_svetla": jas})


def test_jako_dict_s_barvou_svetla_jde_zapsat_jako_json():
    n = slouc(Nastaveni(), {"barva_svetla": (1, 2, 3), "jas_svetla": 40})
    data = n.jako_dict()
    assert data["barva_svetla"] == [1, 2, 3] and isinstance(data["barva_svetla"], list)
    assert json.loads(json.dumps(data)) == data
    assert slouc(Nastaveni(), data) == n


def test_barva_a_jas_svetla_se_ulozi_a_nacte_a_barva_je_n_tice(tmp_path):
    cesta = str(tmp_path / "nastaveni.json")
    n = slouc(Nastaveni(), {"barva_svetla": [10, 20, 30], "jas_svetla": 35})
    uloz(n, cesta)
    nacteno = nacti(cesta)
    assert nacteno == n
    assert nacteno.barva_svetla == (10, 20, 30) and isinstance(nacteno.barva_svetla, tuple)
    with open(cesta, encoding="utf-8") as soubor:
        assert json.load(soubor)["barva_svetla"] == [10, 20, 30]


def test_stary_soubor_bez_barvy_a_jasu_svetla_se_nacte_s_vychozimi_hodnotami(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"cil_den": 24.5, "nocni_svetlo_pri_pohybu": false}', encoding="utf-8")
    n = nacti(str(cesta))
    assert (n.cil_den, n.barva_svetla, n.jas_svetla) == (24.5, (255, 255, 255), 100)


# Migrace starého klíče: soubor na Raspberry ho má z doby, než se přejmenoval.
def test_stary_klic_svetlo_pri_pohybu_se_pri_nacteni_prevede_a_barva_zustane(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"svetlo_pri_pohybu": false, "barva_svetla": [10, 20, 30]}',
                     encoding="utf-8")
    n = nacti(str(cesta))
    assert n.nocni_svetlo_pri_pohybu is False and n.barva_svetla == (10, 20, 30)


def test_kdyz_jsou_v_souboru_oba_klice_plati_novy(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"svetlo_pri_pohybu": true, "nocni_svetlo_pri_pohybu": false}',
                     encoding="utf-8")
    assert nacti(str(cesta)).nocni_svetlo_pri_pohybu is False


def test_soubor_ve_tvaru_pred_prejmenovanim_se_nacte_cely(tmp_path):
    # Přesně takový soubor (všech 14 klíčů) zapsala na Raspberry verze před přejmenováním
    stary = {"rezim_termostatu": "rucni", "cil_den": 24.5, "cil_noc": 19.0, "cil_rucni": 21.5,
             "zacatek_den": "06:30", "zacatek_vecer": "18:45", "zacatek_noc": "23:15",
             "prah_lux": 20.0, "nocni_svetlo_s": 45, "nikdo_doma_po_min": 60,
             "utlum_nikdo_doma": 2.0, "svetlo_pri_pohybu": False,
             "barva_svetla": [255, 147, 41], "jas_svetla": 35}
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text(json.dumps(stary, indent=2), encoding="utf-8")
    ocekavane = dict(stary, barva_svetla=[255, 147, 41])
    ocekavane["nocni_svetlo_pri_pohybu"] = ocekavane.pop("svetlo_pri_pohybu")
    assert nacti(str(cesta)).jako_dict() == ocekavane


def test_oba_klice_a_neplatny_stary_plati_novy(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"svetlo_pri_pohybu": 1, "nocni_svetlo_pri_pohybu": false, "cil_den": 24.5}',
                     encoding="utf-8")
    n = nacti(str(cesta))
    assert (n.nocni_svetlo_pri_pohybu, n.cil_den) == (False, 24.5)


def test_neplatna_hodnota_stareho_klice_se_odmitne_jako_driv(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"svetlo_pri_pohybu": 1, "cil_den": 24.5}', encoding="utf-8")
    assert nacti(str(cesta)) == Nastaveni()


def test_uloz_zapise_jen_novy_klic(tmp_path):
    cesta = tmp_path / "nastaveni.json"
    cesta.write_text('{"svetlo_pri_pohybu": false}', encoding="utf-8")
    uloz(nacti(str(cesta)), str(cesta))
    with open(cesta, encoding="utf-8") as soubor:
        data = json.load(soubor)
    assert "svetlo_pri_pohybu" not in data and data["nocni_svetlo_pri_pohybu"] is False


def test_slouc_stary_klic_odmitne_jako_neznamy():
    with pytest.raises(ChybaNastaveni, match="^Neznama nastaveni: svetlo_pri_pohybu\.$"):
        slouc(Nastaveni(), {"svetlo_pri_pohybu": False})
