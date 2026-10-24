import json
import sys
import types
from datetime import datetime

import pytest

from domek import __main__ as spusteni
from domek.__main__ import main
from domek.ble_format import (NASTAVENI_UUID, PRIKAZ_UUID, STAV_UUID, TEPLOTA_UUID, TLAK_UUID,
                              VLHKOST_UUID, teplota_ess, tlak_ess, vlhkost_ess)
from domek.ble_server import BleServer
from domek.casovac import opakuj
from domek.hardware import cidlo_tsl2561
from domek.hardware.simulace import SimPohyb
from domek.nastaveni import Nastaveni, nacti, slouc, uloz


class FalesneHodiny:
    """Nahrazuje modul time: čas se posouvá jen voláním sleep, takže test nečeká.

    Každé volání se počítá a po `limit` voláních test spadne, aby se chybná smyčka bez konce
    neprotočila donekonečna. Po `preruseni_po` pauzách sleep vyhodí KeyboardInterrupt jako Ctrl+C.
    """

    def __init__(self, preruseni_po=None, limit=5000):
        self.cas = 1000.0
        self.pauzy = []
        self.preruseni_po = preruseni_po
        self.limit = limit
        self.volani = 0

    def _spocitej(self):
        self.volani += 1
        assert self.volani <= self.limit, "smycka se nezastavila"

    def monotonic(self):
        self._spocitej()
        return self.cas

    def sleep(self, sekund):
        self._spocitej()
        if self.preruseni_po is not None and len(self.pauzy) >= self.preruseni_po:
            raise KeyboardInterrupt
        self.pauzy.append(sekund)
        self.cas += sekund


class PevnyCas:
    """Nahrazuje třídu datetime: now() vrací stále stejný čas, takže výstup nezávisí na tom, kdy
    test běží (jinak by se úsek dne měnil podle hodin a s ním i cíl termostatu)."""

    @staticmethod
    def now():
        return datetime(2026, 10, 20, 10, 0, 0)


@pytest.fixture
def hodiny(monkeypatch):
    falesne = FalesneHodiny()
    monkeypatch.setattr(spusteni, "time", falesne)
    monkeypatch.setattr(spusteni, "datetime", PevnyCas)
    return falesne


@pytest.fixture(autouse=True)
def bez_cekani_tsl2561(monkeypatch):
    # Ovladač TSL2561 po zapnutí čeká na první měření, test nečeká
    monkeypatch.setattr(cidlo_tsl2561, "time", types.SimpleNamespace(sleep=lambda sekund: None))


@pytest.fixture
def cesta(tmp_path):
    # vlastní soubor nastavení, aby test nečetl nastaveni-simulace.json z pracovní složky
    return str(tmp_path / "nastaveni.json")


def radky_stavu(capsys):
    return capsys.readouterr().out.splitlines()


@pytest.mark.parametrize("sekund", [0, 1, 3])
def test_simulace_dela_jeden_krok_za_sekundu_po_100_ms(hodiny, cesta, capsys, monkeypatch, sekund):
    cteni = []

    class SledovanyPohyb(SimPohyb):
        def je_pohyb(self):
            cteni.append(hodiny.cas)
            return super().je_pohyb()

    monkeypatch.setattr(spusteni, "SimPohyb", SledovanyPohyb)
    main(["--simulace", "--sekund", str(sekund), "--nastaveni", cesta])
    radky = radky_stavu(capsys)
    assert len(radky) == sekund
    assert hodiny.pauzy == [0.1] * (10 * sekund)
    assert len(cteni) == 10 * sekund  # pohyb se kontroluje při každé pauze
    for radek in radky:
        assert radek == json.dumps(json.loads(radek), ensure_ascii=False)  # jeden řádek, bez úprav


def test_vnitrni_teplota_se_mezi_radky_meni(hodiny, cesta, capsys):
    main(["--simulace", "--sekund", "3", "--nastaveni", cesta])
    stavy = [json.loads(radek) for radek in radky_stavu(capsys)]
    assert [s["cas"] for s in stavy] == ["2026-10-20T10:00:00"] * 3
    assert [s["t_uvnitr"] for s in stavy] == [22.0, 22.06, 22.12]  # ve dne se topí k cíli 23 °C
    assert all(s["topi"] for s in stavy)


def test_simulace_ma_vychozi_namerene_hodnoty(hodiny, cesta, capsys):
    main(["--simulace", "--sekund", "1", "--nastaveni", cesta])
    stav = json.loads(radky_stavu(capsys)[0])
    meteo = (stav["t_venku"], stav["vlhkost"], stav["tlak"], stav["lux"])
    assert meteo == (22.0, 45.0, 1013.25, 200.0)
    assert (stav["pohyb"], stav["chyby"]) == (False, [])


def test_simulace_dostane_vsechen_simulovany_hardware_a_cestu_nastaveni(hodiny, cesta, monkeypatch):
    zachyceno = {}
    puvodni = spusteni.Domek

    def domek_se_zachytem(*args, **kwargs):
        zachyceno.update(kwargs)
        return puvodni(*args, **kwargs)

    monkeypatch.setattr(spusteni, "Domek", domek_se_zachytem)
    main(["--simulace", "--sekund", "0", "--nastaveni", cesta])
    assert zachyceno["cesta_nastaveni"] == cesta
    for nazev in ("displej", "svetelnost", "vystup_svetla", "nastavovac_casu"):
        assert zachyceno[nazev] is not None


def test_nastaveni_se_nacte_z_volby_nastaveni(hodiny, cesta, capsys):
    uloz(slouc(Nastaveni(), {"rezim_termostatu": "vypnuto"}), cesta)
    main(["--simulace", "--sekund", "1", "--nastaveni", cesta])
    assert json.loads(radky_stavu(capsys)[0])["rezim"] == "vypnuto"


def test_bez_volby_nastaveni_se_v_simulaci_cte_nastaveni_simulace_json(
        hodiny, tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    uloz(slouc(Nastaveni(), {"rezim_termostatu": "vypnuto"}), "nastaveni-simulace.json")
    main(["--simulace", "--sekund", "1"])
    assert json.loads(radky_stavu(capsys)[0])["rezim"] == "vypnuto"


@pytest.mark.parametrize("adresa", ["0x77", "119", "0o167"])
def test_adresa_bme280_se_cte_jako_int_s_predponou(hodiny, cesta, capsys, adresa):
    main(["--simulace", "--sekund", "1", "--nastaveni", cesta, "--bme280-adresa", adresa])
    assert len(radky_stavu(capsys)) == 1


STARE_VOLBY = ["--krouzek", "--bh1750", "--tsl2561", "--rgb-led", "--led-svetlo"]


@pytest.mark.parametrize("volba", STARE_VOLBY)
@pytest.mark.parametrize("simulace", [True, False], ids=["simulace", "raspberry"])
def test_stara_volba_sestavy_se_odmitne_jako_neznama(hodiny, cesta, capsys, monkeypatch, volba,
                                                     simulace):
    # Sestava na Raspberry je pevná (TSL2561 a RGB LED), volby součástek už nejsou. Argparse je
    # odmítne hned při čtení voleb, i v simulaci, a nic se nespustí.
    monkeypatch.setattr(spusteni, "_spust_na_raspberry",
                        lambda *argumenty: pytest.fail("nic se nema spustit"))
    with pytest.raises(SystemExit) as konec:
        main((["--simulace", "--sekund", "0"] if simulace else []) + ["--nastaveni", cesta, volba])
    assert konec.value.code == 2
    vystup = capsys.readouterr()
    assert vystup.out == "" and f"unrecognized arguments: {volba}" in vystup.err


@pytest.mark.parametrize("volba", [["--bme280-adresa", "xyz"], ["--sekund", "pet"], ["--neznama"]])
def test_neplatna_volba_skonci_chybou_pri_cteni_voleb(hodiny, capsys, volba):
    with pytest.raises(SystemExit) as konec:
        main(["--simulace"] + volba)
    assert konec.value.code == 2
    assert radky_stavu(capsys) == []  # simulace se vůbec nerozběhla


@pytest.mark.parametrize("volby", [
    ["--simulace", "--sekund", "0", "--bme280-adres", "0x77"],
    ["--simulace", "--sek", "0"],
    ["--sim", "--sekund", "0"],
], ids=["adres", "sek", "sim"])
def test_zkracena_volba_se_odmitne(hodiny, cesta, capsys, volby):
    # Zkratku by argparse jinak vzal jako celou volbu: překlep v instalaci by se do služby zapsal
    with pytest.raises(SystemExit) as konec:
        main(volby + ["--nastaveni", cesta])
    assert konec.value.code == 2
    assert radky_stavu(capsys) == []


def test_napoveda_vypise_volby_a_skonci_bez_chyby(capsys):
    with pytest.raises(SystemExit) as konec:
        main(["--help"])
    assert konec.value.code == 0
    vystup = capsys.readouterr().out
    assert "--simulace" in vystup and "--bme280-adresa" in vystup
    assert not any(volba in vystup for volba in STARE_VOLBY)


# --- kontrola voleb pro službu (volá ji deploy/instaluj.sh před zápisem služby) ---

KNIHOVNY_RASPBERRY = ("bluezero", "dbus", "gi", "gpiozero", "smbus2", "bme280", "luma")


@pytest.mark.parametrize("volby", [
    [],
    ["--bme280-adresa", "0x77"],
    ["--bme280-adresa", "0x77", "--nastaveni", "/var/lib/domek/jine.json"],
])
def test_kontrola_voleb_sluzby_platne_volby_projdou_a_nic_nespusti(monkeypatch, capsys, volby):
    # Instalace kontroluje volby jako root a systémovým python3, ještě před instalací knihoven:
    # kontrola smí volby jen přečíst, nic spustit, načíst ani vypsat
    for nazev in KNIHOVNY_RASPBERRY:
        monkeypatch.setitem(sys.modules, nazev, None)

    def nesmi_se_volat(*argumenty, **pojmenovane):
        pytest.fail("kontrola voleb nesmi nic spoustet")

    for nazev in ("_spust_na_raspberry", "_simuluj", "Domek", "nacti"):
        monkeypatch.setattr(spusteni, nazev, nesmi_se_volat)
    spusteni.over_volby(volby)
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("volby, duvod", [
    (["--bme280-adres"], "unrecognized arguments: --bme280-adres"),
    # staré volby součástek zmizely, sestava je pevná
    (["--krouzek"], "unrecognized arguments: --krouzek"),
    (["--bh1750"], "unrecognized arguments: --bh1750"),
    (["--tsl2561"], "unrecognized arguments: --tsl2561"),
    (["--rgb-led"], "unrecognized arguments: --rgb-led"),
    (["--led-svetlo"], "unrecognized arguments: --led-svetlo"),
    (["--bme280-adresa", "0x77", "--krouzek"], "unrecognized arguments: --krouzek"),
    (["--bme280-adresa", "xyz"], "neni cele cislo"),
    # se --simulace by služba jen simulovala a Bluetooth by nikdy nespustila
    (["--simulace"], "--simulace"),
    # s nápovědou by služba jen vypsala nápovědu a skončila, a tak pořád dokola
    (["--help"], "unrecognized arguments: --help"),
    (["-h"], "unrecognized arguments: -h"),
], ids=["preklep", "krouzek", "bh1750", "tsl2561", "rgb-led", "led-svetlo", "adresa-krouzek",
        "spatna-adresa", "simulace", "help", "h"])
def test_kontrola_voleb_sluzby_odmitne_volbu_se_kterou_by_sluzba_nebezela(capsys, volby, duvod):
    with pytest.raises(SystemExit) as konec:
        spusteni.over_volby(volby)
    assert konec.value.code == 2
    vystup = capsys.readouterr()
    assert vystup.out == "" and duvod in vystup.err


def test_ctrl_c_ukonci_beh_bez_omezeni_bez_chyby(hodiny, cesta, capsys):
    hodiny.preruseni_po = 25  # dvě a půl sekundy simulace
    try:
        main(["--simulace", "--nastaveni", cesta])
    except KeyboardInterrupt:  # jinak by přerušení utnulo celý běh pytestu, a ne jen tento test
        pytest.fail("Ctrl+C se z main nema dostat ven")
    assert len(radky_stavu(capsys)) == 3


@pytest.mark.parametrize("volby, adresa, ocekavana_cesta", [
    ([], 0x76, spusteni.CESTA_NASTAVENI),
    (["--bme280-adresa", "0x77", "--nastaveni", "jinde.json"], 0x77, "jinde.json"),
    (["--bme280-adresa", "0x77"], 0x77, spusteni.CESTA_NASTAVENI),
])
def test_bez_simulace_se_spusti_domek_na_raspberry(monkeypatch, capsys, volby, adresa,
                                                   ocekavana_cesta):
    for nazev in ("bluezero", "dbus", "gi", "smbus2", "gpiozero"):
        monkeypatch.setitem(sys.modules, nazev, None)  # main sám knihovny Raspberry nenačítá
    volani = []
    monkeypatch.setattr(spusteni, "_spust_na_raspberry",
                        lambda *argumenty: volani.append(argumenty))
    main(volby)
    assert volani == [(adresa, ocekavana_cesta)]
    assert radky_stavu(capsys) == []  # simulace se nespustila


def test_na_raspberry_prvni_krok_casovace_a_server(falesny_hardware, falesny_bluetooth, hodiny,
                                                   cesta, monkeypatch):
    # Skutečné ovladače s falešnými knihovnami (conftest.py), skutečný server s falešným
    # bluezero. Falešné `publish` se hned vrátí, takže časovače spouští test sám.
    udalosti = []
    prvni_stav = {}
    konce = []  # místo os._exit: chyba v časovači nebo registraci by jinak ukončila celé testy
    # Nastavení uložené z tabletu před restartem
    uloz(slouc(Nastaveni(), {"rezim_termostatu": "rucni", "cil_rucni": 25}), cesta)

    class SledovanyServer(BleServer):
        def __init__(self, domek):
            udalosti.append(("server", domek.stav()["cas"]))
            prvni_stav.update(domek.stav())
            super().__init__(domek, ukonci=konce.append)

        def spust(self):
            udalosti.append(("spust", [interval for interval, _ in falesny_bluetooth.casovace]))
            super().spust()

    monkeypatch.setattr(spusteni, "BleServer", SledovanyServer)
    monkeypatch.setattr(spusteni, "opakuj", lambda fn: opakuj(fn, ukonci=konce.append))
    spusteni._spust_na_raspberry(0x77, cesta)

    # První krok proběhl dřív, než vznikl server, a časovače jsou nastavené před spuštěním smyčky.
    # Světlo je RGB LED, ta se neprolíná, takže tu není žádný další časovač.
    assert udalosti == [("server", "2026-10-20T10:00:00"), ("spust", [1000, 100])]
    assert (prvni_stav["rezim"], prvni_stav["cil"]) == ("rucni", 25.0)  # nastavení ze souboru
    assert ("sample", 1, 0x77, "kalibrace") in falesny_hardware.volani  # BME280 na zadané adrese
    assert falesny_hardware.snimky  # displej už ukazuje stav
    assert falesny_bluetooth.periferie[-1].inzerat["LocalName"] == "Termostat"

    (_, tik), (_, kontrola_pohybu) = falesny_bluetooth.casovace
    falesny_bluetooth.odebirej(STAV_UUID)
    hodiny.cas += 1.0
    assert tik() is True
    [stav] = [json.loads(hodnota) for hodnota in falesny_bluetooth.notifikace(STAV_UUID)]
    assert (stav["t_venku"], stav["pohyb"]) == (21.5, False)  # z falešného BME280 a PIR

    assert kontrola_pohybu() is True
    assert len(falesny_bluetooth.notifikace(STAV_UUID)) == 1  # pohyb se nezměnil, nic se neposlalo
    falesny_hardware.pohyb = [True]
    assert kontrola_pohybu() is True
    assert json.loads(falesny_bluetooth.notifikace(STAV_UUID)[-1])["pohyb"] is True

    # Zápis z tabletu se uloží do souboru, ze kterého domek čte nastavení po restartu
    falesny_bluetooth.zapis(NASTAVENI_UUID, b'{"cil_den":26}')
    ulozene = nacti(cesta)
    assert (ulozene.cil_den, ulozene.rezim_termostatu, ulozene.cil_rucni) == (26.0, "rucni", 25.0)
    assert konce == []  # registrace prošla a v časovačích nebyla žádná výjimka


def zadrz_ukonceni(monkeypatch):
    """Místo os._exit si server i časovače zapisují, jak by program skončil. Vrací seznam kódů."""
    konce = []
    monkeypatch.setattr(spusteni, "BleServer", lambda domek: BleServer(domek, ukonci=konce.append))
    monkeypatch.setattr(spusteni, "opakuj", lambda fn: opakuj(fn, ukonci=konce.append))
    return konce


# --- oranžová LED jako kontrolka pohybu ---

def test_na_raspberry_sviti_oranzova_led_pri_pohybu_a_10_s_po_nem(
        falesny_hardware, falesny_bluetooth, hodiny, cesta, monkeypatch):
    konce = zadrz_ukonceni(monkeypatch)
    spusteni._spust_na_raspberry(0x76, cesta)

    # Kontrolky vznikly už v konstruktoru: červená GPIO17, zelená GPIO27 a oranžová GPIO22
    oranzova = [led for led in falesny_hardware.ledky if led.pin == 22]
    assert len(oranzova) == 1
    (oranzova,) = oranzova
    assert oranzova.value == 0
    (_, _), (_, kontrola_pohybu) = falesny_bluetooth.casovace[:2]
    falesny_hardware.pohyb = [True]
    kontrola_pohybu()
    assert oranzova.value == 1  # rozsvítí se hned, ve stejném volání, které pohyb přečetlo
    falesny_hardware.pohyb = [False]
    hodiny.cas += 9.5
    kontrola_pohybu()
    assert oranzova.value == 1
    hodiny.cas += 0.5
    kontrola_pohybu()
    assert oranzova.value == 0
    assert konce == []


# --- RGB LED jako světlo ---

def test_na_raspberry_sviti_rgb_led_barvou_i_jasem(
        falesny_hardware, falesny_bluetooth, hodiny, cesta, monkeypatch):
    konce = zadrz_ukonceni(monkeypatch)
    spusteni._spust_na_raspberry(0x76, cesta)

    # RGB LED se neprolíná, takže tu není časovač 50 ms
    assert [interval for interval, _ in falesny_bluetooth.casovace] == [1000, 100]
    (rgb,) = falesny_hardware.rgb_ledky  # vznikla už prvním krokem domku
    assert (rgb.piny, rgb.value) == ((16, 20, 21), (0.0, 0.0, 0.0))  # ve dne bez šera nesvítí
    falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":{"barva":[0,0,255],"jas":50}}')
    assert rgb.value == (0.0, 0.0, 0.5)
    falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":"vypnout"}')
    assert rgb.value == (0.0, 0.0, 0.0)
    assert json.loads(falesny_bluetooth.cti(STAV_UUID))["chyby"] == []
    assert konce == []


# --- čidlo BMP280 bez vlhkosti ---

def test_na_raspberry_bmp280_ma_vlhkost_neznamou_a_zadnou_chybu(
        falesny_hardware, falesny_bluetooth, hodiny, cesta, monkeypatch):
    # Čidlo na adrese 0x76 je BMP280 (ID 0x58): teplota a tlak platí, vlhkost je neznámá na
    # displeji, ve Stavu i v ESS, a nic z toho není porucha
    konce = zadrz_ukonceni(monkeypatch)
    falesny_hardware.chip_id = 0x58
    falesny_hardware.mereni = (21.5, 0.0, 1005.5)  # vlhkost 0.0 vrací knihovna RPi.bme280
    spusteni._spust_na_raspberry(0x76, cesta)

    stav = json.loads(falesny_bluetooth.cti(STAV_UUID))
    assert (stav["t_venku"], stav["vlhkost"], stav["tlak"], stav["chyby"]) == (
        21.5, None, 1005.5, [])
    assert falesny_bluetooth.cti(VLHKOST_UUID) == vlhkost_ess(None)
    assert falesny_bluetooth.cti(TEPLOTA_UUID) == teplota_ess(21.5)
    assert falesny_bluetooth.cti(TLAK_UUID) == tlak_ess(1005.5)
    na_displeji = [text for _, text, _ in falesny_hardware.snimky[-1]]
    assert "Venku  21.5C 1006hPa" in na_displeji  # BMP280 tlak měří, vlhkost ne
    assert not any(radek.startswith("CHYBA") for radek in na_displeji)
    assert konce == []


@pytest.mark.parametrize("porucha, lux, chyby", [
    (set(), 446.9, []),
    ({"zapnuti"}, None, ["osvetleni"]),
    ({"kanaly"}, None, ["osvetleni"]),
])
def test_na_raspberry_se_cte_cidlo_tsl2561_a_jeho_porucha_je_chyba_osvetleni(
        falesny_hardware, falesny_bluetooth, hodiny, cesta, monkeypatch, porucha, lux, chyby):
    konce = zadrz_ukonceni(monkeypatch)
    falesny_hardware.porucha = porucha
    spusteni._spust_na_raspberry(0x76, cesta)

    stav = json.loads(falesny_bluetooth.cti(STAV_UUID))
    assert (stav["lux"], stav["chyby"]) == (lux, chyby)
    nazvy = [volani[0] for volani in falesny_hardware.volani]
    assert "write_byte_data" in nazvy  # ovladač TSL2561 zapíná čidlo přes registr
    assert konce == []
