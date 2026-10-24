"""
Ovladač displeje SSD1306 (OLED 128x64) na sběrnici I2C.

Používá knihovny luma.core (sběrnice I2C a kreslení) a luma.oled (čip SSD1306). Existují jen
na Raspberry, proto se importují až při zobrazení a modul jde načíst i na PC.
"""
from __future__ import annotations

from domek.text_displeje import radky_displeje

# Svislé polohy řádků v bodech. První řádek (čas a úsek dne) je sám v horních 16 bodech, které
# jsou na dvoubarevných displejích žluté, a ostatní začínají až pod nimi s mezerou, aby žádný
# neležel na rozhraní barev. Rozestupy 10 a 9 bodů jsou zvolené tak, aby se ocásky písmen
# (g, p, y) nedotkly písmen dalšího řádku. Písmo zabírá i s ocásky body y+2 až y+11,
# takže šestý řádek (chyby) končí na bodu 63 z 0 až 63.
Y_RADKU = (0, 14, 24, 33, 42, 52)
PRIKAZ_ZHASNI = 0xAE  # příkaz SSD1306 "vypni displej", viz `_priprav_zarizeni`
MAX_VYTVORENI = 10    # tolikrát se nejvýš vytvoří zařízení ssd1306, viz `_priprav_zarizeni`


class OledDisplej:
    """Displej SSD1306 na sběrnici I2C, splňuje protokol `Displej`.

    Konstruktor na hardware nesahá: sběrnice se otevře a zařízení vytvoří až při prvním
    `zobraz`. Displej, který chybí už při startu, se tak ohlásí jen jako OSError z `zobraz`
    a domek ho zkouší při každém kroku znovu, dokud se neozve. Jakákoli chyba vytvoření nebo
    kreslení se převádí na OSError, aby ji `Domek` ohlásil jako chybu "displej": knihovna luma
    hlásí vlastní výjimky, které OSError nejsou, a nemusí být ani nainstalovaná.

    Na displej se kreslí jen tehdy, když se změnily řádky textu, takže když se nic nemění, jen
    jednou za minutu s novým časem. Po poruše se kreslí znovu, i když jsou řádky stejné: displej
    mohl zůstat v půli obrazu. Při normálním konci programu displej zhasne a smaže se, to dělá
    knihovna luma sama.

    Počet vytvoření zařízení ssd1306 je omezený na `MAX_VYTVORENI`, počítají se i nezdary.
    Knihovna luma totiž každé zařízení drží v paměti do konce programu (asi 320 kB), a kdyby
    inicializace nebo kreslení selhávaly pořád dokola, domek by zařízení vytvářel každou sekundu
    znovu a došla by mu paměť. Po vyčerpání limitu `zobraz` jen hlásí OSError a nic nezkouší,
    takže chyba "displej" zůstane vidět až do restartu programu. Limit se týká vytváření, ne
    kreslení: zařízení, které funguje, se používá dál.
    """

    def __init__(self, adresa: int = 0x3C, sbernice: int = 1) -> None:
        self._adresa = adresa
        self._cislo_sbernice = sbernice
        self._serial = None    # luma i2c, otevře se při prvním zobrazení a pak zůstane otevřená
        self._zarizeni = None  # luma ssd1306, vznikne, až displej odpoví
        self._vytvoreno = 0    # kolikrát se zařízení vytvářelo, i neúspěšně; zkoušky se nepočítají
        self._radky = None     # řádky, které jsou právě na displeji; None, když to není jisté

    def zobraz(self, stav: dict, pripojeno: bool) -> None:
        """Ukáže stav na displeji, ale jen když se změnily řádky. Při poruše vyhodí OSError."""
        radky = radky_displeje(stav, pripojeno)
        if radky == self._radky:
            return
        if self._zarizeni is None and self._vytvoreno >= MAX_VYTVORENI:
            # Zkušební příkaz ani vytvoření zařízení se už nezkouší
            raise OSError(f"Displej SSD1306 na adrese 0x{self._adresa:02X} nefunguje ani po "
                          f"{MAX_VYTVORENI} pokusech o vytvoreni zarizeni a dalsi se uz nezkousi, "
                          f"protoze knihovna luma drzi kazde zarizeni v pameti. Pomuze az restart "
                          f"programu.")
        self._radky = None  # kdyby kreslení selhalo, je obraz na displeji neznámý
        try:
            from luma.core.render import canvas

            zarizeni = self._priprav_zarizeni()
            with canvas(zarizeni) as kresleni:  # obraz se na displej pošle až na konci bloku
                for y, radek in zip(Y_RADKU, radky):
                    kresleni.text((0, y), radek, fill="white")
        except Exception as chyba:
            # Displej mohl přijít o napájení a pak je prázdný a vypnutý, dokud ho znovu
            # neinicializuje vytvoření zařízení. Proto se zařízení příště vytvoří znovu, pokud to
            # limit `MAX_VYTVORENI` ještě dovolí.
            self._zarizeni = None
            raise OSError(f"Displej SSD1306 na adrese 0x{self._adresa:02X} nejde ovladat "
                          f"({type(chyba).__name__}: {chyba}).") from chyba
        self._radky = radky

    def _priprav_zarizeni(self):
        """Vrátí zařízení ssd1306. Co ještě není, vytvoří; co se nepovedlo, zkusí příště znovu."""
        from luma.core.interface.serial import i2c
        from luma.oled.device import ssd1306

        # Sběrnice se otevře jen jednou a pak zůstane otevřená, aby opakované pokusy nehromadily
        # otevřené soubory.
        if self._serial is None:
            self._serial = i2c(port=self._cislo_sbernice, address=self._adresa)
        if self._zarizeni is None:
            # Luma.oled si každé vytvořené zařízení, i to, jehož inicializace selhala, drží až do
            # konce programu (atexit) a s ním dva seznamy po 8192 číslech, dohromady asi 320 kB.
            # Domek volá `zobraz` každou sekundu, takže vytvářet zařízení při každém volání by
            # zaplnilo paměť za necelou hodinu. Proti tomu jsou dvě pojistky:
            # 1. Zařízení se nevytvoří, dokud displej neodpoví na zkušební příkaz. Je to "zhasni",
            #    kterým inicializace stejně začíná. Nezapojený displej tak nestojí žádné zařízení.
            # 2. Počet vytvoření je omezený na `MAX_VYTVORENI`, takže to nestojí víc než asi 3 MB.
            #    Bez toho by displej, který na zkoušku odpoví, ale pak mu selže inicializace
            #    nebo kreslení, stál jedno zařízení za sekundu. Limit kontroluje `zobraz`.
            self._serial.command(PRIKAZ_ZHASNI)
            self._vytvoreno += 1
            self._zarizeni = ssd1306(self._serial)
        return self._zarizeni
