#!/usr/bin/env python3
"""
První test BLE komunikace: Raspberry Pi jako periferie (GATT server).

Služby:
  Environmental Sensing (0x181A)
    Teplota (0x2A6E)  read + notify  teplota CPU, sint16 little-endian v setinách °C
  Vlastní služba (5bf00001-...)
    Zpráva (5bf00002-...)  write  text z telefonu se vypíše do konzole

Spuštění:  python ble_teplota.py      Ukončení:  Ctrl+C
"""
from bluezero import adapter, async_tools, peripheral

NAZEV = 'Termostat'
INTERVAL_S = 2  # jak často posílat teplotu při odběru (notify)

ESS_UUID = '181A'                                    # Environmental Sensing
TEPLOTA_UUID = '2A6E'                                # Temperature
VLASTNI_SLUZBA_UUID = '5bf00001-f758-4c41-a34e-bab58ce87673'
ZPRAVA_UUID = '5bf00002-f758-4c41-a34e-bab58ce87673'

_odber_bezi = False


def teplota_cpu():
    """Teplota procesoru ve °C (soubor obsahuje tisíciny stupně)."""
    with open('/sys/class/thermal/thermal_zone0/temp') as f:
        return int(f.read().strip()) / 1000.0


def hodnota_teploty():
    """Hodnota pro charakteristiku 0x2A6E: sint16, setiny °C, little-endian."""
    t = teplota_cpu()
    return list(round(t * 100).to_bytes(2, 'little', signed=True))


def posli_teplotu(charakteristika):
    """Volá se z časovače; vrací True, dokud má telefon zapnutý odběr."""
    global _odber_bezi
    if not charakteristika.is_notifying:
        _odber_bezi = False
        return False
    charakteristika.set_value(hodnota_teploty())
    print(f'-> teplota {teplota_cpu():.1f} C')
    return True


def zmena_odberu(zapnuto, charakteristika):
    """Telefon zapnul nebo vypnul notifikace teploty."""
    global _odber_bezi
    if zapnuto:
        print('Telefon zapnul odber teploty')
        if not _odber_bezi:
            _odber_bezi = True
            posli_teplotu(charakteristika)  # první hodnota hned
            async_tools.add_timer_seconds(INTERVAL_S, posli_teplotu,
                                          charakteristika)
    else:
        print('Telefon vypnul odber teploty')


def prijata_zprava(hodnota, moznosti):
    """Telefon zapsal do charakteristiky Zpráva."""
    text = bytes(hodnota).decode('utf-8', errors='replace')
    print(f'<- zprava z telefonu: {text!r}  (bajty: {bytes(hodnota).hex(" ")})')


def pripojeno(adapter_adresa, zarizeni_adresa):
    print(f'Pripojeno zarizeni {zarizeni_adresa}')


def odpojeno(adapter_adresa, zarizeni_adresa):
    print(f'Odpojeno zarizeni {zarizeni_adresa}')


def main():
    adresa = list(adapter.Adapter.available())[0].address
    ble = peripheral.Peripheral(adresa, local_name=NAZEV)

    ble.add_service(srv_id=1, uuid=ESS_UUID, primary=True)
    ble.add_characteristic(srv_id=1, chr_id=1, uuid=TEPLOTA_UUID,
                           value=[], notifying=False,
                           flags=['read', 'notify'],
                           read_callback=hodnota_teploty,
                           notify_callback=zmena_odberu)

    ble.add_service(srv_id=2, uuid=VLASTNI_SLUZBA_UUID, primary=True)
    ble.add_characteristic(srv_id=2, chr_id=1, uuid=ZPRAVA_UUID,
                           value=[], notifying=False,
                           flags=['write', 'write-without-response'],
                           write_callback=prijata_zprava)

    # Do reklamního paketu (max 31 bajtů) dáme jen krátké UUID 0x181A,
    # jinak by se tam vedle 128bitového UUID nevešel název zařízení.
    # Obě služby v GATT zůstávají primární a telefon je uvidí.
    ble.primary_services = [ESS_UUID]

    ble.on_connect = pripojeno
    ble.on_disconnect = odpojeno

    print(f'BLE periferie "{NAZEV}" bezi na adapteru {adresa}. Ctrl+C ukonci.')
    ble.publish()


if __name__ == '__main__':
    main()