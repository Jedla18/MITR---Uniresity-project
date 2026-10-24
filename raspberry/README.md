# Chytrý dům s termostatem

Raspberry Pi 3B+ simuluje chytrý dům: světlo, termostat a čidla. Tablet s Androidem ho přes Bluetooth LE sleduje a ovládá. Je to čistě simulace: součástky jsou na nepájivém poli a dům existuje jen v programu. Vnitřní teplotu počítá program, topení a chlazení ukazuje červená a zelená LED a venkovní teplotu a tlak měří skutečné čidlo BMP280. Dům má běžet i bez tabletu: po zapnutí napájení se rozběhne sám, bez notebooku, SSH a internetu, a nastavení z tabletu přežije restart.

Je to semestrální projekt do předmětu MITŘ (VŠB-TUO), varianta 2: mobilní aplikace komunikuje s vestavěným systémem bezdrátově.

- [Bluetooth rozhraní](docs/ble-rozhrani.md): přesný formát dat mezi Raspberry a tabletem. Podle něj je napsaná mobilní aplikace ve vedlejší složce `aplikace/`.

**Stav.** Program běží na Raspberry Pi 3B+ se skutečnými součástkami a mobilní aplikace s ním komunikuje přes Bluetooth LE. Logika domu se navíc testuje na PC bez hardwaru. Sekce „Ověření součástek“, „Ověření automatického spuštění“ a „Zkouška přes nRF Connect“ popisují, jak na Raspberry zkontrolovat součástky, spuštění po zapnutí a Bluetooth; aplikace nRF Connect při tom zastoupí mobilní aplikaci.

## Co kde je

| Cesta | K čemu |
|---|---|
| `domek/` | program: logika (nastavení, denní doba, termostat, světlo), Bluetooth server a ovladače součástek v `domek/hardware/` |
| `tests/` | testy pro pytest, běží na PC bez hardwaru |
| `deploy/nahraj.ps1` | nahraje program z PC na Raspberry |
| `deploy/instaluj.sh` | na Raspberry nainstaluje balíčky a knihovny a zapíše službu systemd |
| `requirements-pi.txt` | knihovny pro Raspberry; `bluezero` je záměrně pevně ve verzi 0.9.1, protože `domek/ble_server.py` upravuje jeho vnitřek |
| `requirements-dev.txt` | knihovny pro PC (pytest) |
| `docs/` | popis Bluetooth rozhraní |
| `main.py` | původní zkouška Bluetooth, viz „Pozor na main.py“ |

## Sestava a zapojení

Raspberry Pi 3B+ s Raspberry Pi OS Trixie (uživatel `admin`). Všechno se napájí z Raspberry, použij kvalitní nabíječku 5 V s micro-USB, která dá aspoň 2,5 A.

| Součástka | Připojení |
|---|---|
| BMP280 (prodávaný jako „BME280“) | I2C, adresa `0x76`, VCC 3,3 V. Měří teplotu a tlak, vlhkost ne |
| TSL2561 | I2C, adresa `0x39` (ADDR nezapojený), VCC 3,3 V. Osvětlení v luxech |
| SSD1306 128×64 | I2C, adresa `0x3C`. Dvoubarevný, horních 16 bodů je žlutých |
| LED semafor | R = topení, G = chlazení, Y = oranžová kontrolka pohybu |
| RGB LED, 4 vývody, společná katoda | světlo domu |
| HC-SR501 | čidlo pohybu |

GPIO jsou v číslování BCM, v závorce je fyzický pin na liště Raspberry.

> **5 V se nesmí dostat na žádný pin GPIO ani na sběrnici I2C.** Moduly I2C (BMP280, TSL2561 a SSD1306) se napájejí jen ze 3,3 V. Před zapnutím zkontroluj, že žádný není na 5 V, jinak se Raspberry poškodí.

| Součástka | Vývod | Raspberry (fyzický pin) |
|---|---|---|
| I2C (BMP280, TSL2561, SSD1306) | VCC / GND | 3,3 V (1) / GND (9) |
| I2C (BMP280, TSL2561, SSD1306) | SDA / SCL | GPIO2 (3) / GPIO3 (5), na nepájivém poli společné pro všechny tři moduly |
| LED semafor (rezistory na desce) | R / G / Y / GND | GPIO17 (11) / GPIO27 (13) / GPIO22 (15) / GND (20) |
| RGB LED | R / G / B | GPIO16 (36) / GPIO20 (38) / GPIO21 (40), každá přes rezistor 220 až 330 Ω |
| RGB LED | „−“ (společná katoda, nejdelší vývod) | GND (34) |
| HC-SR501 | VCC / GND / OUT | 5 V (2) / GND (6) / GPIO24 (18); **OUT připoj až po změření, viz níž** |

- **I2C:** všechny tři moduly sdílejí SDA a SCL, takže k Raspberry nevedou víc než dva datové vodiče.
- **BMP280 a vlhkost.** Čidlo se prodává jako BME280, ale má ID čipu `0x58`, tedy BMP280, které vlhkost neměří. Dům s ním běží: teplota a tlak platí, `vlhkost` ve Stavu je `null` (v ESS `0xFFFF`) a `chyby` je přesto prázdné. Displej proto místo vlhkosti ukazuje tlak. Program umí i pravý BME280 (ID `0x60`), ten pak vlhkost měří.
- **TSL2561:** ADDR nezapojuj, čidlo pak má adresu `0x39`. Nesmí mířit na RGB LED: jinak by se světlo samo zhaslo (rozsvítí se, čidlo naměří světlo a automatika ho zase vypne).
- **LED semafor** má ochranné rezistory na desce, takže se zapojuje přímo na GPIO. Červená (R) ukazuje topení, zelená (G) chlazení a oranžová (Y, prostřední) pohyb.
- **RGB LED:** holá LED potřebuje na **každou barvu vlastní rezistor** 220 až 330 Ω, jinak ji GPIO přetíží. Společná anoda (vývod „+“) se nepodporuje.
- **HC-SR501:** propojku dej do polohy H (výstup zůstává nahoře, dokud se někdo hýbe), trimr času na minimum a citlivost doprostřed. Po zapnutí se čidlo asi minutu ustaluje.
- **HC-SR501, změř výstup OUT dřív, než ho připojíš na GPIO24.** Zapoj jen VCC (5 V, pin 2) a GND (pin 6), OUT nech nezapojený. Multimetr nastav na stejnosměrné napětí (DC V) a měř mezi OUT a GND, zatímco čidlo hlásí pohyb. Po zapnutí se čidlo asi minutu ustaluje, takže počkej a pak mávej rukou před čidlem, dokud na multimetru opravdu neuvidíš vysokou úroveň (výstup zůstává nahoře jen krátce a v klidu je OUT na nule, ta nic nedokazuje). Kolem 3,3 V je v pořádku a OUT můžeš připojit na GPIO24. Kolem 5 V, a vůbec cokoli výrazně nad 3,3 V, OUT na GPIO nepřipojuj: levné kopie čidla se od vzoru mohou lišit.
- **Rozmístění na nepájivém poli:** BMP280 dej co nejdál od Raspberry, protože Raspberry hřeje a zkreslovalo by měření. HC-SR501 nasměruj tam, kde se při předvádění pohybují lidé. LED semafor a RGB LED mají být dobře vidět.

## Oranžová LED jako kontrolka pohybu

Oranžová (prostřední) LED modulu semafor (Y na GPIO22, pin 15) ukazuje pohyb. Rozsvítí se do 0,1 s od pohybu a zhasne 10 s po posledním pohybu. HC-SR501 s trimrem času na minimu drží výstup asi 3 s, takže celkem svítí asi 13 s.

Kontrolka nezávisí na světle, na automatice ani na `nocni_svetlo_pri_pohybu`: svítí ve dne i v noci. Kontrolu uděláš zkouškami z části „Ověření součástek“: `pir` ukáže, že čidlo pohyb hlásí, a `led` třikrát blikne červenou, zelenou a oranžovou LED.

## RGB LED jako světlo domu

RGB LED umí barvu i jas. Složky barvy se před zápisem do PWM umocní na 2,2 (gama korekce): oko vnímá jas nelineárně, takže třeba teplá žlutá (255, 147, 41) by bez korekce vypadala bíle. Barva se mění hned, bez prolínání. Stav ukazuje barvu tak, jak ji nastavil tablet.

Světlo svítí vždy barvou (`barva_svetla`) a jasem (`jas_svetla`) z nastavení, tedy tím, co tablet naposledy nastavil, výchozí je bílá na 100 %. Automatika rozhoduje jen o tom, kdy svítí:

- **den:** jen při šeru (viz „Čidlo osvětlení TSL2561“),
- **večer:** svítí,
- **noc:** zhasnuto, jen po pohybu svítí noční světlo (`nocni_svetlo_s`, výchozí 30 s) s jasem `min(jas_svetla, 10)` %. Při `nocni_svetlo_pri_pohybu` rovném `false` se noční světlo nerozsvítí, ale pohyb se dál hlásí ve Stavu a počítá do „nikdo doma“.

Ruční příkaz z tabletu automatiku přebije do konce úseku dne. Zkus to v nRF Connect (viz „Zkouška přes nRF Connect“):

- `{"svetlo":{"barva":[0,0,255],"jas":50}}` zapsané do Příkazu rozsvítí modrou na polovinu jasu. Barva a jas se uloží do nastavení, takže teď svítí touto barvou i automatika a zůstane tak i po restartu.
- `{"svetlo":"auto"}` vrátí automatiku.
- Původní bílou na 100 % vrátí zápis `{"barva_svetla":[255,255,255],"jas_svetla":100}` do Nastavení. Ten jen uloží, nic nezapne (příkaz `svetlo` s objektem by zapnul ruční světlo).

## Čidlo osvětlení TSL2561

Program čte TSL2561 při každém kroku. **Světlo ve dne svítí jen při šeru:** šero začíná, když `lux` klesne pod `prah_lux` (výchozí 50 lx), a končí až nad 1,3násobkem prahu (65 lx), aby světlo na hraně neblikalo. Chybí-li čidlo nebo se nedá přečíst, je ve Stavu chyba `osvetleni`, `lux` je `null` a ve dne se šero nepředpokládá, takže se světlo nerozsvítí.

Vyzkoušej: ve dne (výchozí 7:00 až 19:00) zakryj čidlo rukou. Do 2 s má Stav ukázat `"svetlo":{"zap":true,...}` (na displeji `Svetlo zap`) a po odkrytí zase `false`. Světlo musí být v automatice (`{"svetlo":"auto"}`).

## Na PC: testy a simulace

Na PC stačí Python 3.9 nebo novější. Všechny příkazy se spouští ze složky `raspberry/` (kořen programu pro Raspberry). Jednou se připraví virtuální prostředí:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

**Testy** ověřují logiku domu bez hardwaru (nastavení, denní doba, termostat, světlo, formát dat pro Bluetooth, chování serveru s náhradní knihovnou bluezero):

```powershell
.\.venv\Scripts\python.exe -m pytest
```

Spouštěj je takhle, přes `python -m pytest`. Samotný `pytest` nemusí najít modul `domek`.

**Simulace** spustí dům na PC bez Raspberry. Čidla jsou náhradní s pevnými hodnotami (venku 22 °C, 45 %, 1013,25 hPa, 200 lx), displej ani Bluetooth tam nejsou:

```powershell
.\.venv\Scripts\python.exe -m domek --simulace
```

Každou sekundu vypíše jeden řádek JSON se stavem domu, se stejnými klíči jako Stav v [Bluetooth rozhraní](docs/ble-rozhrani.md). Ve dne a večer se topí k cíli 23 °C (`"topi": true` a `t_uvnitr` pomalu stoupá), v noci se chladí k 20 °C. Ctrl+C simulaci ukončí.

Volby programu (`python -m domek --help`):

| Volba | Význam |
|---|---|
| `--simulace` | bez hardwaru, na PC se simulovanými čidly |
| `--sekund N` | jen se `--simulace`: skončí po N sekundách, třeba `--sekund 30` |
| `--nastaveni CESTA` | soubor s nastavením; výchozí je `/var/lib/domek/nastaveni.json`, se `--simulace` je to `nastaveni-simulace.json` v aktuální složce |
| `--bme280-adresa ADRESA` | I2C adresa BMP280 nebo BME280 na Raspberry, výchozí `0x76`, druhá možnost je `0x77` |

Sestava je pevná, žádné volby součástek nejsou. Volby se píšou celé: zkratku, třeba `--sek` místo `--sekund`, program odmítne.

## Raspberry: první instalace

Potřebuješ Raspberry Pi 3B+ s Raspberry Pi OS a zapnutým SSH, připojené k síti (na instalaci balíčků je potřeba internet) a zapojené podle části „Sestava a zapojení“. V příkladech je `admin@raspberry.local` uživatel a adresa Raspberry v síti, uprav je podle sebe.

1. **Zkontroluj systém.** Po přihlášení (`ssh admin@raspberry.local`) spusť:

   ```bash
   cat /etc/os-release; python3 --version
   ```

   Python musí být 3.9 nebo novější. Časové pásmo Raspberry má odpovídat tabletu, nastav ho příkazem `sudo timedatectl set-timezone Europe/Prague`.

2. **Nahraj program** z PC, ze složky `raspberry/`:

   ```powershell
   .\deploy\nahraj.ps1 -Cil admin@raspberry.local
   ```

   Skript na Raspberry smaže starou složku `~/domek/domek` (složku `~/domek/venv` nechává) a potom do `~/domek` nahraje složky `domek` a `deploy` a soubor `requirements-pi.txt`. Při každém ze čtyř příkazů, které spouští (`ssh`, `ssh`, `scp`, `ssh`), se může zeptat na heslo. Příkazy `sudo` v něm se přes ssh nemají kde zeptat na heslo, takže pokud `sudo` chce heslo, nastav uživateli na Raspberry `sudo` bez hesla (třeba přes `sudo visudo -f /etc/sudoers.d/domek` a řádek `uzivatel ALL=(ALL) NOPASSWD: ALL`, kde `uzivatel` je jeho jméno). Kdyby PowerShell odmítl skript spustit, povol ho jen pro toto okno: `Set-ExecutionPolicy -Scope Process Bypass`.

3. **Nainstaluj** na Raspberry:

   ```bash
   cd ~/domek
   sudo bash deploy/instaluj.sh
   ```

   Skript trvá několik minut a udělá tohle:
   - zkontroluje volby pro službu a s neplatnou skončí hned, ještě než cokoli změní,
   - aktualizuje seznam balíčků a nainstaluje balíčky z apt (`python3-venv`, `python3-dev`, `python3-dbus`, `python3-gi`, `python3-gpiozero`, `python3-pil` s knihovnou Pillow pro displej a `i2c-tools`),
   - zapne I2C,
   - vytvoří virtuální prostředí `~/domek/venv` a nainstaluje do něj knihovny z `requirements-pi.txt`,
   - zapíše službu systemd `domek` (`/etc/systemd/system/domek.service`), zapne ji pro každý start systému a hned spustí.

   Služba běží jako root (kvůli Bluetooth a nastavení času) a po pádu se sama restartuje.

   Žádné volby nejsou potřeba. Jediná užitečná je `--bme280-adresa`: když `i2cdetect` (viz níže) ukáže čidlo na adrese 77, nainstaluj znovu příkazem `sudo bash deploy/instaluj.sh --bme280-adresa 0x77`. Každá instalace zapíše službu znovu jen s volbami z právě spuštěného příkazu. Volby skript kontroluje stejně jako program a s neplatnou (třeba s překlepem `--bme280-adres`) skončí dřív, než cokoli v systému změní: vypíše řádek `python -m domek: error: ...` a pod ním `CHYBA: kontrola voleb pro domek neprosla ...`. Odmítne i `--simulace` a `--help`, se kterými by služba neběžela.

4. **Restartuj Raspberry**, aby se zapnuté I2C jistě projevilo:

   ```bash
   sudo reboot
   ```

5. **Zkontroluj I2C.** Po restartu spusť `i2cdetect -y 1`. V tabulce mají být `39` (TSL2561), `3c` (displej) a `76` (BMP280, případně `77`).

6. **Zkontroluj službu.** `systemctl status domek` (ukončíš ho klávesou `q`) má ukázat `active (running)` a displej má ukázat čas a teploty.

**Čas.** Raspberry nemá hodiny reálného času (RTC), čas bere ze sítě (NTP). Bez internetu je po zapnutí čas špatný, dokud ho tablet nenastaví příkazem `cas` (zápis do Příkazu, viz „Scénář předvedení“).

## Nahrání nové verze

Po každé změně programu na PC, ze složky `raspberry/`:

```powershell
.\deploy\nahraj.ps1 -Cil admin@raspberry.local
```

Skript nahraje program a službu restartuje, pokud běží (`systemctl try-restart`), takže nová verze platí hned. Starou složku `~/domek/domek` na Raspberry před nahráním smaže, takže tam nezůstanou moduly, které na PC už nejsou, ani složky `__pycache__` patřící rootovi. Instalaci není potřeba opakovat. Spusť ji znovu (`cd ~/domek && sudo bash deploy/instaluj.sh`) jen tehdy, když se změnil `requirements-pi.txt`, chceš změnit volby služby, nebo nová verze programu zrušila volbu, se kterou je služba nainstalovaná. Poznáš to tak, že služba po nahrání pořád restartuje a `journalctl -u domek` ukazuje `unrecognized arguments`.

## Ověření součástek

Každou součástku jde vyzkoušet zvlášť, bez zbytku programu. Zkoušky používají stejné ovladače jako dům, takže ověří i je. Nejdřív zastav službu: piny GPIO si program drží výhradně, takže `pir`, `led` a `rgb` by za běhu služby selhaly, a u `bme280` a `displej` by se zkouška míchala s tím, co zrovna dělá služba. Zkoušky se spouštějí na Raspberry z `~/domek`:

```bash
cd ~/domek
sudo systemctl stop domek
sudo venv/bin/python -m domek.over_soucastky bme280
sudo venv/bin/python -m domek.over_soucastky pir
sudo venv/bin/python -m domek.over_soucastky led
sudo venv/bin/python -m domek.over_soucastky displej
sudo venv/bin/python -m domek.over_soucastky tsl2561   # běží i při běžící službě
sudo venv/bin/python -m domek.over_soucastky rgb
sudo systemctl start domek
```

| Zkouška | Co dělá | Co je v pořádku |
|---|---|---|
| `bme280` | vypíše ID čipu z registru `0xD0` a třikrát změří, vždy po sekundě | ID `0x58` je BMP280 (naše čidlo): zkouška ho ohlásí řádkem `POZOR` a vlhkost vypíše jako `--`. Teplota 15 až 35 °C a tlak 950 až 1050 hPa. Pravý BME280 má ID `0x60` a měří i vlhkost (20 až 80 %) |
| `pir` | 30 s vypisuje každou změnu čidla pohybu | při mávnutí rukou se vypíše `pohyb`, po chvíli `klid` (po zapnutí se čidlo asi minutu ustaluje a může hlásit pohyb samo) |
| `led` | postupně třikrát blikne červená LED (topení, pin 11), zelená (chlazení, pin 13) a oranžová (pohyb, pin 15) | všechny tři LED zřetelně bliknou, jedna po druhé |
| `displej` | na 5 s ukáže testovací text s aktuálním časem a stejný text vypíše do terminálu | všech šest řádků je čitelných a nepřekrývají se |
| `tsl2561` | vypíše ID čipu z registru `0x8A` a desetkrát změří osvětlení (kanály CH0 a CH1 a lux), vždy po sekundě. Na rozdíl od ostatních běží i při běžící službě, I2C se sdílí | ID `0x5x` (TSL2561); při zakrytí čidla rukou hodnota klesne pod 10 lx a po odkrytí zase stoupne. Jiné ID zkouška ohlásí řádkem `POZOR` |
| `rgb` | postupně rozsvítí RGB LED červenou, zelenou, modrou a bílou (R pin 36, G pin 38, B pin 40), každou na 1 s a plným jasem, a nakonec ji zhasne | barvy jdou v tomto pořadí. Kdyby barvy neseděly (třeba modrá místo červené), jsou vývody R, G a B zapojené jinak. Svítí-li jen některé, zkontroluj rezistory a pájení |

Když čidlo teploty odpovídá na adrese 77, spusť `bme280` s `--adresa 0x77`. Při poruše vypíšou `bme280`, `tsl2561` a `displej` řádek `CHYBA: ...` s radou, co zkontrolovat (zapojení, napájení 3,3 V, zapnuté I2C, adresu; u TSL2561 i ADDR nezapojený). Zkouška `tsl2561` žádnou volbu nemá a počítá s adresou `0x39`.

## Služba, displej a logy

Dům běží jako služba systemd `domek`. Ovládání:

```bash
systemctl status domek          # běží?
sudo systemctl restart domek    # restart
sudo systemctl stop domek       # zastavení, po restartu Raspberry se zase spustí
sudo systemctl start domek      # spuštění
sudo systemctl disable domek    # služba se po zapnutí nebude spouštět (zpět: enable)
```

Displej může po zastavení služby zůstat svítit s posledním obrazem, i když program neběží. Stejně může zůstat svítit RGB LED s poslední barvou, oranžová LED pohybu a červená nebo zelená LED, podle toho, jestli se zrovna topilo, nebo chladilo. Po spuštění služby tyto LED nejdřív zhasnou a potom se rozsvítí podle stavu domu (nebo zůstanou zhasnuté).

**Log** (`sudo` přidej, když to napíše nedostatečná práva):

```bash
journalctl -u domek -f    # log naživo, Ctrl+C ukončí
journalctl -u domek -b    # log od posledního startu Raspberry
```

| Řádek v logu | Co znamená |
|---|---|
| `BLE periferie "Termostat" bezi na adapteru ...` | Bluetooth server běží, dům je vidět pro tablet |
| `Pripojeno zarizeni ...`, `Odpojeno zarizeni ...` | tablet se připojil nebo odpojil (na displeji se rozsvítí a zhasne `BLE`) |
| `Odber ... zapnut.` | tablet zapnul notifikace charakteristiky |
| `Nastaveni zmeneno: ...`, `Proveden prikaz: ...` | tablet zapsal platné nastavení nebo příkaz |
| `Zapis do Nastaveni odmitnut: ...` (`...Prikazu...`) | neplatný zápis, důvod je jen tady, tablet dostane obecnou chybu; nastavení se nezměnilo |
| `Porucha meteo: ...`, `Porucha meteo skoncila.` | čidlo se přestalo číst a zase čte (stejně `osvetleni`, `displej`, `svetlo` a `nastaveni`). Názvy chyb vysvětluje [Bluetooth rozhraní](docs/ble-rozhrani.md), oddíl „Hodnoty v poli `chyby`“ |
| `BlueZ odmitl registraci ...` | Bluetooth odmítl registrovat služby; program skončí a systemd ho po 2 s spustí znovu |
| `BlueZ zrusil inzerat domku ...`, `BlueZ (bluetoothd) prestal bezet ...` | registrace domu v Bluetooth za běhu zanikla (BlueZ inzerát zrušil, bluetoothd skončil nebo spadl); program skončí a systemd ho po 2 s spustí znovu, takže se zaregistruje znovu |
| `Neocekavana chyba v casovaci ...` a výpis chyby | něco neočekávaného; program skončí a systemd ho spustí znovu |
| `Neocekavana chyba pri obsluze Bluetooth ...` a výpis chyby | chyba v programu při čtení, zápisu nebo zapnutí notifikací z tabletu; tablet dostal chybu, dům běží dál. Výpis chyby ukazuje, kde program opravit |

Displej ukazuje (příklad s připojeným tabletem):

```
10:05 den         BLE     čas a úsek dne; BLE = je připojený tablet
Uvnitr 22.6 cil 23.0      simulovaná vnitřní teplota a cíl
Venku  22.0C 1013hPa      měřená venkovní teplota a tlak (vlhkost je jen v aplikaci)
TOPI   auto               TOPI, CHLADI nebo klid; režim; "pryc" = nikdo doma
Svetlo vyp                světlo; "rucne" = nastavené z tabletu
CHYBA meteo               jen když nějaká porucha trvá
```

## Ověření automatického spuštění

Cíl: dům se po zapnutí napájení rozběhne sám a po pádu ho systemd spustí znovu.

1. **Restart.** Po instalaci spusť `sudo reboot`. Očekávání:
   - `systemctl status domek` ukazuje `active (running)` a podle řádku `Active:` (`since ...; 2min ago`) služba běží déle než minutu, takže se nerestartuje pořád dokola,
   - do minuty po zapnutí, bez SSH, se na displeji ukáže stav a nRF Connect se připojí,
   - v logu od startu není za posledním řádkem `BLE periferie "Termostat" bezi ...` žádný výpis chyby (`Traceback`). Spočítá je příkaz `journalctl -u domek -b -o cat | awk '/BLE periferie/ {n = 0} /Traceback/ {n++} END {print n}'`, který má vypsat `0`. Výpis chyby před tím řádkem je v pořádku: když Bluetooth naběhne později než dům, první start skončí chybou a za 2 s se dům rozběhne, viz „Po zapnutí dům neběží“ v části „Když něco nejde“.
2. **Restart po pádu.** Zapiš si PID z řádku `Main PID` v `systemctl status domek`, potom spusť:

   ```bash
   sudo pkill -f "python -m domek"
   ```

   a za 3 s znovu `systemctl status domek`. Očekávání: služba běží s novým PID.
3. **Restart Bluetooth.** Služba je s `bluetooth.service` svázaná (`PartOf=`), protože po jeho restartu BlueZ registraci domu zahodí, takže se musí restartovat i dům. Zapiš si PID jako v předchozím kroku a spusť `sudo systemctl restart bluetooth`. Očekávání: za pár sekund `systemctl status domek` ukáže `active (running)` s novým PID a tablet dům zase najde.
4. **Pád Bluetooth.** Když bluetoothd skončí jinak než příkazem `systemctl`, třeba pádem, na `PartOf=` se spolehnout nedá a dům to musí poznat sám. Připoj se z nRF Connect, zapiš si PID jako v kroku 2 a spusť `sudo pkill -KILL -x bluetoothd` (napodobí pád). Očekávání: za pár sekund `systemctl status domek` ukáže `active (running)` s novým PID, `systemctl status bluetooth` zase `active (running)` a tablet `Termostat` znovu najde. V `journalctl -u domek -b` je u toho řádek `BlueZ (bluetoothd) prestal bezet ...`; chybět může, jen když dům stihl restartovat dřív systemd. bluetoothd znovu spustí buď systemd (když má `bluetooth.service` v `systemctl cat bluetooth` řádek `Restart=on-failure`), nebo D-Bus, jakmile se dům po svém restartu zeptá BlueZ na adaptér. Kdyby `systemctl status bluetooth` ani po minutě neukazoval `active (running)` (dům se pak restartuje pořád dokola), spusť ho příkazem `sudo systemctl start bluetooth`, dům se potom rozběhne sám. Zapiš si, jestli to bylo potřeba: po skutečném pádu bluetoothd by tak dům bez zásahu nenaběhl.

## Zkouška přes nRF Connect

Bluetooth jde vyzkoušet aplikací nRF Connect (Android) i bez vlastní aplikace. Párování není potřeba, jen se připoj. Přesný popis charakteristik a formátů je v [Bluetooth rozhraní](docs/ble-rozhrani.md). Zápisy se posílají jako **TEXT** (UTF-8). V nRF Connect je šipka dolů čtení, šipka nahoru zápis a ikona se třemi šipkami zapnutí notifikací.

1. Služba běží (`systemctl status domek`). V nRF Connect spusť hledání, najdi `Termostat` a připoj se. Na displeji se objeví `BLE`.
2. **Hned po připojení vyžádej MTU 517** (v nabídce připojeného zařízení, tři tečky vpravo nahoře, položka *Request MTU*; podle verze aplikace se může jmenovat trochu jinak). Výchozí MTU je 23 a notifikace pak unese jen 20 bajtů, takže by se JSON ze Stavu useknul. nRF Connect ukáže, jaké MTU se vyjednalo. Má být 517, nejméně 515: s menším by se notifikace mohly useknout, protože JSON má nejvýš 512 B a záhlaví zabere 3 B.
3. Najdi dvě služby. Environmental Sensing (`0x181A`) má teplotu `0x2A6E`, vlhkost `0x2A6F` a tlak `0x2A6D`; nRF Connect je umí rovnou přeložit. Vlastní služba Dům (`5bf00001-f758-4c41-a34e-bab58ce87673`) má tři charakteristiky:

   | Charakteristika | UUID | Co s ní |
   |---|---|---|
   | Stav | `5bf00010-f758-4c41-a34e-bab58ce87673` | čtení a notifikace |
   | Nastavení | `5bf00011-f758-4c41-a34e-bab58ce87673` | čtení, zápis a notifikace |
   | Příkaz | `5bf00012-f758-4c41-a34e-bab58ce87673` | jen zápis |

4. **Stav:** zapni notifikace. Každou sekundu má přijít celý platný JSON. Teplota ESS se má rovnat `t_venku`, `lux` je číslo a `chyby` je prázdné. Protože je na adrese `76` BMP280 (ID čipu `0x58` ze zkoušky `bme280`), je `vlhkost` `null` a vlhkost v ESS `0xFFFF`, tedy neznámá, a `chyby` je přesto prázdné.
5. **Nastavení:** přečti ho, má mít 14 klíčů. Zapni notifikace a zapiš `{"cil_den":26}`. Zápis má projít, má přijít jedna notifikace Nastavení se všemi klíči a Stav s `"cil":26.0` (ve dne a večer; v noci platí `cil_noc`). Když je vnitřní teplota `t_uvnitr` pod cílem, svítí červená LED.
6. **Odmítnutý zápis:** zapiš `{"cil_den":99}`. nRF Connect má ukázat chybu zápisu, nastavení se nesmí změnit (přečti ho, `cil_den` je pořád `26.0`) a v logu je `Zapis do Nastaveni odmitnut`.
7. **Příkaz:** zapiš `{"svetlo":"zapnout"}`. Stav s `"zap":true` má přijít hned, na displeji je `Svetlo zap rucne` a rozsvítí se RGB LED barvou z nastavení (výchozí je bílá). `{"svetlo":"auto"}` ji vrátí do automatiky. Zápis `{"svetlo":"blikat"}` skončí chybou. Čtení Příkazu skončí chybou, má jen zápis.
8. **Pohyb:** mávni rukou před HC-SR501. Do sekundy má přijít Stav s `"pohyb":true` a zároveň se rozsvítí oranžová LED semaforu. Zhasne asi 10 s potom, co Stav ukáže `"pohyb":false`.
9. **Odpojení:** odpoj se. `BLE` zmizí z displeje. Připoj se znovu (znovu vyžádej MTU 517) a zapni notifikace znovu, musí zase chodit. Zkus to i po delší pauze nebo po vypnutí a zapnutí Bluetooth na tabletu.
10. **Čtení při malém MTU:** odpoj se, připoj se znovu a MTU tentokrát nevyžaduj. Přečti Stav i Nastavení: oboje se má přečíst jako celý platný JSON. Tím se ověří čtení po částech, které server řeší po svém. Když nRF Connect nebo systém tabletu zvýší MTU sám, krok přeskoč: malé MTU se pak nevyzkouší.
11. **Výpadek čidla:** na chvíli vytáhni vodič SDA z BMP280. Stav má mít `"chyby":["meteo"]`, obě LED topení a chlazení zhasnou, hodnoty ESS se ukážou jako neznámé a v logu je `Porucha meteo` jen jednou. Po zapojení se všechno samo obnoví. Stejně to jde s TSL2561: `lux` je pak `null` a v `chyby` je `osvetleni`.
12. **Pád služby při připojeném tabletu:** zůstaň připojený (s MTU 517 a zapnutými notifikacemi) a na Raspberry spusť `sudo systemctl kill domek`. Systemd službu za asi 2 s spustí znovu. Sleduj, jestli notifikace Stavu po restartu pokračují. Když ne, odpoj se a připoj znovu. Výsledek je důležitý pro Android aplikaci: musí umět takové spojení obnovit. Po restartu přečti Nastavení: `cil_den` má být pořád `26.0` z kroku 5, protože dům nastavení po startu načetl ze souboru.
13. **Úklid:** zapiš `{"cil_den":23}`, nebo vrať všechno na výchozí hodnoty, viz „Výchozí nastavení“.

## Scénář předvedení

Před začátkem zkontroluj, že `cas` ve Stavu odpovídá skutečnému času a že `chyby` je prázdné. Bez internetu hodiny Raspberry po vypnutí napájení nejdou správně. Čas se nastaví zápisem do Příkazu, např. `{"cas":"2026-10-20T14:03:00"}` (místní čas, přesně ve tvaru `RRRR-MM-DDTHH:MM:SS`).

1. **Zapni napájení Raspberry.** Dům se rozběhne sám, bez notebooku, SSH a internetu. Displej ukáže čas a teploty.
2. **Připoj tablet** k `Termostat` a zapni notifikace Stavu. Tablet vidí živá data každou sekundu a na displeji je `BLE`.
3. **Zvyš cílovou teplotu**, třeba zápisem `{"cil_den":26}` do Nastavení (v noci platí `cil_noc`). Rozsvítí se červená LED a vnitřní teplota v tabletu i na displeji stoupá. Po dosažení cíle je vidět, jak se topení zapíná a vypíná.
4. **Sniž cílovou teplotu** hluboko pod vnitřní, třeba `{"cil_den":20}`. Chlazení se zapne, když je vnitřní teplota nad cílem o víc než 1 °C: rozsvítí se zelená LED chlazení a teplota klesá.
5. **Zahřej BMP280 rukou.** Venkovní teplota stoupne, vnitřní se k ní pomalu přizpůsobuje a termostat reaguje.
6. **Zakryj TSL2561 (ve dne).** Rozsvítí se RGB LED barvou z nastavení (výchozí je bílá). Po odkrytí zhasne.
7. **Posuň začátek noci na aktuální čas**, třeba `{"zacatek_noc":"14:30"}`, když je 14:30. Čas musí být jiný než začátek dne a večera. Ukáže se noční režim: světlo zhasne a pohyb před čidlem rozsvítí slabé noční světlo na 30 s.
8. **Mávni rukou před HC-SR501.** Rozsvítí se oranžová LED pohybu, ve dne i v noci.

### Výchozí nastavení

Nastavení z tabletu se ukládá do `/var/lib/domek/nastaveni.json` a přežije restart, takže po předvedení (třeba po posunu začátku noci) vrať výchozí hodnoty. Buď zápisem do Nastavení, např. `{"cil_den":23,"zacatek_noc":"22:00"}`, nebo úplně na Raspberry:

```bash
sudo rm -f /var/lib/domek/nastaveni.json
sudo systemctl restart domek
```

Výchozí hodnoty jsou cíl ve dne 23 °C, v noci 20 °C a ručně 22 °C, začátky dne, večera a noci 07:00, 19:00 a 22:00, práh šera 50 lx, noční světlo 30 s, nikdo doma po 30 min s útlumem 3 °C, noční světlo se při pohybu rozsvítí (`nocni_svetlo_pri_pohybu` je `true`), barva světla je bílá (`barva_svetla` `[255,255,255]`) a jas 100 % (`jas_svetla`).

## Pozor na main.py

`main.py` ve složce `raspberry/` je původní zkouška Bluetooth (teplota procesoru a textová zpráva). Dům ho nepoužívá a **nesmí běžet zároveň se službou `domek`**: obě aplikace by na jednom adaptéru zveřejňovaly periferii `Termostat` se stejnými službami a dvě aplikace GATT se pobijí. Když na Raspberry ještě běží `main.py` z dřívějších zkoušek, ukonči ho před prvním spuštěním služby. Chceš-li ho znovu zkusit, nejdřív `sudo systemctl stop domek` a potom službu zase spusť příkazem `sudo systemctl start domek`.

## Když něco nejde

- **`i2cdetect -y 1` neukáže `39`, `3c` nebo `76` (případně `77`).** Zkontroluj zapojení SDA a SCL, napájení 3,3 V a že je zapnuté I2C (instalace ho zapíná, ale je potřeba restart). U TSL2561 nechej ADDR nezapojený. Přehled všech zkoušek je v části „Ověření součástek“.
- **`lux` ve Stavu je `null` a v `chyby` je `osvetleni`.** TSL2561 se nedá přečíst: zkontroluj zapojení, napájení 3,3 V a ADDR (nezapojený) a spusť zkoušku `tsl2561`. Dokud čidlo chybí, světlo se ve dne nerozsvítí.
- **`vlhkost` ve Stavu je `null`, ale `chyby` je prázdné.** Čidlo na adrese `76` je BMP280, které vlhkost neměří, a to porucha není. Zkouška `bme280` ukáže ID čipu `0x58`, upozornění `POZOR` a vlhkost `--`. Teplota a tlak platí, vlhkost je neznámá (v ESS `0xFFFF`; displej vlhkost neukazuje, ukazuje tlak). Je-li `vlhkost` `null` a v `chyby` je `meteo`, čidlo se nedá přečíst (zapojení, napájení 3,3 V, adresa).
- **RGB LED nesvítí nebo svítí jinou barvou.** Zkontroluj, že ve Stavu je `"svetlo":{"zap":true,...}` (ve dne svítí jen při šeru, viz „Čidlo osvětlení TSL2561“). Pak ji vyzkoušej zkouškou `rgb` (nejdřív zastav službu) a zkontroluj zapojení R, G a B (piny 36, 38, 40), rezistory a společnou katodu na GND (pin 34). Společná anoda se nepodporuje. Služba skončí chybou, když pin už používá jiný program: důvod je v `journalctl -u domek -b`. Chyba `svetlo` ve Stavu znamená, že se LED nepodařilo zapsat, po návratu GPIO zmizí sama.
- **Oranžová LED nesvítí při pohybu.** Nejdřív zastav službu a vyzkoušej čidlo zkouškou `pir`: když nehlásí pohyb, je chyba v čidle a jeho zapojení. Pak vyzkoušej samotnou LED zkouškou `led` a zkontroluj zapojení vodiče Y na GPIO22 (pin 15) a GND. Služba skončí chybou, když pin GPIO22 už používá jiný program: důvod je v `journalctl -u domek -b`.
- **Po zapnutí hlásí dům chyby `meteo`, `osvetleni` i `displej` a v logu je `No such file or directory: '/dev/i2c-1'`.** I2C je vypnuté. Nejčastěji proto, že Raspberry přišlo o napájení krátce po zápisu do `/boot/firmware/config.txt` (třeba hned po instalaci) a soubor zůstal prázdný: `ls -la /boot/firmware/config.txt` ukáže velikost 0. Oprava: `sudo raspi-config nonint do_i2c 0 && sudo sync && sudo reboot`. Prevence: Raspberry nikdy nevytahuj ze zásuvky za chodu, nejdřív `sudo poweroff` a počkej, až zelená LED na desce přestane blikat.
- **Tablet `Termostat` nenajde.** Zkontroluj `systemctl status domek` a v logu řádek `BLE periferie "Termostat" bezi ...`. Ověř, že neběží `main.py`. Zapni a vypni Bluetooth na tabletu: Android si pamatuje strukturu služeb zařízení a po výměně programu na Raspberry ukazuje starou.
- **Po zapnutí dům neběží.** Zkontroluj `systemctl status domek`, `systemctl status bluetooth` a log od startu: `journalctl -b -u domek`. Dům se spouští po `bluetooth.service` a restartuje se s ním. Když Bluetooth naběhne později než dům (u `bluetooth.service` je v `systemctl status` nesplněná podmínka `Condition`, protože adaptér ještě nebyl vidět), dům při neúspěšné registraci skončí a systemd ho po 2 s spustí znovu, takže se za chvíli rozběhne sám. Neběží-li ani po minutě, hledej důvod v logu (třeba `BlueZ odmitl registraci ...`) a zkus `sudo systemctl restart domek`. Zastavení Bluetooth (`sudo systemctl stop bluetooth`) zastaví i dům, ten pak spusť příkazem `sudo systemctl start domek`.
- **Služba se pořád restartuje** (v `journalctl -u domek -f` se opakuje start). Důvod je v logu těsně před koncem programu, např. `BlueZ odmitl registraci ...`. Pak zkontroluj, že neběží `main.py` a že je Bluetooth zapnutý: `bluetoothctl show` musí v řádku `Powered:` ukázat `yes`. Když ukazuje `no`, odblokuj a zapni ho a dům restartuj:

  ```bash
  sudo rfkill unblock bluetooth
  bluetoothctl power on
  sudo systemctl restart domek
  ```
- **Zápis `cas` z nRF Connect skončí chybou.** Důvod je v logu (`journalctl -u domek -b`), v řádku `Zapis do Prikazu odmitnut: Cas se nepodarilo nastavit: ...`. Text `Prikaz 'date -s ...' skoncil s kodem ...` znamená, že se systémový čas nenastavil.
- **Hodiny Raspberry jdou špatně.** Bez internetu nemá Raspberry po zapnutí správný čas (nemá RTC): nastav ho příkazem do Příkazu, viz začátek části „Scénář předvedení“. Kdyby se čas lišil o celé hodiny, je špatně časové pásmo: zkontroluj ho příkazem `timedatectl` (řádek `Time zone`), nastavení je v části „Raspberry: první instalace“, krok 1.
