# Chytrý dům – mobilní aplikace pro Android

Aplikace pro telefon a tablet s Androidem. Přes Bluetooth LE se připojí k chytrému domu na Raspberry Pi
(periferie „Termostat“), zobrazuje jeho stav, graf teplot a deník událostí a ovládá termostat a světlo.
Bez Raspberry Pi běží v režimu simulace.

## Požadavky

- Android 8.0 (API 26) nebo novější. Bez Bluetooth LE aplikace běží jen v simulaci.
- Sestavení: Android Studio (s JDK, které obsahuje) a Android SDK 37.

## Sestavení a testy

| Příkaz | Co udělá |
|---|---|
| `./gradlew assembleDebug` | sestaví APK do `app/build/outputs/apk/debug/` |
| `./gradlew installDebug` | nainstaluje aplikaci do připojeného telefonu (ladění přes USB) |
| `./gradlew testDebugUnitTest` | spustí automatické testy; snímky obrazovek uloží do `app/build/screenshots/` |

## Struktura

| Cesta | Obsah |
|---|---|
| `app/src/main/java/cz/vsb/mitr/domek/` | kód aplikace: `ble` (klient Bluetooth LE), `data` (model a zdroj dat), `domain` (logika bez Androidu), `sim` (simulovaný dům), `viewmodel` a `ui` (obrazovky v Compose) |
| `app/src/test/` | automatické testy (JUnit, Robolectric) |
| `docs/ble-rozhrani.md` | popis rozhraní Bluetooth LE od backendu, podle kterého aplikace komunikuje |
| `docs/testovani-na-telefonu.md` | kontrolní seznam pro ruční zkoušku na telefonu |
| `docs/snimky/` | snímky obrazovek |
| `docs/navrh/`, `docs/plan/` | návrh aplikace a plán implementace |
| `docs/report-pro-backend.pdf` | co aplikace čeká od programu na Raspberry Pi |
