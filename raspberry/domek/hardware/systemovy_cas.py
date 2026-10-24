"""
Nastavení systémového času na Raspberry.

Používá příkaz `date`, který umí měnit čas jen root. Služba domku běží jako root, při ručním
zkoušení je potřeba `sudo`. Raspberry nemá hodiny reálného času (RTC), takže čas jinak bere ze
sítě (NTP). Bez internetu je po zapnutí čas špatný, dokud ho tablet nenastaví příkazem `cas`.
"""
from __future__ import annotations

import subprocess
from datetime import datetime


class SystemovyCas:
    """Nastavovač času pro `Domek` (protokol `NastavovacCasu`)."""

    def nastav(self, cas: datetime) -> None:
        """Nastaví systémový čas příkazem `date -s`.

        Selhání příkazu se hlásí jako OSError, stejně jako u ostatního hardwaru.
        """
        prikaz = ["date", "-s", cas.strftime("%Y-%m-%d %H:%M:%S")]
        try:
            subprocess.run(prikaz, check=True, capture_output=True)
        except subprocess.CalledProcessError as chyba:
            podrobnosti = chyba.stderr.decode(errors="replace").strip() if chyba.stderr else ""
            zprava = f"Prikaz '{' '.join(prikaz)}' skoncil s kodem {chyba.returncode}"
            raise OSError(f"{zprava}: {podrobnosti}" if podrobnosti else zprava) from chyba
