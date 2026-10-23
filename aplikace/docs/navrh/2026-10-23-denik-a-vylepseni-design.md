# Deník událostí a další vylepšení – návrh

Datum: 23. 10. 2026. Navazuje na `2026-10-20-chytry-domek-design.md` a na smlouvu Raspberry `docs/ble-rozhrani.md`, která má
přednost. Rozhraní Raspberry se nemění.

## 1. Proč

Při předvádění scénářů má být hned vidět, co domek udělal a kdy (třeba „14:03:12 Začalo chladit“). K tomu pár vylepšení,
která vyšla z dokumentu Raspberry a z kontroly: světlo podle uložené barvy, prahy termostatu, dvě drobné opravy a snímky
obrazovek do zprávy k projektu.

## 2. Deník (schváleno: samostatná záložka, náhled varianty B)

### 2.1 Umístění a vzhled

- Pátá záložka **„Deník“** mezi „Světlo“ a „Nastavení“, ikona seznamu (`Icons.Rounded.ListAlt`). Dolní lišta i boční lišta
  mají pět položek.
- Horní lišta jako u ostatních záložek: nadpis „Deník“ a štítek stavu spojení.
- Deník je přístupný vždy, i před prvními daty (jako Nastavení), protože zapisuje i pokusy o spojení.
- Pod lištou řádek filtrů (`FilterChip`, vybraný je vždy právě jeden): **Vše, Termostat, Světlo, Pohyb, Spojení a chyby**.
  Když se na šířku nevejdou, posouvají se vodorovně.
- Seznam, nejnovější nahoře. Řádek: čas telefonu `HH:MM:SS` (tabulkové číslice), ikona v barevném kruhu, nadpis
  a podrobnost (menší, šedá). U událostí světla je před podrobností tečka v barvě světla.
- Prázdný deník: „Zatím se nic nestalo.“ a pod tím „Deník zapisuje změny od chvíle, kdy aplikace dostane první data.“
  Prázdný filtr: „V této skupině zatím nic není.“
- Na tabletu a na šířku je seznam uprostřed, nejvýš 720 dp široký.

### 2.2 Uchování

- Jen v paměti, dokud běží aplikace. Nejvýš 200 událostí, starší vypadávají.
- Po přepnutí mezi Raspberry a simulací se deník vymaže, stejně jako graf.
- Čas je čas telefonu v okamžiku události: u událostí ze Stavu čas přijetí (`receivedAtWall`), u ostatních hodiny telefonu.

### 2.3 Události

Kategorie určuje filtr. Události s kategorií „ostatní“ jsou vidět jen ve filtru Vše.

| Kategorie | Kdy | Nadpis | Podrobnost |
|---|---|---|---|
| Termostat | `topi` false → true | Začalo topit | Uvnitř 22,6 °C, cíl 23,0 °C |
| Termostat | `topi` true → false | Přestalo topit | Uvnitř 23,0 °C, cíl 23,0 °C |
| Termostat | `chladi` false → true | Začalo chladit | Uvnitř 24,1 °C, cíl 23,0 °C |
| Termostat | `chladi` true → false | Přestalo chladit | Uvnitř 23,0 °C, cíl 23,0 °C |
| Termostat | `usek` se změní | Začal den / Začal večer / Začala noc (nový úsek: „Úsek dne: …“ tak, jak přišel) | v Auto „platí denní cíl 23,0 °C“ nebo „platí noční cíl 20,0 °C“, jinak prázdná |
| Světlo | `svetlo.zap` false → true | Světlo se rozsvítilo | auto · venku je šero / večerní světlo / noční světlo · jas, nebo ručně · jas |
| Světlo | `svetlo.zap` true → false | Světlo zhaslo | auto nebo ručně |
| Světlo | `rucne` se změní a `zap` ne | Ruční volba světla / Světlo zpět v automatice | prázdná |
| Pohyb | `pohyb` false → true | Pohyb | „2× od 14:06:12“, když se sloučí |
| Pohyb | `nikdo_doma` false → true | Nikdo doma | cíl snížen o 3,0 °C (podle `utlum_nikdo_doma`) |
| Pohyb | `nikdo_doma` true → false | Někdo je zase doma | prázdná |
| podle klíče | změna hodnoty v Nastavení | např. „Denní cíl: 26,0 → 23,0 °C“ | změna z aplikace / změna na Raspberry |
| Spojení a chyby | spojení navázáno poprvé | Připojeno k domu Termostat | adresa · MTU 517 |
| Spojení a chyby | spojení po výpadku | Znovu připojeno | výpadek 9 s |
| Spojení a chyby | spojení skončilo samo | Spojení přerušeno | znovu se připojuji… |
| Spojení a chyby | uživatel odpojil | Odpojeno ručně | prázdná |
| Spojení a chyby | simulace běží | Simulace spuštěna | prázdná |
| Spojení a chyby | data zestárla (víc než 3 s) | Data jsou stará | Raspberry přestalo posílat stav (v simulaci „simulace přestala posílat stav“) |
| Spojení a chyby | data zase chodí | Data zase chodí | výpadek 7 s |
| Spojení a chyby | v `chyby` přibude kód | Porucha: text z karty chyby s malým počátečním písmenem, jen „Raspberry“ zůstává s velkým (neznámý kód: „Neznámá porucha: kód“) | podrobnost z karty chyby |
| Spojení a chyby | kód z `chyby` zmizí | Čidlo BME280 / Čidlo osvětlení / Displej / Světlo (RGB LED) / Ukládání nastavení zase funguje (neznámý kód: „Chyba … skončila“) | porucha trvala 43 s |
| Spojení a chyby | hláška o zápisu nebo příkazu (odmítnuto, bez spojení, bez odpovědi) | text hlášky | prázdná |
| ostatní | tlačítko v kartě „Ovládání simulace“ | Simulace: zahřáté čidlo / zakryté čidlo světla / pohyb / porucha BME280 zapnutá nebo vypnutá / výpadek dat | venku o 8 °C víc na 30 s; osvětlení 5 lx na 30 s; prázdná; prázdná; 10 s bez Stavu |
| ostatní | srovnání hodin | Srovnání hodin | podle telefonu 14:03:00 |

Pravidla:
- Události ze Stavu se počítají z rozdílu proti předchozímu Stavu. První Stav po spuštění nebo po přepnutí zdroje žádné
  nedává (není s čím srovnat). Po výpadku spojení se porovnává s posledním Stavem před výpadkem.
- Změnu platného cíle (`cil`) deník zvlášť nepíše: vždy ji vysvětlí jiná událost (úsek dne, nikdo doma, Nastavení).
- Režim termostatu zapisuje jen změna Nastavení (`rezim_termostatu`), ne Stav, aby nebyl dvakrát.
- **Sloučení pohybu:** když je poslední záznam „Pohyb“ zároveň nejnovějším záznamem deníku a není starší než minuta, nový
  pohyb nepřidá řádek, jen zvýší počet a posune čas na poslední pohyb. Podrobnost „N× od HH:MM:SS“ (čas prvního pohybu).
  Jakmile se mezitím zapíše něco jiného, další pohyb dá nový řádek, aby příčina zůstala pod svým účinkem.
- **Sloučení Nastavení:** změna téhož klíče aktualizuje jeho záznam jen tehdy, když je to nejnovější záznam deníku a není
  starší než 10 s (původní stará hodnota zůstane, nová a čas se přepíšou). Tah posuvníku tak dá jeden řádek. Sloučený záznam,
  jehož nová hodnota je zase ta původní, zmizí, pokud je pořád nejnovější.
- **Pořadí příčiny a účinku:** řádek akce (tlačítko simulace, srovnání hodin) se zapíše dřív, než akce vyvolá změny, takže je
  pod nimi. Srovnání hodin se zapíše jako akce „Srovnání hodin · podle telefonu HH:MM:SS“, výsledek ukážou další události.
- Po probuzení telefonu (aplikace dlouho neběžela) se „Data jsou stará“ nepíše hned, ale až po nové 3s lhůtě. Výpadek spojení
  zapíše „Data jsou stará“ jen tehdy, když nastal za spojení; po ručním odpojení ani po novém připojení se „Data zase chodí“
  nepíše.
- Nastavení se porovnává s předchozím přečteným Nastavením; první Nastavení po spuštění nebo přepnutí zdroje nic nepíše.
  „Změna z aplikace“, když aplikace ten klíč za posledních 10 s sama zapsala, jinak „změna na Raspberry“.
- Kategorie Nastavení podle klíče: režim, cíle a útlum → Termostat; začátky úseků → ostatní; práh šera, noční světlo,
  rozsvěcení při pohybu, barva a jas světla → Světlo; „nikdo doma po“ → Pohyb.
- Nadpis změny Nastavení: popisek klíče s velkým počátečním písmenem, dvojtečka, stará → nová hodnota ve stejném tvaru jako
  hlášky (`describeValue`). Barva světla jako „Barva světla změněna“ s tečkami staré a nové barvy, jas jako
  „Jas světla: 100 → 60 %“. Nadpis za 70 znaků se zalomí, nic se neořezává.

### 2.4 Mimo rozsah

Ukládání deníku mezi spuštěními, export, notifikace, události v grafu.

## 3. Světlo podle uložené barvy a jasu

Smlouva Raspberry: světlo svítí vždy barvou a jasem z Nastavení (`barva_svetla`, `jas_svetla`), ručně i v automatice.
Aplikace je dosud jen četla.

- Záložka Světlo ukazuje uloženou barvu (vybraná předvolba, nebo „vlastní“) a uložený jas na posuvníku i ve chvíli, kdy světlo
  nesvítí. Dosud byl posuvník na 0 % a nebyla vybraná žádná barva.
- **Výběr barvy a jasu (rozhodl uživatel 23. 10. 2026):** výběr se vždy jen zapíše do Nastavení (`barva_svetla`,
  `jas_svetla`). Svítící světlo se hned přebarví a zůstane v automatice nebo v ruční volbě, zhasnuté zůstane zhasnuté.
  Zapnutí a vypnutí zůstává na tlačítkách. Raspberry bez těchto klíčů (starší program) dál dostává příkaz světla jako dosud.
- Vysvětlivka na kartě: „Barva a jas se uloží. Svítící světlo se hned přebarví, zhasnuté se jimi rozsvítí po zapnutí nebo
  v automatice.“ U staršího programu zůstává „Výběr barvy nebo jasu světlo rovnou ručně zapne.“
- Náhled světla ukazuje, co světlo opravdu dělá: zhasnuté zůstane zhasnuté, noční světlo v automatice nejvýš na 10 %.
- Výběr platí v aplikaci, dokud ho Raspberry nepotvrdí (nejdéle 3 s): Nastavení musí mít novou barvu a jas a svítící světlo jimi
  musí svítit i ve Stavu, aby náhled mezi oběma notifikacemi neukázal starou barvu.
- Posuvník posílá nejvýš jeden zápis za 150 ms a po puštění poslední hodnotu (stejně jako dnes příkazy).
- **„Vrátit automatiku“ (rozhodl uživatel 23. 10. 2026):** jde stisknout jen při ruční volbě (`rucne` ve Stavu, u staršího
  programu i u výběru barvy, který světlo ručně zapne). Když světlo řídí automatika, je zašedlé stejně jako „Vrátit původní
  časy“ v Nastavení. U starých dat zůstává dostupné, protože aplikace neví, jestli ruční volba pořád platí.
- **Přepnout (rozhodl uživatel 23. 10. 2026):** tlačítko z obrazovky zmizí, protože dělá totéž co Zapnout a Vypnout a stav
  světla je vidět. Zůstanou Zapnout a Vypnout vedle sebe a pod nimi Vrátit automatiku. Příkaz `prepnout` zůstává v modelu
  i v simulaci, protože ho rozhraní Raspberry dál nabízí.

## 4. Prahy termostatu

Na záložce Termostat v kartě se stavem (nahoře vlevo) přibude řádek s prahy podle smlouvy: topení naskočí pod cílem
o víc než 0,3 °C, chlazení nad cílem o víc než 1,0 °C. Text „Topení naskočí pod 22,7 °C, chlazení nad 24,0 °C“ počítaný
z platného cíle ve Stavu (`cil`). Když cíl není (režim Vypnuto nebo bez dat) nebo hlásí porucha `meteo` (termostat pak
stojí), řádek chybí. Při starých datech zešedne jako ostatní hodnoty karty. Řádek „Snížit o“ u „nikdo doma“ říká, že snížení
platí jen v Auto (smlouva Raspberry).

## 5. Drobné opravy

- Dialog vlastní barvy: nedotčený dialog si pamatuje barvu z chvíle otevření (`remember { initial }`), aby ho nepřepsala
  automatická změna světla během otevření.
- Graf: každá čára (uvnitř, venku, cíl) se řídne zvlášť, aby po hodinách běhu venkovní a cílová čára neztrácely krátké výkyvy.

## 6. Snímky obrazovek do zprávy

Po dokončení všeho se ze snímkových testů vyberou PNG všech obrazovek (telefon na výšku, tablet na šířku, deník, světlo,
termostat, nastavení, simulace, chyby) do `docs/snimky/` s českými názvy souborů a krátkým `docs/snimky/README.md`, co
který snímek ukazuje.

## 7. Rozhodnutí, která jsem udělal sám

- Kategorie a texty událostí, pravidla sloučení (pohyb 1 minuta, Nastavení 10 s), limit 200 událostí.
- Deník přístupný i před prvními daty; seznam na tabletu uprostřed do 720 dp.
- Změna platného cíle a režimu se nepíše ze Stavu, ale z jiných událostí a z Nastavení.
- Prahy termostatu v kartě se stavem.
- Snímky do `docs/snimky/`.
