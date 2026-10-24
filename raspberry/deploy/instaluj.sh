#!/usr/bin/env bash
# Instalace domku na Raspberry: balíčky z apt, zapnutí I2C, virtuální prostředí Pythonu
# a služba systemd, která domek spouští po každém zapnutí napájení.
# Spouští se na Raspberry z ~/domek:  sudo bash deploy/instaluj.sh [volby pro domek]
# Volby (třeba --bme280-adresa 0x77, přehled dá `python -m domek --help`) se zapíšou do služby.
# Neplatné volby skript odmítne hned na začátku, ještě než cokoli v systému změní.
# Každá instalace zapíše službu znovu jen s volbami z tohoto spuštění.
set -euo pipefail

# Kořen projektu je složka nad deploy/, ať se skript spustí odkudkoli.
ADRESAR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JEDNOTKA=/etc/systemd/system/domek.service

# Volby se kontrolují dřív než cokoli jiného. Překlep (třeba --bme280-adres) by se jinak zapsal
# do služby a domek by s chybou voleb hned skončil a systemd by ho spouštěl každé 2 s znovu,
# pořád dokola.
# Kontrolu dělá funkce over_volby se stejným parserem voleb, jaký má program: volby jen přečte,
# nic nespustí a na hardware nesáhne, takže jí stačí systémový python3 bez knihoven z pip.
# - -B: skript běží jako root a nesmí v ~/domek/domek nechat složky __pycache__ patřící rootovi.
# - $* bez uvozovek: volby se rozdělí po mezerách stejně, jako je dostane služba v ExecStart.
echo "Kontroluji volby pro domek..."
# shellcheck disable=SC2086
if ! (cd "$ADRESAR" && python3 -B -c \
        'import sys; from domek.__main__ import over_volby; over_volby(sys.argv[1:])' $*); then
    echo "CHYBA: kontrola voleb pro domek neprosla, duvod je vypsany vys. Instalace skoncila" \
         "jeste pred jakoukoli zmenou v systemu." >&2
    echo "Prehled voleb: cd $ADRESAR && python3 -m domek --help" >&2
    exit 1
fi

# Čerstvý obraz systému má zastaralý seznam balíčků a instalace by hlásila, že balíček neexistuje
echo "Aktualizuji seznam balicku z apt..."
apt-get update

# python3-pil je knihovna Pillow pro displej (potřebuje ji luma.oled). Na 32bitovém systému by ji
# pip musel hledat jako hotový balíček pro ARM, nebo ji překládat; z apt přijde hotová i se
# systémovými knihovnami, které potřebuje. Pip ji ve venv použije, a když je pro luma.core moc
# stará (Bullseye), nainstaluje do venv novější.
echo "Instaluji balicky z apt..."
apt-get install -y python3-venv python3-dev python3-dbus python3-gi python3-gpiozero python3-pil \
    i2c-tools

# raspi-config zapíná I2C přepsáním /boot/firmware/config.txt na oddílu FAT. Když Raspberry přijde
# o napájení dřív, než se zápis dostane na kartu, zůstane config.txt prázdný a po startu je I2C
# vypnuté (stalo se 22. 10. 2026). Proto se soubor přepisuje jen tehdy, když I2C zapnuté ještě není
# (get_i2c vrací 0 pro zapnuté), a hned potom se zápis vynutí na kartu.
RESTART_KVULI_I2C=0
if [ "$(raspi-config nonint get_i2c)" != "0" ]; then
    echo "Zapinam I2C..."
    raspi-config nonint do_i2c 0
    sync
    RESTART_KVULI_I2C=1
else
    echo "I2C uz je zapnute."
fi

# --system-site-packages: dbus, gi, gpiozero a Pillow jsou z apt, takže je ve venv musí být vidět
echo "Vytvarim virtualni prostredi v $ADRESAR/venv..."
python3 -m venv --system-site-packages "$ADRESAR/venv"

echo "Instaluji knihovny z requirements-pi.txt..."
"$ADRESAR/venv/bin/pip" install -r "$ADRESAR/requirements-pi.txt"

# Služba systemd: domek se po zapnutí napájení spustí sám a po pádu ho systemd spustí znovu.
# - After a Wants: domek potřebuje Bluetooth, takže se spouští po bluetooth.service.
# - PartOf: po restartu bluetooth.service BlueZ naši registraci tiše zahodí, takže se s ním musí
#   restartovat i domek. BindsTo tu schválně není: spolu s After by domek zastavilo, kdyby se
#   bluetooth.service při startu přeskočil (adaptér se objeví později), a nikdo by ho nespustil.
#   Pozdní start Bluetooth se vyléčí sám: když se BLE nepodaří zaregistrovat, domek skončí
#   chybou a systemd ho dál spouští (Restart=always, StartLimitIntervalSec=0).
# - StartLimitIntervalSec=0: systemd se po opakovaných pádech nevzdá a zkouší to dál po RestartSec.
# - PYTHONDONTWRITEBYTECODE=1: služba běží jako root, takže by v ~/domek/domek vytvářela složky
#   __pycache__ patřící rootovi, do kterých by scp z nahraj.ps1 jako běžný uživatel nemohl zapsat.
# - $* vloží volby tohoto skriptu do příkazu služby.
echo "Zapisuji sluzbu $JEDNOTKA..."
cat > "$JEDNOTKA" <<EOF
[Unit]
Description=Chytry dum (termostat a cidla ovladane pres Bluetooth LE)
After=bluetooth.service
Wants=bluetooth.service
PartOf=bluetooth.service
StartLimitIntervalSec=0

[Service]
WorkingDirectory=$ADRESAR
ExecStart=$ADRESAR/venv/bin/python -m domek $*
Restart=always
RestartSec=2
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONDONTWRITEBYTECODE=1

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable domek
# Restart je potřeba, aby se při opakované instalaci (třeba s jinými volbami) použila nová služba
systemctl restart domek

echo "Hotovo. Sluzba domek bezi a po kazdem zapnuti Raspberry se spusti sama."
echo "Stav: systemctl status domek     Log: journalctl -u domek -f"
if [ "$RESTART_KVULI_I2C" = "1" ]; then
    echo "Aby se zapnute I2C projevilo, restartuj Raspberry: sudo reboot"
fi
