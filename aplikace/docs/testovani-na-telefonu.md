# Testování aplikace „Chytrý dům“ na telefonu

Návod pro ruční zkoušku na skutečném telefonu a s Raspberry Pi. Jednotkové testy ověřují logiku a vzhled na počítači. Bluetooth, oprávnění, otáčení a písmo Samsungu jde ověřit jen na zařízení.

## Než začneš

Potřebuješ:
- telefon Samsung Galaxy A25 5G (Android 15), na kterém se bude předvádět;
- USB kabel, který přenáší data (ne jen nabíjecí);
- počítač s Android Studiem a tímto projektem;
- Raspberry Pi 3B+ s programem „Termostat“ (jen pro části 3 a 4; části 1 a 2 běží bez něj);
- volitelně tablet Galaxy Tab A8 (Android 14) a druhý telefon s aplikací nRF Connect.

Jak s návodem pracovat:
- Odškrtni políčko `- [ ]` až ve chvíli, kdy krok opravdu splníš.
- **Čekáš:** říká, co má aplikace ukázat. Když se to liší, napiš si, co jsi udělal a co se stalo. Pak se podívej do části 5.
- Názvy tlačítek a hlášek jsou přesně z aplikace.
- Simulace běží v reálném čase. Zvýšení cíle o 3 °C trvá asi minutu.
- Když máš málo času, projdi jen 1, 2.1 až 2.7, 3.2, 3.3, 4.1 a 4.2. To pokryje pět scénářů a výpadek ze zadání.

Nahoře vpravo je vždy štítek stavu spojení. Klepnutí na něj otevře Nastavení.

| Štítek | Barva | Znamená |
|---|---|---|
| Připojeno | zelená | spojení běží a data jsou čerstvá |
| Hledám… | šedá | hledá se dům „Termostat“ |
| Vyber dům | šedá | v dosahu je víc domů, čeká se na výběr |
| Připojuji… | modrá | připojování a příprava spojení |
| Stará data · N s | oranžová | přes 3 s nepřišel nový Stav |
| Odpojeno · znovu za N s | červená | spojení spadlo, další pokus za N s |
| Odpojeno | červená | odpojil jsi ručně |
| Chybí oprávnění, Bluetooth vypnutý, Poloha vypnutá, Bez Bluetooth, Neznámé zařízení | červená | viz 1.4, 4.3, 4.5, 4.6 a 4.11 |
| Simulace | fialová | zdroj je simulace |

## 1. Instalace

### 1.1 Telefon (Samsung): zapnout ladění přes USB

- [ ] Otevři Nastavení → O telefonu → Informace o softwaru.
- [ ] Klepni 7× na „Číslo sestavení“ a zadej PIN nebo heslo. **Čekáš:** hláška, že jsi vývojář.
- [ ] Vrať se do Nastavení a úplně dole otevři „Možnosti pro vývojáře“.
- [ ] Zapni přepínač nahoře a potom „Ladění přes USB“.
- [ ] Připoj telefon kabelem k počítači.
- [ ] Na telefonu odpověz na „Povolit ladění USB?“. Zaškrtni „Vždy povolit z tohoto počítače“ a klepni na „Povolit“.
- [ ] Když telefon počítač nevidí, přepni USB v oznámení na „Přenos souborů“.
- [ ] Když se ladění pořád nepovolí, vypni v Nastavení → Zabezpečení a soukromí funkci Auto Blocker. Blokuje příkazy po USB kabelu. Název se může lišit podle verze One UI.

### 1.2 Z Android Studia (Run)

- [ ] Otevři projekt `MyApplication`. Počkej, až skončí Gradle sync.
- [ ] V seznamu zařízení vedle tlačítka Run vyber telefon (třeba „Samsung SM-A256B“).
- [ ] Klepni na zelenou šipku Run (Shift+F10). **Čekáš:** build skončí a aplikace „Chytrý dům“ se spustí na telefonu.
- [ ] Telefon v seznamu chybí? Zkontroluj kabel, režim USB a povolení ladění (1.1). Pomůže i Tools → Troubleshoot Device Connections.
- [ ] Totéž udělej pro tablet, pokud ho testuješ.

### 1.3 Příkazem `adb`

- [ ] Sestav APK. V Git Bash v kořeni projektu:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug --console=plain
```

- [ ] **Čekáš:** `BUILD SUCCESSFUL` a soubor `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] `adb` není v PATH. Leží v `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. V PowerShellu si pro tuto relaci udělej zkratku (níž pak stačí psát `adb`):

```powershell
Set-Alias adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
adb devices
```

- [ ] **Čekáš:** řádek se sériovým číslem a slovem `device`. Při `unauthorized` potvrď dotaz na telefonu. Při `offline` odpoj a znovu připoj kabel.
- [ ] Nainstaluj a spusť (z kořene projektu):

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cz.vsb.mitr.domek/.MainActivity
```

- [ ] **Čekáš:** `Success` a aplikace se spustí.
- [ ] První spuštění jde zopakovat: `adb shell pm clear cz.vsb.mitr.domek` smaže data i oprávnění (nebo aplikaci odinstaluj). Samotné `install -r` oprávnění zachová.

### 1.4 První spuštění a oprávnění

- [ ] Aplikace se spustí bez pádu. Pod ikonou je název „Chytrý dům“.
- [ ] **Čekáš:** nahoře červený štítek „Chybí oprávnění“. Uprostřed „Aplikace potřebuje přístup k Bluetooth“ a „Bez něj nenajde dům v okolí.“ Dole tlačítka „Povolit“ a „Spustit simulaci“.
- [ ] Zapni na telefonu Bluetooth (pokud není).
- [ ] Klepni na „Povolit“. **Čekáš:** systémový dialog pro „Zařízení v okolí“.
- [ ] Klepni v dialogu na „Povolit“. **Čekáš:** hned začne hledání: „Hledám dům „Termostat““, pulzující ikona Bluetooth a štítek „Hledám…“.
- [ ] Bez Raspberry hledání běží dál. To je správně. Pokračuj částí 2.

## 2. Simulace (bez Raspberry)

### 2.1 Spuštění a prohlídka

- [ ] Klepni na „Spustit simulaci“. **Čekáš:** do 2 s Přehled s nadpisem „Chytrý dům“ a fialovým štítkem „Simulace“.
- [ ] Prohlédni Přehled shora. Je tam karta termostatu (úsek dne s časem, „Režim Auto“, velká teplota „Uvnitř“, štítek Topí, Chladí nebo V klidu, tlačítka − a +), graf „Teploty“ a šest dlaždic: Venku, Vlhkost, Tlak, Osvětlení, Pohyb, Světlo. Úplně dole je karta „Ovládání simulace“ (posuň obrazovku).
- [ ] Čísla mají desetinnou čárku a mezeru před jednotkou: `22,4 °C`, `45 %`, `1013 hPa`. Jednotka nikdy nepřeskočí na další řádek.
- [ ] Graf se každou sekundu prodlužuje. Přepínač „5 min · 15 min · Vše“ mění okno grafu.
- [ ] Klepni na dlaždici „Světlo“. **Čekáš:** otevře se záložka Světlo.
- [ ] Klepni na štítek nahoře. **Čekáš:** otevře se Nastavení.
- [ ] Projdi záložky Termostat, Světlo, Deník a Nastavení. **Čekáš:** dole (na šířku vlevo) je pět záložek, nic není oříznuté ani zašedlé, v simulaci všechno funguje.
- [ ] Na záložce Termostat je v horní kartě řádek „Topení naskočí pod 22,7 °C, chlazení nad 24,0 °C“ (čísla podle platného cíle).

### 2.2 Scénář 1: zvýšit cíl

- [ ] Na Přehledu klepni 4× na „+“ (cíl o 2 °C výš). **Čekáš:** hodnota u popisku „Denní cíl“ stoupne hned (třeba 23,0 → 25,0 °C). V noci je popisek „Noční cíl“.
- [ ] Vedle čísla se na chvíli objeví malé kolečko (odesílání). Číslo ani tlačítka se přitom neposunou.
- [ ] **Čekáš:** do několika sekund štítek „Topí“ (červený) a teplý nádech karty. Cíl se odešle 0,6 s po posledním klepnutí.
- [ ] Sleduj asi minutu. **Čekáš:** „Uvnitř“ roste zhruba o 3 °C za minutu. V grafu je červený pruh a čárkovaná čára cíle skočila nahoru.
- [ ] Teplota dosáhne cíle. **Čekáš:** štítek „V klidu“ (šedý), nádech zmizí a červený pruh skončí.
- [ ] Podrž „+“. **Čekáš:** po 0,4 s se hodnota začne opakovaně měnit (asi 10 kroků za sekundu). Po puštění se odešle poslední hodnota.
- [ ] Klepni na číslo cíle. **Čekáš:** dialog s polem a textem „Povoleno je 5–35 °C“. Napiš 24,5 a klepni na „Uložit“. Hodnota se změní.

### 2.3 Scénář 2: snížit cíl

- [ ] Klepni 8× na „−“ (cíl o 4 °C níž). **Čekáš:** štítek „Chladí“ (modrý) a studený nádech karty. Chlazení naskočí, jen když je uvnitř o víc než 1 °C tepleji než cíl, stejně jako na Raspberry.
- [ ] **Čekáš:** „Uvnitř“ klesá zhruba o 3 °C za minutu. V grafu je modrý pruh a čára cíle skočila dolů.
- [ ] Teplota dosáhne cíle. **Čekáš:** „V klidu“.
- [ ] Vrať cíl na 23,0 °C (klepni na číslo, napiš 23, „Uložit“).

### 2.4 Scénář 3: zahřát čidlo

- [ ] Než začneš, počkej, až je termostat „V klidu“ a „Uvnitř“ je u cíle. Hned po scénáři 2 se dům ještě asi minutu dohřívá a chlazení by za 30 s nestihlo naskočit.

- [ ] Klepni na „Zahřát čidlo“ (karta „Ovládání simulace“). **Čekáš:** dlaždice „Venku“ skočí asi o 8 °C výš, 30 s tak zůstane a pak se vrátí.
- [ ] **Čekáš:** šedá čára „Venku“ v grafu má na 30 s schod nahoru.
- [ ] **Čekáš:** „Uvnitř“ stoupá a asi 15–25 s po klepnutí se termostat přepne na „Chladí“ (chladí se až nad cílem o víc než 1 °C): modrý štítek, studený nádech karty a modrý pruh v grafu. Až teplota klesne na cíl, je zase „V klidu“.

### 2.5 Scénář 4: zakrýt čidlo světla

- [ ] Zkontroluj, že Přehled ukazuje „Den · HH:MM“. Když je večer nebo noc, otevři Nastavení a u „Den od“ klepni na „Teď“. **Čekáš:** v kartě „Denní doba“ stojí „teď je den“.
- [ ] Na Přehledu: dlaždice „Světlo“ ukazuje „Zhasnuto“. „Osvětlení“ je kolem 300 lx („světlo · práh 50“).
- [ ] Klepni na „Zakrýt čidlo světla“. **Čekáš:** „Osvětlení“ spadne na 5 lx („šero · práh 50“). Dlaždice „Světlo“ ukáže „Svítí“ a „auto · 100 %“.
- [ ] Otevři záložku Světlo. **Čekáš:** „Svítí · venku je šero“ a bílý kruh.
- [ ] Za 30 s **Čekáš:** světlo zhasne („Zhasnuto · venku je dost světla“).
- [ ] Když jsi měnil „Den od“, vrať časy: Nastavení → „Vrátit původní časy“.

### 2.6 Scénář 5: noc a noční světlo při pohybu

- [ ] Nastavení → u „Noc od“ klepni na „Teď“. **Čekáš:** řádek ukáže aktuální čas, nahoře „teď je noc“. Na Přehledu „Noc · HH:MM“ s ikonou měsíce. Cíl je noční („Noční cíl“).
- [ ] Na Přehledu klepni na „Pohyb“. **Čekáš:** dlaždice „Pohyb“ se na 3 s zvýrazní a ukáže „Ano“. Dlaždice „Světlo“ ukáže „Svítí“ a „auto · 10 %“ (noční světlo svítí nejvýš na 10 %).
- [ ] Otevři záložku Světlo. **Čekáš:** „Noční světlo · pohyb“. Po 30 s „Zhasnuto · noc“.
- [ ] Světlo → Automatika → vypni přepínač „Rozsvítit při pohybu“. Klepni znovu na „Pohyb“. **Čekáš:** světlo se nerozsvítí. Kdyby svítilo, zhasne hned po vypnutí přepínače.
- [ ] Zapni přepínač zpět.
- [ ] Nastavení → „Vrátit původní časy“. **Čekáš:** časy se vrátí jedním zápisem, tlačítko zešedne a úsek dne se vrátí podle původních časů.

### 2.7 Výpadek dat (stará data a obnovení)

- [ ] Na Přehledu klepni na „Výpadek dat“. Simulace 10 s neposílá Stav.
- [ ] **Čekáš:** po 3–4 s oranžový štítek „Stará data · N s“ (N roste). Nahoře oranžová karta „Data jsou N s stará“ s textem „Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.“
- [ ] **Čekáš:** hodnoty na Přehledu, Termostatu a Světle zešednou. Graf se nemaže.
- [ ] Po asi 10 s **Čekáš:** štítek „Simulace“ (fialový), oranžová karta zmizí, hodnoty mají zase plnou barvu a graf pokračuje. V čarách je mezera.

### 2.8 Porucha čidla

- [ ] Klepni na „Porucha BME280“. Štítek se zaškrtne. **Čekáš:** nahoře červená karta „Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá“ s textem „Venkovní teplota, vlhkost a tlak chybí. Dokud čidlo nejde přečíst, termostat netopí ani nechladí.“ Termostat je „V klidu“ a „Uvnitř“ se nemění.
- [ ] **Čekáš:** dlaždice Venku, Vlhkost a Tlak ukazují „—“ s poznámkou „chyba čidla“. Čára „Venku“ v grafu se přeruší.
- [ ] Klepni znovu. **Čekáš:** karta zmizí a hodnoty se vrátí.

### 2.9 Režimy a „nikdo doma“

- [ ] Otevři záložku Termostat, v kartě „Režim“ klepni na „Ruční“. **Čekáš:** řádek „Ruční“ v „Cílové teploty“ má štítek „platí teď“. Na Přehledu je u − a + popisek „Ruční cíl“.
- [ ] Klepni na „Vypnuto“. **Čekáš:** na Přehledu „Termostat je vypnutý“ a tlačítko „Zapnout (Auto)“, štítek „Vypnuto“. Nic netopí ani nechladí.
- [ ] Klepni na „Zapnout (Auto)“. **Čekáš:** zase režim Auto.
- [ ] Termostat → karta „Nikdo doma“ → klepni na číslo u „Snížit cíl po“, napiš 1 a klepni na „Uložit“.
- [ ] **Čekáš:** do minuty „Nikdo doma · cíl je snížený o 3,0 °C“. Na Přehledu je nad − a + štítek „Nikdo doma · cíl -3,0 °C, platí …“.
- [ ] Přepni na „Ruční“. **Čekáš:** „Nikdo doma · v ručním režimu se cíl nesnižuje“, na Přehledu „Nikdo doma · platí …“ bez snížení. Raspberry snižuje cíl jen v Auto. Vrať „Auto“.
- [ ] Na Přehledu klepni na „Pohyb“. **Čekáš:** na Termostatu „Doma je někdo · poslední pohyb před N s“ a štítek „Nikdo doma“ zmizí.
- [ ] Vrať „Snížit cíl po“ na 30 min.

### 2.10 Odmítnutý zápis

- [ ] Nastavení → u „Večer od“ klepni na „Teď“. Počkej 2 s.
- [ ] U „Noc od“ klepni také na „Teď“ (v téže minutě). **Čekáš:** dole hláška „Raspberry změnu odmítlo: začátek noci HH:MM je stejný jako začátek večera. Nic se nezměnilo.“ s tlačítkem OK.
- [ ] **Čekáš:** řádek „Noc od“ ukazuje zase původní čas, ne odmítnutý. Štítek nahoře se nezmění.
- [ ] Klepni na OK. **Čekáš:** hláška zmizí.
- [ ] Jde to i přes výběr času: klepni na čas u „Noc od“, na ciferníku nastav stejný čas jako u „Večer od“ a klepni na „Uložit“. Výsledek je stejný.
- [ ] Nastavení → „Vrátit původní časy“. **Čekáš:** „Večer od“ je zase původní (třeba 19:00).

### 2.10a Deník

- [ ] Po scénářích otevři záložku Deník. **Čekáš:** nejnovější události nahoře s časem telefonu, třeba „Začalo chladit · Uvnitř 24,1 °C, cíl 23,0 °C“, „Světlo se rozsvítilo“, „Pohyb“, „Denní cíl: 23,0 → 26,0 °C · změna z aplikace“.
- [ ] Řádek tlačítka simulace („Simulace: zahřáté čidlo“) je pod tím, co vyvolal (účinek je vždy nad příčinou).
- [ ] Klepni na filtry Termostat, Světlo, Pohyb a Spojení a chyby. **Čekáš:** zůstanou jen události dané skupiny; „Vše“ ukáže všechno.
- [ ] Několikrát za sebou klepni na „Pohyb“ (Přehled → Ovládání simulace). **Čekáš:** pohyby bez jiné události mezi sebou se sloučí do řádku „Pohyb · N× od HH:MM:SS“.
- [ ] Táhni posuvníkem jasu. **Čekáš:** jeden řádek pro celý tah, ne řádek za každou hodnotu.
- [ ] Zhasni displej na minutu a zase ho rozsviť. **Čekáš:** v deníku nepřibude „Data jsou stará“ jen kvůli uspání telefonu.

### 2.11 Přepínání zdroje

- [ ] Nastavení → Zdroj dat → „Raspberry přes Bluetooth“. **Čekáš:** graf se vymaže a začne hledání. Bez Raspberry zůstane „Hledám dům „Termostat““ a funguje „Spustit simulaci“.
- [ ] Nastavení → Zdroj dat → „Simulace“. **Čekáš:** nová simulace, graf i deník začínají od nuly („Simulace spuštěna“).
- [ ] Přepni zdroj 5× rychle za sebou. **Čekáš:** žádný pád ani zamrznutí. V logu není „Hledání selhalo (kód 6)“ a hledání se rozběhne nejpozději 6 s po předchozím startu.
- [ ] Zavři aplikaci úplně: v přehledu posledních aplikací ji smaž (tlačítko Zpět ji jen odloží na pozadí), nebo zadej `adb shell am force-stop cz.vsb.mitr.domek`. Spusť ji znovu. **Čekáš:** začíná hledáním Raspberry, ne simulací. Volba zdroje se neukládá.

### 2.12 Porovnání s náhledy (volitelně)

- [ ] V prohlížeči na počítači otevři `docs/navrh/2026-10-20-chytry-domek-obrazovky.html`.
- [ ] V simulaci pořiď screenshot každé ze čtyř záložek (Samsung: tlačítko Napájení + Snížit hlasitost). Nebo přes adb:

```powershell
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png telefon-prehled.png
```

- [ ] Porovnej rozvržení, barvy a texty s náhledem. Rozdíly si poznamenej.

## 3. S Raspberry

### 3.1 Příprava

- [ ] Raspberry je zapnuté a program „Termostat“ běží.
- [ ] Raspberry je u telefonu (do 2–3 m).
- [ ] Bluetooth v telefonu je zapnuté a oprávnění „Zařízení v okolí“ povolené (1.4).
- [ ] K Raspberry není připojené nic jiného (nRF Connect, telefon spolužáka).
- [ ] Víš, jaký čas má Raspberry (třeba `date` přes SSH). Bez internetu může jít špatně (3.5).

### 3.2 Připojení

- [ ] Zavři aplikaci úplně (smaž ji z přehledu posledních aplikací) a spusť ji znovu. **Čekáš:** aplikace začíná hledáním Raspberry.
- [ ] **Čekáš:** „Hledám dům „Termostat““ (štítek „Hledám…“), potom „Připojuji se k domu…“ s textem „Pokus č. 1“ (štítek „Připojuji…“). Do asi 10 s se ukáže Přehled.
- [ ] **Čekáš:** zelený štítek „Připojeno“.
- [ ] Nastavení → Zdroj dat. **Čekáš:** je vybrané „Raspberry přes Bluetooth“ a pod ním řádek `AA:BB:CC:DD:EE:FF · signál -61 dBm · MTU 517`.
- [ ] MTU je 517 (aspoň 515). Při 23 viz část 5.
- [ ] Signál je nejdřív „—“. Do 5 s se objeví a pak se mění každých 5 s.
- [ ] Počkej 1 minutu na Přehledu. **Čekáš:** hodnoty se mění každou sekundu a štítek se nepřepne na „Stará data“.
- [ ] Čas na kartě termostatu („Den · HH:MM“) souhlasí s časem na Raspberry.
- [ ] Hodnoty odpovídají skutečnosti: Venku podle čidla BME280, Osvětlení podle TSL2561.
- [ ] Mávni rukou před čidlem pohybu. **Čekáš:** dlaždice „Pohyb“ ukáže „Ano“ a pak „Ne“ s textem „před N s“.
- [ ] **Čekáš:** žádná červená karta s chybou, pokud je hardware v pořádku.

### 3.3 Pět scénářů ze zadání

Scénář 1, zvýšit cíl:
- [ ] Na Přehledu klepni 4× na „+“. **Čekáš:** hodnota se změní hned a malé kolečko zmizí do 3 s. Hodnota zůstane.
- [ ] **Čekáš:** štítek „Topí“, teplý nádech karty, červený pruh v grafu. Zvýšení o 3 °C trvá zhruba minutu.

Scénář 2, snížit cíl:
- [ ] Klepni 8× na „−“. **Čekáš:** „Chladí“, studený nádech, modrý pruh v grafu. Raspberry chladí, až je uvnitř o víc než 1 °C tepleji než cíl.
- [ ] Vrať cíl na původní hodnotu.

Scénář 3, zahřát čidlo rukou:
- [ ] Přilož prst nebo dlaň k čidlu BME280 na 30–60 s. **Čekáš:** „Venku“ stoupá a „Uvnitř“ se mění. Termostat zareaguje (přestane topit, nebo začne chladit). Přesnou reakci určuje program na Raspberry.
- [ ] Sundej ruku. **Čekáš:** hodnoty se pomalu vrátí.

Scénář 4, zakrýt čidlo osvětlení:
- [ ] Přehled musí ukazovat „Den“. Úsek určuje Raspberry podle svých hodin. Když ne, otevři Nastavení a u „Den od“ klepni na „Teď“.
- [ ] Zakryj čidlo TSL2561 dlaní. **Čekáš:** „Osvětlení“ klesne pod práh (výchozí 50 lx) a ukáže „šero“. Dlaždice „Světlo“ ukáže „Svítí“. LED na Raspberry svítí uloženou barvou a jasem (výchozí bílá na 100 %). Na záložce Světlo je „Svítí · venku je šero“.
- [ ] Odkryj čidlo. **Čekáš:** světlo zhasne, až osvětlení stoupne nad 1,3násobek prahu.
- [ ] V tmavé místnosti (pod 50 lx) svítí světlo pořád. Rozsviť, nebo sniž „Práh šera“ (Světlo → Automatika).
- [ ] Vrať původní časy (Nastavení → „Vrátit původní časy“), pokud jsi je měnil.

Scénář 5, noc a noční světlo při pohybu:
- [ ] Nastavení → u „Noc od“ klepni na „Teď“. **Čekáš:** „teď je noc“, na Přehledu „Noc · HH:MM“, světlo zhasne, platí noční cíl.
- [ ] Mávni před čidlem pohybu. **Čekáš:** dlaždice „Pohyb“ ukáže „Ano“ a LED svítí slabě jako noční světlo (uloženou barvou, nejvýš na 10 %). Na záložce Světlo je „Noční světlo · pohyb“.
- [ ] Počkej na konec „Noční světlo svítí“ (výchozí 30 s). **Čekáš:** „Zhasnuto · noc“ a LED zhasne.
- [ ] Nastavení → „Vrátit původní časy“.

### 3.4 Odmítnutý zápis

- [ ] Nastavení → u „Večer od“ klepni na „Teď“. Počkej 2 s.
- [ ] U „Noc od“ klepni také na „Teď“ (v téže minutě). **Čekáš:** dole hláška „Raspberry změnu odmítlo: začátek noci HH:MM je stejný jako začátek večera. Nic se nezměnilo.“ s tlačítkem OK.
- [ ] **Čekáš:** „Noc od“ ukazuje zase původní čas. Štítek zůstává „Připojeno“, spojení se kvůli odmítnutí nepřerušilo.
- [ ] Jde to i přes výběr času: „Noc od“ nastav na stejný čas jako „Večer od“.
- [ ] Nastavení → „Vrátit původní časy“.

### 3.5 Hodiny Raspberry

- [ ] Nastavení → karta „Čas Raspberry“. **Čekáš:** řádky „Raspberry“ a „Telefon“ s datem a sekundami. Rozdíl do 1–2 s je normální.
- [ ] Liší se hodiny o víc než minutu? **Čekáš:** oranžový řádek „Hodiny Raspberry jdou o … pozadu“ (nebo „napřed“). Na Přehledu se ukáže až při rozdílu nad 2 minuty, s tlačítkem „Srovnat“.
- [ ] Klepni na „Nastavit čas podle telefonu“ (nebo na Přehledu na „Srovnat“). **Čekáš:** do 1–2 s se časy shodnou a upozornění zmizí.
- [ ] Hodiny se shodují a chceš rozdíl vyvolat? Na Raspberry vypni synchronizaci času (`sudo timedatectl set-ntp false`) a posuň hodiny o pět minut zpět (`sudo date -s "5 minutes ago"`). Pak zopakuj předchozí dva kroky.
- [ ] Po testu na Raspberry synchronizaci zase zapni: `sudo timedatectl set-ntp true`.
- [ ] Po srovnání zkontroluj, že úsek dne (den, večer, noc) odpovídá novému času. Podle hodin Raspberry se řídí i „Teď“.

### 3.6 „Teď“ a „Vrátit původní časy“

- [ ] Nastavení → u „Den od“ klepni na „Teď“. **Čekáš:** čas v řádku se změní na aktuální čas Raspberry, pruh dne se překreslí a tlačítko „Vrátit původní časy“ se zapne.
- [ ] Klepni na čas u „Večer od“. **Čekáš:** dialog „Začátek večera“ s 24h ciferníkem. „Zrušit“ nic nezmění, „Uložit“ čas změní.
- [ ] Klepni na „Vrátit původní časy“. **Čekáš:** všechny tři časy se vrátí na hodnoty před první změnou a tlačítko zešedne.
- [ ] Klepni na „Teď“ a hned potom na „Vrátit původní časy“. **Čekáš:** konečný stav je původní trojice a žádná chybová hláška.

### 3.7 Světlo: tlačítka, barva, jas

- [ ] Záložka Světlo → „Zapnout“. **Čekáš:** LED svítí, text „Svítí · ručně“ a podtitul „Jas N % · ruční volba platí do HH:MM“.
- [ ] „Vypnout“. **Čekáš:** LED zhasne, „Zhasnuto · ručně“.
- [ ] „Vrátit automatiku“. **Čekáš:** text podle denní doby, třeba „Zhasnuto · venku je dost světla“. Tlačítko „Vrátit automatiku“ zešedne a zůstane tak, dokud znovu neklepneš na Zapnout nebo Vypnout.
- [ ] **Čekáš:** tlačítko „Přepnout“ na obrazovce není, stačí Zapnout a Vypnout.
- [ ] Rozsviť světlo („Zapnout“) a klepni na barvu (červená, modrá, zelená…). **Čekáš:** LED změní barvu hned, vybraná předvolba je označená a kruh v aplikaci má stejnou barvu. Světlo zůstane v ruční volbě, nepřepne se.
- [ ] Klepni na „vlastní“. **Čekáš:** dialog „Vlastní barva“ s posuvníky „Odstín“ a „Sytost“. Po „Použít“ má LED zvolenou barvu a označená je „vlastní“. „Zrušit“ nic nezmění.
- [ ] Táhni posuvníkem jasu. **Čekáš:** LED mění jas za tažení bez zasekávání. Po puštění zůstane poslední hodnota. Nejmenší jas je 1 %.
- [ ] Klepni na „Vypnout“, vyber jinou barvu a změň jas. **Čekáš:** LED zůstane zhasnutá, karta ukazuje novou barvu i jas. Vysvětlivka na kartě: „Barva a jas se uloží. Svítící světlo se hned přebarví, zhasnuté se jimi rozsvítí po zapnutí nebo v automatice.“
- [ ] Klepni na „Zapnout“. **Čekáš:** LED se rozsvítí právě vybranou barvou a jasem.
- [ ] Klepni na „Vrátit automatiku“ a počkej na večer (nebo nastav „Večer od“ → „Teď“). Změň barvu. **Čekáš:** světlo se přebarví a zůstane v automatice (podtitul „Svítí · večerní světlo“, ne „ručně“).
- [ ] Raspberry si barvu a jas ukládá do Nastavení (`barva_svetla`, `jas_svetla`). Starší program bez těchto klíčů dostává místo zápisu příkaz světla a výběr barvy ho rovnou ručně rozsvítí; vysvětlivka je pak „Výběr barvy nebo jasu světlo rovnou ručně zapne.“

### 3.8 „Rozsvítit při pohybu“ (`nocni_svetlo_pri_pohybu`)

- [ ] Světlo → karta „Automatika“. **Čekáš:** přepínač „Rozsvítit při pohybu“ je zapnutý a není zašedlý. Pod ním „v noci na chvíli rozsvítí noční světlo“.
- [ ] Nastavení → „Noc od“ → „Teď“ (aby byla noc). Světlo → Automatika → u „Noční světlo svítí“ klepni na číslo a napiš 10 (s), aby byl test rychlý.
- [ ] Vypni přepínač. **Čekáš:** přepne se hned. Mávni před čidlem pohybu. Dlaždice „Pohyb“ ukáže „Ano“, ale LED se nerozsvítí. Když noční světlo svítilo, zhasne hned po vypnutí.
- [ ] Termostat → „Nikdo doma“. **Čekáš:** pohyb dál ruší „nikdo doma“ („Doma je někdo · poslední pohyb před N s“).
- [ ] Zapni přepínač. **Čekáš:** pohyb zase rozsvítí noční světlo.
- [ ] Vrať „Noční světlo svítí“ na 30 s a v Nastavení klepni na „Vrátit původní časy“.
- [ ] Raspberry bez klíče `nocni_svetlo_pri_pohybu` (starší program): **Čekáš:** přepínač „Rozsvítit při pohybu“ je zašedlý a pod ním je „Raspberry tuto volbu zatím nepodporuje“. Zbytek aplikace funguje (Termostat, Nastavení, ostatní ovladače Světla). Vyzkoušíš to jen se starším programem. Simulace klíč má vždy, takže zašedlý přepínač v ní neuvidíš.

## 4. Výpadky a krajní případy

### 4.1 Program na Raspberry skončí

- [ ] Na Přehledu je štítek „Připojeno“. Na Raspberry ukonči program (Ctrl+C), nebo ho zastav: `kill -STOP <PID>`.
- [ ] **Čekáš:** po 3–4 s oranžový štítek „Stará data · N s“ a oranžová karta „Data jsou N s stará“. Hodnoty zešednou na všech záložkách. Graf se nemaže. Spadne-li spojení hned, je štítek červený „Odpojeno · znovu za N s“.
- [ ] Při `kill -STOP` (do 15 s od zastavení) klepni na „+“. **Čekáš:** asi za 6 s se hodnota vrátí na původní a dole je hláška „Raspberry neodpovědělo. Změna se možná neprovedla.“ Aplikace pak spojení zavře a připojuje se znovu.
- [ ] Za asi 15 s od posledních dat aplikace spojení zavře a zkouší se připojit. **Čekáš:** „Připojuji…“ nebo „Odpojeno · znovu za N s“. Odstupy jsou 1, 2, 4, 8 a pak 10 s. Ovládací prvky jsou zašedlé.
- [ ] Spusť program znovu (nebo `kill -CONT <PID>`). **Čekáš:** do asi 10 s „Připojeno“ bez klepnutí. Data jsou živá a graf pokračuje s mezerou v čarách.

### 4.2 Vypnout a zapnout Raspberry

- [ ] Vypni Raspberry (`sudo shutdown now`, nebo odpoj napájení).
- [ ] **Čekáš:** nejdřív „Stará data · N s“. Za chvíli červený štítek „Odpojeno · znovu za N s“ s odpočtem. Ovládací prvky jsou zašedlé, graf zůstává.
- [ ] Zapni Raspberry a počkej na start programu (zhruba minutu). **Čekáš:** aplikace se připojí sama, bez klepnutí. Štítek „Připojeno“, data jsou živá a graf pokračuje.
- [ ] Po startu zkontroluj hodiny. Raspberry 3B+ nemá RTC, takže bez internetu mohou jít špatně. Zobrazí se „Hodiny Raspberry jdou o … pozadu“. Klepni na „Srovnat“ (3.5).

### 4.3 Bluetooth v telefonu vypnout a zapnout

- [ ] Při připojeném Raspberry vypni Bluetooth (rychlé nastavení). **Čekáš:** hned červený štítek „Bluetooth vypnutý“. Hodnoty zešednou. Aplikace nespadne.
- [ ] Zapni Bluetooth. **Čekáš:** bez klepnutí „Připojuji…“ a za pár sekund „Připojeno“. Nové hledání se nespouští, adresa se pamatuje.
- [ ] Vypni Bluetooth a aplikaci úplně zavři a spusť. **Čekáš:** obrazovka „Bluetooth je vypnutý“ s tlačítkem „Zapnout Bluetooth“.
- [ ] Klepni na „Zapnout Bluetooth“ a v systémovém dialogu povol. **Čekáš:** aplikace začne hledat.

### 4.4 Otočení během spojení

- [ ] Při připojeném Raspberry zapni v telefonu automatické otáčení a otoč telefon na šířku. **Čekáš:** žádné nové hledání ani připojování. Štítek zůstává „Připojeno“, vybraná záložka i graf zůstanou. Vlevo je boční lišta a obsah je ve dvou sloupcích.
- [ ] Otoč telefon zpět na výšku. **Čekáš:** zase dolní lišta se čtyřmi záložkami a stejná data.
- [ ] Nastavení → klepni na čas u „Den od“ a otoč telefon. **Čekáš:** dialog výběru času zůstane otevřený.
- [ ] Světlo → klepni na „vlastní“ a otoč telefon. **Čekáš:** dialog „Vlastní barva“ zůstane otevřený.

### 4.5 Dvě zařízení „Termostat“

Potřebuješ druhý domek: Raspberry spolužáka, nebo druhý telefon s nRF Connect (Advertiser s názvem „Termostat“, viz 7.3).

- [ ] Nastavení → Zdroj dat → „Odpojit“ a potom „Připojit“. **Čekáš:** do několika sekund se dole otevře panel „V dosahu je víc domů“. Řádky mají tvar „Termostat · AA:BB:… · signál -61 dBm“. Nejsilnější signál je nahoře.
- [ ] Sleduj panel pár sekund. **Čekáš:** hodnoty signálu se obnovují, nové zařízení se přidá na konec a řádky neposkakují.
- [ ] Klepni na řádek se správnou adresou (adresu vlastního Raspberry ukazuje Nastavení → Zdroj dat po připojení, nebo `bluetoothctl show` na Raspberry). **Čekáš:** panel se zavře, „Připojuji…“ a „Připojeno“ k vybranému.
- [ ] Vypni a zapni vybrané Raspberry. **Čekáš:** aplikace se připojí k téže adrese bez nového výběru.
- [ ] Nastavení → Zdroj dat → „Simulace“ a potom zpět „Raspberry přes Bluetooth“. **Čekáš:** aplikace se připojí k téže adrese bez hledání a bez panelu výběru.
- [ ] Otevři panel znovu („Odpojit“, „Připojit“) a zavři ho tahem dolů. Zopakuj klepnutím mimo panel a tlačítkem Zpět. **Čekáš:** panel zmizí a aplikace reaguje (třeba „Spustit simulaci“). Hledání běží dál. Panel se vrátí až po „Odpojit“ a „Připojit“.
- [ ] Zařízení „Termostat“ bez služby Domek vyber z panelu. **Čekáš:** červený štítek „Neznámé zařízení“ a obrazovka „Tohle zařízení není chytrý dům“ s tlačítkem „Zkusit znovu“. Aplikace se nepřipojuje dokola.
- [ ] Odejdi z aplikace tlačítkem Domů a vrať se do ní. **Čekáš:** obrazovka „Tohle zařízení není chytrý dům“ zůstane a aplikace znovu nehledá. Hledat začne až po „Zkusit znovu“.

### 4.6 Oprávnění zamítnuto dvakrát

- [ ] Smaž oprávnění: `adb shell pm clear cz.vsb.mitr.domek` (nebo aplikaci odinstaluj a nainstaluj).
- [ ] Spusť aplikaci, klepni na „Povolit“ a dialog zavři klepnutím mimo něj. **Čekáš:** zůstane obrazovka s tlačítkem „Povolit“.
- [ ] Klepni znovu na „Povolit“ a v dialogu na „Nepovolit“. **Čekáš:** zůstane obrazovka s tlačítkem „Povolit“.
- [ ] Klepni znovu na „Povolit“ a znovu na „Nepovolit“. **Čekáš:** text „Oprávnění je zamítnuté. Povol ho v nastavení aplikace.“ a tlačítko „Otevřít nastavení“.
- [ ] Otoč telefon a přepni tmavý režim. **Čekáš:** tlačítko „Otevřít nastavení“ zůstane.
- [ ] Klepni na „Otevřít nastavení“. V systému otevři Oprávnění → Zařízení v okolí → Povolit. Vrať se tlačítkem Zpět. **Čekáš:** aplikace se sama připojí nebo začne hledat. Nemusíš nic klepat.
- [ ] „Spustit simulaci“ funguje i bez oprávnění.
- [ ] Známá drobnost: po úplném restartu aplikace můžou první dvě klepnutí na „Povolit“ nic neudělat. Po druhém se tlačítko změní na „Otevřít nastavení“. Aplikace si zamítnutí mezi spuštěními nepamatuje a jedno zamítnutí bez dialogu nepozná od dialogu zavřeného klepnutím mimo.

### 4.7 Písmo a zobrazení Samsungu

- [ ] Nejdřív nech výchozí písmo. Samsung má vlastní písmo (SamsungOne), které je jiné než písmo, ze kterého vznikly náhledy. Projdi všechny čtyři záložky. **Čekáš:** žádné slovo není zalomené uprostřed a nic není oříznuté.
- [ ] Kosmetika, ne chyba: jednopísmenné slovo (v, k, s, o) může zůstat na konci řádku.
- [ ] Nastavení → Displej → Velikost a styl písma. Nastav největší velikost písma. Názvy se mohou lišit podle verze One UI.
- [ ] Projdi Přehled, Termostat, Světlo a Nastavení. **Čekáš:** texty se lámou po slovech, nic není oříznuté ani přes sebe. Tlačítka narostou do výšky. Dlaždice na Přehledu mohou přejít ze 3 sloupců na 2. To je záměr.
- [ ] Změň styl písma na jiný než výchozí a projdi záložky znovu. **Čekáš:** totéž, bez oříznutí.
- [ ] Zapni největší „Zoom obrazovky“ (Nastavení → Displej). **Čekáš:** nic není oříznuté a nic nepřetéká.
- [ ] Známá drobnost: při velikosti písma kolem 1,5× se může „Vypnuto“ v přepínači režimu zalomit uprostřed slova, a to v úzkých sloupcích (telefon na šířku, tablet na výšku). Do 1,3× by to nemělo nastat.
- [ ] Vrať písmo i zoom na původní hodnoty.

### 4.8 Šířka, telefon na šířku a tablet

- [ ] Telefon na šířku. **Čekáš:** vlevo boční lišta, dva sloupce a nic oříznutého. Přehled je kompaktní (menší číslo a dlaždice), první řada dlaždic je vidět pod kartou termostatu. Sloupce se posouvají a poslední ovladač každého sloupce jde dojet.
- [ ] Otoč telefon na šířku na obě strany. **Čekáš:** výřez kamery nepřekrývá lištu ani nadpis.
- [ ] Tablet na výšku (Galaxy Tab A8, 800 × 1280 dp). **Čekáš:** boční lišta a dva sloupce. Přehled má vlevo kartu termostatu a dlaždice (po třech, při jiném písmu po dvou), vpravo graf. Spodní polovina obrazovky je prázdná. To je záměr.
- [ ] Tablet, záložka Světlo. **Čekáš:** tlačítka jsou ve dvou řadách: `Zapnout` + `Vypnout` / `Vrátit automatiku`. Žádné slovo není zalomené uprostřed.
- [ ] Tablet na šířku (1280 × 800 dp). **Čekáš:** obsah je nejvýš 960 dp široký a vystředěný vedle lišty. Nadpis a štítek zůstávají u okrajů okna. Řádky nemají velké mezery mezi názvem a ovladačem.
- [ ] Otoč tablet při připojeném Raspberry. **Čekáš:** spojení i graf zůstanou.

### 4.9 Drobnosti

- [ ] Nech aplikaci chvíli bez dotyku, déle než je časový limit displeje. **Čekáš:** displej nezhasne.
- [ ] Stiskni Domů, po 30 s se vrať. **Čekáš:** aplikace běží dál (nebo se sama znovu připojí) a data jsou živá.
- [ ] Na Přehledu stiskni Zpět. **Čekáš:** aplikace se jen odloží na pozadí (Android 12 a novější), spojení zůstává a po návratu jsou data živá.
- [ ] Smaž aplikaci z přehledu posledních aplikací. **Čekáš:** Raspberry je zase volné (v nRF Connect je „Termostat“ bez spojení). Další spuštění začíná hledáním.
- [ ] Zapni tmavý režim telefonu. **Čekáš:** tmavé schéma, čitelné texty, karty se od pozadí liší, v grafu je vidět červený i modrý pruh.
- [ ] Zavři aplikaci úplně a spusť ji v tmavém režimu. **Čekáš:** okno je tmavé hned od začátku, bez bílého bliknutí.
- [ ] Přepni navigaci mezi gesty a třemi tlačítky (Nastavení → Displej → Navigační lišta). **Čekáš:** dolní lišta aplikace není pod systémovou lištou.
- [ ] Zapni „Odebrat animace“ (Nastavení → Usnadnění, název se může lišit). **Čekáš:** nic nebliká a aplikace funguje dál. Pulzující ikonu uvidíš na obrazovce hledání, proto ji zkus bez Raspberry.

### 4.10 Dlouhý běh grafu

- [ ] Nech aplikaci běžet aspoň 15 minut (s Raspberry, nebo v simulaci). Přepínej „5 min“, „15 min“ a „Vše“.
- [ ] **Čekáš:** přepnutí je okamžité. Posouvání Přehledu je plynulé, bez škubání. Osa X ukazuje čas telefonu HH:MM, osa Y jde po 1 nebo 2 °C.
- [ ] Graf nemá díry kromě výpadků.
- [ ] Volitelně po hodině běhu zkontroluj plynulost znovu (graf „Vše“ se překresluje každou sekundu).

### 4.11 Starší telefon (Android 11 a starší, volitelně)

- [ ] Vypni v telefonu polohu, aplikaci úplně zavři a spusť (před prvním připojením). **Čekáš:** štítek „Poloha vypnutá“ a obrazovka „Zapni polohu“ s tlačítkem „Otevřít nastavení polohy“.
- [ ] Klepni na „Otevřít nastavení polohy“, zapni polohu a vrať se. **Čekáš:** aplikace začne hledat a připojí se sama.
- [ ] Data se nezdvojují: Přehled a graf se aktualizují jednou za sekundu, ne dvakrát.

## 5. Co dělat, když…

### Telefon nenajde žádný „Termostat“

- [ ] Je Raspberry zapnuté a běží program? Na Raspberry: `sudo systemctl status bluetooth` a výpis programu.
- [ ] V nRF Connect (Scanner na druhém telefonu) se musí ukázat zařízení „Termostat“ se službou 0x181A. Když ne, je chyba na straně Raspberry.
- [ ] Název musí být přesně `Termostat`. Aplikace hledá podle názvu.
- [ ] Je štítek „Chybí oprávnění“ nebo „Bluetooth vypnutý“? Oprav to (1.4, 4.3).
- [ ] Přibliž telefon. Raspberry 3B+ má malý dosah.
- [ ] Je k Raspberry připojené něco jiného (nRF Connect)? Odpoj to.
- [ ] Nastavení → Zdroj dat → „Odpojit“ a „Připojit“ spustí nové hledání. Android povolí nejvýš 5 hledání za 30 s, takže mezi pokusy počkej aspoň 6 s.
- [ ] V logu hledej „Hledání: nalezeno 1x Termostat“.

### Připojí se, ale nejsou data

- [ ] Podívej se do logu (část 6). „Zařízení … nemá službu Domek se všemi charakteristikami“ znamená, že program na Raspberry nemá správná UUID (`5bf00001-…` a charakteristiky `…0010`, `…0011`, `…0012`).
- [ ] „MTU nepotvrzeno (…), pokračuje se s 23“ znamená, že telefon nepotvrdil velké MTU. Stav (až 512 B) se pak nevejde do jedné notifikace a v logu bude „Neplatný Stav (20 B)“. Odpoj a připoj, případně vypni a zapni Bluetooth v telefonu. V Nastavení → Zdroj dat musí být MTU 517.
- [ ] „Neplatný Stav (N B): …“ nebo „Neplatné Nastavení (N B): …“ znamená JSON, který aplikace nepřečte. Zkontroluj klíče (české názvy: `cas`, `usek`, `t_venku`, …) a ověř hodnotu v nRF Connect (část 7).
- [ ] „Příprava spojení: … skončilo …“ ukazuje krok, který selhal (MTU, služby, notifikace, čtení).
- [ ] „Vyjednané MTU N je menší než 515, celý Stav se do notifikace nevejde“: Raspberry vyjednalo malé MTU. Aplikace se pořád pokouší připojit znovu („Odpojeno · znovu za N s“). Raspberry musí umět MTU 517 (docs/ble-rozhrani.md, Připojení a MTU 517).
- [ ] Po restartu programu se aplikace nepřipojí? Může pomoct vypnout a zapnout Bluetooth v telefonu (smaže se uložená nabídka služeb).
- [ ] Stále dokola status 133? Raspberry nereklamuje nebo je mimo dosah. Restartuj program na Raspberry.

### Hodnoty jsou „—“

- [ ] „Venku“, „Vlhkost“ nebo „Tlak“ s poznámkou „chyba čidla“: čidlo BME280 hlásí chybu. Na Přehledu je červená karta. Zkontroluj zapojení (I²C).
- [ ] „Vlhkost“ s poznámkou „neměří se“ a bez červené karty: na Raspberry je BMP280, které vlhkost neměří. To není chyba.
- [ ] „Osvětlení“ s poznámkou „čidlo chybí“: čidlo TSL2561 (červená karta „Čidlo osvětlení (TSL2561) neodpovídá“). Zkontroluj zapojení a adresu I²C (0x39, případně 0x29 nebo 0x49).
- [ ] „Uvnitř · chyba čidla“: Raspberry neposlalo vnitřní teplotu.
- [ ] Všechno „—“ a štítek „Stará data“: data nepřicházejí, viz předchozí část.
- [ ] V simulaci to způsobí „Porucha BME280“. Klepni na ni znovu.

### Hodiny se liší

- [ ] Raspberry 3B+ nemá RTC. Bez internetu má po startu špatný čas.
- [ ] Na Přehledu se upozornění ukáže při rozdílu nad 2 minuty („Srovnat“). V Nastavení nad 1 minutu („Nastavit čas podle telefonu“).
- [ ] Úsek dne (den, večer, noc) i tlačítko „Teď“ jdou podle hodin Raspberry, ne telefonu. Při špatném čase bude špatně i denní doba.
- [ ] Po srovnání zkontroluj v Nastavení → „Čas Raspberry“, že se „Raspberry“ a „Telefon“ liší jen o sekundy.
- [ ] Příkaz posílá místní čas telefonu bez časového pásma, ve tvaru `2026-10-21T14:03:00`.

### Zápis se nepovedl

- [ ] Hláška „Raspberry změnu odmítlo: … Nic se nezměnilo.“ říká důvod, který aplikace odhadla. Hodnota se vrátila na tu z Raspberry.
- [ ] „Dům není připojený, změna se neodeslala.“: spojení právě spadlo, počkej na „Připojeno“.
- [ ] „Raspberry neodpovědělo. Změna se možná neprovedla.“: spojení se zasekalo. Aplikace se připojí znovu (4.1).
- [ ] „Raspberry odmítlo příkaz … (kód N)“: zkontroluj v logu programu na Raspberry, proč příkaz selhal.

## 6. Jak číst log

Aplikace píše pod značkou `Domek`. Systém píše pod `BluetoothGatt`. V klidném provozu aplikace skoro nic nepíše. Řádky se objeví při hledání, výpadku nebo chybě.

- [ ] Smaž starý log: `adb logcat -c`.
- [ ] Sleduj log (ukonči Ctrl+C):

```powershell
adb logcat | findstr /i "Domek BluetoothGatt"
```

- [ ] V Android Studiu jde použít okno Logcat s filtrem `tag:Domek`.
- [ ] Když aplikace spadla: `adb logcat -b crash -d`.

| Řádek v logu | Znamená |
|---|---|
| `Hledání: nalezeno 1x Termostat` | hledání skončilo, našlo se tolik zařízení |
| `Pokus N o připojení k … selhal (status N)` | připojení se nepovedlo, aplikace zkusí znovu za 1, 2, 4, 8, pak 10 s |
| `Spojení s … skončilo: status N, stav 0` | spojení spadlo (typicky status 8 časový limit, 19 ukončila Raspberry, 22 ukončil telefon, 133 obecná chyba) |
| `MTU nepotvrzeno (…), pokračuje se s 23` | telefon nepotvrdil MTU, další krok může trvat 10 s |
| `Vyjednané MTU N je menší než 515, celý Stav se do notifikace nevejde` | Raspberry vyjednalo malé MTU, pokus se nepočítá jako spojení a aplikace to zkusí znovu |
| `Mezipaměť služeb nejde obnovit` | telefon nedovolil obnovit uložené služby; vypni a zapni Bluetooth v telefonu |
| `Zařízení … nemá službu Domek se všemi charakteristikami` | Raspberry nemá správnou službu ani po obnovení mezipaměti služeb |
| `Příprava spojení: … skončilo …` | selhal krok přípravy (MTU, služby, notifikace, čtení) |
| `… bez odpovědi do 5000 ms, spojení se zavře` | GATT operace nedostala odpověď |
| `Hlídání dat: přes 15000 ms bez Stavu, spojení se zavře` | 15 s bez Stavu, aplikace se připojí znovu |
| `Neplatný Stav (N B): …`, `Neplatné Nastavení (N B): …` | JSON se nedal přečíst a zahodil se |
| `Callback … nepatří k běžící operaci …, zahazuje se` | opožděná odpověď, ojediněle je to v pořádku |
| `Hledání selhalo (kód N), spustí se znovu` | kód 6 znamená příliš časté hledání |
| `Hledání nejde spustit: chybí oprávnění` nebo `… Bluetooth není zapnutý` | chybí oprávnění, nebo je vypnuté Bluetooth |

## 7. Volitelně: nRF Connect

Potřebuješ druhý telefon s aplikací nRF Connect for Mobile. Ověříš v něm rozhraní Raspberry a to, co aplikace zapíše.

### 7.1 Rozhraní Raspberry (záložka Client)

- [ ] V aplikaci Chytrý dům klepni v Nastavení → Zdroj dat na „Odpojit“. Raspberry nemusí zvládnout dvě spojení naráz.
- [ ] V nRF Connect otevři Scanner a u zařízení „Termostat“ klepni na Connect.
- [ ] Otevři záložku Client. **Čekáš:** služba Environmental Sensing (0x181A) a neznámá služba `5bf00001-f758-4c41-a34e-bab58ce87673`.
- [ ] Ve vlastní službě jsou tři charakteristiky:
  - `5bf00010-…` Stav: READ, NOTIFY;
  - `5bf00011-…` Nastavení: READ, WRITE, NOTIFY;
  - `5bf00012-…` Příkaz: WRITE.
- [ ] Stav: klepni na šipku čtení a zobraz hodnotu jako text (UTF-8). **Čekáš:** JSON s klíči `cas`, `usek`, `t_venku`, `t_uvnitr`, `cil`, `topi`, `svetlo`, `chyby` a dalšími.
- [ ] Zapni notifikace na Stavu (ikona tří šipek). **Čekáš:** nový JSON každou sekundu.
- [ ] Nastavení: přečti hodnotu. **Čekáš:** celý JSON s `rezim_termostatu`, `cil_den`, `zacatek_noc`, … Klíče `nocni_svetlo_pri_pohybu`, `barva_svetla` a `jas_svetla` tam jsou, nebo u staršího programu chybí (potom je v aplikaci přepínač zašedlý, viz 3.8).

### 7.2 Zápis do Nastavení

- [ ] Zapiš do Nastavení (formát Text) například `{"cil_den": 24.5}`. **Čekáš:** zápis projde a notifikace ukáže celé Nastavení s `"cil_den": 24.5`. Po „Připojit“ v aplikaci je „Denní cíl“ 24,5 °C.
- [ ] Zapiš neplatnou hodnotu: `{"cil_den": 99}`. **Čekáš:** nRF Connect hlásí chybu zápisu (status jiný než 0) a hodnota se nezmění.
- [ ] Zapiš `{"zacatek_noc": "19:00"}`, když je „Večer od“ také 19:00. **Čekáš:** chyba zápisu (shodné začátky).
- [ ] Do Příkazu (`…0012`) zapiš `{"svetlo":"zapnout"}`. **Čekáš:** LED svítí.
- [ ] Odpoj nRF Connect a v aplikaci klepni na „Připojit“.

Když Raspberry zvládne dvě spojení naráz, nech aplikaci připojenou a zapni v nRF Connect notifikace na Nastavení. Změň v aplikaci „Denní cíl“. **Čekáš:** notifikace s celým Nastavením a novou hodnotou `cil_den`.

Co aplikace posílá (jen měněné klíče, celá čísla jako celá, ostatní čísla jako desetinná):

```text
Nastavení  {"cil_den":24.5}
Nastavení  {"rezim_termostatu":"rucni"}
Nastavení  {"zacatek_noc":"22:30"}
Nastavení  {"zacatek_den":"07:00","zacatek_vecer":"19:00","zacatek_noc":"22:00"}   (Vrátit původní časy)
Nastavení  {"nocni_svetlo_s":35}
Nastavení  {"nocni_svetlo_pri_pohybu":false}
Příkaz     {"svetlo":"zapnout"}
Příkaz     {"svetlo":{"barva":[255,0,0],"jas":50}}
Příkaz     {"cas":"2026-10-21T14:03:00"}
```

### 7.3 nRF Connect jako náhradní domek

- [ ] Na druhém telefonu otevři nRF Connect → Advertiser → nový inzerát. Přidej záznam „Complete Local Name“ s hodnotou `Termostat`. Inzerát musí být připojitelný (Connectable). Zapni ho.
- [ ] V aplikaci Chytrý dům spusť hledání. **Čekáš:** najde se „Termostat“. Se skutečným Raspberry v dosahu se otevře výběr (4.5).
- [ ] Připoj se k náhradnímu domku. **Čekáš:** „Neznámé zařízení“ a „Tohle zařízení není chytrý dům“ s tlačítkem „Zkusit znovu“. Služba Domek tam není, aplikace se nepřipojuje dokola.

## 8. Zápis výsledků

- Datum a čas testu:
- Telefon (model, verze Androidu):
- APK (soubor a čas sestavení):
- Program na Raspberry (verze; má klíč `nocni_svetlo_pri_pohybu`? ano / ne):
- Prošlo všechno: ano / ne
- Co selhalo (část, krok, co se stalo):
- Poznámky:
