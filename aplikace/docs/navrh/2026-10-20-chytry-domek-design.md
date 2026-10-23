# Chytrý dům – návrh Android aplikace

*2026-10-20 · semestrální projekt MITŘ, VŠB-TUO (Varianta 2, komunikace přes Bluetooth LE)*

Schválené obrazovky: [2026-10-20-chytry-domek-obrazovky.html](2026-10-20-chytry-domek-obrazovky.html) (otevřít v prohlížeči). Popis v kapitole 6 je k nim textový doprovod; kde se liší, platí tento dokument.

## 1. Záměr

Mobilní aplikace v Kotlinu zobrazuje a ovládá „chytrý domek“, který simuluje Raspberry Pi 3B+ s programem v Pythonu. Ten program vzniká zvlášť a jeho BLE rozhraní je pevně dané. Zadání řadí Bluetooth LE mezi „komplexní komunikaci“, u které se hodnotí hlavně funkčnost. Projekt má 40 bodů.

Aplikace se bude předvádět na telefonu **Samsung Galaxy A25 5G (Android 15)** drženém na výšku, případně položeném na stole.

Za úspěch se považuje, že při předvádění všechno funguje plynule a podstatné je vidět na jedné obrazovce:

1. zvýšit cíl a sledovat, jak teplota stoupá a topení se zapíná a vypíná;
2. snížit cíl a sledovat chlazení;
3. zahřát čidlo rukou a sledovat, jak termostat reaguje;
4. zakrýt čidlo osvětlení, aby se ve dne rozsvítilo světlo;
5. posunout začátek noci na aktuální čas a ukázat noční režim s nočním světlem při pohybu.

## 2. Cíle, ne-cíle, anti-cíle

**Cíle**
- Najít domek, připojit se, ukazovat stav připojení a po výpadku se samo znovu připojit.
- Živý přehled všech hodnot ze Stavu a zřetelné označení starých dat (3 s bez notifikace).
- Graf teplot (vnitřní, venkovní, cíl) poskládaný z notifikací, s vyznačeným topením a chlazením.
- Ovládání termostatu (režim, denní, noční a ruční cíl) a „nikdo doma“.
- Ovládání světla (zapnout, vypnout, automatika, barva, jas) a nastavení jeho automatiky.
- Nastavení začátků dne, večera a noci a nastavení času Raspberry podle telefonu.
- Srozumitelné hlášky, když Raspberry zápis odmítne.
- Celé rozhraní česky. Hlavní je rozvržení pro telefon na výšku, které se přizpůsobí na šířku a na tabletu.
- Simulovaný zdroj dat, aby šlo aplikaci vyvíjet a ukazovat bez hardwaru.

**Ne-cíle**
- Ukládání historie mezi spuštěními aplikace. Graf žije jen v paměti.
- Běh na pozadí, foreground service a systémové notifikace.
- Více domků najednou, párování a bonding.
- Čtení standardní služby Environmental Sensing (0x181A). Všechny hodnoty se berou z JSON Stavu. Zadané pořadí kroků po připojení tuto službu nezmiňuje, takže aplikace jen spoléhá na to, že ji Raspberry ukazuje v reklamě. Čtení služby jde doplnit později, protože fronta GATT operací je obecná.
- Jakékoli změny rozhraní Raspberry kromě doplňku `svetlo_pri_pohybu` (kap. 4.2).

**Anti-cíle** (neúspěch, i kdyby to jinak fungovalo)
- Stará nebo simulovaná data vypadají jako živá.
- Aplikace při chybě BLE spadne, zamrzne nebo se už sama nepřipojí.
- Zápis, který Raspberry odmítlo, tiše zmizí, nebo na obrazovce zůstane, jako by prošel.
- Při předvádění je potřeba přepínat obrazovky, aby byl vidět cíl, topení a graf zároveň.

## 3. Omezení a technologie

- Celý projekt `MyApplication` se přepíše. Původní aplikace „baterka“ zmizí, s čímž uživatel souhlasil.
- Kotlin, Jetpack Compose s Material 3, ViewModel se StateFlow, coroutines a kotlinx.serialization (návrh uživatele, beru ho).
- Nástroje budou stejné jako ve funkčním projektu `MyApplicationtest` na tomto počítači: AGP 9.4.1 s vestavěným Kotlinem 2.2.10, plugin `org.jetbrains.kotlin.plugin.compose` 2.2.10, Compose BOM 2026.02.01 a Gradle 9.6.0. Navíc přibude `org.jetbrains.kotlin.plugin.serialization` 2.2.10 a `kotlinx-serialization-json`.
- `compileSdk` a `targetSdk` zůstávají 37, **`minSdk` bude 26** (Android 8.0). Telefon A25 má Android 15. Nižší `minSdk` zachová smysl větve s oprávněním polohy pro Android 11 a starší, kterou zadání chce, a `java.time` je od API 26 k dispozici bez desugaringu.
- Package a applicationId budou **`cz.vsb.mitr.domek`**, název aplikace „Chytrý dům“.
- V SDK je jen platforma 37, build-tools 36.0.0 a emulátor bez systémových obrazů. K počítači teď není připojené žádné zařízení.

## 4. Rozhraní Raspberry

> **Závazné je `docs/ble-rozhrani.md`** (dokument ze strany Raspberry, předaný 23. 10. 2026). Tato kapitola je původní shrnutí. Kde se liší, platí ten dokument a aplikace se podle něj upravila: klíč pro rozsvícení při pohybu je `nocni_svetlo_pri_pohybu`, Nastavení má navíc `barva_svetla` a `jas_svetla` (barvou a jasem svítí automatika i ruční světlo), kódy poruch jsou `meteo` a `osvetleni` (čidlo osvětlení je TSL2561), topení naskočí pod cílem o víc než 0,3 °C a chlazení nad cílem o víc než 1,0 °C, `cas` a `usek` můžou být `null`.

### 4.1 Pevně dané rozhraní (shrnutí)

Raspberry je BLE periferie s názvem **`Termostat`**. V reklamě je služba 0x181A, aplikace hledá podle názvu. Párování není potřeba. Hned po připojení se zavolá `requestMtu(517)` a počká se na `onMtuChanged`, teprve potom se hledají služby. Hodnoty jsou JSON v UTF-8 do 512 B.

Vlastní služba **Domek** `5bf00001-f758-4c41-a34e-bab58ce87673`:

| Charakteristika | UUID | Vlastnosti |
|---|---|---|
| Stav | `5bf00010-f758-4c41-a34e-bab58ce87673` | read, notify |
| Nastavení | `5bf00011-f758-4c41-a34e-bab58ce87673` | read, write, notify |
| Příkaz | `5bf00012-f758-4c41-a34e-bab58ce87673` | write (s odpovědí) |

CCCD má UUID `00002902-0000-1000-8000-00805f9b34fb`.

**Stav.** Notifikace chodí každou sekundu a navíc hned při pohybu, přepnutí světla nebo změně úseku dne.

| Klíč | Typ | Význam |
|---|---|---|
| `cas` | text `YYYY-MM-DDTHH:MM:SS` | místní čas Raspberry |
| `usek` | `"den"`, `"vecer"`, `"noc"` | denní doba |
| `t_venku` | číslo nebo null | venkovní teplota °C (null = chyba čidla) |
| `vlhkost` | číslo nebo null | % |
| `tlak` | číslo nebo null | hPa |
| `lux` | číslo nebo null | osvětlení v lx |
| `t_uvnitr` | číslo nebo null | vypočtená vnitřní teplota °C |
| `cil` | číslo nebo null | právě platný cíl °C (null = termostat vypnutý) |
| `topi`, `chladi` | bool | topí, chladí (nikdy obojí) |
| `rezim` | `"auto"`, `"rucni"`, `"vypnuto"` | režim termostatu |
| `nikdo_doma` | bool | dlouho žádný pohyb, cíl je snížený |
| `pohyb` | bool | PIR právě hlásí pohyb |
| `svetlo` | objekt | `zap` bool, `barva` [r,g,b] 0–255, `jas` 0–100 %, `rucne` bool |
| `chyby` | seznam textů | z `"meteo"`, `"osvetleni"`, `"displej"`, `"svetlo"`, `"nastaveni"` |

**Nastavení.** Čtení vrací celý objekt. Zápis smí obsahovat jen část klíčů a sloučí se s dosavadním nastavením. Neplatný zápis selže GATT chybou (status ≠ 0) a nic se nezmění. Neplatný je neznámý klíč, bool místo čísla nebo hodnota mimo rozsah. Po každé změně přijde notifikace.

| Klíč | Typ | Rozsah | Výchozí |
|---|---|---|---|
| `rezim_termostatu` | text | `"auto"`, `"rucni"`, `"vypnuto"` | `"auto"` |
| `cil_den` | číslo | 5–35 | 23.0 |
| `cil_noc` | číslo | 5–35 | 20.0 |
| `cil_rucni` | číslo | 5–35 | 22.0 |
| `zacatek_den` | text `"HH:MM"` | 00:00–23:59 | `"07:00"` |
| `zacatek_vecer` | text `"HH:MM"` | 00:00–23:59 | `"19:00"` |
| `zacatek_noc` | text `"HH:MM"` | 00:00–23:59, všechny tři začátky různé | `"22:00"` |
| `prah_lux` | číslo | 1–10000 | 50.0 |
| `nocni_svetlo_s` | celé číslo | 5–600 | 30 |
| `nikdo_doma_po_min` | celé číslo | 1–240 | 30 |
| `utlum_nikdo_doma` | číslo | 0–10 | 3.0 |

**Příkaz.** Objekt má vždy právě jeden klíč, neplatný příkaz selže GATT chybou.
- `{"svetlo":"zapnout"}`, `{"svetlo":"vypnout"}`, `{"svetlo":"prepnout"}`, `{"svetlo":"auto"}`
- `{"svetlo":{"barva":[0,0,255],"jas":50}}` zapne světlo ručně. Stačí i jen jeden z klíčů.
- `{"cas":"2026-10-20T14:03:00"}` nastaví hodiny Raspberry (místní čas bez časového pásma).

Chování domku, které aplikace zobrazuje a simulace napodobuje: topení zvedá vnitřní teplotu, chlazení ji snižuje, bez nich se pomalu vrací k venkovní (zvýšení cíle o 3 °C trvá zhruba minutu). V režimu Auto platí ve dne a večer denní cíl, v noci noční. Když se dlouho nikdo nepohne, cíl se sníží o `utlum_nikdo_doma`. Světlo přes den svítí bíle, jen když je venku šero (`lux` < `prah_lux`), večer svítí teple žlutě, v noci je zhasnuté a při pohybu se na `nocni_svetlo_s` rozsvítí noční světlo. Ruční volba světla platí do další změny denní doby.

### 4.2 Doplněk rozhraní: `svetlo_pri_pohybu`

Uživatel si vyžádal, aby šlo rozsvěcení světla pohybem vypnout. Text níže už předal na stranu Raspberry:

| Klíč Nastavení | Typ | Rozsah | Výchozí |
|---|---|---|---|
| `svetlo_pri_pohybu` | bool | `true`, `false` | `true` |

- `true`: chování zůstává stejné.
- `false`: pohyb světlo nerozsvítí. Pokud noční světlo zrovna svítí, hned zhasne. Ruční ovládání světla to neovlivní.
- Čidlo pohybu běží dál: `pohyb` ve Stavu se hlásí a pohyb dál ruší „nikdo doma“.
- Jiná hodnota než `true` nebo `false` (ani `0`/`1`) je neplatná.

**Aplikace musí fungovat i s Raspberry, které klíč ještě nemá.** Pokud ve čteném Nastavení chybí, přepínač je zašedlý s poznámkou „Raspberry tuto volbu zatím nepodporuje“.

### 4.3 Předpoklady

- Raspberry má stálou BLE adresu, takže po výpadku se aplikace může připojit přímo k ní bez nového hledání.
- **Aktuální úsek dne** je ten, jehož začátek nastal naposledy, počítáno cyklicky přes půlnoc. Začátky můžou být v libovolném pořadí. Například den 07:00, večer 19:00 a noc 10:05 znamená v 10:30 noc, v 19:30 večer, v 03:00 večer a v 08:00 den. Toto upřesnění uživatel předal na stranu Raspberry k potvrzení. Stejné pravidlo používá aplikace pro pruh dne, pro „platí do HH:MM“ a v simulaci. Úsek zobrazený na Přehledu se ale vždy bere ze Stavu (`usek`).
- **V dosahu může být víc zařízení „Termostat“**, třeba Raspberry spolužáků se stejným programem.
- Status, se kterým Raspberry zápis odmítne, neříká důvod. Aplikace důvod odvozuje z toho, co poslala (kap. 7).
- Hodiny Raspberry (`cas`) se můžou lišit od telefonu. Raspberry 3B+ nemá RTC, a bez internetu proto může mít po startu špatný čas.

## 5. Architektura

### 5.1 Vrstvy

```
ui/          Compose obrazovky a komponenty, téma
viewmodel/   HouseViewModel: jeden UI stav + jednorázové hlášky
data/        model (JSON), rozhraní HouseDataSource
  ble/       BleHouseDataSource, fronta GATT operací, oprávnění
  sim/       SimulatedHouseDataSource (model domku)
```

Jedna aktivita, `HouseViewModel` a zdroj dat, který vlastní ViewModel. Spojení tak přežije otočení obrazovky i přepínání záložek. Při `onCleared` se zdroj zastaví (BLE se odpojí a zavře). Když aplikace odejde na pozadí, spojení zůstává, dokud Android proces neukončí.

### 5.2 Zdroj dat

Rozhraní, které implementuje BLE i simulace:

- `connection: StateFlow<ConnectionState>` udává, kde se spojení nachází: chybí BLE, chybí oprávnění, Bluetooth vypnutý, vypnutá poloha (Android ≤ 11), hledání, připojování (číslo pokusu), příprava (MTU, služby, notifikace), připojeno (adresa, MTU, RSSI), odpojeno s časem do dalšího pokusu, ručně odpojeno, simulace.
- `status` je proud přijatých Stavů. Každá notifikace i čtení se počítá a nese čas příjmu, protože z nich se skládá graf a měří stáří dat.
- `settings: StateFlow<HouseSettings?>` je poslední známé Nastavení (null = zatím nenačteno).
- `suspend fun writeSettings(patch)` a `suspend fun sendCommand(command)` vrací výsledek: provedeno, odmítnuto (s GATT statusem), nepřipojeno, nebo bez odpovědi (timeout).
- `start()` a `stop()`.

Zdroj se přepíná v Nastavení (Raspberry nebo simulace) a z obrazovky hledání („Spustit simulaci“). Po přepnutí se starý zdroj zastaví, graf se vymaže a spustí se nový. Volba se mezi spuštěními **neukládá**: aplikace vždy startuje s Raspberry.

Model: `@Serializable` třídy pro Stav, světlo a Nastavení s českými názvy klíčů (`@SerialName`). Parser ignoruje neznámé klíče. Nastavení bez klíče `svetlo_pri_pohybu` musí projít. JSON, který nejde přečíst, se zahodí a zaloguje. Poslední dobrá data zůstanou a časem zestárnou. Zápisy se skládají jako `JsonObject` jen s měněnými klíči. Celá čísla (`nocni_svetlo_s`, `nikdo_doma_po_min`) se posílají jako celá čísla, ostatní čísla jako desetinná.

### 5.3 BLE implementace

**Oprávnění.** Android 12 a novější potřebuje `BLUETOOTH_SCAN` (s `neverForLocation`) a `BLUETOOTH_CONNECT`. Android 11 a starší potřebuje `ACCESS_FINE_LOCATION` (spolu s `BLUETOOTH` a `BLUETOOTH_ADMIN`, všechny s `maxSdkVersion="30"`) a zapnutou polohu v systému. O oprávnění se žádá za běhu. Když je uživatel trvale odmítne, aplikace nabídne otevřít nastavení aplikace. `uses-feature bluetooth_le` má `required="false"`, aby simulace běžela i na zařízení bez BLE.

**Hledání.** `BluetoothLeScanner` s filtrem na název `Termostat` a režimem `SCAN_MODE_LOW_LATENCY`. Hledání běží, dokud se domek nenajde, nebo dokud se zdroj nezastaví. Po prvním nalezení hledání ještě 2 s sbírá další zařízení stejného jména. Když najde jen jedno, připojí se k němu. Když jich najde víc, ukáže výběr se seznamem (adresa a síla signálu, seznam se průběžně doplňuje) a připojí se k vybranému. Vybraná adresa platí do konce běhu aplikace a používá se při znovupřipojení. Uživatel ji změní přes Odpojit a Připojit, které spustí nové hledání. Kvůli limitu Androidu (nejvýš 5 spuštění hledání za 30 s) se hledání nesmí restartovat rychleji než jednou za 6 s. Aplikace sleduje vypnutí a zapnutí Bluetooth (`ACTION_STATE_CHANGED`) a podle toho se zastaví nebo znovu rozběhne.

**Připojení.** `connectGatt(context, false, callback, TRANSPORT_LE)`. Pořadí po připojení:

1. `requestMtu(517)`, čekání na `onMtuChanged`. Když telefon ohlásí méně než 515, je to chyba spojení a aplikace to zkusí znovu (docs/ble-rozhrani.md). Když `onMtuChanged` nepřijde, pokračuje se s 23.
2. `discoverServices()`, potom kontrola, že služba Domek i všechny tři charakteristiky existují. Když chybí, aplikace jednou obnoví mezipaměť služeb (`BluetoothGatt.refresh()`) a hledá znovu. Když chybí i potom, ukáže chybu „Zařízení Termostat nemá službu Domek“ a nepřipojuje se dokola.
3. Zapnutí notifikací pro Stav: `setCharacteristicNotification` a zápis CCCD.
4. Totéž pro Nastavení.
5. Čtení Nastavení.
6. Čtení Stavu (navíc oproti zadání, aby se přehled ukázal hned a ne až s první notifikací).

Až potom platí „Připojeno“. Kroky 1–6 hlídá časový limit (10 s na krok). Při jeho překročení se spojení zavře a zkusí se znovu.

**Fronta GATT operací.** Všechny operace (MTU, hledání služeb, zápisy CCCD, čtení, zápisy, čtení RSSI) jdou přes jednu frontu, která pouští vždy jen jednu. Každá operace čeká na svůj callback s časovým limitem 5 s. Při odpojení všechny čekající operace skončí výsledkem „nepřipojeno“. Callbacky přicházejí na binder vlákně a jen dokončí čekající operaci nebo předají data dál.

**Rozdíly API.** Na Androidu 13 a novějším se používají nové přetížení `onCharacteristicChanged(gatt, ch, value)`, `onCharacteristicRead(gatt, ch, value, status)`, `writeCharacteristic(ch, value, writeType)` a `writeDescriptor(desc, value)`. Na starších platí staré verze přes `characteristic.value`. Obě větve musí být v kódu a nesmí se zpracovat dvakrát.

**Zápisy** jdou vždy s odpovědí (`WRITE_TYPE_DEFAULT`). Status v `onCharacteristicWrite` ≠ 0 znamená odmítnutí.

**Výpadek a znovupřipojení.** Při `onConnectionStateChange` se statusem ≠ 0 (včetně 133 a 147) nebo s `STATE_DISCONNECTED` se vždy zavolá `close()` a naplánuje se nový pokus. Odstupy jsou 1, 2, 4, 8 a pak 10 s. Pokus o připojení jde přímo na známou adresu. Hledat se začne znovu jen tehdy, když adresa ještě není známá. Uživatelské „Odpojit“ automatické připojování vypne, „Připojit“ ho zase zapne a začne hledáním. Každých 5 s se čte RSSI pro informaci o síle signálu.

**Hlídání dat.** Když spojení trvá, ale 15 s nepřijde žádný Stav, aplikace spojení zavře a připojí se znovu (zaseklé spojení nebo program na Raspberry). Pod 15 s se data jen označí jako stará.

### 5.4 Simulace

`SimulatedHouseDataSource` se navenek chová jako Raspberry, aby šla celá aplikace vyzkoušet a ukázat bez hardwaru.

- Má vlastní model domku, který běží po sekundách a napodobuje kap. 4.1. Teplota se mění zhruba o 0,05 °C/s při topení a chlazení, bez nich se vnitřní teplota blíží venkovní. Termostat má hysterezi. Simulace počítá úseky dne, „nikdo doma“ (podle posledního pohybu) a automatiku světla včetně nočního světla, ručního přepnutí do další změny úseku a `svetlo_pri_pohybu`.
- Venkovní hodnoty se pomalu a přirozeně mění (teplota kolem 21,5 °C, vlhkost kolem 45 %, tlak kolem 1013 hPa, osvětlení podle úseku dne).
- Zápisy a příkazy ověřuje stejně jako Raspberry: neznámý klíč, špatný typ, hodnota mimo rozsah, shodné začátky úseků. Neplatný zápis odmítne statusem 128, takže i v simulaci jde vidět odmítnutý zápis.
- Notifikace posílá každou sekundu a hned při pohybu, přepnutí světla a změně úseku. Po změně nastavení pošle notifikaci Nastavení.
- Příkaz `cas` posune hodiny simulace.
- Stav spojení hlásí „simulace“, takže se nikdy nedá splést se skutečným domkem.
- Na Přehledu je v simulaci navíc karta **„Ovládání simulace“** s tlačítky, která nahrazují fyzické zásahy do domku: *Zahřát čidlo* (venkovní teplota +8 °C na 30 s), *Zakrýt čidlo světla* (osvětlení 5 lx na 30 s), *Pohyb*, *Porucha BME280* (přepínač, `t_venku`, `vlhkost` a `tlak` jsou null a v `chyby` je `"meteo"`; termostat stojí) a *Výpadek dat* (10 s bez notifikací, aby šla ukázat stará data).

### 5.5 ViewModel

`HouseViewModel` skládá z dat ze zdroje jeden UI stav:

- **Stav připojení** a **poslední Stav** s časem příjmu. Tik každých 500 ms přepočítá stáří dat. Data jsou stará, když stáří přesáhne 3 s.
- **Nastavení** a **rozpracované hodnoty** (drafty) u jednotlivých klíčů (kap. 7).
- **Historie pro graf** (kap. 8).
- **Čas posledního pohybu**, zachycený z přechodu `pohyb` false → true. Používá se pro text „poslední pohyb před…“.
- **Původní začátky úseků** pro tlačítko „Vrátit původní časy“ (kap. 6.4).
- **Rozdíl hodin** Raspberry proti telefonu (`cas` z posledního Stavu proti času příjmu).
- **Jednorázové hlášky** (snackbar) pro odmítnuté a neodeslané zápisy.

## 6. Obrazovky

### 6.0 Společné prvky

- **Horní lišta** s názvem obrazovky a štítkem stavu připojení. Klepnutí na štítek otevře Nastavení. Štítky:

  | Štítek | Barva | Kdy |
  |---|---|---|
  | Připojeno | zelená | připojeno a data čerstvá |
  | Hledám… | šedá | hledání |
  | Připojuji… | modrá | připojování a příprava |
  | Stará data · N s | oranžová | žádný Stav přes 3 s (při spojení i po výpadku) |
  | Odpojeno · znovu za N s | červená | výpadek, čeká se na další pokus |
  | Odpojeno | červená | uživatel odpojil |
  | Bluetooth vypnutý, Chybí oprávnění | červená | viz 6.5 |
  | Simulace | fialová | zdroj je simulace |

  Když platí víc stavů zároveň, má přednost ten výš v tomto pořadí: chybí BLE, oprávnění, Bluetooth nebo poloha, potom Odpojeno, Hledám a Připojuji, potom Stará data, Připojeno a Simulace. Stáří dat ukazuje i karta na Přehledu (6.5), takže při výpadku je vidět obojí.
- **Před prvními daty:** Přehled, Termostat a Světlo ukazují obrazovku stavu spojení (6.5). Nastavení je přístupné vždy. Části závislé na datech (Denní doba, Čas Raspberry) jsou zašedlé, Zdroj dat funguje.

- **Navigace:** při šířce pod 600 dp dolní lišta se čtyřmi záložkami (Přehled, Termostat, Světlo, Nastavení), při 600 dp a víc boční lišta a karty ve dvou sloupcích. Telefon na šířku má přes 600 dp, takže dostane boční lištu. Orientace není zamčená.
- **Obrazovka nezhasne**, dokud je aplikace v popředí.
- **Formát čísel** je český: desetinná čárka a mezera před jednotkou (`22,4 °C`, `45 %`, `1013 hPa`, `320 lx`). Teploty a cíle mají jedno desetinné místo, vlhkost, tlak a osvětlení žádné.
- **Hodnota null** se zobrazí jako „—“ s poznámkou „chyba čidla“ (`t_venku`, `vlhkost`, `tlak`, `t_uvnitr`) nebo „čidlo chybí“ (`lux`). Vlhkost null bez poruchy `meteo` má poznámku „neměří se“ (BMP280 vlhkost neměří).
- **Ovládací prvky** jsou zašedlé, dokud domek není připojený (nebo neběží simulace) a dokud není načtené Nastavení.

### 6.1 Přehled

Všechno pro předvádění na jedné obrazovce, odshora:

1. **Chyby z Raspberry** (`chyby`): jedna červená karta na každou chybu. Texty: `meteo` „Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá“, `osvetleni` „Čidlo osvětlení (TSL2561) neodpovídá“, `displej` „Displej domu nefunguje“, `svetlo` „Světlo (RGB LED) nejde ovládat“, `nastaveni` „Raspberry nemůže uložit nastavení“. Pro neznámý kód: „Raspberry hlásí chybu: kód“.
2. **Upozornění na hodiny:** když se hodiny Raspberry liší od telefonu o víc než 2 minuty, ukáže se „Hodiny Raspberry jdou o X pozadu“ (nebo napřed) s tlačítkem „Srovnat“, které pošle příkaz `cas`.
3. **Karta termostatu**, ve které je:
   - úsek dne s ikonou (slunce, soumrak, měsíc) a časem Raspberry (HH:MM) a štítek režimu;
   - velká vnitřní teplota a štítek **Topí** (červený), **Chladí** (modrý) nebo **V klidu** (šedý);
   - pozadí karty dostane při topení teplý a při chlazení studený nádech;
   - tlačítka **− / +** s hodnotou cíle, který právě platí: v Auto ve dne a večer denní cíl, v noci noční, v ručním režimu ruční. Krok je 0,5 °C. Podržení tlačítka hodnotu opakovaně mění, klepnutí na číslo otevře dialog pro přímé zadání. Popisek říká, o který cíl jde („Denní cíl“);
   - při „nikdo doma“ štítek „Nikdo doma · cíl −3,0 °C, platí 20,0 °C“;
   - v režimu Vypnuto místo tlačítek text „Termostat je vypnutý“ a tlačítko „Zapnout (Auto)“.
4. **Graf teplot** (kap. 8).
5. **Dlaždice** ve třech sloupcích:
   - Venku °C;
   - Vlhkost %;
   - Tlak hPa;
   - Osvětlení lx s poznámkou „šero“ (pod `prah_lux`) nebo „světlo“ (nad 1,3násobkem prahu); mezi tím jen „práh N“, protože šero má hysterezi a Raspberry jeho stav neposílá;
   - Pohyb Ano/Ne s „před X“. Při pohybu je dlaždice zvýrazněná;
   - Světlo „Svítí“/„Zhasnuto“ s tečkou ve skutečné barvě a s „ručně/auto · jas %“.

   Klepnutí na dlaždici světla přepne na záložku Světlo.
6. **Ovládání simulace**, jen v simulaci (kap. 5.4).

Když nejsou žádná data (před prvním připojením), je místo Přehledu obrazovka hledání (6.5).

### 6.2 Termostat

1. **Souhrn:** vnitřní teplota, platný cíl (`cil`) a štítek Topí/Chladí/V klidu.
2. **Režim:** segmentové tlačítko Auto / Ruční / Vypnuto a vysvětlivka (např. „Ve dne a večer drží denní cíl, v noci noční. Teď je den, takže platí denní.“).
3. **Cílové teploty:** řádky Denní (den a večer), Noční (noc) a Ruční (režim Ruční), každý s −/+ a hodnotou 5–35 °C po 0,5. Řádek, který právě platí, je zvýrazněný a má štítek „platí teď“.
4. **Nikdo doma:** stavový řádek („Doma je někdo · poslední pohyb před 40 s“, nebo „Nikdo doma · cíl je snížený o 3,0 °C“, nebo „Pohyb zatím nezaznamenán“). Pod ním „Snížit cíl po“ (1–240 min po 1) a „Snížit o“ (0–10 °C po 0,5).

### 6.3 Světlo

1. **Náhled:** velký kruh ve skutečné barvě, jehož záře odpovídá jasu (zhasnuto = šedý). Text stavu podle `zap`, `rucne` a úseku:

   | Stav | Text |
   |---|---|
   | ručně zapnuto | „Svítí · ručně“ |
   | ručně vypnuto | „Zhasnuto · ručně“ |
   | auto, den, svítí | „Svítí · venku je šero“ |
   | auto, den, nesvítí | „Zhasnuto · venku je dost světla“ |
   | auto, večer | „Svítí · večerní světlo“ |
   | auto, noc, svítí | „Noční světlo · pohyb“ |
   | auto, noc, nesvítí | „Zhasnuto · noc“ |

   Podtitul ukazuje jas a u ruční volby „platí do HH:MM“, tedy do začátku dalšího úseku podle Nastavení.
2. **Tlačítka:** Zapnout a Vypnout vedle sebe a pod nimi „Vrátit automatiku“. To jde stisknout jen při ruční volbě nebo
   u starých dat, jinak je zašedlé. Přepnout aplikace nemá, dělalo by totéž co Zapnout a Vypnout (obojí rozhodl uživatel
   23. 10. 2026, viz 2026-10-23-denik-a-vylepseni-design.md).
3. **Barva:** 11 předvoleb (bílá, teplá bílá, žlutá, oranžová, červená, růžová, fialová, modrá, azurová, zelená, limetková) a „vlastní“. Ta otevře dialog s výběrem odstínu a sytosti a s náhledem. Vybraná předvolba je označená, a když barva světla žádné předvolbě neodpovídá, je označená „vlastní“.
4. **Jas:** posuvník 1–100 %. Během tažení se jas posílá průběžně (kap. 7), takže LED reaguje živě. Vysvětlivka: „Výběr barvy nebo jasu světlo rovnou ručně zapne.“
5. **Automatika:**
   - přepínač „Rozsvítit při pohybu“ (`svetlo_pri_pohybu`, kap. 4.2);
   - „Noční světlo svítí“ (5–600 s po 5);
   - „Práh šera“ (1–10000 lx, krok podle velikosti: pod 10 po 1, pod 100 po 5, pod 1000 po 50, jinak po 500) s poznámkou „teď venku X lx“.

### 6.4 Nastavení

1. **Denní doba:**
   - pruh přes 24 h obarvený podle úseků (noc indigo, den žlutá, večer oranžová) se značkou aktuálního času Raspberry;
   - řádky „Den od“, „Večer od“, „Noc od“. Klepnutí na čas otevře 24h výběr času z Material 3;
   - u každého řádku tlačítko **„Teď“**, které nastaví začátek na aktuální čas Raspberry (HH:MM z posledního Stavu). Bez čerstvých dat je zašedlé;
   - tlačítko **„Vrátit původní časy“**. Před první změnou začátků v tomto běhu aplikace si ViewModel zapamatuje původní trojici a tlačítko ji pak vrátí jedním zápisem všech tří klíčů. Bez zapamatovaných časů je zašedlé.
2. **Čas Raspberry:** čas Raspberry a telefonu (s datem a sekundami), upozornění při rozdílu nad 1 minutu a tlačítko „Nastavit čas podle telefonu“. Formát odesílaného času je vždy `yyyy-MM-dd'T'HH:mm:ss`. Pozor, `LocalDateTime.toString()` vynechá nulové sekundy.
3. **Zdroj dat:** volba „Raspberry přes Bluetooth“ a „Simulace“. U Raspberry je řádek s adresou, sílou signálu (RSSI) a MTU a tlačítko Odpojit nebo Připojit.

### 6.5 Stavy bez dat a chyby

- **Hledání domku:** pulzující ikona Bluetooth, „Hledám dům „Termostat““, „Raspberry musí být zapnuté a v dosahu. Párování není potřeba.“, ukazatel průběhu a tlačítko „Spustit simulaci“.
- **Výběr domku** (víc zařízení „Termostat“, kap. 5.3): spodní panel „V dosahu je víc domů“ se seznamem řádků „Termostat · adresa · signál −61 dBm“, seřazeným podle síly signálu.
- **Chybí oprávnění:** vysvětlení a tlačítko „Povolit“, po trvalém odmítnutí „Otevřít nastavení“. Vždy i „Spustit simulaci“.
- **Bluetooth vypnutý:** tlačítko „Zapnout Bluetooth“ (systémový dialog). **Poloha vypnutá (Android ≤ 11):** tlačítko do nastavení polohy. **Telefon nemá BLE:** jen simulace.
- **Domek nemá službu Domek:** chybová obrazovka s tlačítkem „Zkusit znovu“.
- **Stará data:** všechny hodnoty na Přehledu (i na ostatních záložkách) zešednou a nahoře je oranžová karta „Data jsou N s stará“ s textem podle situace: „Raspberry přestalo posílat stav.“ při spojení, nebo „Spojení se přerušilo, znovu se připojuji…“ po výpadku. Graf se nemaže.
- **Odmítnutý zápis:** snackbar (kap. 7).

## 7. Zápisy a hlášky

- **Kroková tlačítka (− / +):** každý stisk hned změní zobrazenou hodnotu (draft s malým indikátorem odesílání). Zápis se pošle 600 ms po posledním stisku. Když už zápis běží, další stisky se sloučí do jednoho dalšího zápisu.
- **Posuvníky jasu a barvy:** během tažení se posílá jen poslední hodnota. Najednou běží nejvýš jeden zápis a mezi dvěma příkazy světla uplyne aspoň 150 ms (docs/ble-rozhrani.md, Poznámky pro Android). Když zápis doběhne a hodnota se mezitím změnila, pošle se aktuální. Po puštění se pošle konečná hodnota.
- **Přepínače, segmenty, tlačítka světla, výběr času a „Teď“** se posílají hned.
- **Po úspěchu** draft zůstane, dokud notifikace Nastavení nebo Stavu nepřinese stejnou hodnotu, nejdéle však 3 s. Potom platí hodnota z Raspberry.
- **Po odmítnutí nebo chybě** draft hned zmizí (na obrazovce je zase skutečná hodnota z Raspberry) a ukáže se snackbar s tlačítkem OK:
  - **Odmítnutý zápis Nastavení:** „Raspberry změnu odmítlo: ‹co› ‹pravděpodobný důvod›. Nic se nezměnilo.“ Důvod se odvodí:
    - shodné začátky úseků: „začátek noci 19:00 je stejný jako začátek večera“;
    - hodnota mimo rozsah (když by ji aplikace přesto poslala): „povoleno je 5–35 °C“;
    - `svetlo_pri_pohybu`: „Raspberry tuto volbu nezná, nejspíš má starší program“;
    - jinak bez důvodu, jen s kódem: „(kód 128)“.
  - **Odmítnutý příkaz:** „Raspberry odmítlo příkaz ‹co› (kód N).“
  - **Nepřipojeno:** „Dům není připojený, změna se neodeslala.“
  - **Bez odpovědi:** „Raspberry neodpovědělo. Změna se možná neprovedla.“
- **Kontroly v aplikaci:** číselné hodnoty aplikace omezí na povolený rozsah sama. **Shodu začátků úseků nekontroluje**, nechává ji na Raspberry, aby šel předvést odmítnutý zápis.

## 8. Graf

- Vzorek se uloží z každého přijatého Stavu: čas telefonu, `t_uvnitr`, `t_venku`, `cil`, `topi` a `chladi`. Historie drží nejvýš 6 h (starší vzorky se zahazují), přežije otočení i výpadek a vymaže se jen při přepnutí zdroje dat.
- Kreslí se vlastním `Canvas`, bez knihovny pro grafy:
  - plná tlustá čára pro vnitřní teplotu, tenká šedá pro venkovní a čárkovaný schodovitý průběh pro cíl;
  - podbarvené pruhy přes celou výšku tam, kde se topilo (červeně) nebo chladilo (modře);
  - mezera v čarách tam, kde mezi vzorky uplynulo víc než 5 s nebo je hodnota null.
- Okno grafu se volí přepínačem „5 min · 15 min · Vše“, výchozí je „Vše“. Na ose X je čas telefonu (HH:MM). Rozsah osy Y se určí z viditelných dat s okrajem 0,5 °C a nejmenším rozpětím 2 °C, popisky jsou po 1 nebo 2 °C. Pod grafem je legenda.
- Při velkém počtu vzorků se před kreslením zředí, nanejvýš na dva body na pixel.

## 9. Vzhled

- Material 3 s vlastním barevným schématem pro světlý i tmavý motiv. Motiv se řídí nastavením telefonu, dynamické barvy (Material You) jsou vypnuté.
- Barvy:

  | Role | Barva |
  |---|---|
  | hlavní (tyrkysová) | `#0D6A70` |
  | topení | `#DF522E` |
  | chlazení | `#2A77E2` |
  | noc | `#3E4090` |
  | den | `#F5C451` |
  | večer | `#EF8A4C` |

  Ostatní odstíny podle náhledů.
- Ikony z `material-icons-extended`, varianta Rounded. Písmo je systémové.
- Rozložení, velikosti a texty odpovídají schváleným náhledům.

## 10. Testování a ověření

- **Unit testy (JVM):**
  - parsování a skládání JSON (Stav, Nastavení s klíčem `svetlo_pri_pohybu` i bez něj, zápisy, příkazy, formát `cas`);
  - výběr platného cíle;
  - texty stavu světla a čas „platí do“;
  - hlášky při odmítnutí;
  - model a validace simulace;
  - ředění a mezery v grafu;
  - výpočet stáří dat;
  - fronta GATT operací s falešným zařízením.
- **Ruční ověření:** na telefonu A25 v simulaci a s Raspberry, nebo přes nRF Connect jako náhradní periferii. Projde se všech 5 scénářů z kap. 1 a výpadek (vypnout Raspberry, odejít z dosahu).
- Build musí projít přes `gradlew assembleDebug` a testy přes `gradlew testDebugUnitTest`.

## 11. Rozhodnutí, která jsem udělal sám (k odsouhlasení)

- `minSdk` 26, package `cz.vsb.mitr.domek`, název „Chytrý dům“.
- Přehled obsahuje všechno pro předvádění a −/+ mění cíl, který právě platí.
- Nastavení je rozdělené podle témat: „nikdo doma“ je u termostatu, práh šera a noční světlo u světla.
- Tlačítko „Teď“ u začátků úseků a „Vrátit původní časy“.
- Shodu začátků úseků kontroluje jen Raspberry, aby šlo předvést odmítnutý zápis.
- Simulace věrně napodobuje domek a má kartu „Ovládání simulace“ na Přehledu.
- Volba zdroje dat se neukládá, aplikace vždy startuje s Raspberry.
- Kromě Nastavení se po připojení jednou čte i Stav. Služba 0x181A se nečte.
- Graf drží až 6 h a přežije výpadek. Výchozí okno je „Vše“.
- Obrazovka nezhasne, dokud je aplikace v popředí.
- Jas má rozsah 1–100 % (0 % nahrazuje tlačítko Vypnout).
- Varování o hodinách na Přehledu se ukáže při rozdílu nad 2 minuty, v Nastavení už nad 1 minutu.
- Při víc zařízeních „Termostat“ si uživatel vybere ze seznamu. Volba se neukládá mezi spuštěními.
- Když 15 s nepřijde Stav, aplikace se znovu připojí.
- Pořadí štítků stavu připojení a to, co ukazují záložky před prvními daty (6.0).

## 12. Ponecháno na implementaci

Přesné časování (zpoždění zápisu, limity operací v rámci uvedených mezí), konstanty modelu simulace (hystereze, rychlost přibližování k venkovní teplotě, barvy a jas automatiky), přesné odstíny tmavého motivu, rozdělení do souborů a tříd a podoba dialogu vlastní barvy.
