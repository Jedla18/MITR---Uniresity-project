"""
Jádro domku: spojuje čistou logiku (nastavení, denní doba, termostat, světlo) s hardwarem.

Domek zná hardware jen přes rozhraní z `domek.hardware.rozhrani` a o Bluetooth neví nic. BLE
vrstva ho volá z jedné smyčky, bez vláken, takže tu nejsou žádné zámky. Porucha čidla, výstupu
nebo ukládání se nehlásí výjimkou: domek má běžet dál a tablet i displej mají poruchu ukázat,
proto je v `stav()["chyby"]` jen její název. Důvod poruchy se zapíše do logu.
"""
from __future__ import annotations

import logging
import math
from datetime import datetime

from domek.denni_doba import usek_dne
from domek.hardware.rozhrani import (CidloPohybu, Displej, Indikatory, MereniMeteo, Meteo,
                                     NastavovacCasu, Svetelnost, VystupSvetla)
from domek.nastaveni import Nastaveni, je_platna_barva, je_platny_jas, slouc, uloz
from domek.svetlo import VYPNUTO, Svetlo
from domek.termostat import Termostat, aktivni_cil


KONTROLKA_POHYBU_S = 10  # jak dlouho svítí oranžová kontrolka po posledním zaznamenaném pohybu [s]

_NEKONECNE = "cidlo vratilo hodnotu, ktera neni konecne cislo"  # důvod poruchy do logu


class ChybaPrikazu(ValueError):
    """Neplatný příkaz nebo příkaz, který se nepodařilo provést; zpráva říká proč."""


def _over_barvu(barva: object) -> list[int]:
    """Zkontroluje barvu z příkazu, což je seznam tří celých čísel 0 až 255, a vrátí její kopii.

    Kontrola je sdílená s nastavením. Kopie je kvůli tomu, že seznam z příkazu patří volajícímu
    a kdyby ho později změnil, nesmí se tím změnit nastavení, které se z něj uložilo.
    """
    if not je_platna_barva(barva):
        raise ChybaPrikazu("Barva musi byt seznam tri celych cisel 0 az 255.")
    return list(barva)


def _over_jas(jas: object) -> int:
    """Zkontroluje jas z příkazu, což je celé číslo 0 až 100 (kontrola je sdílená s nastavením)."""
    if not je_platny_jas(jas):
        raise ChybaPrikazu("Jas musi byt cele cislo 0 az 100.")
    return jas


def _zaokrouhli(hodnota: float | None, mist: int) -> float | None:
    """Zaokrouhlí hodnotu na `mist` desetinných míst; None zůstane None.

    Stav jde přes Bluetooth, kde se JSON musí vejít do 512 B, a dlouhé zlomky by jen zabíraly místo.
    """
    return None if hodnota is None else round(hodnota, mist)


class Domek:
    """Chytrý domek: termostat, světlo, čidla a jejich poruchy v jednom stavu.

    Volající (BLE vrstva nebo `__main__`) volá `krok` jednou za sekundu a `kontroluj_pohyb`
    zhruba každých 100 ms, `stav` čte pro tablet a displej a podle toho, co tablet pošle, volá
    `zmen_nastaveni` a `proved_prikaz`.

    Meteo, čidlo pohybu a kontrolky jsou povinné. Čidlo osvětlení, výstup světla, displej
    a nastavovač času jsou nepovinné: když chybí, příslušná část se přeskočí, bez nastavovače
    času příkaz "cas" selže. Bez `cesta_nastaveni` se nastavení neukládá.
    """

    def __init__(self, nastaveni: Nastaveni, meteo: Meteo, cidlo_pohybu: CidloPohybu,
                 indikatory: Indikatory, *, cesta_nastaveni: str | None = None,
                 displej: Displej | None = None, svetelnost: Svetelnost | None = None,
                 vystup_svetla: VystupSvetla | None = None,
                 nastavovac_casu: NastavovacCasu | None = None) -> None:
        self.nastaveni = nastaveni  # platné nastavení; mění se jen přes zmen_nastaveni
        self.pripojeno = False      # je připojený tablet? Nastavuje BLE vrstva a ukazuje displej
        self._meteo = meteo
        self._cidlo_pohybu = cidlo_pohybu
        self._indikatory = indikatory
        self._cesta_nastaveni = cesta_nastaveni
        self._displej = displej
        self._svetelnost = svetelnost
        self._vystup_svetla = vystup_svetla
        self._nastavovac_casu = nastavovac_casu
        self._termostat = Termostat()
        self._svetlo = Svetlo()
        self._stav_svetla = VYPNUTO
        self._chyby: set[str] = set()  # názvy právě trvajících poruch
        # Hodnoty z posledního kroku. Dokud žádný neproběhl, je `_ted` None.
        self._ted: datetime | None = None
        self._predchozi_mono = 0.0
        self._usek: str | None = None
        self._t_venku: float | None = None
        self._vlhkost: float | None = None
        self._tlak: float | None = None
        self._lux: float | None = None
        self._cil: float | None = None
        self._nikdo_doma = False
        # Pohyb: poslední přečtená hodnota PIR a kdy byl pohyb naposledy vidět (monotonní čas)
        self._pohyb = False
        self._posledni_pohyb = 0.0
        # Kontrolka pohybu (oranžová LED): co se naposledy poslalo na LED a kdy zhasne
        # (monotonní čas)
        self._kontrolka_pohybu = False
        self._kontrolka_pohybu_do: float | None = None

    def krok(self, ted: datetime, mono: float) -> None:
        """Jeden krok domku, volá se jednou za sekundu. `ted` je čas dne, `mono` monotonní čas [s].

        Na pořadí záleží: úsek dne a měření musí být hotové dřív, než podle nich rozhodne
        termostat a světlo, a displej se obnovuje jako poslední, aby ukázal už hotový stav.
        """
        # 1. Čas od minulého kroku. První krok žádný minulý nemá: dt je 0 a `mono` platí i jako čas
        #    posledního pohybu, takže "nikdo doma" se počítá od startu domku.
        if self._ted is None:
            dt = 0.0
            self._posledni_pohyb = mono
        else:
            dt = mono - self._predchozi_mono
        self._ted = ted
        self._predchozi_mono = mono
        # 2. Úsek dne
        self._usek = usek_dne(ted.time(), self.nastaveni)
        # 3. Meteo. Při poruše se regulace zastaví, ale čtení se dál opakuje a po návratu čidla
        #    se chyba sama smaže.
        mereni = self._precti_meteo()
        if mereni is None:
            self._t_venku = self._vlhkost = self._tlak = None
        else:
            self._t_venku, self._vlhkost, self._tlak = mereni
        # 4. Osvětlení, jen když je čidlo zapojené
        if self._svetelnost is not None:
            self._lux = self._precti_osvetleni()
        # 5. "Nikdo doma" se měří na monotonních hodinách, takže skok hodin (třeba nastavení
        #    času z tabletu) odpočet neovlivní.
        self._nikdo_doma = mono - self._posledni_pohyb >= self.nastaveni.nikdo_doma_po_min * 60
        self._cil = aktivni_cil(self.nastaveni, self._usek, self._nikdo_doma)
        # 6. Termostat a kontrolky
        self._termostat.krok(self._t_venku, self._cil, dt)
        self._indikatory.nastav(self._termostat.topi, self._termostat.chladi)
        # 7. Světlo
        self._prepocitej_svetlo(mono)
        # 8. Displej
        if self._displej is not None:
            try:
                self._displej.zobraz(self.stav(), self.pripojeno)
                self._hlas("displej", False)
            except OSError as chyba:
                self._hlas("displej", True, chyba)

    def kontroluj_pohyb(self, mono: float) -> bool:
        """Přečte PIR a vrátí True, když se hodnota změnila (pak má BLE vrstva poslat stav).

        Volá se zhruba každých 100 ms, aby se pohyb projevil hned, a ne až při dalším kroku.
        Při každém volání se také vyhodnotí kontrolka pohybu (oranžová LED): rozsvítí se v témže
        volání, které pohyb přečetlo, a zhasne `KONTROLKA_POHYBU_S` sekund po posledním čtení
        s pohybem. Nezávisí na úseku dne, světle ani na `nocni_svetlo_pri_pohybu`, funguje i před
        prvním krokem a návratovou hodnotu neovlivňuje.
        """
        pohyb = self._cidlo_pohybu.je_pohyb()
        if pohyb:
            # PIR drží výstup nahoře, dokud se někdo hýbe, takže se čas pohybu posouvá při každém
            # čtení, ne jen při změně hodnoty
            self._posledni_pohyb = mono
            self._kontrolka_pohybu_do = mono + KONTROLKA_POHYBU_S
        # Kontrolka se vyhodnocuje při každém volání, jinak by po 10 s při nezměněném PIR nezhasla
        sviti = self._kontrolka_pohybu_do is not None and mono < self._kontrolka_pohybu_do
        if sviti != self._kontrolka_pohybu:
            self._indikatory.ukaz_pohyb(sviti)
            self._kontrolka_pohybu = sviti
        if pohyb == self._pohyb:
            return False
        self._pohyb = pohyb
        if pohyb:
            self._nikdo_doma = False
        if self._ted is not None:  # před prvním krokem ještě není znám úsek dne
            self._prepocitej_svetlo(mono)
        return True

    def stav(self) -> dict:
        """Celý stav domku pro tablet a displej jako slovník, který jde rovnou převést na JSON.

        Měření, cíl a termostat jsou z posledního kroku. Pohyb, "nikdo doma" a světlo se po
        pohybu nebo příkazu změní hned a režim je z aktuálního nastavení. Před prvním krokem jsou
        čas a měření None. Každé volání vrací nový slovník, jeho změna domek neovlivní.
        """
        svetlo = self._stav_svetla
        return {
            "cas": None if self._ted is None else self._ted.isoformat(timespec="seconds"),
            "usek": self._usek,
            "t_venku": _zaokrouhli(self._t_venku, 2),
            "vlhkost": _zaokrouhli(self._vlhkost, 1),
            "tlak": _zaokrouhli(self._tlak, 2),
            "lux": _zaokrouhli(self._lux, 1),
            "t_uvnitr": _zaokrouhli(self._termostat.t_uvnitr, 2),
            "cil": _zaokrouhli(self._cil, 2),
            "topi": self._termostat.topi,
            "chladi": self._termostat.chladi,
            "rezim": self.nastaveni.rezim_termostatu,
            "nikdo_doma": self._nikdo_doma,
            "pohyb": self._pohyb,
            "svetlo": {"zap": svetlo.zapnuto, "barva": list(svetlo.barva), "jas": svetlo.jas,
                       "rucne": svetlo.rucne},
            "chyby": sorted(self._chyby),
        }

    def zmen_nastaveni(self, zmeny: object) -> None:
        """Sloučí změny s nastavením a uloží je do souboru. Neplatné změny vyhodí ChybaNastaveni.

        Nepodaří-li se nastavení uložit, změna platí dál a ve stavu je chyba "nastaveni": domek
        běží, jen po restartu se vrátí k naposledy uloženému nastavení. Úspěšné uložení chybu
        smaže. Vše běží v jedné smyčce, takže se dvě uložení nepřekryjí (pomocný soubor má
        pevný název).
        """
        self.nastaveni = slouc(self.nastaveni, zmeny)
        if self._cesta_nastaveni is not None:
            try:
                uloz(self.nastaveni, self._cesta_nastaveni)
                self._hlas("nastaveni", False)
            except OSError as chyba:
                self._hlas("nastaveni", True, chyba)

    def proved_prikaz(self, prikaz: object, mono: float) -> None:
        """Provede příkaz z tabletu; při neplatném nebo neproveditelném vyhodí ChybaPrikazu.

        Příkaz je objekt s jedním klíčem: `{"svetlo": ...}` ovládá světlo a `{"cas": ...}`
        nastaví čas. Před prvním krokem se žádný příkaz neprovede. Odmítnutý příkaz nic nezmění.
        """
        if self._ted is None:
            raise ChybaPrikazu("Dum se jeste nerozbehl, prikaz zatim nejde provest.")
        if not isinstance(prikaz, dict) or len(prikaz) != 1:
            raise ChybaPrikazu("Prikaz musi byt objekt s jednou dvojici klic: hodnota.")
        klic, hodnota = next(iter(prikaz.items()))
        if klic == "svetlo":
            self._prikaz_svetlo(hodnota, mono)
        elif klic == "cas":
            self._prikaz_cas(hodnota)
        else:
            raise ChybaPrikazu(f"Neznamy prikaz {klic!r}, znam svetlo a cas.")

    def _prikaz_svetlo(self, hodnota: object, mono: float) -> None:
        """Ovládání světla: zapnout, vypnout, prepnout, auto, nebo objekt s barvou a jasem.

        Objekt s barvou a/nebo jasem je uloží do nastavení a světlo zapne ručně.
        """
        if isinstance(hodnota, dict):
            # Nejdřív se zkontroluje všechno a teprve potom se nastavení změní: odmítnutý příkaz
            # si nesmí uložit ani správnou barvu vedle špatného jasu.
            if not hodnota or not hodnota.keys() <= {"barva", "jas"}:
                raise ChybaPrikazu("Svetlo: objekt musi mit 'barva' a nebo 'jas' a nic jineho.")
            zmeny = {}
            if "barva" in hodnota:
                zmeny["barva_svetla"] = _over_barvu(hodnota["barva"])
            if "jas" in hodnota:
                zmeny["jas_svetla"] = _over_jas(hodnota["jas"])
            # Barva a jas se uloží do nastavení, takže platí i po restartu a pro automatiku.
            # Chyba uložení světlo nezastaví, jako u každé jiné změny nastavení.
            self.zmen_nastaveni(zmeny)
            self._svetlo.rucne(True, self._usek)
        elif hodnota == "zapnout":
            self._svetlo.rucne(True, self._usek)
        elif hodnota == "vypnout":
            self._svetlo.rucne(False, self._usek)
        elif hodnota == "prepnout":
            self._svetlo.prepni(self._stav_svetla, self._usek)
        elif hodnota == "auto":
            self._svetlo.auto()
        else:
            raise ChybaPrikazu("Svetlo: povolene jsou zapnout, vypnout, prepnout, auto nebo objekt "
                               "s barvou a jasem.")
        self._prepocitej_svetlo(mono)

    def _prikaz_cas(self, hodnota: object) -> None:
        """Nastavení času: text ve tvaru ISO 8601 bez časového pásma, třeba 2026-10-20T14:03:00."""
        if not isinstance(hodnota, str):
            raise ChybaPrikazu("Cas musi byt text ve tvaru 2026-10-20T14:03:00.")
        try:
            cas = datetime.fromisoformat(hodnota)
        except ValueError:
            raise ChybaPrikazu("Cas musi byt text ve tvaru 2026-10-20T14:03:00.") from None
        if cas.tzinfo is not None:
            raise ChybaPrikazu("Cas musi byt bez casoveho pasma.")
        if self._nastavovac_casu is None:
            raise ChybaPrikazu("Tento dum nema cim nastavit cas.")
        try:
            self._nastavovac_casu.nastav(cas)
        except OSError as chyba:
            raise ChybaPrikazu(f"Cas se nepodarilo nastavit: {chyba}") from chyba

    def _precti_meteo(self) -> MereniMeteo | None:
        """Měření z BME280; None, když čtení selhalo nebo vrátilo něco, co není konečné číslo.

        Poruchu "meteo" rovnou ohlásí, nebo smaže, když čtení projde. NaN nebo nekonečno se
        berou jako porucha čidla. Kdyby se dostaly do tepelného modelu, zůstaly by v něm natrvalo:
        každé srovnání s NaN je nepravda, topení by se nikdy nevypnulo a vnitřní teplota by už
        nikdy nebyla číslo. Do JSON pro tablet by se NaN navíc zapsalo jako text, který JSON nezná.

        Vlhkost None porucha není: BMP280 vlhkost neměří. Teplota a tlak z něj platí a vlhkost
        zůstane neznámá. Teplota a tlak musí být konečná čísla vždy.
        """
        try:
            mereni = self._meteo.precti()
        except OSError as chyba:
            self._hlas("meteo", True, chyba)
            return None
        teplota, vlhkost, tlak = mereni
        if not (math.isfinite(teplota) and math.isfinite(tlak)
                and (vlhkost is None or math.isfinite(vlhkost))):
            self._hlas("meteo", True, f"{_NEKONECNE}: {tuple(mereni)}")
            return None
        self._hlas("meteo", False)
        return mereni

    def _precti_osvetleni(self) -> float | None:
        """Osvětlení z TSL2561; None, když čtení selhalo nebo vrátilo NaN či nekonečno.

        Poruchu "osvetleni" rovnou ohlásí, nebo smaže, když čtení projde.
        """
        try:
            lux = self._svetelnost.precti_lux()
        except OSError as chyba:
            self._hlas("osvetleni", True, chyba)
            return None
        if not math.isfinite(lux):
            self._hlas("osvetleni", True, f"{_NEKONECNE}: {lux}")
            return None
        self._hlas("osvetleni", False)
        return lux

    def _prepocitej_svetlo(self, mono: float) -> None:
        """Zeptá se logiky světla, jak má svítit, a stav pošle na výstup (když je zapojený).

        Volá se v každém kroku, takže se výstup po poruše zkusí znovu bez zvláštní obsluhy.
        """
        self._stav_svetla = self._svetlo.krok(
            self._usek, self._lux, self._pohyb, mono, self.nastaveni)
        if self._vystup_svetla is not None:
            try:
                self._vystup_svetla.nastav(self._stav_svetla)
                self._hlas("svetlo", False)
            except OSError as chyba:
                self._hlas("svetlo", True, chyba)

    def _hlas(self, chyba: str, selhalo: bool, duvod: object = None) -> None:
        """Zapíše poruchu `chyba` do stavu, nebo ji smaže, když věc zase funguje.

        Do logu jde jen začátek poruchy i s důvodem `duvod` a její konec. Porucha, která trvá,
        se znovu nezapisuje, jinak by do logu přibýval řádek každou sekundu.
        """
        if selhalo:
            if chyba not in self._chyby:
                logging.warning("Porucha %s: %s", chyba, duvod)
            self._chyby.add(chyba)
        elif chyba in self._chyby:
            logging.info("Porucha %s skoncila.", chyba)
            self._chyby.discard(chyba)
