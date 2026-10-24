"""Ověření součástek (`python -m domek.over_soucastky`), s falešným hardwarem a bez čekání."""
from datetime import datetime

import pytest

from domek import over_soucastky
from domek.hardware import cidlo_tsl2561
from domek.over_soucastky import main


class FalesneHodiny:
    """Nahrazuje modul time: sleep nečeká, jen si zapíše pauzu, a monotonic běží jen s pauzami.

    Po `limit` voláních test spadne, aby se chybná smyčka bez konce neprotočila donekonečna.
    """

    def __init__(self, limit=1000):
        self.pauzy = []
        self.limit = limit

    def sleep(self, sekund):
        self.pauzy.append(sekund)
        assert len(self.pauzy) <= self.limit, "smycka se nezastavila"

    def monotonic(self):
        return 1000.0 + sum(self.pauzy)


@pytest.fixture
def hodiny(monkeypatch):
    falesne = FalesneHodiny()
    monkeypatch.setattr(over_soucastky, "time", falesne)
    return falesne


@pytest.fixture
def hodiny_tsl2561(monkeypatch):
    """Hodiny ovladače TSL2561, který po zapnutí počká na první měření."""
    falesne = FalesneHodiny()
    monkeypatch.setattr(cidlo_tsl2561, "time", falesne)
    return falesne


def radky_vystupu(capsys):
    return capsys.readouterr().out.splitlines()


# --- bme280 ---

def test_bme280_vypise_id_cipu_a_tri_mereni_po_sekunde(falesny_hardware, hodiny, capsys):
    main(["bme280"])
    radky = radky_vystupu(capsys)
    assert radky[0] == "Registr 0xD0 (ID cipu) na adrese 0x76: 0x60"
    assert "BME280" in radky[1] and "POZOR" not in radky[1]
    assert radky[2:] == [
        "Mereni 1/3: teplota 21.50 C, vlhkost 40.0 %, tlak 1005.5 hPa",
        "Mereni 2/3: teplota 21.50 C, vlhkost 40.0 %, tlak 1005.5 hPa",
        "Mereni 3/3: teplota 21.50 C, vlhkost 40.0 %, tlak 1005.5 hPa"]
    assert hodiny.pauzy == [1.0, 1.0]  # mezi třemi měřeními, za posledním se nečeká
    assert ("read_byte_data", 0x76, 0xD0) in falesny_hardware.volani
    assert ("close", 1) in falesny_hardware.volani  # sběrnice pro čtení ID se zavřela


@pytest.mark.parametrize("chip_id, v_upozorneni", [
    (0x58, ("BMP280", "vlhkost")),
    (0x61, ("neznamy",)),
])
def test_jiny_cip_nez_bme280_se_ohlasi_ale_mereni_se_vypise(
        falesny_hardware, hodiny, capsys, chip_id, v_upozorneni):
    falesny_hardware.chip_id = chip_id
    main(["bme280"])
    radky = radky_vystupu(capsys)
    assert radky[0].endswith(f"0x{chip_id:02X}")
    assert radky[1].startswith("POZOR")
    assert all(text in radky[1] for text in v_upozorneni)
    assert len([radek for radek in radky if radek.startswith("Mereni")]) == 3


def test_bmp280_vypise_vlhkost_jako_pomlcky_a_teplotu_a_tlak_normalne(
        falesny_hardware, hodiny, capsys):
    falesny_hardware.chip_id = 0x58
    falesny_hardware.mereni = (21.5, 0.0, 1005.5)  # vlhkost 0.0 vrací knihovna RPi.bme280
    main(["bme280"])
    radky = radky_vystupu(capsys)
    assert radky[1].startswith("POZOR") and "BMP280" in radky[1]  # upozornění zůstává
    assert radky[2:] == [
        f"Mereni {cislo}/3: teplota 21.50 C, vlhkost --, tlak 1005.5 hPa" for cislo in (1, 2, 3)]


def test_neznamy_cip_vypise_vlhkost_z_knihovny_jako_bme280(falesny_hardware, hodiny, capsys):
    falesny_hardware.chip_id = 0x61
    main(["bme280"])
    assert radky_vystupu(capsys)[2] == (
        "Mereni 1/3: teplota 21.50 C, vlhkost 40.0 %, tlak 1005.5 hPa")


def test_adresa_z_volby_plati_pro_id_i_mereni(falesny_hardware, hodiny, capsys):
    main(["bme280", "--adresa", "0x77"])
    assert radky_vystupu(capsys)[0] == "Registr 0xD0 (ID cipu) na adrese 0x77: 0x60"
    assert ("read_byte_data", 0x77, 0xD0) in falesny_hardware.volani
    assert ("load_calibration_params", 1, 0x77) in falesny_hardware.volani


@pytest.mark.parametrize("porucha", ["sbernice", "id", "kalibrace", "mereni"])
def test_porucha_cidla_skonci_srozumitelnou_chybou(falesny_hardware, hodiny, porucha):
    falesny_hardware.porucha = {porucha}
    with pytest.raises(SystemExit) as konec:
        main(["bme280"])
    zprava = str(konec.value.code)  # zpráva v SystemExit se vypíše a program skončí s kódem 1
    assert zprava.startswith("CHYBA") and "0x76" in zprava and "i2cdetect" in zprava


# --- pir ---

def test_pir_vypisuje_jen_zmeny_po_30_sekund(falesny_hardware, hodiny, capsys):
    falesny_hardware.pohyb = [False] * 5 + [True] * 4 + [False]  # poslední hodnota se opakuje
    main(["pir"])
    zmeny = [radek for radek in radky_vystupu(capsys) if radek in ("pohyb", "klid")]
    assert zmeny == ["klid", "pohyb", "klid"]
    assert falesny_hardware.cteni_pohybu == 300
    assert hodiny.pauzy == [0.1] * 300  # 30 s po 100 ms


# --- led ---

def test_led_trikrat_blikne_cervenou_zelenou_a_oranzovou_a_nakonec_jsou_zhasnute(
        falesny_hardware, hodiny, capsys):
    main(["led"])
    cervena, zelena, oranzova = falesny_hardware.ledky
    assert (cervena.pin, zelena.pin, oranzova.pin) == (17, 27, 22)  # piny 11, 13 a 15 na liště
    assert cervena.historie[1:] == [True, False] * 3 + [False] * 6
    assert zelena.historie[1:] == [False] * 6 + [True, False] * 3
    assert oranzova.historie[1:] == [True, False] * 3  # kontrolka pohybu
    assert hodiny.pauzy == [0.5] * 18
    assert radky_vystupu(capsys) == [
        "LED topeni (cervena, pin 11): 3x blikne",
        "LED chlazeni (zelena, pin 13): 3x blikne",
        "Oranzova LED (pohyb, pin 15): 3x blikne"]


def test_led_blikaji_jedna_po_druhe_a_vzdy_sviti_jen_jedna(falesny_hardware, hodiny, monkeypatch):
    svitici = []

    def sleep(sekund):
        # Čísla LED, které právě svítí, v pořadí červená 0, zelená 1 a oranžová 2
        ledky = falesny_hardware.ledky
        svitici.append([cislo for cislo, led in enumerate(ledky) if led.value])

    monkeypatch.setattr(hodiny, "sleep", sleep)
    main(["led"])
    assert svitici == [[0], []] * 3 + [[1], []] * 3 + [[2], []] * 3


# --- displej ---

class FalesneDatum(datetime):
    """Nahrazuje datetime: now() vrací pevný čas, aby šel zkontrolovat text na displeji."""

    @classmethod
    def now(cls, tz=None):
        return cls(2026, 10, 20, 14, 3, 7)


def test_displej_pet_sekund_ukazuje_testovaci_text_s_aktualnim_casem(
        falesny_hardware, hodiny, capsys, monkeypatch):
    monkeypatch.setattr(over_soucastky, "datetime", FalesneDatum)
    main(["displej"])
    radky = [
        "14:03 test        BLE",
        "Uvnitr 22.5 cil 23.0",
        "Venku  22.0C 1013hPa",
        "TOPI   auto",
        "Svetlo zap rucne",
        "CHYBA zkouska",  # i šestý, nejnižší řádek, ať je vidět, jestli se vejde
    ]
    (snimek,) = falesny_hardware.snimky
    assert [text for _, text, _ in snimek] == radky
    assert falesny_hardware.volani[0] == ("i2c", 1, 0x3C)
    assert hodiny.pauzy == [5]  # text zůstane na displeji 5 s
    assert radky_vystupu(capsys) == (
        ["Displej na adrese 0x3C: 5 s ukazuje tento text:"]
        + ["    " + radek for radek in radky]
        + ["Konec zkousky displeje."])


def test_displej_ukaze_text_driv_nez_zacne_cekat(falesny_hardware, hodiny, monkeypatch):
    # Po konci programu displej zhasne, takže text musí být vidět už během čekání
    snimku_pri_cekani = []
    monkeypatch.setattr(hodiny, "sleep",
                        lambda sekund: snimku_pri_cekani.append(len(falesny_hardware.snimky)))
    main(["displej"])
    assert snimku_pri_cekani == [1]


@pytest.mark.parametrize("porucha", ["sbernice", "displej", "inicializace", "text", "odeslani"])
def test_porucha_displeje_skonci_srozumitelnou_chybou(falesny_hardware, hodiny, porucha):
    falesny_hardware.porucha = {porucha}
    with pytest.raises(SystemExit) as konec:
        main(["displej"])
    zprava = str(konec.value.code)
    assert zprava.startswith("CHYBA") and "0x3C" in zprava and "i2cdetect" in zprava
    assert hodiny.pauzy == []  # po chybě se nečeká


# --- tsl2561 ---

def test_tsl2561_vypise_id_a_deset_mereni_po_sekunde(
        falesny_hardware, hodiny, hodiny_tsl2561, capsys):
    falesny_hardware.chip_id = 0x50  # TSL2561
    main(["tsl2561"])
    radky = radky_vystupu(capsys)
    assert radky[0] == "Registr 0x8A (ID cipu) na adrese 0x39: 0x50"
    assert "TSL2561" in radky[1] and "POZOR" not in radky[1]
    assert "10" in radky[2]  # počet měření
    assert radky[3:] == [
        f"Mereni {cislo}/10: CH0 1000, CH1 100, 446.9 lx" for cislo in range(1, 11)]
    assert hodiny.pauzy == [1.0] * 9  # mezi deseti měřeními, za posledním se nečeká
    assert len(hodiny_tsl2561.pauzy) == 1  # ovladač počká jen na první měření
    assert falesny_hardware.volani[:2] == [("SMBus", 1), ("read_byte_data", 0x39, 0x8A)]
    assert [volani[0] for volani in falesny_hardware.volani].count("read_word_data") == 20


def test_tsl2561_s_jinym_id_cipu_upozorni(falesny_hardware, hodiny, hodiny_tsl2561, capsys):
    falesny_hardware.chip_id = 0x60  # něco jiného než TSL2561
    main(["tsl2561"])
    radky = radky_vystupu(capsys)
    assert radky[0] == "Registr 0x8A (ID cipu) na adrese 0x39: 0x60"
    assert "POZOR" in radky[1] and "0x5" in radky[1]
    assert len(radky) == 13  # měření se přesto provedou


@pytest.mark.parametrize("porucha", ["sbernice", "id", "zapnuti", "kanaly"])
def test_porucha_tsl2561_skonci_srozumitelnou_chybou(
        falesny_hardware, hodiny, hodiny_tsl2561, porucha):
    falesny_hardware.porucha = {porucha}
    with pytest.raises(SystemExit) as konec:
        main(["tsl2561"])
    zprava = str(konec.value.code)
    assert zprava.startswith("CHYBA") and "0x39" in zprava and "i2cdetect" in zprava
    assert hodiny.pauzy == []  # po chybě se nečeká


# --- rgb ---

def test_rgb_ukaze_postupne_cervenou_zelenou_modrou_a_bilou_kazdou_na_sekundu_a_zhasne(
        falesny_hardware, hodiny, capsys):
    main(["rgb"])
    (rgb,) = falesny_hardware.rgb_ledky
    assert rgb.piny == (16, 20, 21)
    assert rgb.historie == [
        (0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0), (1.0, 1.0, 1.0),
        (0.0, 0.0, 0.0)]
    assert hodiny.pauzy == [1.0] * 4  # čtyři barvy po sekundě, po zhasnutí se nečeká
    radky = radky_vystupu(capsys)
    assert "R pin 36" in radky[0] and "G pin 38" in radky[0] and "B pin 40" in radky[0]
    assert radky[1:] == ["    cervena", "    zelena", "    modra", "    bila",
                         "Konec zkousky RGB LED."]


def test_rgb_ukaze_kazdou_barvu_driv_nez_zacne_cekat(falesny_hardware, hodiny, monkeypatch):
    # Barva musí svítit celou dobu čekání, a ne se rozsvítit až po něm
    pocet_zapisu = []
    puvodni_sleep = hodiny.sleep  # původní sleep hlídá, aby se smyčka nezacyklila

    def sleep(sekund):
        pocet_zapisu.append(len(falesny_hardware.rgb_ledky[0].historie))
        puvodni_sleep(sekund)

    monkeypatch.setattr(hodiny, "sleep", sleep)
    main(["rgb"])
    assert pocet_zapisu == [2, 3, 4, 5]  # zhasnutá po vytvoření a pak každá barva


def test_porucha_rgb_led_skonci_chybou_bez_cekani(falesny_hardware, hodiny):
    falesny_hardware.porucha = {"gpio"}
    with pytest.raises(OSError):
        main(["rgb"])
    assert hodiny.pauzy == []  # po chybě se nečeká


# --- volby ---

@pytest.mark.parametrize("volby", [
    [], ["neznama"], ["bme280", "--adresa", "xyz"], ["pir", "--adresa", "0x76"],
    ["tsl2561", "--adresa", "0x39"], ["rgb", "--adresa", "0x76"],
    # součástky, které už nejsou součástí sestavy
    ["bh1750"], ["krouzek"]])
def test_neplatna_volba_skonci_chybou_pri_cteni_voleb(falesny_hardware, hodiny, volby):
    with pytest.raises(SystemExit) as konec:
        main(volby)
    assert konec.value.code == 2
    assert falesny_hardware.volani == [] and falesny_hardware.vstupy == []  # nic se nespustilo
    assert falesny_hardware.ledky == [] and hodiny.pauzy == []
