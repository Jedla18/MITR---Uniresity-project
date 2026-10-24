"""
Společné pomůcky testů: náhrady knihoven jen pro Raspberry (smbus2, bme280, gpiozero, luma,
bluezero, dbus a gi).

Knihovny na PC nejsou a nesmí se do `.venv` instalovat, takže ovladače a Bluetooth server se
zkouší s falešnými moduly vloženými do `sys.modules`. Signatury odpovídají skutečným knihovnám
(smbus2 0.6.1, RPi.bme280 0.2.4 a gpiozero 2.0.1, jejichž zdroje se četly při psaní ovladačů čidel,
luma.core 2.6.0 s luma.oled 3.16.0, jejichž zdroje se četly při psaní ovladače displeje, bluezero 0.9.1,
jehož zdroje se četly při psaní Bluetooth serveru, `RGBLED` z gpiozero 2.0.1, jehož zdroj se četl při
psaní ovladače RGB LED,
a `Advertisement.Release` z bluezero 0.9.1 se `SystemBus.watch_name_owner` z dbus-python 1.3.2,
které se četly při závěrečné opravě, a `write_byte_data` s `read_word_data` ze smbus2 0.6.1,
které se četly při psaní ovladače TSL2561): kód, který by je volal špatně, tak spadne i v testu.
"""
import inspect
import logging
import sys
import types

import pytest


class FalesnyHardware:
    """Falešná I2C čidla BME280 a TSL2561, PIR, LED, LED s PWM a RGB LED a displej SSD1306.
    Fixture `falesny_hardware` je zapojí místo knihoven.

    `volani` je záznam toho, co ovladač zavolal. `porucha` je množina míst, kde hardware selže:
    "sbernice" (otevření sběrnice), "id" (čtení registru čipu), "kalibrace", "mereni",
    "zapnuti" (zápis do registru TSL2561), "kanaly" (čtení kanálů TSL2561) a "gpio" (PIR, LED,
    LED s PWM i RGB LED)
    selžou s OSError. U displeje selže "sbernice" i "displej" (neodpovídá na příkaz)
    a "inicializace" (nejde vytvořit zařízení ssd1306) s výjimkou z `luma.core.error`, která
    OSError není, "text" (kreslení textu) s ValueError a "odeslani" (poslání obrazu na displej)
    s OSError. Ostatní atributy říkají, co čidla "naměří" a co se ukázalo na displeji.
    """

    def __init__(self):
        self.volani = []
        self.porucha = set()
        self.chip_id = 0x60               # co vrátí čtení registru 0xD0
        self.mereni = (21.5, 40.0, 1005.5)  # teplota, vlhkost, tlak
        self.kanaly = (1000, 100)         # kanály CH0 a CH1, které TSL2561 vrátí při čtení
        self.pohyb = [False]              # hodnoty PIR po řadě, poslední se opakuje
        self.cteni_pohybu = 0
        self.vstupy = []                  # vytvořená DigitalInputDevice
        self.ledky = []                   # vytvořené LED
        self.rgb_ledky = []               # vytvořené RGBLED
        self.snimky = []                  # obrazy poslané na displej, každý jako seznam kreslení
        #                                   (poloha, text, barva) v pořadí, v jakém se kreslilo
    def _over(self, misto):
        if misto in self.porucha:
            if misto == "sbernice":
                raise FileNotFoundError(2, "No such file or directory", "/dev/i2c-1")
            raise OSError(121, "Remote I/O error")

    def moduly(self):
        """Falešné moduly pod jmény, pod jakými je ovladače importují."""
        hw = self

        class SMBus:
            def __init__(self, bus=None, force=False):
                hw.volani.append(("SMBus", bus))
                hw._over("sbernice")
                self.cislo = bus

            def __enter__(self):
                return self

            def __exit__(self, typ, hodnota, stopa):
                self.close()

            def close(self):
                hw.volani.append(("close", self.cislo))

            def read_byte_data(self, i2c_addr, register, force=None):
                hw.volani.append(("read_byte_data", i2c_addr, register))
                hw._over("id")
                return hw.chip_id

            def write_byte_data(self, i2c_addr, register, value, force=None):
                hw.volani.append(("write_byte_data", i2c_addr, register, value))
                assert 0 <= register <= 0xFF and 0 <= value <= 0xFF, "registr i hodnota jsou bajt"
                hw._over("zapnuti")

            def read_word_data(self, i2c_addr, register, force=None):
                # Skutečná metoda vrací 16bitové slovo, nižší bajt čidla je první (little-endian)
                hw.volani.append(("read_word_data", i2c_addr, register))
                hw._over("kanaly")
                kanal = {0xAC: 0, 0xAE: 1}[register]  # CH0 a CH1; jiný registr by čip nevydal
                return hw.kanaly[kanal]

        def load_calibration_params(bus, address=0x76):
            assert isinstance(bus, SMBus)
            hw.volani.append(("load_calibration_params", bus.cislo, address))
            hw._over("kalibrace")
            return "kalibrace"  # vzorek, podle kterého se pozná, že ho sample dostal

        def sample(bus, address=0x76, compensation_params=None, sampling=1):
            assert isinstance(bus, SMBus)
            hw.volani.append(("sample", bus.cislo, address, compensation_params))
            hw._over("mereni")
            teplota, vlhkost, tlak = hw.mereni
            return types.SimpleNamespace(temperature=teplota, humidity=vlhkost, pressure=tlak)

        class DigitalInputDevice:
            def __init__(self, pin=None, *, pull_up=False, active_state=None, bounce_time=None,
                         pin_factory=None):
                self.pin = pin
                self.pull_up = pull_up
                hw.vstupy.append(self)

            @property
            def is_active(self):
                hw._over("gpio")
                i = min(hw.cteni_pohybu, len(hw.pohyb) - 1)
                hw.cteni_pohybu += 1
                return hw.pohyb[i]

        class LED:
            def __init__(self, pin=None, *, active_high=True, initial_value=False,
                         pin_factory=None):
                self.pin = pin
                self.historie = [bool(initial_value)]  # všechny hodnoty, na které se LED nastavila
                hw.ledky.append(self)

            @property
            def value(self):
                return int(self.historie[-1])

            @value.setter
            def value(self, hodnota):
                hw._over("gpio")
                self.historie.append(bool(hodnota))

        class OutputDeviceBadValue(ValueError):  # gpiozero.exc: dědí i od OutputDeviceError
            pass

        class GPIOPinMissing(ValueError):  # gpiozero.exc
            pass

        class RGBLED:
            # gpiozero 2.0.1: RGBLED(red, green, blue, *, active_high=True, initial_value=(0, 0, 0),
            # pwm=True, pin_factory=None). Bez některého pinu vyhodí GPIOPinMissing. `value` je
            # trojice (červená, zelená, modrá), každá složka od 0 do 1 (s pwm=False jen 0 nebo 1).
            # Složka mimo rozsah vyhodí OutputDeviceBadValue dřív, než se sáhne na pin, takže se
            # nezapíše ani složka před ní. Po vytvoření má LED barvu `initial_value`.
            def __init__(self, red=None, green=None, blue=None, *, active_high=True,
                         initial_value=(0, 0, 0), pwm=True, pin_factory=None):
                if not all(pin is not None for pin in (red, green, blue)):
                    raise GPIOPinMissing("red, green, and blue pins must be provided")
                self.piny = (red, green, blue)
                self.active_high = active_high
                self.pwm = pwm
                self.historie = []  # všechny barvy (trojice), na které se LED nastavila
                self.value = initial_value
                hw.rgb_ledky.append(self)

            @property
            def value(self):
                return self.historie[-1]

            @value.setter
            def value(self, hodnota):
                for slozka in hodnota:
                    if not 0 <= slozka <= 1:
                        raise OutputDeviceBadValue(
                            "each RGB color component must be between 0 and 1")
                    if not self.pwm and slozka not in (0, 1):
                        raise OutputDeviceBadValue(
                            "each RGB color component must be 0 or 1 with non-PWM RGBLEDs")
                if self.historie:  # vytvoření LED se přes `hw._over` nezkouší
                    hw._over("gpio")
                self.historie.append(tuple(float(slozka) for slozka in hodnota))

        # Knihovny luma: chyby displeje nejsou OSError, ale výjimky z luma.core.error, které od
        # Exception dědí přes vlastní třídu Error. Ovladač je proto musí převést sám.
        class Error(Exception):
            pass

        class DeviceNotFoundError(Error):
            pass

        def over_luma(misto, zprava):
            try:
                hw._over(misto)
            except OSError:  # skutečná knihovna převádí I/O chybu na vlastní výjimku
                raise DeviceNotFoundError(zprava) from None

        class i2c:
            def __init__(self, bus=None, port=1, address=0x3C):
                hw.volani.append(("i2c", port, address))
                over_luma("sbernice", f"I2C device not found: /dev/i2c-{port}")
                self.adresa = address

            def command(self, *cmd):
                hw.volani.append(("command",) + cmd)
                over_luma("displej", f"I2C device not found on address: 0x{self.adresa:02X}")

        class ssd1306:
            def __init__(self, serial_interface=None, width=128, height=64, rotate=0, **kwargs):
                assert isinstance(serial_interface, i2c)
                hw.volani.append(("ssd1306", width, height))
                over_luma("inicializace", "I2C device not found on init")

            def display(self, image):
                hw.volani.append(("display",))
                hw._over("odeslani")
                hw.snimky.append(image.kresleni)

        class Obraz:  # náhrada PIL.Image: pamatuje si, co se do něj kreslilo
            def __init__(self):
                self.kresleni = []

        class Kresleni:  # náhrada PIL.ImageDraw.ImageDraw
            def __init__(self, obraz):
                self._obraz = obraz

            def text(self, xy, text, fill=None, font=None, anchor=None, spacing=4, align="left",
                     *args, **kwargs):
                if "text" in hw.porucha:
                    raise ValueError("Falesna chyba pri kresleni textu")
                self._obraz.kresleni.append((xy, text, fill))

        class canvas:
            def __init__(self, device, background=None, dither=False):
                self.device = device
                self.image = Obraz()

            def __enter__(self):
                return Kresleni(self.image)

            def __exit__(self, typ, hodnota, stopa):
                if typ is None:  # jako skutečná knihovna: obraz se pošle jen po úspěšném kreslení
                    self.device.display(self.image)
                return False

        def modul(nazev, **obsah):
            novy = types.ModuleType(nazev)
            novy.__dict__.update(obsah)
            return novy

        return {
            "smbus2": modul("smbus2", SMBus=SMBus),
            "bme280": modul("bme280", load_calibration_params=load_calibration_params,
                            sample=sample),
            "gpiozero": modul("gpiozero", DigitalInputDevice=DigitalInputDevice, LED=LED,
                              RGBLED=RGBLED,
                              OutputDeviceBadValue=OutputDeviceBadValue),
            "luma": modul("luma"),
            "luma.core": modul("luma.core"),
            "luma.core.error": modul("luma.core.error", Error=Error,
                                     DeviceNotFoundError=DeviceNotFoundError),
            "luma.core.interface": modul("luma.core.interface"),
            "luma.core.interface.serial": modul("luma.core.interface.serial", i2c=i2c),
            "luma.core.render": modul("luma.core.render", canvas=canvas),
            "luma.oled": modul("luma.oled"),
            "luma.oled.device": modul("luma.oled.device", ssd1306=ssd1306),
        }


@pytest.fixture
def falesny_hardware(monkeypatch):
    hw = FalesnyHardware()
    for nazev, modul in hw.moduly().items():
        monkeypatch.setitem(sys.modules, nazev, modul)
    return hw


GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"
DEVICE_INTERFACE = "org.bluez.Device1"
LE_ADVERTISEMENT_IFACE = "org.bluez.LEAdvertisement1"
BLUEZ_SERVICE_NAME = "org.bluez"


class FalesnyBluetooth:
    """Falešné knihovny bluezero 0.9.1, dbus (dbus-python) a gi (PyGObject) pro Bluetooth server.
    Fixture `falesny_bluetooth` je zapojí místo skutečných.

    Třídy napodobují skutečné zdroje bluezero 0.9.1 (localGATT.py, peripheral.py, adapter.py,
    tools.py a dbus_tools.py), jen bez D-Bus a BlueZ:

    - `localGATT.Characteristic` má metody skutečné třídy i s jejími chybami: ReadValue nebere
      ohled na offset a ReadValue i WriteValue ukládají hodnotu přes `Set`. Test tak pozná,
      jestli je server opravil.
    - Každé `Set` vlastnosti charakteristiky se zapíše do `signaly` jako signál PropertiesChanged.
      Změnu vlastnosti "Value" u charakteristiky se zapnutými notifikacemi pošle BlueZ
      odběratelům jako notifikaci, proto `notifikace(uuid)` vrací právě tyto změny.
    - Připojení a odpojení volá callbacky jako bluezero: podle počtu jejich parametrů, a když se
      připojí zařízení, které BlueZ dosud neznal, s jedním objektem Device.
    - `publish` jako skutečný zaregistruje aplikaci (`GATT.GattManager`) a inzerát
      (`advertisement.AdvertisingManager`). Obsluhy odpovědi se hledají v modulech `GATT`
      a `advertisement` až při registraci, stejně jako ve skutečném bluezero. BlueZ registraci
      přijme, nebo odmítne chybou z `chyby_registrace`, a náhrada odpoví hned; skutečný
      `publish` by pak blokoval ve smyčce GLib, náhrada jen doručí, co ve `smycka` čeká na
      smyčku, a vrátí se.
    - `advertisement.Advertisement.Release` jako ve skutečném bluezero nedělá nic.
    - `dbus.SystemBus()` vrací jako skutečné dbus-python pořád stejné sdílené spojení. Jeho
      `watch_name_owner` jako skutečné pošle GetNameOwner a odpověď s vlastníkem názvu
      (z `vlastnici`, "" když ho nikdo nedrží) dá callbacku až smyčka GLib; potom ho callback
      dostane při každém signálu NameOwnerChanged (`zmen_vlastnika`).
    - `GLib.timeout_add` časovač jen zapíše do `casovace`, spouští ho test sám.

    Metody `cti`, `zapis`, `odebirej`, `pripoj` a `odpoj` dělají to, co by udělal BlueZ, když
    tablet čte, zapisuje, zapíná notifikace (zápis do CCCD) nebo se připojuje. `zrus_inzerat`
    a `zmen_vlastnika` dělají to, co BlueZ a D-Bus, když inzerát zanikne nebo bluetoothd skončí.
    """

    ADRESA_ADAPTERU = "B8:27:EB:12:34:56"

    def __init__(self):
        self.adaptery = [self.ADRESA_ADAPTERU]  # adresy adaptérů, které vrátí Adapter.available
        self.periferie = []  # vytvořené Peripheral; testy pracují s poslední
        self.casovace = []   # (interval v ms, funkce) z GLib.timeout_add, v pořadí nastavení
        self.signaly = []    # PropertiesChanged charakteristik: (uuid, vlastnost, hodnota,
        #                      jestli měla charakteristika v tu chvíli zapnuté notifikace)
        self.chyby_registrace = {}  # "aplikace" nebo "inzerat" -> chyba, kterou BlueZ odmítne
        #                             registraci; co tu není, BlueZ přijme
        self.vlastnici = {BLUEZ_SERVICE_NAME: ":1.7"}  # název na systémové sběrnici -> jedinečný
        #                             název spojení, které ho drží (org.bluez drží bluetoothd)
        self.hlidani = []    # hlídání vlastníků názvů z watch_name_owner, v pořadí zavedení
        self.smycka = []     # odpovědi D-Bus, které doručí smyčka GLib, až poběží (v publish)
        self.sbernice = None  # sdílené spojení, které vrací dbus.SystemBus()

    def moduly(self):
        """Falešné moduly pod jmény, pod jakými je server importuje."""
        bt = self

        # --- dbus (dbus-python) ---
        class Array(list):
            def __init__(self, iterable=(), signature=None, variant_level=0):
                super().__init__(iterable)
                self.signature = signature

        class Dictionary(dict):
            def __init__(self, mapping=(), signature=None, variant_level=0):
                super().__init__(mapping)
                self.signature = signature

        class Boolean(int):
            # Jako skutečný dbus.Boolean dědí od int, ne od bool, takže `Boolean(True) is True`
            # neplatí. Na tom závisí kontrola "už notifikuje" ve StartNotify v bluezero.
            def __new__(cls, value=False, variant_level=0):
                return super().__new__(cls, bool(value))

        class DBusException(Exception):
            def __init__(self, *args, **kwargs):
                name = kwargs.pop("name", None)
                if kwargs:
                    raise TypeError("DBusException does not take keyword arguments: "
                                    + ", ".join(kwargs))
                super().__init__(*args)
                self._dbus_error_name = name

            def __str__(self):
                text = Exception.__str__(self)
                if self._dbus_error_name is not None:
                    return f"{self._dbus_error_name}: {text}"
                return text

            def get_dbus_name(self):
                return self._dbus_error_name

            def get_dbus_message(self):
                return Exception.__str__(self)

        def method(dbus_interface, in_signature=None, out_signature=None, **kwargs):
            # Skutečný dekorátor funkci nemění, jen jí přidá údaje pro D-Bus
            def dekorator(funkce):
                funkce._dbus_is_method = True
                funkce._dbus_interface = dbus_interface
                funkce._dbus_in_signature = in_signature
                funkce._dbus_out_signature = out_signature
                return funkce
            return dekorator

        class NameOwnerWatch:
            # dbus.bus.NameOwnerWatch: `cancel` hlídání zruší
            def __init__(self, bus_name, callback):
                self.nazev, self.callback, self.zruseno = bus_name, callback, False

            def cancel(self):
                self.zruseno = True

        class SystemBus:
            # Jako skutečný dbus.SystemBus(): bez private=True je to pořád stejné sdílené spojení
            def __new__(cls, private=False, mainloop=None):
                if private:
                    return super().__new__(cls)
                if bt.sbernice is None:
                    bt.sbernice = super().__new__(cls)
                return bt.sbernice

            def watch_name_owner(self, bus_name, callback):
                # GetNameOwner se pošle hned, ale odpověď (vlastník, nebo "" pro chybu
                # NameHasNoOwner) callback dostane až ze smyčky GLib
                hlidani = NameOwnerWatch(bus_name, callback)
                bt.hlidani.append(hlidani)
                vlastnik = bt.vlastnici.get(bus_name, "")
                bt.smycka.append(lambda: None if hlidani.zruseno else callback(vlastnik))
                return hlidani

        # --- bluezero.dbus_tools a bluezero.tools (get_fn_parameters a adresa zařízení z cesty
        #     používá jen náhrada adaptéru) ---
        def dbus_to_python(data):
            if isinstance(data, Boolean):
                return bool(data)
            if isinstance(data, Array):
                if data.signature == "y":
                    return bytearray(data)
                return [dbus_to_python(hodnota) for hodnota in data]
            if isinstance(data, dict):
                return {dbus_to_python(klic): dbus_to_python(hodnota)
                        for klic, hodnota in data.items()}
            return data

        def get_fn_parameters(fn):
            pocet = len(inspect.getfullargspec(fn).args)
            if inspect.ismethod(fn):
                pocet -= 1
            return pocet

        def adresa_zarizeni_z_cesty(cesta):  # dbus_tools.get_device_address_from_dbus_path
            for cast in cesta.split("/"):
                if cast.startswith("dev_"):
                    return cast.replace("dev_", "").replace("_", ":")
            return ""

        # --- bluezero.localGATT ---
        class Service:
            def __init__(self, service_id, uuid, primary):
                self.path = f"/ukBaz/bluezero/service{service_id:04d}"
                self.props = {"org.bluez.GattService1": {"UUID": uuid, "Primary": primary}}

        class Characteristic:
            def __init__(self, service_id, characteristic_id, uuid, value, notifying, flags,
                         read_callback=None, write_callback=None, notify_callback=None):
                self.read_callback = read_callback
                self.write_callback = write_callback
                self.notify_callback = notify_callback
                service_path = f"/ukBaz/bluezero/service{service_id:04d}"
                self.path = f"{service_path}/char{characteristic_id:04d}"
                self.props = {GATT_CHRC_IFACE: {
                    "UUID": uuid, "Service": service_path,
                    "Value": Array(value, signature="y"), "Notifying": notifying, "Flags": flags}}
                for prop in self.props[GATT_CHRC_IFACE].keys():
                    self.Set(GATT_CHRC_IFACE, prop, self.props[GATT_CHRC_IFACE][prop])

            @property
            def is_notifying(self):
                return bool(self.Get(GATT_CHRC_IFACE, "Notifying"))

            def set_value(self, value):
                self.Set(GATT_CHRC_IFACE, "Value", Array(value, signature="y"))

            @method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
            def GetAll(self, interface_name):
                if interface_name != GATT_CHRC_IFACE:
                    raise DBusException(name="org.freedesktop.DBus.Error.InvalidArgs")
                return self.props[interface_name]

            @method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
            def Get(self, interface_name, property_name):
                return self.GetAll(interface_name)[property_name]

            @method("org.freedesktop.DBus.Properties", in_signature="ssv", out_signature="")
            def Set(self, interface_name, property_name, value):
                if property_name not in self.props[GATT_CHRC_IFACE]:
                    raise DBusException("no such property " + property_name,
                                        name=GATT_CHRC_IFACE + ".UnknownProperty")
                vlastnosti = self.props[GATT_CHRC_IFACE]
                vlastnosti[property_name] = value
                bt.signaly.append((vlastnosti["UUID"], property_name, value,
                                   bool(vlastnosti["Notifying"])))

            @method(GATT_CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
            def ReadValue(self, options):
                # Původní bluezero: offset nečte a hodnotu ukládá přes Set
                if self.read_callback:
                    if len(inspect.signature(self.read_callback).parameters) == 1:
                        value = self.read_callback(dbus_to_python(options))
                    else:
                        value = self.read_callback()
                    self.Set(GATT_CHRC_IFACE, "Value", Array(value, signature="y"))
                return self.GetAll(GATT_CHRC_IFACE)["Value"]

            @method(GATT_CHRC_IFACE, in_signature="aya{sv}", out_signature="")
            def WriteValue(self, value, options):
                # Původní bluezero: po callbacku uloží zapsanou hodnotu přes Set
                if self.write_callback:
                    self.write_callback(dbus_to_python(value), dbus_to_python(options))
                self.Set(GATT_CHRC_IFACE, "Value", value)

            @method(GATT_CHRC_IFACE, in_signature="", out_signature="")
            def StartNotify(self):
                if self.props[GATT_CHRC_IFACE]["Notifying"] is True:
                    return
                self.Set(GATT_CHRC_IFACE, "Notifying", Boolean(True, variant_level=1))
                if self.notify_callback:
                    self.notify_callback(True, self)

            @method(GATT_CHRC_IFACE, in_signature="", out_signature="")
            def StopNotify(self):
                if self.props[GATT_CHRC_IFACE]["Notifying"] is False:
                    return
                self.Set(GATT_CHRC_IFACE, "Notifying", Boolean(False, variant_level=1))
                if self.notify_callback:
                    self.notify_callback(False, self)

        # --- bluezero.device a bluezero.adapter ---
        class Device:
            def __init__(self, adapter_addr, device_addr):
                self.adapter = adapter_addr
                self.address = device_addr

        class AdapterError(Exception):
            pass

        class Adapter:
            @staticmethod
            def available():
                # Skutečné bluezero se ptá BlueZ (GetManagedObjects na org.bluez) a dbus-python
                # název nejdřív aktivuje: když bluetoothd neběží, D-Bus ho spustí
                bt.vlastnici.setdefault(BLUEZ_SERVICE_NAME, ":1.8")
                for adresa in bt.adaptery:
                    yield Adapter(adresa)
                if not bt.adaptery:
                    raise AdapterError("No Bluetooth adapter found")

            def __init__(self, adapter_addr=None):
                self.address = adapter_addr
                self.on_connect = None
                self.on_disconnect = None
                self.on_device_found = None

            def _properties_changed(self, interface, changed, invalidated, path):
                device_address = adresa_zarizeni_z_cesty(path)
                adapter_addr = bt.ADRESA_ADAPTERU  # adaptér hci0 z cesty
                if "Connected" in changed:
                    if all((changed["Connected"], self.address == adapter_addr,
                            self.on_connect)):
                        if get_fn_parameters(self.on_connect) == 0:
                            self.on_connect()
                        elif get_fn_parameters(self.on_connect) == 1:
                            self.on_connect(Device(self.address, device_address))
                        elif get_fn_parameters(self.on_connect) == 2:
                            self.on_connect(adapter_addr, device_address)
                    elif not changed["Connected"] and self.on_disconnect:
                        if get_fn_parameters(self.on_disconnect) == 0:
                            self.on_disconnect()
                        elif get_fn_parameters(self.on_disconnect) == 1:
                            self.on_disconnect(Device(self.address, device_address))
                        elif get_fn_parameters(self.on_disconnect) == 2:
                            self.on_disconnect(self.address, device_address)

            def _interfaces_added(self, path, device_info):
                if DEVICE_INTERFACE in device_info:
                    dev_addr = device_info[DEVICE_INTERFACE].get("Address")
                    dev_connected = device_info[DEVICE_INTERFACE].get("Connected")
                    if self.on_device_found and dev_addr:
                        self.on_device_found(Device(self.address, dev_addr))
                    if all((self.on_connect, dev_connected, dev_addr)):
                        # bluezero tu volá callback vždy s jedním parametrem, ať jich má kolik chce
                        self.on_connect(Device(self.address, dev_addr))

        # --- bluezero.GATT a bluezero.advertisement ---
        # Moduly vzniknou hned, protože správci registrace z nich obsluhy berou až při volání
        gatt = types.ModuleType("bluezero.GATT")
        reklama = types.ModuleType("bluezero.advertisement")

        def register_app_cb():
            logging.getLogger("bluezero.GATT").info("GATT application registered")

        def register_app_error_cb(error):
            logging.getLogger("bluezero.GATT").warning("Failed to register application: %s",
                                                       str(error))

        def register_ad_cb():
            print("Advertisement registered")

        def register_ad_error_cb(error):
            print("Failed to register advertisement: " + str(error))

        def odpovez_na_registraci(co, obsluha_uspechu, obsluha_chyby):
            # BlueZ odpoví ve smyčce GLib; dbus-python pak zavolá obsluhu úspěchu bez parametrů,
            # nebo obsluhu chyby s jedním parametrem, chybou DBusException
            chyba = bt.chyby_registrace.get(co)
            if chyba is None:
                obsluha_uspechu()
            else:
                obsluha_chyby(chyba)

        class GattManager:
            def __init__(self, adapter_addr):
                self.adapter_addr = adapter_addr

            def register_application(self, application, options):
                odpovez_na_registraci("aplikace", gatt.register_app_cb, gatt.register_app_error_cb)

        class Advertisement:
            def __init__(self, advert_id, ad_type):
                self.path = f"/ukBaz/bluezero/advertisement{advert_id:04d}"
                self.ad_type = ad_type

            @method(LE_ADVERTISEMENT_IFACE, in_signature="", out_signature="")
            def Release(self):
                # Původní bluezero: BlueZ inzerát zrušil, ale program se to nedozví
                pass

        class AdvertisingManager:
            def __init__(self, adapter_addr=None):
                self.adapter_addr = adapter_addr

            def register_advertisement(self, advertisement, options=()):
                odpovez_na_registraci("inzerat", reklama.register_ad_cb,
                                      reklama.register_ad_error_cb)

        gatt.__dict__.update(register_app_cb=register_app_cb,
                             register_app_error_cb=register_app_error_cb, GattManager=GattManager)
        reklama.__dict__.update(register_ad_cb=register_ad_cb,
                                register_ad_error_cb=register_ad_error_cb,
                                Advertisement=Advertisement,
                                AdvertisingManager=AdvertisingManager)

        # --- bluezero.peripheral ---
        class Peripheral:
            def __init__(self, adapter_address, local_name=None, appearance=None):
                self.app = types.SimpleNamespace(path="/ukBaz/bluezero")  # localGATT.Application
                self.srv_mng = GattManager(adapter_address)
                self.services = []
                self.characteristics = []
                self.descriptors = []
                self.primary_services = []
                self.dongle = Adapter(adapter_address)
                self.local_name = local_name
                self.appearance = appearance
                self.advert = Advertisement(1, "peripheral")
                self.ad_manager = AdvertisingManager(adapter_address)
                self.inzerat = None  # jen v náhradě: co se inzerovalo, vyplní ho publish
                bt.periferie.append(self)

            def add_service(self, srv_id, uuid, primary):
                self.services.append(Service(srv_id, uuid, primary))
                if primary:
                    self.primary_services.append(uuid)

            def add_characteristic(self, srv_id, chr_id, uuid, value, notifying, flags,
                                   read_callback=None, write_callback=None,
                                   notify_callback=None):
                self.characteristics.append(Characteristic(
                    srv_id, chr_id, uuid, value, notifying, flags,
                    read_callback, write_callback, notify_callback))

            def publish(self):
                assert self.inzerat is None, "periferie se zverejnuje jen jednou"
                self.inzerat = {"ServiceUUIDs": list(self.primary_services),
                                "LocalName": self.local_name}
                self.srv_mng.register_application(self.app, {})
                self.ad_manager.register_advertisement(self.advert, {})
                # Skutečný publish teď spustí smyčku GLib a ta doručí odpovědi, které čekají
                cekajici, bt.smycka[:] = list(bt.smycka), []
                for odpoved in cekajici:
                    odpoved()

            @property
            def on_connect(self):
                return self.dongle.on_connect

            @on_connect.setter
            def on_connect(self, callback):
                self.dongle.on_connect = callback

            @property
            def on_disconnect(self):
                return self.dongle.on_disconnect

            @on_disconnect.setter
            def on_disconnect(self, callback):
                self.dongle.on_disconnect = callback

        # --- gi (PyGObject) ---
        def timeout_add(interval, function, *user_data, priority=0):
            bt.casovace.append((interval, function))
            return len(bt.casovace)

        def modul(nazev, **obsah):
            novy = types.ModuleType(nazev)
            novy.__dict__.update(obsah)
            return novy

        self.Array, self.Dictionary, self.DBusException = Array, Dictionary, DBusException
        dbus_exceptions = modul("dbus.exceptions", DBusException=DBusException)
        dbus_service = modul("dbus.service", method=method)
        glib = modul("gi.repository.GLib", timeout_add=timeout_add)
        moduly = {
            "dbus": modul("dbus", Array=Array, Dictionary=Dictionary, Boolean=Boolean,
                          SystemBus=SystemBus, exceptions=dbus_exceptions,
                          service=dbus_service),
            "dbus.exceptions": dbus_exceptions,
            "dbus.service": dbus_service,
            "bluezero.constants": modul("bluezero.constants", GATT_CHRC_IFACE=GATT_CHRC_IFACE,
                                        DEVICE_INTERFACE=DEVICE_INTERFACE,
                                        LE_ADVERTISEMENT_IFACE=LE_ADVERTISEMENT_IFACE,
                                        BLUEZ_SERVICE_NAME=BLUEZ_SERVICE_NAME),
            "bluezero.dbus_tools": modul("bluezero.dbus_tools", dbus_to_python=dbus_to_python),
            "bluezero.adapter": modul("bluezero.adapter", Adapter=Adapter,
                                      AdapterError=AdapterError),
            "bluezero.localGATT": modul("bluezero.localGATT", Service=Service,
                                        Characteristic=Characteristic),
            "bluezero.GATT": gatt,
            "bluezero.advertisement": reklama,
            "bluezero.peripheral": modul("bluezero.peripheral", Peripheral=Peripheral),
            "gi.repository": modul("gi.repository", GLib=glib),
        }
        moduly["bluezero"] = modul("bluezero", **{nazev.split(".")[1]: hodnota
                                                  for nazev, hodnota in moduly.items()
                                                  if nazev.startswith("bluezero.")})
        moduly["gi"] = modul("gi", repository=moduly["gi.repository"])
        return moduly

    # --- Co by udělal BlueZ, když tablet něco provede ---

    def charakteristika(self, uuid):
        """Charakteristika poslední vytvořené periferie podle UUID."""
        nalezene = [ch for ch in self.periferie[-1].characteristics
                    if ch.props[GATT_CHRC_IFACE]["UUID"] == uuid]
        assert len(nalezene) == 1, f"periferie ma mit prave jednu charakteristiku {uuid}"
        return nalezene[0]

    def cti(self, uuid, offset=0):
        """Čtení: BlueZ volá ReadValue a offset přidá do voleb, jen když není nulový."""
        moznosti = self.Dictionary({"device": "/org/bluez/hci0/dev_AA_AA_AA_AA_AA_01",
                                    "link": "LE", "mtu": 23}, signature="sv")
        if offset:
            moznosti["offset"] = offset
        return bytes(self.charakteristika(uuid).ReadValue(moznosti))

    def zapis(self, uuid, data):
        """Zápis s odpovědí: BlueZ volá WriteValue s polem bajtů a volbami."""
        moznosti = self.Dictionary({"device": "/org/bluez/hci0/dev_AA_AA_AA_AA_AA_01",
                                    "link": "LE", "mtu": 517, "type": "request"},
                                   signature="sv")
        self.charakteristika(uuid).WriteValue(self.Array(data, signature="y"), moznosti)

    def odebirej(self, uuid, zapnout=True):
        """Zápis do CCCD: BlueZ zavolá StartNotify při každém zapnutí notifikací a StopNotify,
        když je vypne poslední odběratel."""
        charakteristika = self.charakteristika(uuid)
        if zapnout:
            charakteristika.StartNotify()
        else:
            charakteristika.StopNotify()

    def notifikace(self, uuid):
        """Hodnoty, které BlueZ poslal odběratelům charakteristiky jako notifikace."""
        return [bytes(hodnota) for (u, vlastnost, hodnota, notifikuje) in self.signaly
                if u == uuid and vlastnost == "Value" and notifikuje]

    def pripoj(self, zarizeni, nove=False):
        """Připojení zařízení. Zařízení, které BlueZ už zná, ohlásí změnou vlastnosti Connected,
        nové (`nove`) signálem InterfacesAdded, kde už je připojené."""
        cesta = "/org/bluez/hci0/dev_" + zarizeni.replace(":", "_")
        dongle = self.periferie[-1].dongle
        if nove:
            dongle._interfaces_added(cesta, {DEVICE_INTERFACE: {"Address": zarizeni,
                                                                "Connected": True}})
        else:
            dongle._properties_changed(DEVICE_INTERFACE, {"Connected": True}, [], cesta)

    def odpoj(self, zarizeni):
        """Odpojení zařízení: BlueZ ho ohlásí změnou vlastnosti Connected."""
        cesta = "/org/bluez/hci0/dev_" + zarizeni.replace(":", "_")
        self.periferie[-1].dongle._properties_changed(DEVICE_INTERFACE, {"Connected": False},
                                                      [], cesta)

    def zrus_inzerat(self):
        """BlueZ inzerát zrušil (třeba když bluetoothd končí nebo zmizí adaptér) a zavolá jeho
        Release."""
        self.periferie[-1].advert.Release()

    def zmen_vlastnika(self, nazev, novy):
        """Signál NameOwnerChanged: název na sběrnici má nového vlastníka, "" znamená žádného
        (třeba bluetoothd spadl). Dostanou ho všechna nezrušená hlídání toho názvu."""
        if novy:
            self.vlastnici[nazev] = novy
        else:
            self.vlastnici.pop(nazev, None)
        for hlidani in self.hlidani:
            if hlidani.nazev == nazev and not hlidani.zruseno:
                hlidani.callback(novy)


@pytest.fixture
def falesny_bluetooth(monkeypatch):
    bt = FalesnyBluetooth()
    for nazev, modul in bt.moduly().items():
        monkeypatch.setitem(sys.modules, nazev, modul)
    return bt
