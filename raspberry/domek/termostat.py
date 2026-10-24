"""
Simulovaný termostat: tepelný model vnitřní teploty a regulace s hysterezí.

Vnitřní teplota se počítá jednoduchým modelem prvního řádu. Bez topení a chlazení se
s časovou konstantou TAU_S blíží venkovní teplotě, topení ji zvedá a chlazení snižuje
stálou rychlostí. Regulace s hysterezí zapíná a vypíná topení a chlazení tak, aby se
nepřepínaly při každém kmitu teploty kolem cíle.
"""
from __future__ import annotations

from dataclasses import dataclass

from domek.nastaveni import Nastaveni

HYSTEREZE_TOPENI = 0.3    # topí se, když je teplota pod cílem o víc než tolik [°C]
HYSTEREZE_CHLAZENI = 1.0  # chladí se, když je teplota nad cílem o víc než tolik [°C]
TAU_S = 300.0             # časová konstanta přibližování k venkovní teplotě [s]
VYKON_TOPENI = 0.06       # o kolik °C za sekundu topení teplotu zvedá
VYKON_CHLAZENI = 0.06     # o kolik °C za sekundu ji chlazení snižuje
MAX_DT_S = 5.0            # nejdelší krok simulace [s]


def krok_simulace(t_uvnitr: float, t_venku: float, topi: bool, chladi: bool, dt: float) -> float:
    """Jeden Eulerův krok tepelného modelu; vrátí novou vnitřní teplotu.

    `dt` se omezí na 0 až MAX_DT_S sekund. Dlouhá pauza mezi kroky (zaseknutá smyčka,
    skok hodin) by jinak teplotu přeskočila daleko za cíl a záporný krok by model
    pustil pozpátku.
    """
    dt = min(max(dt, 0.0), MAX_DT_S)
    # topi a chladi jsou bool, tedy 0 nebo 1
    zmena = (t_venku - t_uvnitr) / TAU_S + VYKON_TOPENI * topi - VYKON_CHLAZENI * chladi
    return t_uvnitr + dt * zmena


def regulace(t_uvnitr: float, cil: float, topi: bool, chladi: bool) -> tuple[bool, bool]:
    """Rozhodne o topení a chlazení; vrátí nové (topi, chladi).

    Topení se vypne, když teplota dosáhne cíle, a znovu zapne, až když klesne pod cíl
    o víc než HYSTEREZE_TOPENI. Chlazení se vypne na cíli a zapne nad cílem o víc než
    HYSTEREZE_CHLAZENI. Topení a chlazení se nikdy nezapnou zároveň. Na pořadí kroků
    záleží: nejdřív se vypíná a teprve potom zapíná, takže z topení jde v jednom kroku
    přepnout rovnou na chlazení.
    """
    if topi and t_uvnitr >= cil:
        topi = False
    if chladi and t_uvnitr <= cil:
        chladi = False
    if not topi and not chladi:
        if t_uvnitr < cil - HYSTEREZE_TOPENI:
            topi = True
        elif t_uvnitr > cil + HYSTEREZE_CHLAZENI:
            chladi = True
    return topi, chladi


def aktivni_cil(nastaveni: Nastaveni, usek: str, nikdo_doma: bool) -> float | None:
    """Cílová teplota, kterou má termostat právě držet, nebo None, když je vypnutý.

    V režimu auto je to denní cíl ve dne a večer a noční cíl v noci; když nikdo není
    doma, sníží se o `utlum_nikdo_doma`. Ruční režim má pevný cíl bez útlumu.
    """
    rezim = nastaveni.rezim_termostatu
    if rezim == "rucni":
        return nastaveni.cil_rucni
    if rezim == "auto":
        cil = nastaveni.cil_noc if usek == "noc" else nastaveni.cil_den
        if nikdo_doma:
            cil -= nastaveni.utlum_nikdo_doma
        return cil
    return None  # vypnuto; neznámý režim se z bezpečnosti bere stejně (netopí se ani nechladí)


@dataclass
class Termostat:
    """Stav simulovaného termostatu: vnitřní teplota a to, jestli se právě topí nebo chladí."""

    t_uvnitr: float | None = None  # None, dokud nepřišlo první měření venkovní teploty
    topi: bool = False
    chladi: bool = False

    def krok(self, t_venku: float | None, cil: float | None, dt: float) -> None:
        """Posune simulaci o `dt` sekund a potom podle cíle rozhodne o topení a chlazení.

        `t_venku` je None, když se čidlo nepodařilo přečíst: regulace pak stojí, topení
        i chlazení jsou vypnuté a vnitřní teplota se nemění. `cil` je None, když je
        termostat vypnutý: simulace běží dál, ale netopí se ani nechladí.
        """
        if t_venku is None:
            self.topi = False
            self.chladi = False
            return
        if self.t_uvnitr is None:
            self.t_uvnitr = t_venku  # po startu se vnitřní teplota rovná venkovní
        else:
            # simulace počítá se stavem topení a chlazení z minulého kroku
            self.t_uvnitr = krok_simulace(self.t_uvnitr, t_venku, self.topi, self.chladi, dt)
        if cil is None:
            self.topi = False
            self.chladi = False
        else:
            self.topi, self.chladi = regulace(self.t_uvnitr, cil, self.topi, self.chladi)
