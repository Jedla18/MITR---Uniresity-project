"""
Formát dat pro Bluetooth LE: názvy a UUID, hodnoty služby Environmental Sensing a JSON.

Modul je čistý: nezná BlueZ ani žádnou knihovnu pro Raspberry, takže se dá importovat a testovat
na PC. Jak data vidí aplikace, popisuje `docs/ble-rozhrani.md`.
"""
from __future__ import annotations

import json
import math

NAZEV = "Termostat"  # název periferie, podle kterého ji aplikace najde

# Standardní služba Environmental Sensing (ESS): hodnoty umí zobrazit i obecná aplikace
ESS_UUID = "181A"
TEPLOTA_UUID = "2A6E"
VLHKOST_UUID = "2A6F"
TLAK_UUID = "2A6D"

# Vlastní služba "Dům" a její charakteristiky
DOMEK_UUID = "5bf00001-f758-4c41-a34e-bab58ce87673"
STAV_UUID = "5bf00010-f758-4c41-a34e-bab58ce87673"
NASTAVENI_UUID = "5bf00011-f758-4c41-a34e-bab58ce87673"
PRIKAZ_UUID = "5bf00012-f758-4c41-a34e-bab58ce87673"

MAX_JSON_B = 512  # nejdelší hodnota atributu v BLE [B]; delší JSON se neposílá ani nepřijímá

# Co ESS posílá, když hodnotu nezná. U tlaku norma nic takového nemá, proto je to nula.
_TEPLOTA_NEZNAMA = b"\x00\x80"          # 0x8000
_VLHKOST_NEZNAMA = b"\xff\xff"          # 0xFFFF
_TLAK_NEZNAMY = b"\x00\x00\x00\x00"


def _je_zname(hodnota: float | None) -> bool:
    """False pro None, NaN a nekonečno: nic z toho není měření."""
    return hodnota is not None and math.isfinite(hodnota)


def _na_cele(hodnota: float, nasobek: int, minimum: int, maximum: int) -> int:
    """Vynásobí `hodnotu`, omezí ji na `minimum` až `maximum` (včetně) a zaokrouhlí.

    Omezuje se před zaokrouhlením: obří číslo má po vynásobení nekonečno a round() by z něj
    vyhodilo OverflowError, místo aby se hodnota omezila.
    """
    return round(min(max(hodnota * nasobek, minimum), maximum))


def teplota_ess(t: float | None) -> bytes:
    """Teplota pro charakteristiku 0x2A6E: sint16 little-endian v setinách °C.

    Hodnota se omezí na -273,15 až 327,67 °C. Neznámá hodnota (None, NaN, nekonečno) je 0x8000.
    """
    if not _je_zname(t):
        return _TEPLOTA_NEZNAMA
    return _na_cele(t, 100, -27315, 32767).to_bytes(2, "little", signed=True)


def vlhkost_ess(v: float | None) -> bytes:
    """Vlhkost pro charakteristiku 0x2A6F: uint16 little-endian v setinách %.

    Hodnota se omezí na 0 až 100 %. Neznámá hodnota (None, NaN, nekonečno) je 0xFFFF.
    """
    if not _je_zname(v):
        return _VLHKOST_NEZNAMA
    return _na_cele(v, 100, 0, 10000).to_bytes(2, "little")


def tlak_ess(tlak_hpa: float | None) -> bytes:
    """Tlak pro charakteristiku 0x2A6D: uint32 little-endian v desetinách Pa (hPa krát 1000).

    Hodnota se omezí na 0 až 2^32 - 1. Neznámá hodnota (None, NaN, nekonečno) je nula.
    """
    if not _je_zname(tlak_hpa):
        return _TLAK_NEZNAMY
    return _na_cele(tlak_hpa, 1000, 0, 2 ** 32 - 1).to_bytes(4, "little")


def _zkontroluj_delku(hodnota: bytes) -> None:
    """Vyhodí ValueError, když je `hodnota` delší než `MAX_JSON_B` bajtů."""
    if len(hodnota) > MAX_JSON_B:
        raise ValueError(f"JSON ma {len(hodnota)} B, nejvic smi mit {MAX_JSON_B} B.")


def zakoduj_json(data: object) -> bytes:
    """JSON v UTF-8 pro BLE, bez mezer.

    Vyhodí ValueError, když je delší než `MAX_JSON_B` bajtů (počítají se bajty, ne znaky) nebo
    když obsahuje NaN či nekonečno, které nejsou platný JSON a aplikace by je nepřečetla.
    """
    hodnota = json.dumps(data, ensure_ascii=False, separators=(",", ":"),
                         allow_nan=False).encode("utf-8")
    _zkontroluj_delku(hodnota)
    return hodnota


def dekoduj_json(hodnota: bytes) -> object:
    """Přečte JSON v UTF-8.

    Delší než `MAX_JSON_B` bajtů, neplatné UTF-8 i neplatný JSON vyhodí ValueError.
    """
    _zkontroluj_delku(hodnota)
    return json.loads(hodnota.decode("utf-8"))
