"""Ovladače čidel, kontrolek a displeje pro Raspberry, zkoušené s falešnými knihovnami (viz
conftest.py)."""
import sys
from datetime import datetime

import pytest

from domek.hardware.cidlo_bme280 import Bme280
from domek.hardware.cidlo_pohybu import Pir
from domek.hardware.displej_oled import OledDisplej
from domek.hardware.indikatory import LedIndikatory
from domek.hardware.rozhrani import MereniMeteo
from domek.hardware.simulace import SimIndikatory, SimMeteo, SimPohyb
from domek.jadro import Domek
from domek.nastaveni import Nastaveni
from domek.text_displeje import radky_displeje


def jmena_volani(hw):
    return [volani[0] for volani in hw.volani]


# --- Bme280 ---

def test_bme280_pri_vytvoreni_nenacita_knihovny_a_nesaha_na_hardware(monkeypatch):
    for nazev in ("smbus2", "bme280"):  # knihovny RPi.bme280 a smbus2
        monkeypatch.setitem(sys.modules, nazev, None)  # import by teď selhal s ImportError
    Bme280()
    Bme280(0x77, 0)


@pytest.mark.parametrize("argumenty, sbernice, adresa", [
    ((), 1, 0x76),
    ((0x77, 0), 0, 0x77),
])
def test_bme280_cte_pres_zadanou_sbernici_a_adresu(falesny_hardware, argumenty, sbernice, adresa):
    assert Bme280(*argumenty).precti() == MereniMeteo(21.5, 40.0, 1005.5)  # teplota, vlhkost, tlak
    assert falesny_hardware.volani == [
        ("SMBus", sbernice), ("read_byte_data", adresa, 0xD0),  # ID čipu je v registru 0xD0
        ("load_calibration_params", sbernice, adresa),
        ("sample", sbernice, adresa, "kalibrace")]  # sample dostal kalibraci z load


def test_bme280_vraci_namerene_hodnoty_beze_zmeny(falesny_hardware):
    falesny_hardware.mereni = (-5.25, 99.5, 987.65)
    mereni = Bme280().precti()
    assert (mereni.teplota, mereni.vlhkost, mereni.tlak) == (-5.25, 99.5, 987.65)


def test_bme280_otevre_sbernici_a_nacte_id_i_kalibraci_jen_jednou(falesny_hardware):
    ovladac = Bme280()
    for _ in range(3):
        ovladac.precti()
    assert jmena_volani(falesny_hardware) == [
        "SMBus", "read_byte_data", "load_calibration_params", "sample", "sample", "sample"]


@pytest.mark.parametrize("porucha, volani", [
    ("sbernice", ["SMBus", "SMBus", "SMBus", "read_byte_data", "load_calibration_params",
                  "sample"]),
    ("id", ["SMBus", "read_byte_data", "read_byte_data", "read_byte_data",
            "load_calibration_params", "sample"]),
    ("kalibrace", ["SMBus", "read_byte_data", "load_calibration_params", "load_calibration_params",
                   "load_calibration_params", "sample"]),
    ("mereni", ["SMBus", "read_byte_data", "load_calibration_params", "sample", "sample",
                "sample"]),
])
def test_bme280_porucha_je_oserror_a_pozdejsi_cteni_dokonci_jen_co_chybi(
        falesny_hardware, porucha, volani):
    ovladac = Bme280()
    falesny_hardware.porucha = {porucha}
    for _ in range(2):  # čidlo, které se zatím neozývá, hlásí chybu při každém čtení
        with pytest.raises(OSError):
            ovladac.precti()
    falesny_hardware.porucha = set()
    assert ovladac.precti() == MereniMeteo(21.5, 40.0, 1005.5)
    # sběrnice se neotevírá znovu, když už se otevřela, a ID ani kalibrace se nenačítají znovu,
    # když už je ovladač má
    assert jmena_volani(falesny_hardware) == volani


def test_bme280_porucha_po_uspesnem_cteni_nevyhodi_id_ani_kalibraci(falesny_hardware):
    ovladac = Bme280()
    ovladac.precti()
    falesny_hardware.porucha = {"mereni"}
    with pytest.raises(OSError):
        ovladac.precti()
    falesny_hardware.porucha = set()
    ovladac.precti()
    jmena = jmena_volani(falesny_hardware)
    assert jmena.count("read_byte_data") == jmena.count("load_calibration_params") == 1


# --- Bme280 a BMP280 ---
# BMP280 vypadá na sběrnici stejně jako BME280 (stejná adresa), ale vlhkost neměří a má v registru
# 0xD0 ID 0x58. Knihovna RPi.bme280 u něj místo vlhkosti vrací 0.0, což je nesmysl.

def test_bmp280_ma_teplotu_a_tlak_a_vlhkost_none(falesny_hardware):
    falesny_hardware.chip_id = 0x58
    falesny_hardware.mereni = (21.5, 0.0, 1005.5)  # vlhkost 0.0 vrací knihovna
    assert Bme280().precti() == MereniMeteo(21.5, None, 1005.5)


@pytest.mark.parametrize("chip_id", [0x60, 0x61, 0x00, 0xFF])
def test_jine_id_nez_bmp280_se_chova_jako_bme280(falesny_hardware, chip_id):
    # BME280 má ID 0x60. Jiné ID není důvod k chybě: vlhkost z knihovny se použije, jak je.
    falesny_hardware.chip_id = chip_id
    assert Bme280().precti() == MereniMeteo(21.5, 40.0, 1005.5)


def test_bmp280_se_pozna_podle_id_na_zadane_adrese(falesny_hardware):
    falesny_hardware.chip_id = 0x58
    assert Bme280(0x77, 0).precti().vlhkost is None
    assert ("read_byte_data", 0x77, 0xD0) in falesny_hardware.volani


def test_id_se_cte_jen_jednou_i_u_bmp280(falesny_hardware):
    falesny_hardware.chip_id = 0x58
    ovladac = Bme280()
    assert [ovladac.precti().vlhkost for _ in range(3)] == [None] * 3
    assert jmena_volani(falesny_hardware).count("read_byte_data") == 1


def test_cidlo_ktere_se_zatim_neozvalo_se_nepovazuje_za_bme280(falesny_hardware):
    # Dokud se ID nepřečte, nikdo neví, jestli čidlo vlhkost měří: ovladač musí ohlásit chybu,
    # a ne vrátit vlhkost 0.0 jako BME280
    falesny_hardware.chip_id = 0x58
    ovladac = Bme280()
    falesny_hardware.porucha = {"id"}
    with pytest.raises(OSError):
        ovladac.precti()
    falesny_hardware.porucha = set()
    assert ovladac.precti() == MereniMeteo(21.5, None, 1005.5)


# --- Pir ---

def test_pir_cte_vstup_s_pulldownem_a_vraci_bool(falesny_hardware):
    falesny_hardware.pohyb = [False, True, False]
    pir = Pir()
    (vstup,) = falesny_hardware.vstupy
    assert (vstup.pin, vstup.pull_up) == (24, False)
    assert [pir.je_pohyb() for _ in range(3)] == [False, True, False]


def test_pir_pouzije_zadany_pin(falesny_hardware):
    Pir(5)
    assert [vstup.pin for vstup in falesny_hardware.vstupy] == [5]


# --- LedIndikatory ---

def test_indikatory_ovladaji_tri_led_a_zacinaji_zhasnute(falesny_hardware):
    LedIndikatory()
    assert [(led.pin, led.historie) for led in falesny_hardware.ledky] == [
        (17, [False]), (27, [False]), (22, [False])]


def test_indikatory_nastavuji_led_podle_stavu_i_pri_opakovani(falesny_hardware):
    indikatory = LedIndikatory(5, 6, 7)
    topeni, chlazeni, pohyb = falesny_hardware.ledky
    assert (topeni.pin, chlazeni.pin, pohyb.pin) == (5, 6, 7)
    for topi, chladi in [(True, False), (False, True), (False, True), (False, False)]:
        indikatory.nastav(topi, chladi)
    assert topeni.historie[1:] == [True, False, False, False]
    assert chlazeni.historie[1:] == [False, True, True, False]
    assert pohyb.historie == [False]  # `nastav` kontrolky pohybu nemění


def test_indikatory_ukazuji_pohyb_na_treti_led(falesny_hardware):
    indikatory = LedIndikatory()
    topeni, chlazeni, pohyb = falesny_hardware.ledky
    for sviti in (True, True, False):
        indikatory.ukaz_pohyb(sviti)
    assert pohyb.historie[1:] == [True, True, False]
    assert topeni.historie == [False] and chlazeni.historie == [False]


# --- OledDisplej ---

DEN = datetime(2026, 10, 20, 10, 5, 9)
KNIHOVNY_LUMA = ("luma.core.interface.serial", "luma.core.render", "luma.oled.device")


def stav_domku(**zmeny):
    """Skutečný stav domku po prvním kroku (10:05, den). Záleží na jeho tvaru, ne na hodnotách."""
    domek = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory())
    domek.krok(DEN, 0.0)
    return dict(domek.stav(), **zmeny)


def test_oled_pri_vytvoreni_nenacita_knihovny_a_nesaha_na_hardware(monkeypatch):
    for nazev in KNIHOVNY_LUMA:
        monkeypatch.setitem(sys.modules, nazev, None)  # import by teď selhal s ImportError
    OledDisplej()
    OledDisplej(0x3D, 0)


def test_oled_vytvori_zarizeni_az_pri_prvnim_zobrazeni_a_nakresli_radky(falesny_hardware):
    displej = OledDisplej()
    assert falesny_hardware.volani == []
    stav = stav_domku()
    displej.zobraz(stav, True)
    # sběrnice, zkouška, že displej odpovídá, zařízení a odeslání obrazu
    assert falesny_hardware.volani == [
        ("i2c", 1, 0x3C), ("command", 0xAE), ("ssd1306", 128, 64), ("display",)]
    (snimek,) = falesny_hardware.snimky
    assert [poloha for poloha, _, _ in snimek] == [(0, 0), (0, 14), (0, 24), (0, 33), (0, 42)]
    assert [text for _, text, _ in snimek] == radky_displeje(stav, True)
    assert {barva for _, _, barva in snimek} == {"white"}


def test_oled_sesty_radek_s_chybami_se_vejde_na_displej(falesny_hardware):
    # Písmo luma (výchozí z Pillow) zabírá i s ocásky písmen body y+2 až y+11, tedy 12 bodů
    # od horního okraje řádku, takže řádek na 52 končí na bodu 63 z 0 až 63
    OledDisplej().zobraz(stav_domku(chyby=["osvetleni"]), True)
    (snimek,) = falesny_hardware.snimky
    assert [poloha for poloha, _, _ in snimek] == [
        (0, 0), (0, 14), (0, 24), (0, 33), (0, 42), (0, 52)]
    assert snimek[-1][1].startswith("CHYBA")


def test_oled_pouzije_zadanou_adresu_a_sbernici(falesny_hardware):
    OledDisplej(0x3D, 0).zobraz(stav_domku(), False)
    assert ("i2c", 0, 0x3D) in falesny_hardware.volani


def test_oled_kresli_jen_kdyz_se_radky_zmenily(falesny_hardware):
    displej = OledDisplej()
    stav = stav_domku()
    displej.zobraz(stav, False)
    # Jiná sekunda téže minuty a hodnoty, které displej neukazuje: řádky jsou stejné
    displej.zobraz(dict(stav, cas="2026-10-20T10:05:59", vlhkost=10.0, lux=3.0, pohyb=True), False)
    assert len(falesny_hardware.snimky) == 1
    displej.zobraz(dict(stav, cas="2026-10-20T10:06:00"), False)  # nová minuta
    assert len(falesny_hardware.snimky) == 2
    displej.zobraz(dict(stav, cas="2026-10-20T10:06:00"), True)   # připojil se tablet
    assert len(falesny_hardware.snimky) == 3
    # Zařízení se přitom vytvořilo jen jednou
    assert jmena_volani(falesny_hardware).count("ssd1306") == 1


@pytest.mark.parametrize("porucha", ["sbernice", "displej", "inicializace", "text", "odeslani"])
def test_oled_porucha_je_oserror_a_po_navratu_se_stejne_radky_nakresli_znovu(
        falesny_hardware, porucha):
    displej = OledDisplej()
    stav = stav_domku()
    falesny_hardware.porucha = {porucha}
    for _ in range(2):  # displej, který se zatím neozývá, hlásí chybu při každém zobrazení
        with pytest.raises(OSError, match="0x3C"):  # z chyby knihovny luma musí být OSError
            displej.zobraz(stav, True)
    assert falesny_hardware.snimky == []
    falesny_hardware.porucha = set()
    displej.zobraz(stav, True)
    assert len(falesny_hardware.snimky) == 1
    displej.zobraz(stav, True)  # a pak zase jen při změně řádků
    assert len(falesny_hardware.snimky) == 1


def test_oled_po_neuspesnem_kresleni_posle_i_obraz_ktery_uz_na_displeji_byl(falesny_hardware):
    # Po poruše je displej v neznámém stavu, takže stejné řádky jako před ní se musí poslat znovu
    displej = OledDisplej()
    puvodni, novy = stav_domku(), stav_domku(cas="2026-10-20T10:06:00")
    displej.zobraz(puvodni, False)
    falesny_hardware.porucha = {"odeslani"}
    with pytest.raises(OSError):
        displej.zobraz(novy, False)
    falesny_hardware.porucha = set()
    displej.zobraz(puvodni, False)
    assert len(falesny_hardware.snimky) == 2


def test_oled_nevytvari_zarizeni_dokud_displej_neodpovida(falesny_hardware):
    # luma.oled drží každé vytvořené zařízení až do konce programu (atexit) a s ním dva seznamy
    # po 8192 číslech, dohromady asi 320 kB. Domek volá zobraz každou sekundu, takže by pokus
    # o vytvoření zařízení při každém volání zaplnil paměť za necelou hodinu. Zařízení se proto
    # vytvoří, až když displej odpoví na zkušební příkaz, a sběrnice se otevře jen jednou.
    displej = OledDisplej()
    stav = stav_domku()
    falesny_hardware.porucha = {"displej"}
    for _ in range(5):
        with pytest.raises(OSError):
            displej.zobraz(stav, True)
    assert jmena_volani(falesny_hardware) == ["i2c"] + ["command"] * 5


LIMIT_VYTVORENI = 10  # rozhodnutí z kontroly kódu: víc zařízení se nesmí vytvořit ani při poruše


@pytest.mark.parametrize("porucha", ["inicializace", "text", "odeslani"])
def test_oled_pri_trvale_poruse_po_odpovedi_displeje_vytvori_zarizeni_nejvys_desetkrat(
        falesny_hardware, porucha):
    # Displej odpovídá na zkušební příkaz, ale zařízení se nedá inicializovat nebo se na něj nedá
    # kreslit. Zkušební příkaz tedy nestačí: každé vytvořené zařízení drží luma.oled v paměti
    # (asi 320 kB) a domek volá zobraz každou sekundu, takže počet vytvoření musí mít strop.
    displej = OledDisplej()
    stav = stav_domku()
    falesny_hardware.porucha = {porucha}
    vytvoreno = []
    for _ in range(1000):
        with pytest.raises(OSError):  # i po vyčerpání limitu se chyba musí hlásit
            displej.zobraz(stav, True)
        vytvoreno.append(jmena_volani(falesny_hardware).count("ssd1306"))
    # Každé volání vytvoří jedno zařízení, až do limitu, a pak už žádné
    assert vytvoreno == [min(volani, LIMIT_VYTVORENI) for volani in range(1, 1001)]
    assert jmena_volani(falesny_hardware).count("command") == LIMIT_VYTVORENI  # i zkoušky
    assert jmena_volani(falesny_hardware).count("i2c") == 1
    # Ani zotavený displej se pak nezkouší, zůstane chyba, dokud se program nerestartuje
    falesny_hardware.porucha = set()
    pocet_volani = len(falesny_hardware.volani)
    with pytest.raises(OSError, match="restart"):
        displej.zobraz(stav, True)
    assert len(falesny_hardware.volani) == pocet_volani and falesny_hardware.snimky == []


def test_oled_zkusebni_prikazy_se_do_limitu_nepocitaji(falesny_hardware):
    displej = OledDisplej()
    stav = stav_domku()
    falesny_hardware.porucha = {"displej"}
    for _ in range(5 * LIMIT_VYTVORENI):  # displej dlouho neodpovídá, ale nic se nevytváří
        with pytest.raises(OSError):
            displej.zobraz(stav, True)
    falesny_hardware.porucha = set()
    displej.zobraz(stav, True)
    assert len(falesny_hardware.snimky) == 1


def test_oled_limit_neberou_displeji_ktery_funguje(falesny_hardware):
    # Limit se týká jen vytváření zařízení. Displej, který se při posledním dovoleném pokusu
    # vytvořil, dál funguje. Teprve když pak selže, už se znovu nevytvoří.
    displej = OledDisplej()
    stav = stav_domku()
    falesny_hardware.porucha = {"inicializace"}
    for _ in range(LIMIT_VYTVORENI - 1):
        with pytest.raises(OSError):
            displej.zobraz(stav, True)
    falesny_hardware.porucha = set()
    displej.zobraz(stav, True)  # poslední dovolené vytvoření
    displej.zobraz(dict(stav, cas="2026-10-20T10:06:00"), True)  # a displej dál funguje
    assert len(falesny_hardware.snimky) == 2
    falesny_hardware.porucha = {"odeslani"}
    with pytest.raises(OSError):
        displej.zobraz(dict(stav, cas="2026-10-20T10:07:00"), True)
    falesny_hardware.porucha = set()
    with pytest.raises(OSError, match="restart"):  # nové zařízení už se nevytvoří
        displej.zobraz(dict(stav, cas="2026-10-20T10:07:00"), True)
    assert len(falesny_hardware.snimky) == 2


def test_oled_po_selhani_odeslani_inicializuje_zarizeni_znovu(falesny_hardware):
    # Displej, který mezitím ztratil napájení, je po návratu vypnutý a prázdný, dokud se mu znovu
    # neposlou inicializační příkazy, které posílá až vytvoření zařízení
    displej = OledDisplej()
    stav = stav_domku()
    displej.zobraz(stav, False)
    falesny_hardware.porucha = {"odeslani"}
    with pytest.raises(OSError):
        displej.zobraz(dict(stav, cas="2026-10-20T10:06:00"), False)
    falesny_hardware.porucha = set()
    displej.zobraz(dict(stav, cas="2026-10-20T10:06:00"), False)
    assert jmena_volani(falesny_hardware) == [
        "i2c", "command", "ssd1306", "display",  # první zobrazení
        "display",                               # odeslání, které selhalo
        "command", "ssd1306", "display"]         # nové zařízení a obraz


def test_oled_bez_knihovny_luma_hlasi_oserror(monkeypatch):
    for nazev in KNIHOVNY_LUMA:
        monkeypatch.setitem(sys.modules, nazev, None)
    with pytest.raises(OSError, match="0x3C"):
        OledDisplej().zobraz(stav_domku(), False)


def test_domek_ohlasi_poruchu_displeje_a_po_navratu_ji_smaze(falesny_hardware):
    domek = Domek(Nastaveni(), SimMeteo(), SimPohyb(), SimIndikatory(), displej=OledDisplej())
    falesny_hardware.porucha = {"displej"}
    domek.krok(DEN, 0.0)
    assert domek.stav()["chyby"] == ["displej"]
    falesny_hardware.porucha = set()
    domek.krok(DEN, 1.0)
    assert domek.stav()["chyby"] == []
    domek.krok(DEN, 2.0)  # od dalšího kroku už displej chybu neukazuje
    posledni_obraz = [text for _, text, _ in falesny_hardware.snimky[-1]]
    assert len(posledni_obraz) == 5 and not any(r.startswith("CHYBA") for r in posledni_obraz)


# --- chyby GPIO ---

def test_chyba_gpio_se_v_pir_a_indikatorech_nezachyti(falesny_hardware):
    # Domek tyto dva ovladače nehlídá: chyba má program ukončit a systemd ho spustí znovu
    pir, indikatory = Pir(), LedIndikatory()
    falesny_hardware.porucha = {"gpio"}
    with pytest.raises(OSError):
        pir.je_pohyb()
    with pytest.raises(OSError):
        indikatory.nastav(True, False)
    with pytest.raises(OSError):
        indikatory.ukaz_pohyb(True)
