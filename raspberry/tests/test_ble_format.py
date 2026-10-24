import json
import re
from datetime import datetime
from pathlib import Path

import pytest

from domek.ble_format import (DOMEK_UUID, ESS_UUID, MAX_JSON_B, NASTAVENI_UUID, NAZEV, PRIKAZ_UUID,
                              STAV_UUID, TEPLOTA_UUID, TLAK_UUID, VLHKOST_UUID, dekoduj_json,
                              teplota_ess, tlak_ess, vlhkost_ess, zakoduj_json)
from domek.hardware.simulace import (SimDisplej, SimIndikatory, SimMeteo, SimPohyb, SimSvetelnost,
                                     SimVystupSvetla)
from domek.jadro import Domek
from domek.nastaveni import ChybaNastaveni, Nastaveni, slouc


def test_teplota_ess():
    assert teplota_ess(23.05) == (2305).to_bytes(2, "little", signed=True)
    assert teplota_ess(-5.5) == (-550).to_bytes(2, "little", signed=True)
    assert teplota_ess(None) == b"\x00\x80"


def test_vlhkost_a_tlak_ess():
    assert vlhkost_ess(45.2) == (4520).to_bytes(2, "little")
    assert vlhkost_ess(None) == b"\xff\xff"
    assert tlak_ess(987.65) == (987650).to_bytes(4, "little")
    assert tlak_ess(None) == b"\x00\x00\x00\x00"


def test_zakoduj_json_je_kompaktni():
    assert zakoduj_json({"usek": "noc", "t": 1.5}) == b'{"usek":"noc","t":1.5}'


def test_prilis_dlouhy_json_se_odmitne():
    with pytest.raises(ValueError):
        zakoduj_json({"x": "a" * MAX_JSON_B})


@pytest.mark.parametrize("hodnota", [b"\xff\xfe", b"{rozbite", b""])
def test_neplatny_json_se_odmitne(hodnota):
    with pytest.raises(ValueError):
        dekoduj_json(hodnota)


def test_dekoduj_json():
    assert dekoduj_json(b'{"svetlo":"zapnout"}') == {"svetlo": "zapnout"}


def test_nejdelsi_stav_a_nastaveni_se_vejdou():
    displej, vystup = SimDisplej(), SimVystupSvetla()
    displej.selhava = vystup.selhava = True
    d = Domek(Nastaveni(), SimMeteo(teplota=-40.25, vlhkost=100.0, tlak=1100.25), SimPohyb(),
              SimIndikatory(), displej=displej, svetelnost=SimSvetelnost(lux=65535.0),
              vystup_svetla=vystup)
    d.krok(datetime(2026, 12, 31, 23, 59, 59), 0.0)
    d.proved_prikaz({"svetlo": {"barva": [255, 255, 255], "jas": 100}}, 0.1)
    assert len(zakoduj_json(d.stav())) <= MAX_JSON_B
    assert len(zakoduj_json(Nastaveni().jako_dict())) <= MAX_JSON_B


# --- Doplňující testy: meze, nečíselné hodnoty, délka v bajtech a obrat tam a zpět ---

@pytest.mark.parametrize("t, ocekavano", [
    (-273.15, -27315), (-300.0, -27315), (-1e308, -27315),
    (327.67, 32767), (400.0, 32767), (1e308, 32767),
    (0.0, 0), (-0.004, 0),
])
def test_teplota_ess_se_omezi_na_rozsah(t, ocekavano):
    assert teplota_ess(t) == ocekavano.to_bytes(2, "little", signed=True)


@pytest.mark.parametrize("v, ocekavano", [
    (0.0, 0), (-1.0, 0), (-1e308, 0),
    (100.0, 10000), (150.0, 10000), (1e308, 10000),
])
def test_vlhkost_ess_se_omezi_na_rozsah(v, ocekavano):
    assert vlhkost_ess(v) == ocekavano.to_bytes(2, "little")


@pytest.mark.parametrize("tlak, ocekavano", [
    (0.0, 0), (-5.0, 0), (-1e308, 0),
    (1013.25, 1013250), (4294967.295, 2 ** 32 - 1), (4294967.296, 2 ** 32 - 1),
    (5_000_000.0, 2 ** 32 - 1), (1e308, 2 ** 32 - 1),
])
def test_tlak_ess_se_omezi_na_rozsah(tlak, ocekavano):
    assert tlak_ess(tlak) == ocekavano.to_bytes(4, "little")


@pytest.mark.parametrize("hodnota", [float("nan"), float("inf"), float("-inf")])
def test_ess_nan_a_nekonecno_jsou_nezname(hodnota):
    # NaN ani nekonečno není měření; hodnota "neznámé" je lepší než výjimka v časovači
    assert teplota_ess(hodnota) == teplota_ess(None)
    assert vlhkost_ess(hodnota) == vlhkost_ess(None)
    assert tlak_ess(hodnota) == tlak_ess(None)


@pytest.mark.parametrize("hodnota", [float("nan"), float("inf"), float("-inf")])
def test_zakoduj_json_nikdy_neposle_nan_ani_nekonecno(hodnota):
    # NaN a Infinity nejsou platný JSON, aplikace by je nepřečetla
    with pytest.raises(ValueError):
        zakoduj_json({"t_venku": hodnota})


def test_zakoduj_json_nechava_diakritiku_jako_utf8():
    assert zakoduj_json({"x": "ěš"}) == '{"x":"ěš"}'.encode("utf-8")


def test_delka_json_se_meri_v_bajtech_a_hranice_je_512():
    # {"x":"..."} má kolem hodnoty 8 B, takže 504 znaků dá přesně 512 B
    assert len(zakoduj_json({"x": "a" * 504})) == MAX_JSON_B
    with pytest.raises(ValueError):
        zakoduj_json({"x": "a" * 505})
    # "č" má v UTF-8 dva bajty: 253 znaků je 514 B, i když znaků je pod limitem
    with pytest.raises(ValueError):
        zakoduj_json({"x": "č" * 253})


def test_dekoduj_json_odmitne_hodnotu_delsi_nez_512_bajtu():
    presne = b'{"x":"' + b"a" * 504 + b'"}'
    assert len(presne) == MAX_JSON_B
    assert dekoduj_json(presne) == {"x": "a" * 504}
    with pytest.raises(ValueError):
        dekoduj_json(b'{"x":"' + b"a" * 505 + b'"}')


def test_dekoduj_json_cte_utf8_a_odmitne_neplatne_utf8_uvnitr_textu():
    assert dekoduj_json('{"x":"ěš"}'.encode("utf-8")) == {"x": "ěš"}
    with pytest.raises(ValueError):
        dekoduj_json(b'{"x":"\xff"}')  # bajt 0xFF není platné UTF-8, ani uvnitř JSON textu


def test_stav_a_nastaveni_projdou_kodovanim_tam_a_zpet():
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), svetelnost=SimSvetelnost())
    d.krok(datetime(2026, 10, 20, 10, 0, 0), 0.0)
    assert dekoduj_json(zakoduj_json(d.stav())) == d.stav()
    assert dekoduj_json(zakoduj_json(Nastaveni().jako_dict())) == Nastaveni().jako_dict()


# --- Dokument docs/ble-rozhrani.md: je podkladem pro aplikaci, takže musí sedět s kódem ---

DOKUMENT = Path(__file__).resolve().parent.parent / "docs" / "ble-rozhrani.md"
CISELNA_NASTAVENI = [klic for klic, vychozi in Nastaveni().jako_dict().items()
                     if not isinstance(vychozi, (str, bool, list))]


def _text_dokumentu() -> str:
    return DOKUMENT.read_text(encoding="utf-8")


def _oddil(nadpis: str) -> str:
    """Text oddílu `## nadpis` dokumentu až do dalšího nadpisu druhé úrovně."""
    shoda = re.search(rf"^## {re.escape(nadpis)}[ \t]*\n(.*?)(?=^## |\Z)", _text_dokumentu(),
                      re.MULTILINE | re.DOTALL)
    assert shoda, f"V dokumentu chybí oddíl '## {nadpis}'."
    return shoda.group(1)


def _radek_tabulky(oddil: str, klic: str) -> str:
    """Řádek tabulky, který začíná buňkou s `klic` v zpětných apostrofech."""
    radky = [radek for radek in oddil.splitlines() if radek.startswith(f"| `{klic}` |")]
    assert len(radky) == 1, f"V tabulce má být přesně jeden řádek pro '{klic}'."
    return radky[0]


def _stav_po_prvnim_kroku() -> dict:
    d = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), svetelnost=SimSvetelnost())
    d.krok(datetime(2026, 10, 20, 10, 0, 0), 0.0)
    return d.stav()


def test_dokument_obsahuje_nazev_periferie():
    # na velikosti písmen záleží: aplikace podle názvu domek hledá
    assert f"`{NAZEV}`" in _text_dokumentu()


@pytest.mark.parametrize("uuid", [ESS_UUID, TEPLOTA_UUID, VLHKOST_UUID, TLAK_UUID, DOMEK_UUID,
                                  STAV_UUID, NASTAVENI_UUID, PRIKAZ_UUID])
def test_dokument_obsahuje_uuid(uuid):
    # UUID se píše malými i velkými písmeny, na tom nezáleží
    assert uuid.lower() in _text_dokumentu().lower()


def test_dokument_popisuje_vsechny_klice_stavu():
    stav = _stav_po_prvnim_kroku()
    oddil = _oddil("Stav")
    # klíče stavu a klíče objektu "svetlo" jsou v dokumentu jako `klic`
    chybi = [klic for klic in [*stav, *stav["svetlo"]] if f"`{klic}`" not in oddil]
    assert chybi == []


def test_dokument_u_vlhkosti_popisuje_i_bmp280_ktery_vlhkost_nemeri():
    # BMP280 se na sběrnici tváří jako BME280, ale vlhkost neměří: ve Stavu je pak `null`, v ESS
    # neznámá hodnota 0xFFFF a v `chyby` nic
    radek = _radek_tabulky(_oddil("Stav"), "vlhkost")
    assert "BMP280" in radek and "`0xFFFF`" in radek
    assert "`chyby`" in radek and "`null`" in radek


@pytest.mark.parametrize("klic", list(Nastaveni().jako_dict()))
def test_dokument_ma_u_kazdeho_nastaveni_vychozi_hodnotu(klic):
    radek = _radek_tabulky(_oddil("Nastavení"), klic)
    assert f"`{json.dumps(Nastaveni().jako_dict()[klic], separators=(',', ':'))}`" in radek


def test_dokument_popisuje_svetlo_pri_pohybu_jako_bool_true_nebo_false():
    radek = _radek_tabulky(_oddil("Nastavení"), "nocni_svetlo_pri_pohybu")
    assert "bool" in radek and "`true`" in radek and "`false`" in radek
    for platne in (True, False):
        nove = slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": platne})
        assert nove.nocni_svetlo_pri_pohybu is platne
    for neplatne in (0, 1, "true"):
        with pytest.raises(ChybaNastaveni):
            slouc(Nastaveni(), {"nocni_svetlo_pri_pohybu": neplatne})


def test_dokument_u_svetla_a_chyby_svetlo_zminuje_rgb_led():
    assert "RGB LED" in _oddil("Stav")
    assert "RGB LED" in _radek_tabulky(_oddil("Hodnoty v poli `chyby`"), "svetlo")


@pytest.mark.parametrize("klic", CISELNA_NASTAVENI)
def test_rozsah_nastaveni_v_dokumentu_je_ten_skutecny(klic):
    radek = _radek_tabulky(_oddil("Nastavení"), klic)
    shoda = re.search(r"(\d+) až (\d+)", radek)
    assert shoda, f"U '{klic}' chybí rozsah ve tvaru 'MIN až MAX'."
    nejmin, nejvic = int(shoda.group(1)), int(shoda.group(2))
    # u celých čísel je nejbližší hodnota mimo rozsah o 1 vedle, u desetinných o setinu
    odstup = 1 if isinstance(Nastaveni().jako_dict()[klic], int) else 0.01
    for uvnitr in (nejmin, nejvic):
        slouc(Nastaveni(), {klic: uvnitr})
    for mimo in (nejmin - odstup, nejvic + odstup):
        with pytest.raises(ChybaNastaveni):
            slouc(Nastaveni(), {klic: mimo})


def test_dokument_vyjmenovava_vsechny_chyby_ze_stavu(tmp_path):
    # všechno, co umí selhat, selže naráz: ukládání nastavení do cesty, která nejde vytvořit,
    # i obě čidla, displej a výstup světla
    (tmp_path / "soubor").write_text("neni slozka", encoding="utf-8")
    meteo, svetelnost = SimMeteo(), SimSvetelnost()
    displej, vystup = SimDisplej(), SimVystupSvetla()
    for sim in (meteo, svetelnost, displej, vystup):
        sim.selhava = True
    d = Domek(Nastaveni(), meteo, SimPohyb(), SimIndikatory(), displej=displej,
              svetelnost=svetelnost, vystup_svetla=vystup,
              cesta_nastaveni=str(tmp_path / "soubor" / "nastaveni.json"))
    d.krok(datetime(2026, 10, 20, 10, 0, 0), 0.0)
    d.zmen_nastaveni({})
    chyby = d.stav()["chyby"]
    assert chyby == ["displej", "meteo", "nastaveni", "osvetleni", "svetlo"]
    oddil = _oddil("Hodnoty v poli `chyby`")
    assert [chyba for chyba in chyby if f"`{chyba}`" not in oddil] == []


def test_priklady_json_v_dokumentu_jsou_platne_a_uplne():
    bloky = re.findall(r"```json\n(.*?)```", _text_dokumentu(), re.DOTALL)
    priklady = [json.loads(blok) for blok in bloky]  # neplatný JSON spadne už tady
    stav = _stav_po_prvnim_kroku()
    stavy = [p for p in priklady if isinstance(p, dict) and "t_venku" in p]
    assert stavy, "V dokumentu chybí příklad stavu."
    for priklad in stavy:
        assert priklad.keys() == stav.keys()
        assert priklad["svetlo"].keys() == stav["svetlo"].keys()
    assert Nastaveni().jako_dict() in priklady, "V dokumentu chybí příklad výchozího nastavení."


def test_nejdelsi_mozne_nastaveni_se_vejde_do_512_bajtu():
    # dlouhá desetinná čísla (utlum_nikdo_doma má nejdelší zápis, s exponentem e-304),
    # trojmístné složky barvy a jas
    nejdelsi = slouc(Nastaveni(), {
        "cil_den": 34.123456789012345, "cil_noc": 34.223456789012345,
        "cil_rucni": 34.323456789012345, "prah_lux": 9999.123456789012,
        "utlum_nikdo_doma": 2.7470047388660945e-304, "nocni_svetlo_s": 600,
        "nikdo_doma_po_min": 240,
        "rezim_termostatu": "vypnuto", "barva_svetla": [255, 255, 255], "jas_svetla": 100})
    assert len(zakoduj_json(nejdelsi.jako_dict())) <= MAX_JSON_B
