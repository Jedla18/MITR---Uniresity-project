"""
Nastavení domku: výchozí hodnoty, kontrola, slučování změn a ukládání do souboru.

Tablet posílá jen změněné hodnoty. slouc() je zkontroluje a sloučí s dosavadním
nastavením; při jakékoli chybě vyhodí ChybaNastaveni a nic se nezmění.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, dataclass, fields, replace

REZIMY = ("auto", "rucni", "vypnuto")

# Čas HH:MM. Kontroluje se přes fullmatch a s re.ASCII: "$" by totiž pustilo i znak
# nového řádku na konci a "\d" i číslice jiných písem.
_CAS = re.compile(r"([01]\d|2[0-3]):[0-5]\d", re.ASCII)
_CASY = ("zacatek_den", "zacatek_vecer", "zacatek_noc")

# Povolené rozsahy (minimum, maximum), hranice platí včetně.
_DESETINNA = {
    "cil_den": (5, 35), "cil_noc": (5, 35), "cil_rucni": (5, 35),
    "prah_lux": (1, 10000),
    "utlum_nikdo_doma": (0, 10),
}
_CELA = {
    "nocni_svetlo_s": (5, 600),
    "nikdo_doma_po_min": (1, 240),
}


class ChybaNastaveni(ValueError):
    """Neplatné nastavení; zpráva říká, co je špatně."""


@dataclass(frozen=True)
class Nastaveni:
    """Všechna nastavení domku. Objekt je neměnný, změny se dělají přes slouc()."""

    rezim_termostatu: str = "auto"  # auto, rucni nebo vypnuto
    cil_den: float = 23.0           # cílová teplota ve dne a večer [°C]
    cil_noc: float = 20.0           # cílová teplota v noci [°C]
    cil_rucni: float = 22.0         # cílová teplota v ručním režimu [°C]
    zacatek_den: str = "07:00"      # začátky úseků dne ve tvaru HH:MM
    zacatek_vecer: str = "19:00"
    zacatek_noc: str = "22:00"
    prah_lux: float = 50.0          # pod tímto osvětlením je venku šero [lx]
    nocni_svetlo_s: int = 30        # jak dlouho po pohybu svítí noční světlo [s]
    nikdo_doma_po_min: int = 30     # po kolika minutách bez pohybu nikdo není doma [min]
    utlum_nikdo_doma: float = 3.0   # o kolik se sníží cíl, když nikdo není doma [°C]
    nocni_svetlo_pri_pohybu: bool = True  # jestli pohyb v noci rozsvítí noční světlo
    barva_svetla: tuple[int, int, int] = (255, 255, 255)  # RGB, každá složka 0 až 255
    jas_svetla: int = 100           # jas světla v procentech, 0 až 100

    def jako_dict(self) -> dict:
        """Nastavení jako obyčejný slovník, vhodný pro JSON (barva světla je seznam)."""
        data = asdict(self)
        data["barva_svetla"] = list(self.barva_svetla)
        return data


_POLE = frozenset(pole.name for pole in fields(Nastaveni))


def _je_cele(hodnota: object) -> bool:
    # bool je v Pythonu podtyp int, ale true a false čísla nejsou
    return isinstance(hodnota, int) and not isinstance(hodnota, bool)


def je_platna_barva(barva: object) -> bool:
    """Je to seznam (nebo n-tice) tří celých čísel 0 až 255? Sdílí ji nastavení i příkaz světla."""
    return (isinstance(barva, (list, tuple)) and len(barva) == 3
            and all(_je_cele(slozka) and 0 <= slozka <= 255 for slozka in barva))


def je_platny_jas(jas: object) -> bool:
    """Je to celé číslo 0 až 100? Sdílí ji nastavení i příkaz světla."""
    return _je_cele(jas) and 0 <= jas <= 100


def _over_rozsah(klic: str, hodnota: float, minimum: int, maximum: int) -> None:
    # "not (a <= x <= b)" odmítne i NaN, který by přes "x < a or x > b" prošel.
    if not minimum <= hodnota <= maximum:
        raise ChybaNastaveni(f"Hodnota '{klic}' musi byt v rozsahu {minimum} az {maximum}.")


def _over(klic: str, hodnota: object) -> object:
    """Zkontroluje jednu hodnotu a vrátí ji ve tvaru, v jakém se uloží."""
    if klic == "rezim_termostatu":
        if not isinstance(hodnota, str) or hodnota not in REZIMY:
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt jedna z: {', '.join(REZIMY)}.")
        return hodnota
    if klic in _CASY:
        if not isinstance(hodnota, str) or not _CAS.fullmatch(hodnota):
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt cas ve tvaru HH:MM (00:00 az 23:59).")
        return hodnota
    if klic == "nocni_svetlo_pri_pohybu":
        if not isinstance(hodnota, bool):
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt true nebo false.")
        return hodnota
    if klic == "barva_svetla":
        if not je_platna_barva(hodnota):
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt seznam tri celych cisel 0 az 255.")
        return tuple(hodnota)  # kopie: seznam od volajícího se pak nesmí měnit nastavení
    if klic == "jas_svetla":
        if not je_platny_jas(hodnota):
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt cele cislo 0 az 100.")
        return hodnota
    # U čísel se musí bool vyloučit zvlášť: v Pythonu je podtyp int, ale true a false čísla nejsou.
    if klic in _DESETINNA:
        if isinstance(hodnota, bool) or not isinstance(hodnota, (int, float)):
            raise ChybaNastaveni(f"Hodnota '{klic}' musi byt cislo.")
        _over_rozsah(klic, hodnota, *_DESETINNA[klic])
        # float() až po kontrole rozsahu: obrovské celé číslo by jinak vyhodilo OverflowError
        return float(hodnota)
    if isinstance(hodnota, bool) or not isinstance(hodnota, int):
        raise ChybaNastaveni(f"Hodnota '{klic}' musi byt cele cislo.")
    _over_rozsah(klic, hodnota, *_CELA[klic])
    return hodnota


def slouc(nastaveni: Nastaveni, zmeny: object) -> Nastaveni:
    """Vrátí nastavení s použitými změnami; původní objekt se nemění.

    Změny jsou slovník jen s klíči z polí Nastaveni. Při jakékoli chybě vyhodí
    ChybaNastaveni a nepoužije se žádná z nich.
    """
    if not isinstance(zmeny, dict):
        raise ChybaNastaveni("Zmeny nastaveni musi byt objekt s dvojicemi klic: hodnota.")
    neznama = sorted(str(klic) for klic in zmeny if klic not in _POLE)
    if neznama:
        raise ChybaNastaveni("Neznama nastaveni: " + ", ".join(neznama) + ".")
    nove = replace(nastaveni, **{klic: _over(klic, hodnota) for klic, hodnota in zmeny.items()})
    if len({nove.zacatek_den, nove.zacatek_vecer, nove.zacatek_noc}) < 3:
        raise ChybaNastaveni("Zacatky dne, vecera a noci musi byt ruzne.")
    return nove


def _preved_stary_klic(data: object) -> object:
    """Přejmenuje starý klíč `svetlo_pri_pohybu` na `nocni_svetlo_pri_pohybu`.

    Soubor na Raspberry může mít starý klíč z doby před přejmenováním. Bez převodu by ho `slouc`
    odmítlo jako neznámý a `nacti` by zahodilo celý soubor včetně uložené barvy. Hodnota se
    přenese beze změny, takže ji `slouc` zkontroluje jako dřív. Když jsou v souboru oba klíče,
    platí nový. Vrací kopii, původní slovník se nemění.
    """
    if not isinstance(data, dict) or "svetlo_pri_pohybu" not in data:
        return data
    data = dict(data)
    stara = data.pop("svetlo_pri_pohybu")
    data.setdefault("nocni_svetlo_pri_pohybu", stara)
    return data


def nacti(cesta: str) -> Nastaveni:
    """Načte nastavení ze souboru. Když chybí nebo je poškozené, vrátí výchozí.

    Starý klíč `svetlo_pri_pohybu` se převede na `nocni_svetlo_pri_pohybu`.
    """
    try:
        with open(cesta, encoding="utf-8") as soubor:
            data = json.load(soubor)
        return slouc(Nastaveni(), _preved_stary_klic(data))
    except (OSError, ValueError):  # ValueError je i chybný JSON, nečitelný text a ChybaNastaveni
        return Nastaveni()


def uloz(nastaveni: Nastaveni, cesta: str) -> None:
    """Uloží nastavení do souboru jako JSON.

    Zapisuje se do pomocného souboru, který se nakonec přejmenuje na cílový. Výpadek
    proudu tak nenechá rozepsaný soubor: zůstane buď celý starý, nebo celý nový. Po
    přejmenování se na disk zapíše i složka, aby výpadek nevrátil starý soubor.
    """
    slozka = os.path.dirname(cesta)
    if slozka:  # cesta jen s názvem souboru složku nemá
        os.makedirs(slozka, exist_ok=True)
    pomocny = cesta + ".tmp"
    with open(pomocny, "w", encoding="utf-8") as soubor:
        json.dump(nastaveni.jako_dict(), soubor, indent=2)
        soubor.flush()
        os.fsync(soubor.fileno())  # data musí být na disku dřív než přejmenování
    os.replace(pomocny, cesta)
    _zapis_slozku_na_disk(slozka or os.curdir)


def _zapis_slozku_na_disk(slozka: str) -> None:
    """Zapíše na disk složku, aby přejmenování souboru v ní přežilo výpadek proudu.

    Jen na POSIX (Raspberry): na Windows, kde běží simulace, složku otevřít nejde. Když to
    systém souborů neumí, nic se neděje: soubor je už uložený a přejmenovaný, takže se uložení
    nesmí hlásit jako chyba.
    """
    if os.name != "posix":
        return
    try:
        popisovac = os.open(slozka, os.O_RDONLY)
        try:
            os.fsync(popisovac)
        finally:
            os.close(popisovac)
    except OSError:
        pass  # chybí jen jistota, že přejmenování přežije výpadek proudu
