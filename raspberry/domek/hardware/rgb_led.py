"""
Ovladač světla domku: obyčejná RGB LED se čtyřmi vývody a společnou katodou (PWM na každé barvě).

Používá knihovnu gpiozero, která existuje jen na Raspberry, proto se importuje až při prvním
`nastav` a modul jde načíst i na PC.
"""
from __future__ import annotations

from domek.svetlo import StavSvetla

GPIO_CERVENA = 16  # fyzický pin 36
GPIO_ZELENA = 20   # fyzický pin 38
GPIO_MODRA = 21    # fyzický pin 40
CERNA = (0.0, 0.0, 0.0)
GAMA = 2.2  # oko vnímá jas nelineárně; bez korekce se teplá žlutá na RGB LED slije do bílé


class RgbLed:
    """RGB LED na pinech `gpio_r`, `gpio_g` a `gpio_b` (číslování BCM), splňuje `VystupSvetla`.

    LED je se společnou katodou (vývod "-" na GND), což je výchozí `active_high=True` v gpiozero.
    Barva i jas ze `StavSvetla` se přenesou na střídy PWM: každá složka je
    `(složka / 255) ** GAMA * jas / 100`, když je světlo zapnuté, jinak je LED zhasnutá. Gama
    korekce nechá plné a nulové složky beze změny a ztlumí mezistupně. Bez ní by třeba
    teplá žlutá (255, 147, 41) svítila skoro bíle, protože střída PWM roste lineárně, ale oko vnímá
    jas nelineárně a zelený čip RGB LED svítí víc než červený. Barva se mění hned, bez prolínání.
    Rezistory (220 až 330 ohmů na každou barvu) musí být zapojené mimo ovladač.

    Konstruktor na hardware nesahá a knihovnu neimportuje. `RGBLED` vznikne při prvním `nastav`
    a hned po vytvoření je zhasnutá. Jádro volá `nastav` každou sekundu, i když se stav nezměnil,
    proto se barva zapíše jen při změně. Barva, která se nezapsala, se za zapsanou nepovažuje,
    takže ji další `nastav` zapíše znovu.

    Chyby gpiozero se tu nezachycují: OSError ohlásí `Domek` jako
    chybu "svetlo" a po návratu GPIO zmizí sama. Jiná chyba (třeba pin obsazený jiným programem)
    ukončí program a systemd ho spustí znovu.
    """

    def __init__(self, gpio_r: int = GPIO_CERVENA, gpio_g: int = GPIO_ZELENA,
                 gpio_b: int = GPIO_MODRA) -> None:
        self._gpio = (gpio_r, gpio_g, gpio_b)
        self._led = None       # gpiozero.RGBLED, vznikne při prvním nastav
        self._zapsano = CERNA  # barva, na kterou je LED nastavená; po vytvoření je zhasnutá

    def nastav(self, stav: StavSvetla) -> None:
        """Rozsvítí LED na barvu a jas ze `stav`, nebo ji zhasne. Zapíše jen změnu."""
        if self._led is None:
            from gpiozero import RGBLED

            self._led = RGBLED(*self._gpio)
        if stav.zapnuto:
            barva = tuple((slozka / 255) ** GAMA * stav.jas / 100 for slozka in stav.barva)
        else:
            barva = CERNA
        if barva != self._zapsano:
            self._led.value = barva
            self._zapsano = barva
