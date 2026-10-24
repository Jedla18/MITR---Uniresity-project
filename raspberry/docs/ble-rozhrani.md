# Bluetooth rozhraní domu „Termostat“

Podklad pro Android aplikaci (Kotlin). Dokument je smlouva mezi programem na Raspberry Pi (dál „dům“) a aplikací na tabletu: aplikace se staví jen podle něj. Říká, co dům přes Bluetooth LE posílá a přijímá, v jakém formátu a co má aplikace v které situaci dělat. Datové formáty a pravidla odpovídají kódu v `domek/`. Bluetooth server je ověřený automatickými testy (`tests/test_ble_server.py`, `tests/test_ble_format.py`), během domu na Raspberry Pi i aplikací pro Android, která se k němu připojuje, čte Stav, dostává notifikace a zapisuje Nastavení i Příkaz.

Zdroj v kódu: `domek/ble_format.py` (UUID a formát), `domek/ble_server.py` (server), `domek/jadro.py` (stav a příkazy), `domek/nastaveni.py` (nastavení). Testy v `tests/test_ble_format.py` hlídají, aby dokument s kódem seděl: UUID, klíče stavu, výchozí hodnoty a rozsahy nastavení, hodnoty v `chyby` a platnost příkladů JSON.

## Co je ověřené a co je doporučení

Dva body ověřené nejsou a aplikace je má brát jako doporučení, ne jako zaručené chování serveru:

- **MTU 517.** Vyjednává ho klient, server si ho nevynucuje. Aplikace si ho proto vyžádá vždy (viz „Připojení a MTU 517“).
- **Opětovná inzerce po odpojení.** Server po odpojení inzerát nijak neobnovuje, takže se aplikace na opětovné nalezení domu nesmí spoléhat bez vlastního hlídání (viz „Ztráta spojení a obnova“).

Všechno ostatní v dokumentu odpovídá kódu v `domek/` a je hlídané testy (`test_ble_format.py`, `test_ble_server.py`, `test_jadro.py`, `test_nastaveni.py`, `test_svetlo.py`, `test_termostat.py`, `test_denni_doba.py`, `test_spusteni.py`).

## Přehled

Dům je BLE **periferie** (GATT server) s názvem `Termostat`, tablet je **centrála** (GATT klient). Dům je simulace: vnitřní teplotu a topení či chlazení jen počítá program, zatímco venkovní teplotu, vlhkost, tlak a osvětlení měří skutečná čidla na Raspberry a topení či chlazení ukazuje červená a zelená LED. Aplikace dělá tři věci:

| Co | Jak |
|---|---|
| Sleduje dům | zapne notifikace charakteristiky **Stav** a každou sekundu dostane celý stav jako JSON |
| Mění nastavení | zapíše do charakteristiky **Nastavení** JSON jen s hodnotami, které se mění |
| Ovládá světlo a čas | zapíše do charakteristiky **Příkaz** JSON s jednorázovým příkazem |

Dům běží i bez tabletu, po zapnutí napájení se rozběhne sám a po restartu načte uložené nastavení. Nastavení se ukládá do souboru po každé změně. Historii měření si dům nepamatuje: graf si kreslí aplikace z dat, která dostala během připojení.

## Nalezení domu

- Název periferie je **`Termostat`**, je v inzerátu a podle něj se dům hledá.
- V inzerátu je služba **Environmental Sensing, 0x181A** (plné UUID `0000181a-0000-1000-8000-00805f9b34fb`).
- Vlastní služba Dům (`5bf00001-f758-4c41-a34e-bab58ce87673`) v inzerátu **není**: 128bitové UUID by se vedle názvu nevešlo do 31 bajtů reklamního paketu. Objeví se až po připojení, při `discoverServices()`.
- Službu 0x181A může inzerovat i cizí zařízení (každé BLE čidlo), takže filtr podle služby nestačí. Po nalezení zkontroluj, že název je přesně `Termostat`. Název čti z inzerátu (`ScanRecord.deviceName`), `BluetoothDevice.name` může být zastaralý z cache.
- **Párování není potřeba.** Nevolej `createBond()`, žádná charakteristika nevyžaduje šifrování ani ověření. Připojit se může kdokoli v dosahu (rozhodnutí pro školní ukázku).
- Po prvním nalezení si aplikace může zapamatovat adresu zařízení a příště se připojit přímo, bez hledání.

## Připojení a MTU 517

Hned po připojení si aplikace vyžádá **MTU 517** (`BluetoothGatt.requestMtu(517)`).

Proč: výchozí ATT MTU je 23 B a notifikace unese jen MTU − 3 = 20 B. Stav má asi 270 až 310 B a notifikace se nedělí na kusy, jen se uřízne, takže by aplikaci přišel rozbitý JSON. S MTU 517 unese notifikace 514 B, tedy každý JSON domu (nejvýš 512 B) celý. MTU domlouvá klient. Že ho Raspberry opravdu vyjedná, ověřené není.

- Aplikace potřebuje aspoň MTU 515 (512 B + 3 B záhlaví). Když `onMtuChanged` ohlásí menší hodnotu, notifikace se mohou useknout a JSON nepůjde přečíst: aplikace to má ukázat jako chybu spojení.
- Čtení Stavu a Nastavení funguje i při malém MTU: server umí čtení po částech (čtení s offsetem) a Android si zbytek vyžádá sám. Notifikace ne. Knihovna bluezero 0.9.1 offset sama nepodporuje, server proto čtení obsluhuje po svém: hodnotu zjistí při čtení od začátku a další části vrací z téže hodnoty, takže se poskládá celý platný JSON, i když se stav mezitím změní. Přesto si aplikace MTU 517 vyžádá vždy.
- Zápis Nastavení a Příkazu má nejvýš 512 B a s MTU 517 se vejde do jednoho požadavku.

## Postup po připojení

Doporučené pořadí v Androidu:

1. `connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`.
2. V `onConnectionStateChange` se stavem `STATE_CONNECTED` zavolej `requestMtu(517)`.
3. V `onMtuChanged` zavolej `discoverServices()`.
4. V `onServicesDiscovered` najdi charakteristiky podle UUID z tabulky níže. Když některá chybí, je to chyba spojení (zastaralá cache, viz „Ztráta spojení a obnova“).
5. Zapni notifikace Stavu a Nastavení (případně i ESS): `setCharacteristicNotification(ch, true)` a zápis `BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE` do deskriptoru CCCD `00002902-0000-1000-8000-00805f9b34fb` té charakteristiky.
6. Přečti Nastavení (`readCharacteristic`). Notifikace Nastavení chodí jen při změně a Stav přijde z notifikací do sekundy. Proto si aplikace počáteční Nastavení přečte sama; Stav jde přečíst taky hned.
7. Od té chvíle platí pravidla o čerstvosti dat (oddíl „Notifikace a čerstvost dat“).

Android provede jen jednu GATT operaci naráz. Další (čtení, zápis, zápis deskriptoru) spusť až po zavolání callbacku předchozí, nejlépe přes frontu.

## Ztráta spojení a obnova

- Aplikace se po ztrátě spojení připojí znovu **sama**, bez zásahu uživatele: novým hledáním, nebo `connectGatt` na zapamatovanou adresu. Server po odpojení inzerát nijak neobnovuje: jestli se dům po odpojení znovu inzeruje, ověřené není, a aplikace to proto nesmí brát jako zaručené.
- Po restartu Raspberry (výpadek napájení, restart služby) se spojení přeruší a dům se sám znovu spustí (služba systemd), třeba až za desítky sekund. Po každém novém připojení projde aplikace celý postup výše znovu: znovu najde služby a znovu zapne notifikace. Nic z minulého spojení neplatí.
- Android hlásí přerušené spojení se zpožděním, někdy i desítky sekund. Aplikace se proto nesmí spoléhat jen na událost odpojení a hlídá i stáří dat (pravidlo 3 s níže).
- Android si pamatuje strukturu služeb zařízení. Když se struktura změní (třeba po výměně programu na Raspberry), může tablet vidět starou. Pomůže vypnout a zapnout Bluetooth na tabletu, případně před `discoverServices()` zavolat skrytou metodu `BluetoothGatt.refresh()` přes reflexi.

## Služby a charakteristiky

UUID a názvy jsou v kódu (`domek/ble_format.py`) a hlídá je test. **Vlastnosti** charakteristik (read, write, notify) nastavuje `domek/ble_server.py`.

| Služba | Charakteristika | UUID | Vlastnosti | Hodnota |
|---|---|---|---|---|
| Environmental Sensing (`0x181A`) | Teplota | `0x2A6E` | read, notify | sint16, setiny °C |
| Environmental Sensing (`0x181A`) | Vlhkost | `0x2A6F` | read, notify | uint16, setiny % |
| Environmental Sensing (`0x181A`) | Tlak | `0x2A6D` | read, notify | uint32, desetiny Pa |
| Dům (`5bf00001-f758-4c41-a34e-bab58ce87673`) | Stav | `5bf00010-f758-4c41-a34e-bab58ce87673` | read, notify | JSON, UTF-8 |
| Dům (`5bf00001-f758-4c41-a34e-bab58ce87673`) | Nastavení | `5bf00011-f758-4c41-a34e-bab58ce87673` | read, write, notify | JSON, UTF-8 |
| Dům (`5bf00001-f758-4c41-a34e-bab58ce87673`) | Příkaz | `5bf00012-f758-4c41-a34e-bab58ce87673` | write | JSON, UTF-8 |

- 16bitové UUID jsou zkratky: `0xXXXX` znamená `0000XXXX-0000-1000-8000-00805f9b34fb`.
- `write` je zápis **s odpovědí** (`WRITE_TYPE_DEFAULT`). Zápis bez odpovědi server nenabízí, protože by se aplikace nedozvěděla, že byl odmítnut.
- Příkaz nejde číst ani odebírat, čtení Příkazu skončí chybou.
- Hodnoty ESS jsou binární, vše ostatní je JSON.

## Environmental Sensing (ESS)

Standardní služba, aby hodnoty uměla zobrazit i obecná aplikace (nRF Connect). Server plní ESS **venkovními** hodnotami ze Stavu (`t_venku`, `vlhkost`, `tlak`) a notifikace ESS posílá ve stejných okamžicích jako notifikace Stavu, vždy z téhož stavu. Vlastní aplikace ESS nepotřebuje: Stav obsahuje totéž a navíc chyby čidel. ESS může použít jako doplněk.

Všechny hodnoty jsou **little-endian**.

| Charakteristika | UUID | Typ | Jednotka | Převod | Neznámá hodnota |
|---|---|---|---|---|---|
| Teplota | `0x2A6E` | sint16 (se znaménkem) | 0,01 °C | `hodnota / 100` je °C | `0x8000`, bajty `00 80` |
| Vlhkost | `0x2A6F` | uint16 | 0,01 % | `hodnota / 100` je % | `0xFFFF`, bajty `FF FF` |
| Tlak | `0x2A6D` | uint32 | 0,1 Pa | `hodnota / 1000` je hPa | `0`, bajty `00 00 00 00` |

- Když BME280 nejde přečíst (`chyby` obsahuje `meteo`), server pošle „neznámou hodnotu“. U tlaku norma žádnou zvláštní hodnotu nemá, proto je to nula.
- Hodnoty mimo rozsah se omezí: teplota na −273,15 až 327,67 °C, vlhkost na 0 až 100 %, tlak na 0 až 4 294 967,295 hPa.
- Příklady: 14,5 °C je `AA 05`, 23,05 °C je `01 09`, −5,5 °C je `DA FD`, vlhkost 61,3 % je `F2 17`, tlak 1013,25 hPa je `02 76 0F 00`.

## Formát JSON

Stav, Nastavení i Příkaz jsou JSON.

- Text v **UTF-8 bez BOM**. Kořen je vždy objekt `{...}`.
- Hodnota má nejvýš **512 bajtů** (bajtů, ne znaků; je to limit atributu v BLE). Stav i Nastavení se do něj vždy vejdou: Stav má v praxi asi 270 až 310 B (nejdelší v testu 304 B), nastavení s výchozími hodnotami 301 B. Delší zápis server odmítne (kontrola je v `dekoduj_json`).
- JSON se kóduje kompaktně, bez mezer (`zakoduj_json`). Čtení (`dekoduj_json`) přijme i JSON s mezerami, když se vejde do 512 B. Příklady níže jsou kvůli čitelnosti rozepsané.
- Aplikace **musí ignorovat klíče, které nezná**, a nesmí se spoléhat na pořadí klíčů: dům může časem přidat další.
- Ve Stavu jsou vždy všechny klíče. Hodnota, která není k dispozici, je `null`.
- Texty (`usek`, `rezim`, hodnoty v `chyby`, slovní příkazy) jsou **malými písmeny a bez diakritiky**, přesně jak jsou v tabulkách. Do čitelné podoby (Den, Večer, Noc, Ruční, …) je převádí aplikace.
- Čísla ve Stavu čti jako desetinná (`Double`), i když některé přijde bez desetinné části (`22` místo `22.0`). Celá čísla (`jas`, složky barvy, `nocni_svetlo_s`, `nikdo_doma_po_min`) jsou vždy bez desetinné tečky. Při zápisu na tom záleží, viz oddíl Nastavení.
- Neznámou textovou hodnotu (nový název v `chyby`, nový úsek dne) ukaž tak, jak přišla, a nespadni na ní.
- JSON nikdy neobsahuje `NaN` ani `Infinity`.

## Stav

Charakteristika Stav (`5bf00010-f758-4c41-a34e-bab58ce87673`) má vlastnosti read + notify a čtení i notifikace dávají celý aktuální stav ve stejném tvaru. Obsah Stavu (klíče, typy, jednotky, zaokrouhlení) odpovídá `Domek.stav()`.

| Klíč | Typ | Jednotka | Význam |
|---|---|---|---|
| `cas` | text nebo `null` | – | Čas domu ve tvaru `YYYY-MM-DDTHH:MM:SS`, místní čas bez časového pásma (např. `2026-10-20T10:00:40`). `null` je před prvním krokem domu. Program první krok udělá dřív, než se začne inzerovat, takže aplikace `null` neuvidí, ale musí ho snést. |
| `usek` | text nebo `null` | – | Úsek dne: `"den"`, `"vecer"` nebo `"noc"`. |
| `t_venku` | číslo nebo `null` | °C | Venkovní teplota z BME280, na 2 desetinná místa. `null` při poruše čidla. |
| `vlhkost` | číslo nebo `null` | % | Venkovní vlhkost z BME280, na 1 desetinné místo. `null` při poruše čidla a také tehdy, když je místo BME280 zapojený BMP280, který vlhkost neměří: to porucha není, takže `chyby` kvůli tomu nic nehlásí a `t_venku` s `tlak` platí. ESS pak vlhkost posílá jako neznámou (`0xFFFF`). |
| `tlak` | číslo nebo `null` | hPa | Venkovní tlak z BME280, na 2 desetinná místa. `null` při poruše čidla. |
| `lux` | číslo nebo `null` | lx | Venkovní osvětlení z čidla TSL2561, na 1 desetinné místo. `null`, když čidlo nejde přečíst (není zapojené nebo selhalo), a `chyby` pak obsahuje `osvetleni`. |
| `t_uvnitr` | číslo nebo `null` | °C | **Simulovaná** vnitřní teplota, na 2 desetinná místa. `null`, dokud nepřišlo první měření venkovní teploty. Při poruše BME280 si drží poslední hodnotu. |
| `cil` | číslo nebo `null` | °C | Cílová teplota, kterou termostat právě drží, na 2 desetinná místa. `null`, když je `rezim` `"vypnuto"`. |
| `topi` | boolean | – | Topení je zapnuto. |
| `chladi` | boolean | – | Chlazení je zapnuto. `topi` a `chladi` nejsou nikdy `true` zároveň. |
| `rezim` | text | – | Režim termostatu: `"auto"`, `"rucni"` nebo `"vypnuto"`. Je to `rezim_termostatu` z nastavení. |
| `nikdo_doma` | boolean | – | `true`, když čidlo pohybu `nikdo_doma_po_min` minut nezachytilo pohyb. První pohyb to zruší. |
| `pohyb` | boolean | – | Čidlo pohybu právě vidí pohyb. Ukazuje přímo čidlo: `true` je jen tak dlouho, dokud PIR hlásí pohyb (HC-SR501 asi 3 s). Oranžová kontrolka pohybu v domě svítí ještě 10 s po posledním pohybu a ve Stavu není. |
| `svetlo` | objekt | – | Stav světla, položky jsou v další tabulce. |
| `chyby` | pole textů | – | Názvy právě trvajících poruch, viz oddíl „Hodnoty v poli `chyby`“. Prázdné pole znamená, že vše funguje. |

Objekt `svetlo`:

| Klíč | Typ | Jednotka | Význam |
|---|---|---|---|
| `zap` | boolean | – | Světlo je zapnuté. |
| `barva` | pole tří celých čísel | RGB, každé 0 až 255 | Barva světla. U vypnutého světla `[0,0,0]`. |
| `jas` | celé číslo | %, 0 až 100 | Jas světla. U vypnutého světla `0`. |
| `rucne` | boolean | – | `true`, když stav vybral člověk příkazem z tabletu. `false`, když o světle rozhoduje automatika. |

`svetlo` je stav, v jakém světlo **má** být. Světlem je obyčejná RGB LED (umí barvu i jas, ale barva se mění hned, bez prolínání).

### Příklad: ve dne, topí se

Čtyřicet sekund po spuštění domu. Venku je 14,5 °C, vnitřní teplota (16,75 °C) stoupá k cíli 23 °C a termostat topí. Je den a venku je světlo (420,5 lx), takže světlo nesvítí.

```json
{
  "cas": "2026-10-20T10:00:40",
  "usek": "den",
  "t_venku": 14.5,
  "vlhkost": 61.3,
  "tlak": 1008.4,
  "lux": 420.5,
  "t_uvnitr": 16.75,
  "cil": 23.0,
  "topi": true,
  "chladi": false,
  "rezim": "auto",
  "nikdo_doma": false,
  "pohyb": false,
  "svetlo": {"zap": false, "barva": [0, 0, 0], "jas": 0, "rucne": false},
  "chyby": []
}
```

### Příklad: večer, světlo nastavené ručně

Tablet poslal příkaz `{"svetlo":{"barva":[0,0,255],"jas":50}}`, takže `rucne` je `true`.

```json
{
  "cas": "2026-10-20T19:30:02",
  "usek": "vecer",
  "t_venku": 19.8,
  "vlhkost": 48.0,
  "tlak": 1013.25,
  "lux": 12.0,
  "t_uvnitr": 19.92,
  "cil": 23.0,
  "topi": true,
  "chladi": false,
  "rezim": "auto",
  "nikdo_doma": false,
  "pohyb": false,
  "svetlo": {"zap": true, "barva": [0, 0, 255], "jas": 50, "rucne": true},
  "chyby": []
}
```

### Příklad: v noci vypadlo čidlo BME280

Venkovní hodnoty jsou `null`, termostat stojí (`topi` i `chladi` jsou `false`) a `t_uvnitr` drží poslední hodnotu. Čidlo osvětlení funguje dál, takže `lux` je číslo a `chyby` obsahuje jen `meteo`.

```json
{
  "cas": "2026-10-20T23:30:05",
  "usek": "noc",
  "t_venku": null,
  "vlhkost": null,
  "tlak": null,
  "lux": 0.4,
  "t_uvnitr": 21.76,
  "cil": 20.0,
  "topi": false,
  "chladi": false,
  "rezim": "auto",
  "nikdo_doma": false,
  "pohyb": false,
  "svetlo": {"zap": false, "barva": [0, 0, 0], "jas": 0, "rucne": false},
  "chyby": ["meteo"]
}
```

### Jak dům hodnoty určuje

Aplikace to nemusí počítat, ale pomůže jí to hodnoty správně popsat.

- **Úsek dne** (`usek`): tři začátky z nastavení (`zacatek_den`, `zacatek_vecer`, `zacatek_noc`). Platí úsek, jehož začátek byl naposledy před časem `cas` (nebo přesně v něm). Před nejranějším začátkem dne platí úsek s nejpozdějším začátkem, ten trvá přes půlnoc. Při výchozím nastavení je noc od 22:00 do 7:00, den od 7:00 do 19:00 a večer od 19:00 do 22:00.
- **Cíl** (`cil`): v režimu `"auto"` je to `cil_den` ve dne a večer a `cil_noc` v noci, a když je `nikdo_doma`, o `utlum_nikdo_doma` méně. V režimu `"rucni"` je to `cil_rucni` bez útlumu. V režimu `"vypnuto"` je `cil` `null`: netopí se ani nechladí a `t_uvnitr` se jen blíží k `t_venku`.
- **Topení a chlazení**: topení se zapne, když je `t_uvnitr` pod cílem o víc než 0,3 °C, a vypne, když cíle dosáhne. Chlazení se zapne, když je `t_uvnitr` nad cílem o víc než 1,0 °C, a vypne, když klesne na cíl. Po startu se `t_uvnitr` rovná `t_venku`.
- **Nikdo doma** (`nikdo_doma`): odpočet začíná při spuštění domu a každý pohyb ho začne znovu.
- **Ruční světlo** (`rucne` je `true`) přebíjí automatiku do konce úseku dne, ve kterém vzniklo, nebo do příkazu `{"svetlo":"auto"}`.

**Světlo v automatice** (`rucne` je `false`) podle úseku dne. Automatika rozhoduje jen o tom, **kdy** světlo svítí. Svítí vždy barvou `barva_svetla` a jasem `jas_svetla` z nastavení, tedy tím, co tablet naposledy nastavil (výchozí je bílá na 100 %). Jediná výjimka je jas nočního světla:

| `usek` | Světlo |
|---|---|
| `"den"` | Jen při šeru, tedy když `lux` klesne pod `prah_lux` (šero končí až nad 1,3násobkem prahu): barva `barva_svetla`, jas `jas_svetla`. Jinak zhasnuto. Bez osvětlení (`lux` je `null`) se šero nepředpokládá. |
| `"vecer"` | Vždy: barva `barva_svetla`, jas `jas_svetla`. |
| `"noc"` | Zhasnuto. Při pohybu se na `nocni_svetlo_s` sekund rozsvítí slabé noční světlo, ale jen když je `nocni_svetlo_pri_pohybu` `true`: barva `barva_svetla`, jas `min(jas_svetla, 10)`, takže nikdy nad 10 %. Každý další pohyb odpočet začne znovu. |

Ruční světlo (`rucne` je `true`) svítí také barvou a jasem z nastavení. Změní-li se `barva_svetla` nebo `jas_svetla`, změní se i ručně zapnuté světlo nejpozději v dalším kroku domu (do 1 s).

## Nastavení

Charakteristika Nastavení (`5bf00011-f758-4c41-a34e-bab58ce87673`) má vlastnosti read + write + notify, čtení i notifikace dávají **všech 14 klíčů** a zápis smí obsahovat jen některé. Obsah a pravidla níže (klíče, typy, rozsahy, výchozí hodnoty, sloučení, odmítnutí) odpovídají kódu (`domek/nastaveni.py`, `Domek.zmen_nastaveni`).

| Klíč | Typ | Rozsah | Výchozí | Význam |
|---|---|---|---|---|
| `rezim_termostatu` | text | `"auto"`, `"rucni"` nebo `"vypnuto"` | `"auto"` | Režim termostatu. |
| `cil_den` | číslo | 5 až 35 °C | `23.0` | Cílová teplota ve dne a večer (v režimu auto). |
| `cil_noc` | číslo | 5 až 35 °C | `20.0` | Cílová teplota v noci (v režimu auto). |
| `cil_rucni` | číslo | 5 až 35 °C | `22.0` | Cílová teplota v ručním režimu. |
| `zacatek_den` | text `HH:MM` | `00:00` až `23:59` | `"07:00"` | Kdy začíná den. |
| `zacatek_vecer` | text `HH:MM` | `00:00` až `23:59` | `"19:00"` | Kdy začíná večer. |
| `zacatek_noc` | text `HH:MM` | `00:00` až `23:59` | `"22:00"` | Kdy začíná noc. |
| `prah_lux` | číslo | 1 až 10000 lx | `50.0` | Pod tímto osvětlením je venku šero. |
| `nocni_svetlo_s` | **celé číslo** | 5 až 600 s | `30` | Jak dlouho po pohybu svítí noční světlo. |
| `nikdo_doma_po_min` | **celé číslo** | 1 až 240 min | `30` | Po kolika minutách bez pohybu nikdo není doma. |
| `utlum_nikdo_doma` | číslo | 0 až 10 °C | `3.0` | O kolik se v režimu auto sníží cíl, když nikdo není doma. |
| `nocni_svetlo_pri_pohybu` | **bool** | `true` nebo `false` | `true` | Jestli pohyb v noci rozsvítí noční světlo. Při `false` se noční světlo nerozsvítí, ale `pohyb` ve Stavu a `nikdo_doma` fungují dál. V zápisu je nepovinný jako každý klíč. |
| `barva_svetla` | **pole tří celých čísel** `[r,g,b]` | každé 0 až 255 | `[255,255,255]` | Barva, kterou světlo svítí, kdykoli svítí (automatika i ruční zapnutí). Aplikace si ji čte pro záložku Světlo. Zápis `barva_svetla` do Nastavení ji jen uloží, příkaz `svetlo` s objektem ji uloží a navíc zapne ruční světlo. |
| `jas_svetla` | **celé číslo** | 0 až 100 % | `100` | Jas, kterým světlo svítí. Noční světlo je nejvýš na 10 %, tedy `min(jas_svetla, 10)`. |

Hranice rozsahů platí včetně.

```json
{
  "rezim_termostatu": "auto",
  "cil_den": 23.0,
  "cil_noc": 20.0,
  "cil_rucni": 22.0,
  "zacatek_den": "07:00",
  "zacatek_vecer": "19:00",
  "zacatek_noc": "22:00",
  "prah_lux": 50.0,
  "nocni_svetlo_s": 30,
  "nikdo_doma_po_min": 30,
  "utlum_nikdo_doma": 3.0,
  "nocni_svetlo_pri_pohybu": true,
  "barva_svetla": [255, 255, 255],
  "jas_svetla": 100
}
```

### Pravidla zápisu

- Zápis může obsahovat **jen část nastavení**, tedy jen klíče, které se mění. Ostatní zůstanou, jak byly. Prázdný objekt `{}` je platný a nic nezmění.
- **Celý zápis platí, nebo neplatí.** Je-li neplatná jediná hodnota, neuplatní se žádná z hodnot toho zápisu.
- **Čísla** (`cil_*`, `prah_lux`, `utlum_nikdo_doma`): JSON číslo, celé i desetinné (`26` i `26.5`). Dům si je uloží jako desetinná, takže po přečtení je `26.0`. `true`, `false` a text jsou chyba.
- **Bool** (`nocni_svetlo_pri_pohybu`): jen JSON `true` nebo `false`. Číslo (`1`, `0`) a text (`"true"`) jsou chyba.
- **Barva světla** (`barva_svetla`): pole **právě tří celých čísel** 0 až 255, třeba `[255,147,41]`. Desetinné číslo, `true`, text i jiný počet složek jsou chyba. Při čtení přijde zase pole.
- **Celá čísla** (`nocni_svetlo_s`, `nikdo_doma_po_min`, `jas_svetla`): musí být zapsána **bez desetinné tečky**. `30` projde, `30.0` se odmítne. Pozor na knihovny, které zapisují každé číslo jako `Double`.
- **Časy** (`zacatek_*`): text `HH:MM`, dvě číslice hodin (`00` až `23`), dvojtečka, dvě číslice minut (`00` až `59`). `7:00` ani `24:00` ani `07:00:00` neprojdou.
- **Tři začátky musí být po zápisu navzájem různé.** Kontroluje se výsledek po sloučení, takže jde v jednom zápisu třeba prohodit dva začátky. Jejich pořadí v čase je libovolné, úsek dne se určuje podle pravidla v oddílu Stav.
- Neznámý klíč je chyba (chrání před překlepem).
- Nastavení se po každé změně uloží do souboru a po restartu domu se načte. Když se uložení nepovede, změna platí dál a `chyby` obsahuje `nastaveni`.

### Po zápisu

- **Povedl se:** `onCharacteristicWrite` dostane `GATT_SUCCESS`. Server pošle notifikaci Nastavení (celé nové nastavení) a Stav, který už změnu obsahuje: před odesláním Stavu provede jeden krok domu (`krok`), aby Stav nesměšoval nové nastavení se starým cílem a topením.
- **Nepovedl se:** **nastavení zůstane beze změny** (neplatný zápis nic nezmění), zápis skončí chybou a server běží dál. Viz oddíl „Když zápis selže“.

Příklady platných zápisů:

| Zápis | Účinek |
|---|---|
| `{"cil_den":26}` | ve dne a večer se drží 26 °C |
| `{"rezim_termostatu":"rucni","cil_rucni":24.5}` | ruční režim s cílem 24,5 °C |
| `{"zacatek_noc":"21:30"}` | noc začíná ve 21:30 |
| `{"nikdo_doma_po_min":1,"nocni_svetlo_s":60}` | po minutě bez pohybu nikdo není doma, noční světlo svítí minutu |

Příklady zápisů, které se **odmítnou**:

| Zápis | Proč |
|---|---|
| `{"cil_den":99}` | mimo rozsah 5 až 35 |
| `{"cil_den":"26"}` | text místo čísla |
| `{"cil_den":true}` | `true` a `false` nejsou čísla |
| `{"nocni_svetlo_pri_pohybu":1}` | bool musí být `true` nebo `false`, číslo ne |
| `{"nocni_svetlo_s":30.0}` | celé číslo musí být bez desetinné tečky |
| `{"zacatek_den":"7:00"}` | čas musí mít tvar `HH:MM` se dvěma číslicemi hodin |
| `{"zacatek_den":"19:00"}` | při výchozím začátku večera 19:00 by měly dva úseky stejný začátek |
| `{"teplota":20}` | neznámý klíč |
| `{"cil_den":26,"cil_noc":99}` | jedna neplatná hodnota shodí celý zápis, neplatí ani `cil_den` |
| `[1,2]` | kořen není objekt |
| `{"cil_den":` | neplatný JSON |

## Příkaz

Charakteristika Příkaz (`5bf00012-f758-4c41-a34e-bab58ce87673`) má jen vlastnost write. Příkaz je JSON **objekt s právě jedním klíčem** (viz `Domek.proved_prikaz`). Klíčů je zatím dva, `svetlo` a `cas`. Příkaz je jednorázový: dům si ho nepamatuje (výjimkou je `svetlo` s objektem, který barvu a jas uloží do Nastavení, viz Světlo) a odpovědí je jen úspěch nebo chyba zápisu. Stav s výsledkem přijde hned po příkazu.

| Příkaz | Účinek |
|---|---|
| `{"svetlo":"zapnout"}` | Zapne světlo ručně. Barva a jas jsou z nastavení (`barva_svetla`, `jas_svetla`; výchozí je bílá, jas 100). |
| `{"svetlo":"vypnout"}` | Vypne světlo ručně. |
| `{"svetlo":"prepnout"}` | Přepne světlo na opak toho, co právě dělá (`svetlo.zap`), jako ruční volbu. |
| `{"svetlo":"auto"}` | Zruší ruční volbu, světlo se zase řídí automatikou. |
| `{"svetlo":{"barva":[0,0,255],"jas":50}}` | Uloží barvu a jas do nastavení a zapne světlo ručně touto barvou a jasem. |
| `{"svetlo":{"jas":30}}` | Uloží jen jas, barva zůstane z nastavení, a zapne světlo ručně. |
| `{"svetlo":{"barva":[255,147,41]}}` | Uloží jen barvu, jas zůstane z nastavení, a zapne světlo ručně. |
| `{"cas":"2026-10-20T14:03:00"}` | Nastaví čas Raspberry: dům ho předá nastavovači času, který ho nastaví v systému, viz oddíl Čas. |

### Světlo

- Slovní příkazy jsou přesně `"zapnout"`, `"vypnout"`, `"prepnout"` a `"auto"`, malými písmeny.
- Objekt s barvou a jasem musí mít aspoň jeden z klíčů `barva` a `jas` a **žádný jiný**.
  - `barva` je pole **právě tří celých čísel** 0 až 255 (červená, zelená, modrá).
  - `jas` je **celé číslo** 0 až 100.
  - `true`, `false`, desetinná čísla (`50.0`) a text se odmítnou.
- Ruční volba (`zapnout`, `vypnout`, `prepnout`, objekt) platí **do konce úseku dne, ve kterém vznikla**, tedy do další změny `usek`, nebo dokud ji příkaz `auto` nezruší. Pak se světlo vrátí k automatice. Ve Stavu to ukazuje `svetlo.rucne`.
- Objekt s barvou a jasem je zároveň **změna Nastavení**: zadané hodnoty se uloží jako `barva_svetla` a `jas_svetla` do souboru, takže platí i po restartu domu a pro automatiku (večer, noční světlo, šero ve dne svítí touto barvou). Změní se tedy i Nastavení a aplikace si z něj může barvu a jas přečíst pro záložku Světlo. Když se tím Nastavení opravdu změní, server po příkazu pošle i notifikaci Nastavení (před Stavem); příkaz se stejnou barvou a jasem, jaké už jsou uložené, Nastavení neposílá. Odmítnutý příkaz nic nezmění ani neuloží. Nepodaří-li se nastavení uložit, změna platí dál a `chyby` obsahuje `nastaveni`.
- Ruční volba si pamatuje jen zapnuto, nebo vypnuto. Barva a jas se berou vždy z nastavení, takže `zapnout` po `vypnout` rozsvítí světlo tak, jak ho tablet nastavil naposled (po restartu domu také).
- `{"svetlo":{"jas":0}}` je platný příkaz, ale výsledkem je `zap: true` při jasu 0, tedy světlo, které nesvítí. Jas 0 se uloží do nastavení, takže automatika pak „svítí“ se `zap: true` a jasem 0, i po restartu, dokud tablet nenastaví jiný jas. Pro zhasnutí používej `vypnout`; posuvník jasu v aplikaci by měl mít nejmenší hodnotu 1.

### Čas

- Text přesně ve tvaru **`YYYY-MM-DDTHH:MM:SS`**: 19 znaků, písmeno `T` mezi datem a časem, např. `2026-10-20T14:03:00`.
  - Je to **místní čas** tabletu, **bez časového pásma** (žádné `Z` ani `+02:00`) a bez zlomků sekund.
  - Dům může přijmout i jiné tvary (třeba samotné datum jako půlnoc nebo čas bez sekund) a vyložit je po svém. Aplikace proto posílá jen tento tvar. Čas s časovým pásmem dům odmítne.
- Raspberry bere čas jako svůj místní čas, takže časové pásmo nastavené v jeho systému má odpovídat tabletu.
- Čas se nastaví v systému Raspberry (příkazem `date`); nový `cas` se pak ve Stavu projeví nejpozději v další sekundové notifikaci. Podle nového času se přepočítá `usek` a odpočty „nikdo doma“ a nočního světla se skokem času nerozbijí (odpočty se měří na monotonních hodinách).
- Raspberry nemá hodiny reálného času (RTC). Čas jinak bere ze sítě (NTP), ale po vypnutí bez internetu je po zapnutí čas špatný, dokud ho tablet nenastaví příkazem `cas`.
- Příkaz selže i tehdy, když Raspberry čas nastavit nedokáže (chyba příkazu `date`).

### Příkazy, které se odmítnou

| Příkaz | Proč |
|---|---|
| `{}` nebo `{"svetlo":"auto","cas":"2026-10-20T14:03:00"}` | příkaz musí mít právě jeden klíč |
| `"zapnout"` nebo `[]` | kořen není objekt |
| `{"teplota":20}` | neznámý příkaz |
| `{"svetlo":"blikat"}` | neznámá hodnota světla |
| `{"svetlo":{}}` nebo `{"svetlo":{"bila":1}}` | objekt bez `barva` a `jas`, nebo s cizím klíčem |
| `{"svetlo":{"barva":[0,0,256]}}` | složka barvy mimo 0 až 255 |
| `{"svetlo":{"barva":[0,0]}}` | barva nemá tři složky |
| `{"svetlo":{"jas":101}}` | jas mimo 0 až 100 |
| `{"cas":"zitra"}` | není to čas |
| `{"cas":"2026-10-20T14:03:00+02:00"}` | čas s časovým pásmem |

## Notifikace a čerstvost dat

Aplikace odebírá notifikace Stavu. Dům pošle Stav:

| Kdy | Poznámka |
|---|---|
| **každou sekundu**, po kroku domu | vždy, i když se nic nezměnilo: podle toho aplikace pozná, že dům žije |
| **hned při změně pohybu** | `Domek.kontroluj_pohyb` vrací `True`, když se pohyb změnil; program ji volá zhruba každých 100 ms a na `True` hned pošle Stav, takže `pohyb` i noční světlo se v aplikaci projeví skoro okamžitě |
| **hned po příkazu** | např. po přepnutí světla z tabletu; změnil-li příkaz `svetlo` s objektem barvu nebo jas, přijde před Stavem i notifikace Nastavení |
| **hned po zápisu Nastavení** | spolu s notifikací Nastavení; Stav už změnu obsahuje, protože server po zápisu provede jeden krok domu (`krok`) dřív, než Stav pošle |

Změny, které dům způsobí sám (změna úseku dne, automatické rozsvícení a zhasnutí, zapnutí a vypnutí topení, změna osvětlení), se objeví nejpozději v další sekundové notifikaci. Každá změna se tedy v aplikaci projeví do 1 s.

- Každá notifikace nese **celý** JSON, nikdy jeho část.
- Notifikace **Nastavení** chodí po každé změně nastavení, i když ho změnilo jiné zařízení. Jinak nechodí, ani po zapnutí odběru, proto si aplikace aktuální nastavení po připojení přečte.
- Notifikace ESS chodí ve stejných okamžicích jako notifikace Stavu.

### Pravidlo o čerstvosti dat

**Když 3 s nepřijde žádná notifikace Stavu, jsou zobrazená data stará.**

Pravidlo je závazné pro aplikaci a vychází z toho, že Stav chodí každou sekundu.

- Aplikace si pamatuje, kdy naposledy dostala Stav (notifikaci nebo přečtení), a měří to monotonními hodinami (`SystemClock.elapsedRealtime()`), ne hodinami tabletu ani `cas` ze Stavu.
- Stará data označí jako stará (třeba zešedlými hodnotami a časem posledního příjmu) a nevydává je za aktuální.
- Spojení při tom může vypadat živé: Android ohlásí přerušení spojení se zpožděním. Pravidlo 3 s je proto nezávislé na události odpojení.
- Doporučení: když stará data trvají déle (např. 10 s), zkus Stav přečíst, a když ani to nevyjde, spojení zruš a připoj se znovu (viz „Ztráta spojení a obnova“).

## Hodnoty v poli `chyby`

Pole `chyby` ve Stavu obsahuje názvy poruch, které právě trvají. Je seřazené abecedně a bez opakování, `[]` znamená, že vše funguje. Poruchy mizí **samy**, jakmile věc zase funguje (výjimku má `displej`, viz tabulka); dům se kvůli nim nezastaví. Aplikace je jen ukáže.

| Hodnota | Co selhalo | Důsledek | Kdy zmizí |
|---|---|---|---|
| `meteo` | Čidlo teploty, vlhkosti a tlaku (BME280) nejde přečíst nebo vrátilo neplatné číslo. | `t_venku`, `vlhkost` a `tlak` jsou `null`. Termostat stojí: `topi` i `chladi` jsou `false`. `t_uvnitr` drží poslední hodnotu. Server v ESS pošle „neznámé“. | Sama, jakmile se čidlo zase přečte (čte se při každém kroku domu). |
| `osvetleni` | Čidlo osvětlení (TSL2561) nejde přečíst nebo vrátilo neplatné číslo. | `lux` je `null`. Automatika světla ve dne nepředpokládá šero, takže se světlo nerozsvítí. | Sama, jakmile se čidlo zase přečte. |
| `displej` | Displej na Raspberry nejde zapsat. | Displej nic neukazuje, ostatní funguje. | Sama, jakmile se na displej zase dá zapsat. Zařízení displeje ale program za celý běh vytvoří nejvýš desetkrát, včetně prvního vytvoření a nepovedených pokusů. Když displej selže i potom, chyba zůstane až do restartu programu na Raspberry. |
| `svetlo` | Výstup světla (RGB LED) nejde zapsat. | `svetlo` ve Stavu ukazuje, jak má světlo svítit, ne jak svítí. | Sama, jakmile se na RGB LED zase dá zapsat (zapisuje se při každém kroku domu). |
| `nastaveni` | Nastavení nejde uložit do souboru. | Změna platí dál, ale po restartu domu se vrátí nastavení z posledního úspěšného uložení. | Po dalším úspěšném uložení, tedy po dalším úspěšném zápisu Nastavení. |

Čidlo osvětlení TSL2561 se čte při každém kroku domu. Nezapojené nebo vadné čidlo se hlásí jako `osvetleni` a `lux` je pak `null`.

## Když zápis selže

Server na zápis odpoví až po jeho zpracování.

- **Úspěch:** `onCharacteristicWrite` dostane `status` `GATT_SUCCESS` (0). Změna je provedená a hned dorazí notifikace (po Nastavení notifikace Nastavení i Stav, po Příkazu Stav a k tomu Nastavení, když příkaz `svetlo` změnil uloženou barvu nebo jas).
- **Odmítnutí:** zápis skončí chybou protokolu ATT, tedy `status` jiný než `GATT_SUCCESS`; na přesné hodnotě nezáleží. **Nic se nezmění**, ani částečně, a server běží dál, takže další zápisy fungují.
- Důvod odmítnutí (text chyby) se přes BLE **nepřenáší**: ATT chyba nese jen kód. Text se objeví jen v logu na Raspberry. Aplikace proto hodnoty kontroluje předem podle tabulek v tomto dokumentu a při chybě zápisu ukáže obecnou chybu.
- Po chybě zápisu Nastavení aplikace znovu přečte Nastavení, aby zobrazovala to, co skutečně platí.
- Odmítne se zápis, který není platný UTF-8, není platný JSON, je delší než 512 B, nemá jako kořen objekt, obsahuje neznámý klíč nebo má hodnotu špatného typu či mimo rozsah (kontroly jsou v `dekoduj_json` a `slouc`). U Příkazu navíc ten, který nemá právě jeden klíč nebo ho nejde provést (`Domek.proved_prikaz`).

## Poznámky pro Android

Konstanty (UUID jsou přesně ty z tabulky):

```kotlin
import java.util.UUID

object DomekBle {
    const val NAZEV = "Termostat"

    private fun uuid16(kratke: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(kratke))

    val ESS_SLUZBA = uuid16(0x181A)
    val TEPLOTA = uuid16(0x2A6E)
    val VLHKOST = uuid16(0x2A6F)
    val TLAK = uuid16(0x2A6D)
    val CCCD = uuid16(0x2902)

    val DOMEK_SLUZBA: UUID = UUID.fromString("5bf00001-f758-4c41-a34e-bab58ce87673")
    val STAV: UUID = UUID.fromString("5bf00010-f758-4c41-a34e-bab58ce87673")
    val NASTAVENI: UUID = UUID.fromString("5bf00011-f758-4c41-a34e-bab58ce87673")
    val PRIKAZ: UUID = UUID.fromString("5bf00012-f758-4c41-a34e-bab58ce87673")
}
```

Dekódování ESS (little-endian, `null` je „neznámé“):

```kotlin
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun little(bajty: ByteArray) = ByteBuffer.wrap(bajty).order(ByteOrder.LITTLE_ENDIAN)

fun teplotaZEss(b: ByteArray): Double? {
    val v = little(b).short
    return if (v == Short.MIN_VALUE) null else v / 100.0
}

fun vlhkostZEss(b: ByteArray): Double? {
    val v = little(b).short.toInt() and 0xFFFF
    return if (v == 0xFFFF) null else v / 100.0
}

fun tlakHpaZEss(b: ByteArray): Double? {
    val v = little(b).int.toLong() and 0xFFFFFFFFL
    return if (v == 0L) null else v / 1000.0
}
```

Další pasti:

- **JSON zapisuj knihovnou, ne složením textu.** Desetinná čísla musí mít tečku: `String.format` s českým nastavením napíše čárku a vznikne neplatný JSON. Celá čísla (`nocni_svetlo_s`, `nikdo_doma_po_min`, `jas`, složky barvy) posílej jako `Int`, ne `Double`.
- **Čtení Stavu:** `cas`, `usek`, `t_venku`, `vlhkost`, `tlak`, `lux`, `t_uvnitr` a `cil` jsou nullable. U `org.json` použij `isNull(klic)`, protože `getDouble` na `null` vyhodí výjimku a `optDouble` vrátí `NaN`. U `kotlinx.serialization` zapni `ignoreUnknownKeys = true`.
- **Čas pro příkaz** formátuj výslovně: `LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))`. Nepoužívej `toString()`: u celé minuty vynechá sekundy a se zlomky sekundy je napíše s desetinnou částí.
- **Zápis:** hodnota je `json.toByteArray(Charsets.UTF_8)`, typ zápisu `WRITE_TYPE_DEFAULT`. Další zápis pošli až po `onCharacteristicWrite` předchozího.
- **Posuvníky** (jas, barva, cílová teplota) neposílej při každé změně: pošli nejvýš jeden zápis za 100 až 200 ms, a po puštění posuvníku vždy poslední hodnotu.
- **Oprávnění:** od Androidu 12 `BLUETOOTH_SCAN` a `BLUETOOTH_CONNECT` (za běhu), na starších verzích oprávnění k poloze kvůli hledání.

## Zkouška v nRF Connect

Rozhraní jde vyzkoušet i bez aplikace v nRF Connect: připojit se k `Termostat`, vyžádat MTU 517, zapnout notifikace Stavu (každou sekundu přijde JSON) a zapisovat jako text (UTF-8) do Nastavení například `{"cil_den":26}` a do Příkazu například `{"svetlo":"zapnout"}`. Odmítnutý zápis, např. `{"cil_den":99}`, se v nRF Connect ukáže jako chyba zápisu.

## Kontrolní seznam pro aplikaci

Shrnuje předchozí oddíly. Vyjednání MTU 517 a opětovné nalezení domu po odpojení jsou doporučení, ne zaručené chování serveru (viz „Co je ověřené a co je doporučení“).

1. Hledat podle názvu `Termostat` (inzerovaná služba 0x181A), nepárovat.
2. Po připojení `requestMtu(517)`, až potom `discoverServices()`.
3. Zapnout notifikace Stavu a Nastavení (zápis do CCCD) a přečíst Nastavení. GATT operace řadit do fronty.
4. Stav zpracovat tolerantně: neznámé klíče ignorovat, `null` umět zobrazit, texty jsou bez diakritiky.
5. Když 3 s nepřijde notifikace Stavu, data označit jako stará.
6. Zapisovat s odpovědí, UTF-8 JSON do 512 B. Celá čísla bez desetinné tečky, čas přesně `YYYY-MM-DDTHH:MM:SS`.
7. Hodnoty kontrolovat předem podle rozsahů; po chybě zápisu přečíst Nastavení.
8. Ztracené spojení obnovit samo a po každém připojení projít celý postup znovu.
9. `chyby` ukázat uživateli; poruchy mizí samy.
