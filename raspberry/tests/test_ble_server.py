"""Bluetooth server domku, zkoušený s falešnými knihovnami bluezero, dbus a gi (viz conftest.py)."""
import inspect
import json
import logging
import subprocess
import sys
import types
from datetime import datetime
from functools import partial
from pathlib import Path

import pytest

from domek import ble_server
from domek.ble_format import (DOMEK_UUID, ESS_UUID, NASTAVENI_UUID, NAZEV, PRIKAZ_UUID, STAV_UUID,
                              TEPLOTA_UUID, TLAK_UUID, VLHKOST_UUID, teplota_ess, tlak_ess,
                              vlhkost_ess, zakoduj_json)
from domek.ble_server import BleServer
from domek.hardware.simulace import (SimDisplej, SimIndikatory, SimMeteo, SimNastavovacCasu,
                                     SimPohyb, SimVystupSvetla)
from domek.jadro import Domek
from domek.nastaveni import Nastaveni, nacti

GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"
DEN = datetime(2026, 10, 20, 10, 0, 0)
KOREN = Path(__file__).resolve().parent.parent


class StojiciCas:
    """Nahrazuje v ble_server třídu datetime i modul time: den v 10:00 a monotonní čas 100 s."""

    @staticmethod
    def now():
        return DEN

    @staticmethod
    def monotonic():
        return 100.0


@pytest.fixture
def dum(tmp_path):
    """Domek se simulovaným hardwarem po prvním kroku, jako ho BleServer dostane od __main__."""
    dum = types.SimpleNamespace(meteo=SimMeteo(teplota=14.5), pohyb=SimPohyb(),
                                vystup=SimVystupSvetla(), cesta=str(tmp_path / "nastaveni.json"))
    dum.domek = Domek(Nastaveni(), dum.meteo, dum.pohyb, SimIndikatory(),
                      cesta_nastaveni=dum.cesta, displej=SimDisplej(),
                      vystup_svetla=dum.vystup, nastavovac_casu=SimNastavovacCasu())
    dum.domek.krok(DEN, 0.0)
    return dum


@pytest.fixture
def konce():
    """Kódy, se kterými by server ukončil program; v testech místo os._exit."""
    return []


@pytest.fixture
def server(falesny_bluetooth, dum, konce, monkeypatch):
    monkeypatch.setattr(ble_server, "datetime", StojiciCas)
    monkeypatch.setattr(ble_server, "time", StojiciCas)
    return BleServer(dum.domek, ukonci=konce.append)


def vlastnost(objekt, rozhrani, nazev):
    return objekt.props[rozhrani][nazev]


def notifikace_json(bt, uuid):
    return [json.loads(hodnota) for hodnota in bt.notifikace(uuid)]


# --- Periferie, služby a charakteristiky ---

def test_periferie_termostat_se_zverejni_az_ve_spust_a_inzeruje_jen_ess(falesny_bluetooth,
                                                                         server, konce):
    [periferie] = falesny_bluetooth.periferie
    assert periferie.dongle.address == falesny_bluetooth.ADRESA_ADAPTERU
    assert periferie.local_name == NAZEV
    assert periferie.inzerat is None  # konstruktor periferii jen připraví
    server.spust()
    # 128bitové UUID služby Domek by se vedle názvu do reklamního paketu nevešlo
    assert periferie.inzerat == {"ServiceUUIDs": [ESS_UUID], "LocalName": NAZEV}
    assert konce == []  # BlueZ registraci přijal, program běží dál


@pytest.mark.parametrize("registrace, popis", [("aplikace", "GATT aplikace"),
                                               ("inzerat", "inzeratu")])
def test_odmitnuta_registrace_v_bluez_ukonci_program(falesny_bluetooth, server, konce, caplog,
                                                     registrace, popis):
    # Bez registrace by časovače a displej běžely dál, ale tablet by domek nikdy nenašel.
    # Program proto skončí a systemd ho spustí znovu.
    falesny_bluetooth.chyby_registrace[registrace] = falesny_bluetooth.DBusException(
        "Maximum advertisements reached", name="org.bluez.Error.NotPermitted")
    server.spust()
    assert konce == [1]
    [zaznam] = [z for z in caplog.records if z.levelno == logging.ERROR]
    assert zaznam.getMessage() == (
        f"BlueZ odmitl registraci {popis}: org.bluez.Error.NotPermitted: Maximum advertisements "
        f"reached. Program konci, aby ho systemd spustil znovu.")


def test_sluzby_a_charakteristiky_maji_dohodnute_uuid_a_vlastnosti(falesny_bluetooth, server):
    periferie = falesny_bluetooth.periferie[-1]
    sluzby = [(vlastnost(s, "org.bluez.GattService1", "UUID"),
               vlastnost(s, "org.bluez.GattService1", "Primary")) for s in periferie.services]
    assert sluzby == [(ESS_UUID, True), (DOMEK_UUID, True)]
    # cesta říká, ve které službě charakteristika je (service0001 je ESS, service0002 Domek)
    charakteristiky = [(ch.path, vlastnost(ch, GATT_CHRC_IFACE, "UUID"),
                        vlastnost(ch, GATT_CHRC_IFACE, "Flags"))
                       for ch in periferie.characteristics]
    assert charakteristiky == [
        ("/ukBaz/bluezero/service0001/char0001", TEPLOTA_UUID, ["read", "notify"]),
        ("/ukBaz/bluezero/service0001/char0002", VLHKOST_UUID, ["read", "notify"]),
        ("/ukBaz/bluezero/service0001/char0003", TLAK_UUID, ["read", "notify"]),
        ("/ukBaz/bluezero/service0002/char0001", STAV_UUID, ["read", "notify"]),
        ("/ukBaz/bluezero/service0002/char0002", NASTAVENI_UUID, ["read", "write", "notify"]),
        ("/ukBaz/bluezero/service0002/char0003", PRIKAZ_UUID, ["write"]),
    ]


def test_bez_adapteru_bluetooth_server_nevznikne(falesny_bluetooth, dum):
    falesny_bluetooth.adaptery = []
    with pytest.raises(Exception, match="No Bluetooth adapter found"):
        BleServer(dum.domek)  # program skončí a systemd ho spustí znovu


@pytest.mark.parametrize("modul, nazev_tridy, nazev", [
    ("bluezero.localGATT", "Characteristic", "ReadValue"),
    ("bluezero.localGATT", "Characteristic", "WriteValue"),
    ("bluezero.advertisement", "Advertisement", "Release"),
])
def test_oprava_bluezero_zachova_rozhrani_dbus(falesny_bluetooth, dum, modul, nazev_tridy, nazev):
    trida = getattr(sys.modules[modul], nazev_tridy)
    stara = getattr(trida, nazev)
    BleServer(dum.domek)
    nova = getattr(trida, nazev)
    assert nova is not stara
    # dbus-python hledá metodu podle jména a rozhraní, BlueZ ji volá s danými parametry
    assert nova.__name__ == nazev
    assert ((nova._dbus_interface, nova._dbus_in_signature, nova._dbus_out_signature)
            == (stara._dbus_interface, stara._dbus_in_signature, stara._dbus_out_signature))
    assert list(inspect.signature(nova).parameters) == list(inspect.signature(stara).parameters)


def test_kdyz_bluez_zrusi_inzerat_program_skonci(falesny_bluetooth, server, konce, caplog):
    # Bez inzerátu by tablet domek nenašel, i když registrace na začátku prošla. Program proto
    # skončí a systemd ho spustí znovu, takže se inzerát zaregistruje znovu.
    server.spust()
    assert konce == []
    falesny_bluetooth.zrus_inzerat()
    assert konce == [1]
    [zaznam] = [z for z in caplog.records if z.levelno == logging.ERROR]
    assert zaznam.getMessage() == ("BlueZ zrusil inzerat domu, tablet ho nenajde. Program konci, "
                                   "aby ho systemd spustil znovu.")


def test_kdyz_bluetoothd_prestane_bezet_program_skonci(falesny_bluetooth, server, konce, caplog):
    # Nový bluetoothd o registraci domku neví a tablet by domek nenašel
    server.spust()
    assert konce == []
    falesny_bluetooth.zmen_vlastnika("org.bluez", "")  # bluetoothd spadl nebo ho někdo zabil
    assert konce == [1]
    [zaznam] = [z for z in caplog.records if z.levelno == logging.ERROR]
    assert zaznam.getMessage() == ("BlueZ (bluetoothd) prestal bezet a registrace domu s nim "
                                   "zanikla. Program konci, aby ho systemd spustil znovu.")


def test_domek_spusteny_driv_nez_bluetoothd_neskonci(falesny_bluetooth, dum, konce):
    # Po zapnutí může domek začít dřív, než běží bluetoothd. První dotaz na BlueZ (seznam
    # adaptérů) ho přes D-Bus spustí a hlídání už dostane jeho vlastníka, ne prázdný název.
    falesny_bluetooth.vlastnici.clear()
    server = BleServer(dum.domek, ukonci=konce.append)
    server.spust()
    assert konce == []
    falesny_bluetooth.zmen_vlastnika("org.bluez", "")  # hlídání přitom hlídá dál
    assert konce == [1]


def test_dokud_bluez_bezi_program_nekonci(falesny_bluetooth, server, konce, caplog):
    # Hlídá se právě org.bluez. Prvního vlastníka hlídání dostane, až poběží smyčka GLib:
    # bluetoothd běží, takže se nic nestane.
    assert [h.nazev for h in falesny_bluetooth.hlidani] == ["org.bluez"]
    server.spust()
    assert falesny_bluetooth.smycka == []  # smyčka odpověď s vlastníkem doručila
    falesny_bluetooth.zmen_vlastnika("org.freedesktop.NetworkManager", "")  # jiný název nevadí
    assert konce == []
    assert [z for z in caplog.records if z.levelno >= logging.ERROR] == []


# --- Čtení ---

def test_cteni_vrati_aktualni_stav_nastaveni_a_venkovni_hodnoty(falesny_bluetooth, server, dum):
    stav = dum.domek.stav()
    assert json.loads(falesny_bluetooth.cti(STAV_UUID)) == stav
    assert json.loads(falesny_bluetooth.cti(NASTAVENI_UUID)) == Nastaveni().jako_dict()
    assert falesny_bluetooth.cti(TEPLOTA_UUID) == teplota_ess(14.5)
    assert falesny_bluetooth.cti(VLHKOST_UUID) == vlhkost_ess(stav["vlhkost"])
    assert falesny_bluetooth.cti(TLAK_UUID) == tlak_ess(stav["tlak"])


def test_pri_poruse_meteo_vrati_ess_nezname_hodnoty(falesny_bluetooth, server, dum):
    dum.meteo.selhava = True
    dum.domek.krok(DEN, 1.0)
    assert falesny_bluetooth.cti(TEPLOTA_UUID) == teplota_ess(None)
    assert falesny_bluetooth.cti(VLHKOST_UUID) == vlhkost_ess(None)
    assert falesny_bluetooth.cti(TLAK_UUID) == tlak_ess(None)


def test_bmp280_bez_vlhkosti_ma_v_ess_jen_nezname_vlhkost_a_nic_se_nehlasi(
        falesny_bluetooth, server, dum):
    # BMP280 vlhkost neměří: není to porucha, takže teplota a tlak platí a `chyby` je prázdné
    dum.meteo.vlhkost = None
    dum.domek.krok(DEN, 1.0)
    stav = json.loads(falesny_bluetooth.cti(STAV_UUID))
    assert (stav["vlhkost"], stav["chyby"]) == (None, [])
    assert (stav["t_venku"], stav["tlak"]) == (14.5, 1013.25)
    assert falesny_bluetooth.cti(VLHKOST_UUID) == b"\xff\xff"  # ESS: neznámá vlhkost
    assert falesny_bluetooth.cti(TEPLOTA_UUID) == teplota_ess(14.5)
    assert falesny_bluetooth.cti(TLAK_UUID) == tlak_ess(1013.25)
    for uuid in (STAV_UUID, VLHKOST_UUID):
        falesny_bluetooth.odebirej(uuid)
    server.posli_stav()
    assert falesny_bluetooth.notifikace(VLHKOST_UUID) == [b"\xff\xff"]
    assert notifikace_json(falesny_bluetooth, STAV_UUID)[0]["vlhkost"] is None


def test_cteni_po_castech_slozi_jednu_hodnotu_i_kdyz_se_stav_mezitim_zmeni(falesny_bluetooth,
                                                                            server, dum):
    # Při MTU 23 přijde na čtení jen prvních 22 bajtů a Android si zbytek vyžádá čtením
    # s offsetem. Stav se mezitím změní, ale všechny části musí být z téže hodnoty.
    cela = falesny_bluetooth.cti(STAV_UUID)
    dum.meteo.teplota = 30.0
    dum.domek.krok(DEN, 1.0)
    assert cela[:22] + falesny_bluetooth.cti(STAV_UUID, offset=22) == cela
    assert falesny_bluetooth.cti(STAV_UUID, offset=len(cela)) == b""
    # nové čtení od začátku už vrátí nový stav
    assert json.loads(falesny_bluetooth.cti(STAV_UUID))["t_venku"] == 30.0


def test_cteni_neposle_notifikaci(falesny_bluetooth, server):
    for uuid in (STAV_UUID, NASTAVENI_UUID, TEPLOTA_UUID):
        falesny_bluetooth.odebirej(uuid)
        falesny_bluetooth.cti(uuid)
        assert falesny_bluetooth.notifikace(uuid) == []


# --- Notifikace ---

def test_posli_stav_posle_stav_a_ess_s_venkovnimi_hodnotami(falesny_bluetooth, server, dum):
    for uuid in (STAV_UUID, NASTAVENI_UUID, TEPLOTA_UUID, VLHKOST_UUID, TLAK_UUID):
        falesny_bluetooth.odebirej(uuid)
    server.posli_stav()
    stav = dum.domek.stav()
    assert falesny_bluetooth.notifikace(STAV_UUID) == [zakoduj_json(stav)]
    assert falesny_bluetooth.notifikace(TEPLOTA_UUID) == [teplota_ess(stav["t_venku"])]
    assert falesny_bluetooth.notifikace(VLHKOST_UUID) == [vlhkost_ess(stav["vlhkost"])]
    assert falesny_bluetooth.notifikace(TLAK_UUID) == [tlak_ess(stav["tlak"])]
    assert falesny_bluetooth.notifikace(NASTAVENI_UUID) == []  # nastavení chodí jen po změně


def test_posli_nastaveni_posle_cele_nastaveni(falesny_bluetooth, server):
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    server.posli_nastaveni()
    assert notifikace_json(falesny_bluetooth, NASTAVENI_UUID) == [Nastaveni().jako_dict()]


def test_bez_odberu_se_hodnoty_vubec_nemeni(falesny_bluetooth, server):
    # Hodnota se mění jen u odebíraných charakteristik: jinak by každá změna byla zbytečný
    # signál D-Bus, který BlueZ stejně zahodí
    falesny_bluetooth.signaly.clear()
    server.posli_stav()
    server.posli_nastaveni()
    assert falesny_bluetooth.signaly == []


def test_odber_jde_vypnout_a_znovu_zapnout(falesny_bluetooth, server):
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.odebirej(STAV_UUID)  # BlueZ volá StartNotify při každém zapnutí
    server.posli_stav()
    assert len(falesny_bluetooth.notifikace(STAV_UUID)) == 1
    falesny_bluetooth.odebirej(STAV_UUID, zapnout=False)
    falesny_bluetooth.signaly.clear()
    server.posli_stav()
    assert [s for s in falesny_bluetooth.signaly if s[0] == STAV_UUID] == []
    falesny_bluetooth.odebirej(STAV_UUID)  # třeba po novém připojení
    server.posli_stav()
    assert len(falesny_bluetooth.notifikace(STAV_UUID)) == 1


# --- Zápis Nastavení ---

def test_zapis_nastaveni_posle_cele_nastaveni_a_stav_uz_se_zmenou(falesny_bluetooth, server, dum):
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    falesny_bluetooth.zapis(NASTAVENI_UUID, b'{"cil_den":26}')
    assert dum.domek.nastaveni.cil_den == 26.0
    assert nacti(dum.cesta).cil_den == 26.0  # uloženo do souboru
    # Jedna notifikace s celým novým nastavením, ne se zapsaným kusem JSON
    assert notifikace_json(falesny_bluetooth, NASTAVENI_UUID) == [dum.domek.nastaveni.jako_dict()]
    # Server po zápisu udělal krok, takže Stav už drží nový cíl, a ne ten z minulého kroku
    [stav] = notifikace_json(falesny_bluetooth, STAV_UUID)
    assert stav["cil"] == 26.0


def test_prijaty_zapis_se_zaloguje(falesny_bluetooth, server, caplog):
    with caplog.at_level(logging.INFO):
        falesny_bluetooth.zapis(NASTAVENI_UUID, b'{"cil_den":26}')
        falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":"zapnout"}')
    zpravy = [zaznam.getMessage() for zaznam in caplog.records]
    assert "Nastaveni zmeneno: {'cil_den': 26}" in zpravy
    assert "Proveden prikaz: {'svetlo': 'zapnout'}" in zpravy


@pytest.mark.parametrize("data", [
    b'{"cil_den":99}',
    b'{"cil_den":26,"cil_noc":99}',
    b'{"teplota":20}',
    b"[1,2]",
    b'{"cil_den":',
    b'{"rezim_termostatu":"\xff"}',
    b'{"cil_den":26}' + b" " * 499,  # 513 B
], ids=["mimo-rozsah", "jedna-spatna-hodnota-shodi-vse", "neznamy-klic", "koren-neni-objekt",
        "neplatny-json", "neplatne-utf8", "delsi-nez-512-b"])
def test_neplatny_zapis_nastaveni_skonci_chybou_a_nic_nezmeni(falesny_bluetooth, server, dum,
                                                              caplog, data):
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    with pytest.raises(falesny_bluetooth.DBusException) as chyba:
        falesny_bluetooth.zapis(NASTAVENI_UUID, data)
    # BlueZ chybu org.bluez.Error.Failed pošle tabletu jako chybu ATT
    assert chyba.value.get_dbus_name() == "org.bluez.Error.Failed"
    assert dum.domek.nastaveni == Nastaveni()
    assert not Path(dum.cesta).exists()  # nic se neuložilo
    assert falesny_bluetooth.notifikace(NASTAVENI_UUID) == []
    assert falesny_bluetooth.notifikace(STAV_UUID) == []
    [zaznam] = [z for z in caplog.records if z.levelno == logging.WARNING]
    assert zaznam.getMessage().startswith("Zapis do Nastaveni odmitnut: ")
    # server běží dál a další zápis projde
    falesny_bluetooth.zapis(NASTAVENI_UUID, b'{"cil_den":26}')
    assert dum.domek.nastaveni.cil_den == 26.0


# --- Zápis Příkazu ---

def test_zapis_prikazu_provede_prikaz_a_hned_posle_stav(falesny_bluetooth, server, dum):
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":"zapnout"}')
    assert dum.vystup.posledni.zapnuto  # světlo dostalo příkaz hned
    [stav] = notifikace_json(falesny_bluetooth, STAV_UUID)
    assert stav["svetlo"]["zap"] is True and stav["svetlo"]["rucne"] is True


def test_prikaz_svetla_s_barvou_posle_i_zmenene_nastaveni(falesny_bluetooth, server, dum):
    # Barva a jas z příkazu se ukládají do nastavení, takže tablet musí dostat i nové Nastavení
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":{"barva":[0,0,255],"jas":40}}')
    poradi = [u for (u, v, _, n) in falesny_bluetooth.signaly
              if v == "Value" and n and u in (STAV_UUID, NASTAVENI_UUID)]
    assert poradi == [NASTAVENI_UUID, STAV_UUID]  # Nastavení přijde před Stavem
    [nastaveni] = notifikace_json(falesny_bluetooth, NASTAVENI_UUID)
    assert (nastaveni["barva_svetla"], nastaveni["jas_svetla"]) == ([0, 0, 255], 40)
    [stav] = notifikace_json(falesny_bluetooth, STAV_UUID)
    assert (stav["svetlo"]["barva"], stav["svetlo"]["jas"]) == ([0, 0, 255], 40)


def test_prikaz_bez_zmeny_nastaveni_nastaveni_neposila(falesny_bluetooth, server, dum):
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    for prikaz in (b'{"svetlo":"zapnout"}', b'{"svetlo":"auto"}', b'{"svetlo":{"jas":100}}',
                   b'{"svetlo":{"barva":[255,255,255]}}'):
        # jas 100 a bílá jsou výchozí (bílá jako seznam proti n-tici v nastavení)
        falesny_bluetooth.zapis(PRIKAZ_UUID, prikaz)  # nastavení se nemění
    assert falesny_bluetooth.notifikace(NASTAVENI_UUID) == []


@pytest.mark.parametrize("data", [
    b'{"svetlo":"blikat"}',
    b"{}",
    b'"zapnout"',
    b'{"svetlo":',
    b'{"cas":"2026-10-20T14:03:00+02:00"}',
], ids=["nezname-svetlo", "zadny-klic", "koren-neni-objekt", "neplatny-json", "cas-s-pasmem"])
def test_neplatny_prikaz_skonci_chybou_a_nic_nezmeni(falesny_bluetooth, server, dum, caplog,
                                                     data):
    falesny_bluetooth.odebirej(STAV_UUID)
    falesny_bluetooth.odebirej(NASTAVENI_UUID)
    pred = dum.domek.stav()
    with pytest.raises(falesny_bluetooth.DBusException) as chyba:
        falesny_bluetooth.zapis(PRIKAZ_UUID, data)
    assert chyba.value.get_dbus_name() == "org.bluez.Error.Failed"
    assert dum.domek.stav() == pred
    assert falesny_bluetooth.notifikace(STAV_UUID) == []
    assert falesny_bluetooth.notifikace(NASTAVENI_UUID) == []
    [zaznam] = [z for z in caplog.records if z.levelno == logging.WARNING]
    assert zaznam.getMessage().startswith("Zapis do Prikazu odmitnut: ")


# --- Neočekávaná chyba v obsluze tabletu ---

def _rozbita(*argumenty, **pojmenovane):
    raise TypeError("chyba v programu")


@pytest.mark.parametrize("co", ["cteni-stavu", "cteni-ess", "cteni-nastaveni", "zapis-nastaveni",
                                "zapis-prikazu", "odber"])
def test_neocekavana_chyba_v_obsluze_se_zaloguje_a_tablet_dostane_chybu(
        falesny_bluetooth, server, dum, caplog, monkeypatch, co):
    # Výjimku z obsluhy volání D-Bus pošle dbus-python BlueZ jako chybovou odpověď a nikam ji
    # nezapíše. Server ji proto zapíše do logu i s výpisem volání a nechá projít dál.
    if co in ("cteni-stavu", "cteni-ess"):
        monkeypatch.setattr(dum.domek, "stav", _rozbita)
        akce = partial(falesny_bluetooth.cti, STAV_UUID if co == "cteni-stavu" else TLAK_UUID)
    elif co == "cteni-nastaveni":
        monkeypatch.setattr(dum.domek, "nastaveni", types.SimpleNamespace(jako_dict=_rozbita))
        akce = partial(falesny_bluetooth.cti, NASTAVENI_UUID)
    elif co == "zapis-nastaveni":
        monkeypatch.setattr(dum.domek, "zmen_nastaveni", _rozbita)
        akce = partial(falesny_bluetooth.zapis, NASTAVENI_UUID, b'{"cil_den":26}')
    elif co == "zapis-prikazu":
        monkeypatch.setattr(dum.domek, "proved_prikaz", _rozbita)
        akce = partial(falesny_bluetooth.zapis, PRIKAZ_UUID, b'{"svetlo":"zapnout"}')
    else:  # obsluha zapnutí notifikací se ptá charakteristiky na UUID
        monkeypatch.setattr(falesny_bluetooth.charakteristika(STAV_UUID), "Get", _rozbita)
        akce = partial(falesny_bluetooth.odebirej, STAV_UUID)
    with pytest.raises(TypeError, match="chyba v programu"):
        akce()
    [zaznam] = [z for z in caplog.records if z.levelno >= logging.ERROR]
    assert zaznam.getMessage().startswith("Neocekavana chyba pri obsluze Bluetooth")
    assert zaznam.exc_info[0] is TypeError  # v logu je i výpis volání


def test_odmitnuty_zapis_neni_neocekavana_chyba(falesny_bluetooth, server, caplog):
    # Odmítnutí je chyba D-Bus, kterou server vyhodil schválně: projde beze změny a v logu je
    # jen varování o odmítnutí, žádná chyba
    with pytest.raises(falesny_bluetooth.DBusException) as chyba:
        falesny_bluetooth.zapis(PRIKAZ_UUID, b'{"svetlo":"blikat"}')
    assert chyba.value.get_dbus_name() == "org.bluez.Error.Failed"
    assert [z.levelno for z in caplog.records] == [logging.WARNING]


# --- Připojení ---

def test_pripojeni_a_odpojeni_nastavuji_pripojeno(falesny_bluetooth, server, dum):
    assert dum.domek.pripojeno is False
    falesny_bluetooth.pripoj("AA:AA:AA:AA:AA:01")
    assert dum.domek.pripojeno is True
    # zařízení, které BlueZ dosud neznal: bluezero zavolá callback s jedním objektem Device
    falesny_bluetooth.pripoj("AA:AA:AA:AA:AA:02", nove=True)
    falesny_bluetooth.odpoj("AA:AA:AA:AA:AA:01")
    assert dum.domek.pripojeno is True  # druhé zařízení je pořád připojené
    falesny_bluetooth.odpoj("AA:AA:AA:AA:AA:02")
    assert dum.domek.pripojeno is False


def test_odpojeni_nezname_pripojeni_pocet_nepokazi(falesny_bluetooth, server, dum):
    # zařízení připojené ještě před spuštěním serveru se jen odpojí
    falesny_bluetooth.odpoj("AA:AA:AA:AA:AA:09")
    falesny_bluetooth.pripoj("AA:AA:AA:AA:AA:01")
    falesny_bluetooth.pripoj("AA:AA:AA:AA:AA:02")
    falesny_bluetooth.odpoj("AA:AA:AA:AA:AA:01")
    assert dum.domek.pripojeno is True


# --- Knihovny pro Raspberry ---

def test_server_spusteni_a_zkousky_jdou_nacist_bez_knihoven_raspberry():
    # Knihovny pro Raspberry se smějí importovat jen uvnitř funkcí. V novém procesu se jejich
    # import zakáže a moduly se načtou: kdyby některý importoval knihovnu už při načtení, spadl
    # by s ImportError, a to i tam, kde knihovny jsou.
    kod = ("import sys\n"
           "for nazev in ('bluezero', 'dbus', 'gi', 'gpiozero', 'smbus2', 'bme280', 'luma'):\n"
           "    sys.modules[nazev] = None\n"
           "import domek.ble_server, domek.casovac, domek.__main__, domek.over_soucastky\n")
    vysledek = subprocess.run([sys.executable, "-c", kod], cwd=KOREN, capture_output=True,
                              text=True, timeout=60)
    assert vysledek.returncode == 0, vysledek.stderr
