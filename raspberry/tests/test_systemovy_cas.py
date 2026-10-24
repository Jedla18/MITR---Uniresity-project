import os
import subprocess
from datetime import datetime

import pytest

from domek.hardware.systemovy_cas import SystemovyCas

CAS = datetime(2026, 10, 20, 14, 3, 0)


def test_nastavi_jen_systemovy_cas(monkeypatch):
    volani = []
    monkeypatch.setattr(subprocess, "run", lambda prikaz, **kw: volani.append(prikaz))
    SystemovyCas().nastav(CAS)
    assert volani == [["date", "-s", "2026-10-20 14:03:00"]]


def test_hodiny_rtc_se_nezapisuji_ani_kdyz_je_v_systemu_vidi(monkeypatch):
    # Raspberry nemá RTC; kdyby se někdy objevilo /dev/rtc0, hwclock se přesto nevolá
    volani = []
    monkeypatch.setattr(os.path, "exists", lambda cesta: True)
    monkeypatch.setattr(subprocess, "run", lambda prikaz, **kw: volani.append(prikaz))
    SystemovyCas().nastav(CAS)
    assert [prikaz[0] for prikaz in volani] == ["date"]


def test_selhani_je_oserror(monkeypatch):
    def selze(prikaz, **kw):
        raise subprocess.CalledProcessError(1, prikaz)
    monkeypatch.setattr(subprocess, "run", selze)
    with pytest.raises(OSError):
        SystemovyCas().nastav(CAS)


# Doplňující testy: to, co testy výše nepokryjí (falešné run totiž vyhodí chybu samo od sebe).
def test_prikaz_se_spousti_s_kontrolou_vysledku_a_zachycenym_vystupem(monkeypatch):
    volby = []
    monkeypatch.setattr(subprocess, "run", lambda prikaz, **kw: volby.append(kw))
    SystemovyCas().nastav(CAS)
    # bez check=True by selhání příkazu prošlo bez povšimnutí
    assert volby == [{"check": True, "capture_output": True}]


def test_selhani_date_je_oserror_a_zprava_rika_proc(monkeypatch):
    def selze(prikaz, **kw):
        raise subprocess.CalledProcessError(1, prikaz, stderr=b"date: Operation not permitted\n")

    monkeypatch.setattr(subprocess, "run", selze)
    with pytest.raises(OSError, match="Operation not permitted") as chyba:
        SystemovyCas().nastav(CAS)
    assert "date -s" in str(chyba.value)
    assert isinstance(chyba.value.__cause__, subprocess.CalledProcessError)
