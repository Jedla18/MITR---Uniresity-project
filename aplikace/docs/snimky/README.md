# Snímky obrazovek

Snímky aplikace Chytrý dům pro zprávu o projektu. Nejsou vyfocené na telefonu, kreslí je snímkové testy (Robolectric):
`./gradlew testDebugUnitTest` je uloží do `app/build/screenshots/` a sem jsou zkopírované s českými názvy. Data jsou ukázková:
Raspberry hlásí 20. 10. 2026 10:00, je den a termostat je v režimu Auto.

- Telefon na výšku: 384 × 832 dp, tedy 1152 × 2496 px. Snímky s celou obrazovkou až po konec obsahu mají 1152 × 3600 px.
- Tablet na šířku: 1280 × 800 dp, tedy 1920 × 1200 px.

## Telefon

| Soubor | Z testu | Co ukazuje |
|---|---|---|
| `01-prehled.png` | `prehled-demo-telefon.png` | Přehled: denní doba a čas Raspberry, režim, teplota uvnitř se stavem „Topí“, denní cíl s tlačítky − a +, graf teplot (uvnitř, venku, cíl, úseky topení a chlazení) a dlaždice čidel. Obrazovka pokračuje pod spodní lištou. |
| `02-termostat.png` | `termostat-demo-telefon.png` | Termostat: teplota uvnitř a platný cíl, kdy naskočí topení a chlazení, režim Auto, Ruční nebo Vypnuto s vysvětlením, tři cílové teploty (platná je zvýrazněná) a začátek karty Nikdo doma. |
| `03-svetlo.png` | `svetlo-demo-telefon.png` | Světlo svítí ručně modře na 60 %: kruh se září v barvě světla, tlačítka Zapnout, Vypnout a Vrátit automatiku, 11 předvoleb barvy, vlastní barva (duhové kolečko) a jas. |
| `04-svetlo-zhasnute-ulozena-barva.png` | `svetlo-zhasnuto-ulozena-barva.png` | Zhasnuté světlo v automatice s uloženou oranžovou na 40 %. Karta Barva ukazuje uloženou barvu a jas i u zhasnutého světla a vysvětluje, že se jimi světlo rozsvítí po zapnutí nebo v automatice. „Vrátit automatiku“ je zašedlé, protože světlo už řídí automatika. Celá obrazovka včetně karty Automatika. |
| `05-denik.png` | `denik-telefon.png` | Deník: filtry podle skupin a události od nejnovější. Topení a chlazení s teplotami, změny z aplikace, odmítnutá změna, porucha čidla osvětlení a její konec, rozsvícení světla a sloučené pohyby („2× od 10:00:35“). |
| `06-nastaveni.png` | `nastaveni-demo-telefon.png` | Nastavení: pruh denní doby s aktuálním časem, začátky dne, večera a noci s tlačítky „Teď“, čas Raspberry proti času telefonu (hodiny jdou o 2 min 12 s pozadu) a tlačítko „Nastavit čas podle telefonu“. |
| `07-simulace.png` | `prehled-simulace.png` | Přehled v simulaci (štítek „Simulace“), celá obrazovka: pod dlaždicemi karta Ovládání simulace s tlačítky Zahřát čidlo, Zakrýt čidlo světla, Pohyb, Porucha BME280 a Výpadek dat. |
| `08-poruchy-cidel.png` | `prehled-chyby.png` | Raspberry hlásí poruchu obou čidel: červené karty BME280 a TSL2561 s vysvětlením, termostat je v klidu a dlaždice místo hodnot ukazují „—“. |
| `09-stara-data.png` | `prehled-stara-data.png` | Raspberry 7 s neposlalo stav: žlutá karta „Data jsou 7 s stará“, štítek „Stará data“ a zešedlé hodnoty. |
| `10-hledani-domku.png` | `spojeni-scanning.png` | Hledání domku „Termostat“ přes Bluetooth s tlačítkem Spustit simulaci. |
| `11-vyber-domku.png` | `spojeni-choosing.png` | V dosahu jsou dva domky „Termostat“, vybírá se podle adresy a síly signálu. |
| `12-tmavy-motiv.png` | `prehled-dark.png` | Přehled v tmavém motivu. |
| `13-velke-pismo.png` | `prehled-velke-pismo.png` | Přehled při písmu zvětšeném na 150 %: dlaždice jsou ve dvou sloupcích a popisky záložek se zmenší, aby se vešly. |

## Tablet na šířku

| Soubor | Z testu | Co ukazuje |
|---|---|---|
| `14-tablet-prehled.png` | `tablet-sirka-prehled.png` | Záložky jsou v boční liště. Přehled je ve dvou sloupcích: vlevo teplota a dlaždice, vpravo graf. |
| `15-tablet-termostat.png` | `tablet-sirka-termostat.png` | Termostat: vlevo stav a režim, vpravo cílové teploty a Nikdo doma. |
| `16-tablet-svetlo.png` | `tablet-sirka-svetlo.png` | Světlo: vlevo náhled a tlačítka, vpravo barva, jas a automatika. |
| `17-tablet-denik.png` | `denik-tablet.png` | Deník: seznam uprostřed, nejvýš 720 dp široký. |
| `18-tablet-nastaveni.png` | `tablet-sirka-nastaveni.png` | Nastavení: vlevo denní doba, vpravo čas Raspberry a zdroj dat s adresou, signálem a MTU. |

## Co tu chybí

Dialog Vlastní barva. Testy ho kreslí k hornímu okraji a bez ztmavení pozadí, takže by vypadal jinak než na telefonu. Vyfoť ho
při zkoušce na telefonu: Světlo → duhové kolečko.

## Jak snímky obnovit

Po změně vzhledu spusť `./gradlew testDebugUnitTest` a soubory ze sloupce „Z testu“ zkopíruj sem pod názvy ze sloupce „Soubor“.
