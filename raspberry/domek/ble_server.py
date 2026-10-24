"""
Bluetooth LE server domku: periferie "Termostat" se službami Environmental Sensing a Domek.

Server je postavený na knihovně bluezero stejně jako původní test `main.py`. Knihovny bluezero,
dbus a gi existují jen na Raspberry, proto se importují až uvnitř funkcí a metod a modul jde
načíst i na PC. Co server posílá a přijímá, popisuje `docs/ble-rozhrani.md`.

Čtení, zápisy, odběry i připojení volá bluezero ze smyčky GLib a ve stejné smyčce běží časovače
domku, které nastavuje `__main__`. Všechno tedy běží v jednom vlákně a nejsou potřeba zámky.
"""
from __future__ import annotations

import logging
import os
import time
from datetime import datetime
from functools import partial, wraps
from typing import Callable, NoReturn

from domek.ble_format import (DOMEK_UUID, ESS_UUID, NASTAVENI_UUID, NAZEV, PRIKAZ_UUID, STAV_UUID,
                              TEPLOTA_UUID, TLAK_UUID, VLHKOST_UUID, dekoduj_json, teplota_ess,
                              tlak_ess, vlhkost_ess, zakoduj_json)
from domek.jadro import Domek


def _oprav_bluezero(ukonci: Callable[[int], object] = os._exit) -> object:
    """Opraví v knihovně bluezero 0.9.1 to, co by porušilo smlouvu s aplikací nebo chod domku.

    1. ReadValue ve třídě `localGATT.Characteristic` nebere ohled na `options["offset"]` a vždy
       vrátí hodnotu od začátku. Klient s malým MTU si dlouhou hodnotu čte po částech (čtení
       s offsetem) a dostal by pořád dokola jen její začátek. Nová metoda zavolá read callback
       jen při offsetu 0, hodnotu si uloží a vrátí ji od offsetu. Všechny části jednoho čtení
       jsou tak z téže hodnoty, i když se stav mezi nimi změní.
    2. ReadValue i WriteValue ukládají hodnotu přes `Set("Value")` a tuto změnu BlueZ pošle
       odběratelům jako notifikaci. Po každém čtení by tak přišla notifikace navíc a po zápisu
       Nastavení notifikace se zapsaným JSON, tedy jen se změněnými klíči místo celého
       nastavení. Nové metody vlastnost "Value" nemění: notifikace posílá jen `set_value`
       v `BleServer.posli_stav` a `BleServer.posli_nastaveni`.
    3. Když BlueZ odmítne registraci GATT aplikace nebo inzerátu, bluezero to jen zapíše do logu
       (`GATT.register_app_error_cb`) nebo vypíše (`advertisement.register_ad_error_cb`)
       a program běží dál bez Bluetooth: časovače a displej fungují, ale tablet domek nenajde.
       Nové obsluhy chybu zalogují a zavolají `ukonci(1)`, aby program skončil a systemd ho
       spustil znovu. Bluezero si obsluhy bere z modulu až při registraci (v `publish`), takže
       stačí nahradit funkce v modulech.
    4. Registrace může zaniknout i za běhu a program by pak běžel dál bez Bluetooth. BlueZ inzerát
       zruší a zavolá jeho metodu Release třeba při ukončení bluetoothd nebo když zmizí adaptér;
       v bluezero tahle metoda nedělá nic. Když bluetoothd spadne nebo ho někdo zabije, nový
       bluetoothd o registraci domku nic neví. Nová metoda Release i hlídání vlastníka názvu
       `org.bluez` na systémové sběrnici (vlastník je prázdný, když bluetoothd neběží) proto
       chybu zalogují a zavolají `ukonci(1)`. Prvního vlastníka hlídání dostane, až poběží smyčka
       GLib; když už tehdy bluetoothd neběží, program skončí také.

    Výchozí `ukonci` je `os._exit` jako u `opakuj`, testy předají vlastní funkci. Volá se jednou
    za běh programu, z konstruktoru `BleServer`, ne při importu modulu, protože knihovny pro
    Raspberry se smějí importovat jen uvnitř funkcí. Vrací objekt hlídání `org.bluez`, který si
    volající drží po celý běh.
    """
    import dbus
    import dbus.service
    from bluezero import GATT, advertisement, constants, dbus_tools, localGATT

    # Názvy, parametry i signatury D-Bus jsou stejné jako v bluezero: podle nich BlueZ metody volá
    @dbus.service.method(constants.GATT_CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        offset = int(options.get("offset", 0))  # BlueZ offset posílá, jen když není nulový
        if offset == 0 or not hasattr(self, "_domek_cteni"):
            if self.read_callback:
                hodnota = self.read_callback()
            else:
                hodnota = self.props[constants.GATT_CHRC_IFACE]["Value"]
            self._domek_cteni = list(hodnota)  # hodnota posledního čtení od začátku
        return dbus.Array(self._domek_cteni[offset:], signature="y")

    @dbus.service.method(constants.GATT_CHRC_IFACE, in_signature="aya{sv}", out_signature="")
    def WriteValue(self, value, options):
        if self.write_callback:
            self.write_callback(dbus_tools.dbus_to_python(value),
                                dbus_tools.dbus_to_python(options))

    localGATT.Characteristic.ReadValue = ReadValue
    localGATT.Characteristic.WriteValue = WriteValue

    def obsluha_chyby_registrace(co: str) -> Callable[[object], None]:
        # dbus-python obsluhu chyby volá s jedním parametrem, chybou DBusException
        def obsluha(chyba: object) -> None:
            logging.error("BlueZ odmitl registraci %s: %s. Program konci, aby ho systemd "
                          "spustil znovu.", co, chyba)
            ukonci(1)
        return obsluha

    GATT.register_app_error_cb = obsluha_chyby_registrace("GATT aplikace")
    advertisement.register_ad_error_cb = obsluha_chyby_registrace("inzeratu")

    @dbus.service.method(constants.LE_ADVERTISEMENT_IFACE, in_signature="", out_signature="")
    def Release(self):
        logging.error("BlueZ zrusil inzerat domu, tablet ho nenajde. Program konci, aby ho "
                      "systemd spustil znovu.")
        ukonci(1)

    advertisement.Advertisement.Release = Release

    def zmena_vlastnika_bluez(vlastnik: str) -> None:
        # dbus-python volá s jedinečným názvem nového vlastníka, nebo "", když název nikdo nedrží
        if not vlastnik:
            logging.error("BlueZ (bluetoothd) prestal bezet a registrace domu s nim zanikla. "
                          "Program konci, aby ho systemd spustil znovu.")
            ukonci(1)

    return dbus.SystemBus().watch_name_owner(constants.BLUEZ_SERVICE_NAME, zmena_vlastnika_bluez)


def _s_logem(obsluha: Callable[..., object]) -> Callable[..., object]:
    """Obalí obsluhu čtení, zápisu nebo odběru, kterou bluezero volá z volání D-Bus od BlueZ.

    Výjimku z takové obsluhy dbus-python jen pošle BlueZ jako chybovou odpověď (tablet dostane
    chybu ATT) a nikam ji nezapíše, takže by chyba v programu nenechala v logu žádnou stopu.
    Obal ji proto zapíše i s výpisem volání a vyhodí dál: tablet dostane chybu jako dřív a domek
    běží dál. Chyba D-Bus projde beze změny, tu server vyhazuje schválně při odmítnutí zápisu
    a zapisuje ji `_odmitni`. `wraps` zachová podpis obsluhy pro `inspect.signature`, podle
    kterého původní ReadValue v bluezero pozná, s kolika parametry obsluhu volat.
    """
    import dbus.exceptions

    @wraps(obsluha)
    def obal(*argumenty: object) -> object:
        try:
            return obsluha(*argumenty)
        except dbus.exceptions.DBusException:
            raise
        except Exception:
            logging.exception("Neocekavana chyba pri obsluze Bluetooth, tablet dostane chybu "
                              "a dum bezi dal.")
            raise
    return obal


def _odmitni(charakteristika: str, chyba: ValueError) -> NoReturn:
    """Zaloguje odmítnutý zápis a vyhodí chybu D-Bus, kterou BlueZ tabletu vrátí jako chybu ATT.

    Text chyby se přes Bluetooth nepřenáší (chyba ATT nese jen kód), proto se zapíše do logu.
    """
    import dbus.exceptions

    zprava = f"Zapis do {charakteristika} odmitnut: {chyba}"
    logging.warning(zprava)
    raise dbus.exceptions.DBusException(zprava, name="org.bluez.Error.Failed") from chyba


class BleServer:
    """Periferie "Termostat": služba ESS s venkovními hodnotami a služba Domek se Stavem,
    Nastavením a Příkazem.

    Konstruktor periferii připraví a `spust` ji zveřejní a spustí smyčku GLib. `posli_stav`
    pošle notifikaci Stavu a hodnot ESS a `posli_nastaveni` notifikaci Nastavení, obojí jen
    charakteristikám, které někdo odebírá. Čtení vrací aktuální hodnotu. Zápis Nastavení
    a Příkazu se zpracuje hned; neplatný se odmítne chybou ATT a nic nezmění. Neočekávaná chyba
    při čtení, zápisu nebo odběru se zapíše do logu (`_s_logem`). Když BlueZ odmítne registraci
    služeb nebo inzerátu, nebo když registrace za běhu zanikne (BlueZ zruší inzerát, bluetoothd
    přestane běžet), zavolá se `ukonci(1)` (výchozí `os._exit`, viz `_oprav_bluezero`).
    """

    def __init__(self, domek: Domek, *, ukonci: Callable[[int], object] = os._exit) -> None:
        from bluezero import adapter, peripheral

        self._domek = domek
        self._pripojenych = 0  # kolik zařízení je právě připojeno
        self._odebirane: dict[str, object] = {}  # UUID -> charakteristika se zapnutými notifikacemi
        # Seznam adaptérů dá BlueZ, a kdyby bluetoothd ještě neběžel, D-Bus se ho pokusí spustit.
        # Bluezero se proto opravuje (a hlídání org.bluez zavádí) až potom: první odpověď hlídání
        # tak nepochází z doby před spuštěním bluetoothd. Opravy platí pro celé třídy a moduly
        # a jsou hotové před `publish`. Hlídání musí žít, dokud běží program.
        self._adresa = list(adapter.Adapter.available())[0].address
        self._hlidani_bluez = _oprav_bluezero(ukonci)
        ble = peripheral.Peripheral(self._adresa, local_name=NAZEV)
        # Všechny obsluhy, které bluezero volá při čtení, zápisu a odběru, jsou obalené `_s_logem`
        zmena_odberu = _s_logem(self._zmena_odberu)

        # Služba 1: Environmental Sensing s venkovními hodnotami z BME280
        ble.add_service(srv_id=1, uuid=ESS_UUID, primary=True)
        for chr_id, uuid in enumerate((TEPLOTA_UUID, VLHKOST_UUID, TLAK_UUID), start=1):
            ble.add_characteristic(srv_id=1, chr_id=chr_id, uuid=uuid, value=[],
                                   notifying=False, flags=["read", "notify"],
                                   read_callback=_s_logem(partial(self._precti, uuid)),
                                   notify_callback=zmena_odberu)

        # Služba 2: Domek. Zápis je jen s odpovědí ("write"), aby se tablet dozvěděl o odmítnutí.
        ble.add_service(srv_id=2, uuid=DOMEK_UUID, primary=True)
        ble.add_characteristic(srv_id=2, chr_id=1, uuid=STAV_UUID, value=[], notifying=False,
                               flags=["read", "notify"],
                               read_callback=_s_logem(partial(self._precti, STAV_UUID)),
                               notify_callback=zmena_odberu)
        ble.add_characteristic(srv_id=2, chr_id=2, uuid=NASTAVENI_UUID, value=[],
                               notifying=False, flags=["read", "write", "notify"],
                               read_callback=_s_logem(self._precti_nastaveni),
                               write_callback=_s_logem(self._zapis_nastaveni),
                               notify_callback=zmena_odberu)
        ble.add_characteristic(srv_id=2, chr_id=3, uuid=PRIKAZ_UUID, value=[], notifying=False,
                               flags=["write"], write_callback=_s_logem(self._zapis_prikazu))

        # Do reklamního paketu (max 31 bajtů) jde jen krátké UUID 0x181A, jinak by se tam vedle
        # 128bitového UUID nevešel název. Obě služby v GATT zůstávají primární.
        ble.primary_services = [ESS_UUID]
        ble.on_connect = self._pripojeno
        ble.on_disconnect = self._odpojeno
        self._periferie = ble

    def spust(self) -> None:
        """Zveřejní služby a inzerát a spustí smyčku GLib. Blokuje, dokud smyčka běží."""
        logging.info('BLE periferie "%s" bezi na adapteru %s.', NAZEV, self._adresa)
        self._periferie.publish()

    def posli_stav(self) -> None:
        """Pošle odběratelům notifikaci Stavu a venkovních hodnot ESS, všechno z jednoho stavu."""
        for uuid, hodnota in self._hodnoty_ze_stavu().items():
            if uuid in self._odebirane:
                self._odebirane[uuid].set_value(list(hodnota))

    def posli_nastaveni(self) -> None:
        """Pošle odběratelům notifikaci Nastavení se všemi klíči."""
        if NASTAVENI_UUID in self._odebirane:
            self._odebirane[NASTAVENI_UUID].set_value(self._precti_nastaveni())

    def _hodnoty_ze_stavu(self) -> dict[str, bytes]:
        """Hodnoty Stavu a charakteristik ESS podle UUID, všechny z jednoho volání `stav()`."""
        stav = self._domek.stav()
        return {
            STAV_UUID: zakoduj_json(stav),
            TEPLOTA_UUID: teplota_ess(stav["t_venku"]),
            VLHKOST_UUID: vlhkost_ess(stav["vlhkost"]),
            TLAK_UUID: tlak_ess(stav["tlak"]),
        }

    def _precti(self, uuid: str) -> list[int]:
        """Read callback Stavu a charakteristik ESS: aktuální hodnota jako seznam bajtů."""
        return list(self._hodnoty_ze_stavu()[uuid])

    def _precti_nastaveni(self) -> list[int]:
        """Read callback Nastavení: celé aktuální nastavení jako JSON."""
        return list(zakoduj_json(self._domek.nastaveni.jako_dict()))

    def _zapis_nastaveni(self, hodnota: bytearray, moznosti: dict) -> None:
        """Tablet zapsal do Nastavení JSON se změnami. Neplatný zápis se odmítne a nic nezmění.

        Po změně proběhne krok domku, aby poslaný Stav už podle nového nastavení počítal cíl
        a topení, a ne aby nový režim ukazoval se starým cílem z minulého kroku.
        """
        try:
            zmeny = dekoduj_json(bytes(hodnota))
            self._domek.zmen_nastaveni(zmeny)
        except ValueError as chyba:  # i neplatné UTF-8, neplatný JSON a ChybaNastaveni
            _odmitni("Nastaveni", chyba)
        logging.info("Nastaveni zmeneno: %s", zmeny)
        self._domek.krok(datetime.now(), time.monotonic())
        self.posli_nastaveni()
        self.posli_stav()

    def _zapis_prikazu(self, hodnota: bytearray, moznosti: dict) -> None:
        """Tablet zapsal Příkaz. Provede se hned a Stav s výsledkem se pošle ještě teď.

        Příkaz světla s barvou nebo jasem je ukládá do nastavení, a když se tím nastavení
        změnilo, pošle se i Nastavení.
        """
        pred = self._domek.nastaveni
        try:
            prikaz = dekoduj_json(bytes(hodnota))
            self._domek.proved_prikaz(prikaz, time.monotonic())
        except ValueError as chyba:  # i neplatné UTF-8, neplatný JSON a ChybaPrikazu
            _odmitni("Prikazu", chyba)
        logging.info("Proveden prikaz: %s", prikaz)
        if self._domek.nastaveni != pred:
            self.posli_nastaveni()
        self.posli_stav()

    def _zmena_odberu(self, zapnuto: bool, charakteristika: object) -> None:
        """Klient zapnul nebo vypnul notifikace; odebírané charakteristiky se pamatují podle UUID.

        Bluezero 0.9.1 tuto funkci může zavolat se zapnuto=True víckrát po sobě: kontrola
        "už notifikuje" ve StartNotify porovnává dbus.Boolean přes `is True`, a ta tedy nikdy
        neplatí. Opakované zapnutí nic nemění.
        """
        from bluezero import constants

        uuid = str(charakteristika.Get(constants.GATT_CHRC_IFACE, "UUID"))
        if zapnuto:
            self._odebirane[uuid] = charakteristika
        else:
            self._odebirane.pop(uuid, None)
        logging.info("Odber %s %s.", uuid, "zapnut" if zapnuto else "vypnut")

    def _pripojeno(self, adapter_adresa: object, zarizeni_adresa: object = None) -> None:
        """Připojilo se zařízení. Domek pak na displeji ukáže, že je připojený tablet.

        Bluezero 0.9.1 tuto funkci volá s adresou adaptéru a zařízení, ale když se připojí
        zařízení, které BlueZ dosud neznal, jen s jedním objektem Device (adapter.py,
        `_interfaces_added`). Druhý parametr proto má výchozí hodnotu.
        """
        self._pripojenych += 1
        self._domek.pripojeno = True
        logging.info("Pripojeno zarizeni %s, pripojenych je %d.",
                     zarizeni_adresa or "dosud nezname", self._pripojenych)

    def _odpojeno(self, adapter_adresa: object, zarizeni_adresa: object) -> None:
        """Odpojilo se zařízení. Až se odpojí všechna, domek ukáže, že tablet připojený není.

        Počet neklesne pod nulu: odpojit se může i zařízení, které se připojilo ještě před
        spuštěním serveru.
        """
        self._pripojenych = max(0, self._pripojenych - 1)
        self._domek.pripojeno = self._pripojenych > 0
        logging.info("Odpojeno zarizeni %s, pripojenych je %d.", zarizeni_adresa,
                     self._pripojenych)
