# Chytrý domek – plán implementace

Kroky mají zaškrtávací syntaxi (`- [ ]`) a plní se úkol po úkolu.

**Cíl:** Android aplikace „Chytrý domek“ (Kotlin, Compose), která přes Bluetooth LE nebo v simulaci zobrazuje a ovládá domek na Raspberry Pi podle návrhu.

**Architektura:** Jedna aktivita s Compose UI. `HouseViewModel` vlastní zdroj dat `HouseDataSource` a skládá z něj jeden `HouseUiState`. Zdroj má dvě implementace: `BleHouseDataSource` (GATT klient s frontou operací, hledáním a znovupřipojením) a `SimulatedHouseDataSource` (model domku v čistém Kotlinu). Logika je v čistém Kotlinu a pokrývají ji JVM testy. Obrazovky jsou bezstavové composables, které se ověřují screenshoty z Robolectricu.

**Tech stack:** AGP 9.4.1 s vestavěným Kotlinem 2.2.10, Jetpack Compose (BOM 2026.02.01, Material 3, material-icons-extended), lifecycle 2.9.4, kotlinx.serialization 1.9.0, kotlinx.coroutines 1.10.2, JUnit 4, kotlinx-coroutines-test, Robolectric 4.16.1 s compose ui-test.

**Spec:** `docs/navrh/2026-10-20-chytry-domek-design.md` a schválené náhledy `docs/navrh/2026-10-20-chytry-domek-obrazovky.html`. Odkazy „spec §N“ míří do návrhu.

## Globální omezení

- Package i applicationId je `cz.vsb.mitr.domek`, název aplikace „Chytrý domek“. `minSdk = 26`, `compileSdk` a `targetSdk` 37. AGP 9.4.1, pluginy `org.jetbrains.kotlin.plugin.compose` a `org.jetbrains.kotlin.plugin.serialization` 2.2.10, Compose BOM 2026.02.01.
- Gradle z příkazové řádky (Git Bash, kořen projektu): nejdřív `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`, pak `./gradlew …`. Testy: `./gradlew testDebugUnitTest` (jedna třída přes `--tests "<plně kvalifikovaný název>"`). Build: `./gradlew assembleDebug`.
- Rozhraní Raspberry je pevné (spec §4). UUID a klíče JSON odpovídají spec přesně. Jediný doplněk je `svetlo_pri_pohybu`, který při čtení nemusí přijít.
- UI je celé česky. Desetinná čárka, mezi číslem a jednotkou nezlomitelná mezera `\u00A0` (`22,4 °C`, `45 %`). Teploty mají 1 desetinné místo, vlhkost, tlak a lux žádné. Testy píšou očekávané texty s `\u00A0`.
- Texty UI jsou přímo v Kotlinu (aplikace je jen česká). V `strings.xml` je jen `app_name`.
- Identifikátory jsou anglicky. Komentáře česky a střídmě, jen tam, kde vysvětlují „proč“ (jako v původním kódu projektu).
- Čistá logika (balíčky `data.model`, `data.source`, `domain`, `sim` kromě `SimulatedHouseDataSource`, pomocníci v `ble` z úkolu 7, `viewmodel` kromě `HouseViewModel`) nesmí volat Android API ani `android.util.Log`, aby běžela v JVM testech.
- Monotónní čas jde přes `TimeSource` (v produkci `SystemClock.elapsedRealtime()`), čas telefonu přes `() -> LocalDateTime` (v produkci `LocalDateTime::now`).
- Konstanty (spec): stará data po 3000 ms, hlídání dat 15000 ms, tik UI 500 ms, zpoždění zápisu krokových tlačítek 600 ms, potvrzení draftu nejdéle 3000 ms, okno hledání 2000 ms, RSSI každých 5000 ms. Časový limit běžné GATT operace je 5000 ms, kroků přípravy spojení (spec §5.3, kroky 1–6) 10000 ms a samotného připojení 15000 ms. Odstupy znovupřipojení 1, 2, 4, 8 a pak 10 s. Mezi starty hledání aspoň 6000 ms. Historie grafu drží 6 h. Status odmítnutí v simulaci je 128.
- Git jen se souhlasem uživatele (viz předání plánu). Se souhlasem se commituje po každém úkolu, zpráva je česky. Bez souhlasu se kroky „Commit“ přeskakují.
- Ve ViewModel testech se nesmí volat `advanceUntilIdle()`, protože tik po 500 ms nikdy nedoběhne. Používej `advanceTimeBy` a `runCurrent` a na konci testu `viewModel.viewModelScope.cancel()`.

## Review Focus

1. **Raspberry pošle číslo v jiném, ale platném tvaru** (`"jas":50.0`, `"barva":[255.0,128,0]`), přidá neznámý klíč, nebo v Nastavení chybí `svetlo_pri_pohybu`. Stav i Nastavení se musí přijmout. Test: úkol 2.
2. **Notifikace s useknutým nebo rozbitým JSON** (třeba když se nepodaří zvýšit MTU). Zahodí se bez pádu, data zestárnou a aplikace jede dál. Test: úkol 2.
3. **Hodiny Raspberry jsou po startu o dny nebo roky mimo.** Varování napíše „o více než den“, „Teď“ použije čas Raspberry a „Srovnat“ pošle čas telefonu. Testy: úkoly 3 a 11.
4. **Rychlé opakované klepání na −/+ bez spojení.** Odejde jediný zápis a ukáže se jediná hláška „Domek není připojený…“, hodnota se vrátí. Test: úkol 11.
5. **Všechny hodnoty z BME280 jsou null a v `chyby` je chyba.** Dlaždice ukážou „—“, v grafu jsou mezery, rozsah osy se spočítá i z prázdných dat a nic nespadne. Testy: úkoly 9 a 16.

---

## Mapa souborů

Kód leží v `app/src/main/java/cz/vsb/mitr/domek/`, testy v `app/src/test/java/cz/vsb/mitr/domek/`.

```
MainActivity.kt                 aktivita: Compose, oprávnění, zapnutí BT, obrazovka nezhasne
data/model/
  HouseStatus.kt                Stav: DayPart, ThermostatMode, LightState, HouseStatus
  HouseSettings.kt              Nastavení
  FlexibleIntSerializer.kt      celé číslo přijímající i 50.0
  Rgb.kt                        barva
  HouseJson.kt                  Json + decodeStatus/decodeSettings (null místo výjimky)
  SettingKey.kt                 klíče Nastavení s rozsahy, SettingChange, skládání zápisu
  HouseCommand.kt               příkazy, formát času Raspberry
data/source/
  HouseDataSource.kt            rozhraní zdroje, ConnectionState, WriteResult, ovládání BLE a simulace
  TimeSource.kt                 monotónní čas
domain/
  DayStarts.kt                  úseky dne (cyklické pravidlo), časová osa dne
  Thermostat.kt                 platný cíl
  LightTexts.kt                 texty stavu světla
  Format.kt                     české formátování čísel, dob a rozdílu hodin
  Steps.kt                      kroky −/+ pro jednotlivé klíče
  PiErrors.kt                   texty chyb z `chyby`
sim/
  RaspberryRules.kt             validace zápisů a příkazů stejně jako Raspberry
  HouseSimulation.kt            model domku
  SimulatedHouseDataSource.kt   zdroj dat ze simulace
ble/
  BleUuids.kt, BlePermissions.kt, BleLogic.kt, GattOperationQueue.kt   čisté pomocníky
  HouseGattClient.kt            jedno GATT spojení (Android)
  BleHouseDataSource.kt         hledání, výběr, připojení, znovupřipojení, hlídání
viewmodel/
  ChartHistory.kt               vzorky grafu, okno, ředění, mezery, pruhy, rozsah
  HouseUiState.kt               UI stav + odvozené hodnoty
  HouseActions.kt               akce UI
  Messages.kt                   hlášky k zápisům
  HouseViewModel.kt             ViewModel
ui/
  theme/ Color.kt, Theme.kt
  components/ StatusPill.kt, ValueStepper.kt, Cards.kt, TwoPane.kt
  DomekApp.kt                   kostra: horní lišta, navigace, snackbar
  connection/ ConnectionScreen.kt, DevicePicker.kt
  chart/ TemperatureChart.kt
  overview/ OverviewScreen.kt, HeroCard.kt, SensorTiles.kt, OverviewBanners.kt, SimulationCard.kt
  thermostat/ ThermostatScreen.kt
  light/ LightScreen.kt, LightPresets.kt, ColorDialog.kt
  settings/ SettingsScreen.kt, DayTimeline.kt, TimePickerDialog.kt
test: TestData.kt, testing/FakeHouseDataSource.kt, screenshots/*, + testy po balíčcích
```

---

### Úkol 1: Založení projektu

**Soubory:**
- Upravit: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/drawable/ic_launcher_background.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `.gitignore`
- Smazat:
  - `app/src/main/java/com/example/`, `app/src/test/java/com/example/` a `app/src/androidTest/java/com/example/`;
  - `app/src/main/res/layout/`, `app/src/main/res/values-night/` a `app/src/main/res/values/colors.xml`;
  - všechny `app/src/main/res/mipmap-*dpi/` (s `minSdk` 26 se používá jen adaptivní ikona v `mipmap-anydpi`, ta zůstává).
- Vytvořit: `app/src/main/java/cz/vsb/mitr/domek/MainActivity.kt` (dočasná), `app/src/test/resources/robolectric.properties`

**Rozhraní:**
- Produkuje: modul `app` v balíčku `cz.vsb.mitr.domek`, který jde přeložit, s aliasy knihoven podle `libs.versions.toml` níže a nastavením Robolectricu pro JVM testy.

- [ ] **Krok 1: Uložit původní stav (jen se schváleným gitem)**

```bash
git init
printf '\n.kotlin/\n' >> .gitignore
git add -A && git commit -m "Původní projekt (baterka) před přepsáním"
```

- [ ] **Krok 2: Přepsat `gradle/libs.versions.toml` přesně takto**

```toml
[versions]
agp = "9.4.1"
kotlin = "2.2.10"
composeBom = "2026.02.01"
activityCompose = "1.10.1"
lifecycle = "2.9.4"
coreKtx = "1.16.0"
kotlinxSerialization = "1.9.0"
kotlinxCoroutines = "1.10.2"
junit = "4.13.2"
androidxTestExtJunit = "1.2.1"
robolectric = "4.16.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Krok 3: Upravit Gradle skripty**

`settings.gradle.kts`: změnit jen `rootProject.name = "ChytryDomek"`. V kořenovém `build.gradle.kts` budou tři pluginy s `apply false`: `android.application`, `kotlin.compose` a `kotlin.serialization`. `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cz.vsb.mitr.domek"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "cz.vsb.mitr.domek"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { optimization { enable = false } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
```

- [ ] **Krok 4: Přepsat `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />

    <!-- Android 11 a starší: hledání BLE potřebuje polohu. -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <!-- Android 12+: polohu z hledání nepoužíváme. -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" tools:targetApi="31" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Domek">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Krok 5: Zdroje**
  - `strings.xml` obsahuje jen `<string name="app_name">Chytrý domek</string>`.
  - `themes.xml` obsahuje jen `<style name="Theme.Domek" parent="android:Theme.Material.Light.NoActionBar" />`.
  - `ic_launcher_background.xml` je plná barva `#0D6A70`.
  - `ic_launcher_foreground.xml` je vektor 108×108 dp s viewportem 108×108 a bílým domkem `M54,30 L80,52 L74,52 L74,78 L60,78 L60,62 L48,62 L48,78 L34,78 L34,52 L28,52 Z`.
  - Smazat soubory uvedené výše.

- [ ] **Krok 6: Dočasná `MainActivity` a Robolectric**

`MainActivity : ComponentActivity` v `onCreate` zavolá `enableEdgeToEdge()` a `setContent { Text("Chytrý domek") }` (v úkolu 13 se nahradí). Soubor `app/src/test/resources/robolectric.properties` bude mít jediný řádek `sdk=35`.

- [ ] **Krok 7: Ověřit build**

Spustit `./gradlew assembleDebug` a pak `./gradlew testDebugUnitTest`.
Očekávaný výsledek: obě skončí `BUILD SUCCESSFUL`. Testy zatím nejsou žádné.

- [ ] **Krok 8: Commit**: `git add -A && git commit -m "Založení projektu Chytrý domek (Compose, oprávnění BLE)"`

---

### Úkol 2: Model JSON (Stav, Nastavení, zápisy, příkazy)

**Soubory:**
- Vytvořit: `data/model/HouseStatus.kt`, `HouseSettings.kt`, `FlexibleIntSerializer.kt`, `Rgb.kt`, `HouseJson.kt`, `SettingKey.kt`, `HouseCommand.kt`
- Test: `TestData.kt`, `data/model/HouseJsonTest.kt`, `data/model/SettingChangeTest.kt`, `data/model/HouseCommandTest.kt`

**Rozhraní:**
- Produkuje:

```kotlin
@Serializable enum class DayPart { @SerialName("den") DAY, @SerialName("vecer") EVENING, @SerialName("noc") NIGHT }
@Serializable enum class ThermostatMode(val json: String, val label: String) {
    @SerialName("auto") AUTO("auto", "Auto"), @SerialName("rucni") MANUAL("rucni", "Ruční"), @SerialName("vypnuto") OFF("vypnuto", "Vypnuto") }
@Serializable data class LightState(
    @SerialName("zap") val on: Boolean,
    @SerialName("barva") val color: List<@Serializable(with = FlexibleIntSerializer::class) Int>,
    @SerialName("jas") @Serializable(with = FlexibleIntSerializer::class) val brightness: Int,
    @SerialName("rucne") val manual: Boolean,
) { val rgb: Rgb }                       // první tři složky, chybějící = 0, oříznuté na 0..255
@Serializable data class HouseStatus(
    @SerialName("cas") val time: String, @SerialName("usek") val dayPart: DayPart,
    @SerialName("t_venku") val outsideTemp: Double? = null, @SerialName("vlhkost") val humidity: Double? = null,
    @SerialName("tlak") val pressure: Double? = null, @SerialName("lux") val lux: Double? = null,
    @SerialName("t_uvnitr") val insideTemp: Double? = null, @SerialName("cil") val target: Double? = null,
    @SerialName("topi") val heating: Boolean, @SerialName("chladi") val cooling: Boolean,
    @SerialName("rezim") val mode: ThermostatMode, @SerialName("nikdo_doma") val nobodyHome: Boolean,
    @SerialName("pohyb") val motion: Boolean, @SerialName("svetlo") val light: LightState,
    @SerialName("chyby") val errors: List<String> = emptyList())
@Serializable data class HouseSettings(
    @SerialName("rezim_termostatu") val mode: ThermostatMode,
    @SerialName("cil_den") val targetDay: Double, @SerialName("cil_noc") val targetNight: Double,
    @SerialName("cil_rucni") val targetManual: Double,
    @SerialName("zacatek_den") val dayStart: String, @SerialName("zacatek_vecer") val eveningStart: String,
    @SerialName("zacatek_noc") val nightStart: String, @SerialName("prah_lux") val luxThreshold: Double,
    @SerialName("nocni_svetlo_s") @Serializable(with = FlexibleIntSerializer::class) val nightLightSeconds: Int,
    @SerialName("nikdo_doma_po_min") @Serializable(with = FlexibleIntSerializer::class) val nobodyHomeAfterMin: Int,
    @SerialName("utlum_nikdo_doma") val nobodyHomeSetback: Double,
    @SerialName("svetlo_pri_pohybu") val lightOnMotion: Boolean? = null)
data class Rgb(val r: Int, val g: Int, val b: Int)
val HouseJson: Json                                          // ignoreUnknownKeys = true
fun decodeStatus(bytes: ByteArray): HouseStatus?             // null místo jakékoli výjimky
fun decodeSettings(bytes: ByteArray): HouseSettings?
enum class SettingKind { DECIMAL, WHOLE, TIME, MODE, FLAG }
enum class SettingKey(val json: String, val kind: SettingKind, val label: String,
                      val min: Double = 0.0, val max: Double = 0.0, val unit: String = "")
data class SettingChange(val key: SettingKey, val value: JsonPrimitive)
fun SettingKey.change(value: Double): SettingChange          // WHOLE → celé číslo (roundToInt), jinak desetinné
fun SettingKey.change(value: String): SettingChange          // TIME „HH:MM“, MODE (ThermostatMode.json)
fun SettingKey.change(value: Boolean): SettingChange         // FLAG
fun List<SettingChange>.toJsonObject(): JsonObject           // pořadí zachované
fun HouseSettings.valueOf(key: SettingKey): JsonPrimitive?   // null jen u LIGHT_ON_MOTION, když chybí
enum class LightAction(val json: String, val label: String)
sealed interface HouseCommand {
    data class Light(val action: LightAction) : HouseCommand
    data class LightLook(val color: Rgb? = null, val brightness: Int? = null) : HouseCommand  // aspoň jedno vyplněné
    data class SetClock(val time: LocalDateTime) : HouseCommand }
fun HouseCommand.toJsonObject(): JsonObject
fun HouseCommand.label(): String   // Light → action.label, LightLook → "nastavit barvu a jas", SetClock → "nastavit čas"
fun formatPiTime(time: LocalDateTime): String               // "yyyy-MM-dd'T'HH:mm:ss", sekundy vždy
fun parsePiTime(text: String): LocalDateTime?
```

`SettingKey` (název: json, kind, label, min, max, unit):
- `MODE`: `rezim_termostatu`, MODE, „režim termostatu“
- `TARGET_DAY`: `cil_den`, DECIMAL, „denní cíl“, 5–35, °C
- `TARGET_NIGHT`: `cil_noc`, DECIMAL, „noční cíl“, 5–35, °C
- `TARGET_MANUAL`: `cil_rucni`, DECIMAL, „ruční cíl“, 5–35, °C
- `DAY_START`: `zacatek_den`, TIME, „začátek dne“
- `EVENING_START`: `zacatek_vecer`, TIME, „začátek večera“
- `NIGHT_START`: `zacatek_noc`, TIME, „začátek noci“
- `LUX_THRESHOLD`: `prah_lux`, DECIMAL, „práh šera“, 1–10000, lx
- `NIGHT_LIGHT_SECONDS`: `nocni_svetlo_s`, WHOLE, „délka nočního světla“, 5–600, s
- `NOBODY_HOME_AFTER`: `nikdo_doma_po_min`, WHOLE, „snížení cíle po“, 1–240, min
- `NOBODY_HOME_SETBACK`: `utlum_nikdo_doma`, DECIMAL, „snížení cíle o“, 0–10, °C
- `LIGHT_ON_MOTION`: `svetlo_pri_pohybu`, FLAG, „rozsvěcení při pohybu“

`LightAction` (json, label): `ON` („zapnout“, „zapnout světlo“), `OFF` („vypnout“, „vypnout světlo“), `TOGGLE` („prepnout“, „přepnout světlo“), `AUTO` („auto“, „vrátit světlo do automatiky“).

- [ ] **Krok 1: Napsat testovací data a padající testy**

`TestData.kt` (sdílené v celém test source setu):

```kotlin
const val EXAMPLE_STATUS = """{"cas":"2026-10-20T10:00:00","usek":"den","t_venku":22.0,"vlhkost":45.0,"tlak":1013.25,"lux":null,"t_uvnitr":22.0,"cil":23.0,"topi":true,"chladi":false,"rezim":"auto","nikdo_doma":false,"pohyb":false,"svetlo":{"zap":false,"barva":[0,0,0],"jas":0,"rucne":false},"chyby":[]}"""
const val EXAMPLE_SETTINGS = """{"rezim_termostatu":"auto","cil_den":23.0,"cil_noc":20.0,"cil_rucni":22.0,"zacatek_den":"07:00","zacatek_vecer":"19:00","zacatek_noc":"22:00","prah_lux":50.0,"nocni_svetlo_s":30,"nikdo_doma_po_min":30,"utlum_nikdo_doma":3.0}"""
val EXAMPLE: HouseStatus get() = decodeStatus(EXAMPLE_STATUS.toByteArray())!!
val DEFAULTS = HouseSettings(ThermostatMode.AUTO, 23.0, 20.0, 22.0, "07:00", "19:00", "22:00", 50.0, 30, 30, 3.0, lightOnMotion = true)
```

`HouseJsonTest`:
- `decodesExampleStatus`: kontroluje `time == "2026-10-20T10:00:00"`, `dayPart == DAY`, `outsideTemp == 22.0`, `lux == null`, `target == 23.0`, `heating`, `!cooling`, `mode == AUTO` a `light == LightState(false, listOf(0, 0, 0), 0, false)`, `errors.isEmpty()`.
- `acceptsFloatIntegersAndUnknownKeys` (Review Focus 1): v `EXAMPLE_STATUS` nahradí `"jas":0` za `"jas":50.0`, `"barva":[0,0,0]` za `"barva":[255.0,128,0]` a na začátek přidá `"novy_klic":1,`. Pak `light.brightness == 50` a `light.color == listOf(255, 128, 0)`.
- `brokenJsonGivesNull` (Review Focus 2): `decodeStatus(EXAMPLE_STATUS.take(40).toByteArray())`, `decodeStatus("{}".toByteArray())` a `decodeSettings(ByteArray(0))` jsou `null`.
- `nullSensorValues`: když jsou `t_venku`, `vlhkost`, `tlak` a `t_uvnitr` `null`, příslušná pole jsou `null`.
- `settingsWithoutLightOnMotion`: `decodeSettings(EXAMPLE_SETTINGS)` dá `DEFAULTS.copy(lightOnMotion = null)`.
- `settingsWithLightOnMotion`: s připojeným `,"svetlo_pri_pohybu":false` je `lightOnMotion == false`.

`SettingChangeTest` (porovnává `toJsonObject().toString()`):

```kotlin
assertEquals("""{"cil_den":23.5}""", listOf(SettingKey.TARGET_DAY.change(23.5)).toJsonObject().toString())
assertEquals("""{"nocni_svetlo_s":30}""", listOf(SettingKey.NIGHT_LIGHT_SECONDS.change(30.0)).toJsonObject().toString())
assertEquals("""{"rezim_termostatu":"rucni"}""", listOf(SettingKey.MODE.change(ThermostatMode.MANUAL.json)).toJsonObject().toString())
assertEquals("""{"svetlo_pri_pohybu":false}""", listOf(SettingKey.LIGHT_ON_MOTION.change(false)).toJsonObject().toString())
assertEquals("""{"zacatek_den":"07:00","zacatek_vecer":"19:00","zacatek_noc":"22:00"}""",
    listOf(SettingKey.DAY_START.change("07:00"), SettingKey.EVENING_START.change("19:00"), SettingKey.NIGHT_START.change("22:00")).toJsonObject().toString())
assertEquals(JsonPrimitive(23.0), DEFAULTS.valueOf(SettingKey.TARGET_DAY))
assertEquals(JsonPrimitive(30), DEFAULTS.valueOf(SettingKey.NOBODY_HOME_AFTER))
assertEquals(JsonPrimitive("auto"), DEFAULTS.valueOf(SettingKey.MODE))
assertNull(DEFAULTS.copy(lightOnMotion = null).valueOf(SettingKey.LIGHT_ON_MOTION))
```

`HouseCommandTest`:

```kotlin
assertEquals("""{"svetlo":"zapnout"}""", HouseCommand.Light(LightAction.ON).toJsonObject().toString())
assertEquals("""{"svetlo":"prepnout"}""", HouseCommand.Light(LightAction.TOGGLE).toJsonObject().toString())
assertEquals("""{"svetlo":{"barva":[0,0,255],"jas":50}}""", HouseCommand.LightLook(Rgb(0, 0, 255), 50).toJsonObject().toString())
assertEquals("""{"svetlo":{"jas":50}}""", HouseCommand.LightLook(brightness = 50).toJsonObject().toString())
assertEquals("""{"cas":"2026-10-20T14:03:00"}""", HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)).toJsonObject().toString())
assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0, 0), parsePiTime("2026-10-20T10:00:00"))
assertNull(parsePiTime("20.10.2026 10:00"))
assertEquals("přepnout světlo", HouseCommand.Light(LightAction.TOGGLE).label())
```

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.data.model.*"` skončí chybou překladu (typy ještě neexistují).
- [ ] **Krok 3: Implementovat soubory z bloku Rozhraní.** `FlexibleIntSerializer` je `KSerializer<Int>`, který při čtení vezme `JsonPrimitive`, převede ho přes `double` a zaokrouhlí (`roundToInt`), a při zápisu zapíše Int. `decodeStatus`/`decodeSettings` dekódují UTF-8 a jakoukoli výjimku převedou na `null`.
- [ ] **Krok 4: Spustit testy**: stejný příkaz skončí `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: model JSON Stavu, Nastavení a příkazů`

---

### Úkol 3: Doménová pravidla a formátování

**Soubory:**
- Vytvořit: `domain/DayStarts.kt`, `domain/Thermostat.kt`, `domain/LightTexts.kt`, `domain/Format.kt`, `domain/Steps.kt`, `domain/PiErrors.kt`
- Test: `domain/DayStartsTest.kt`, `ThermostatTest.kt`, `LightTextsTest.kt`, `FormatTest.kt`, `StepsTest.kt`, `PiErrorsTest.kt`

**Rozhraní:**
- Používá: `DayPart`, `ThermostatMode`, `LightState`, `HouseSettings`, `SettingKey` (úkol 2).
- Produkuje:

```kotlin
data class DayStarts(val day: LocalTime, val evening: LocalTime, val night: LocalTime) {
    fun startOf(part: DayPart): LocalTime
    fun with(part: DayPart, time: LocalTime): DayStarts }
fun dayStartsOf(settings: HouseSettings): DayStarts?          // null, když některý čas nejde přečíst
fun parseHhMm(text: String): LocalTime?                       // jen přesně „HH:MM“ 00:00–23:59
fun formatHhMm(time: LocalTime): String
fun dayPartAt(time: LocalTime, starts: DayStarts): DayPart    // platí úsek s posledním začátkem ≤ time, cyklicky
fun nextStart(time: LocalTime, starts: DayStarts): Pair<DayPart, LocalTime>  // první začátek ostře po time, cyklicky
data class TimelineSegment(val fromMinute: Int, val toMinute: Int, val part: DayPart)
fun timeline(starts: DayStarts): List<TimelineSegment>        // pokryje 0..1440 bez děr, seřazené
val DayPart.title: String                                     // "Den", "Večer", "Noc"
enum class TargetSlot(val key: SettingKey, val label: String) {
    DAY(SettingKey.TARGET_DAY, "Denní cíl"), NIGHT(SettingKey.TARGET_NIGHT, "Noční cíl"), MANUAL(SettingKey.TARGET_MANUAL, "Ruční cíl") }
fun activeTargetSlot(mode: ThermostatMode, dayPart: DayPart): TargetSlot?
data class LightTexts(val title: String, val detail: String, val tileValue: String, val tileDetail: String)
fun lightTexts(light: LightState, dayPart: DayPart, manualUntil: LocalTime?): LightTexts
const val NBSP = '\u00A0'
fun formatDecimal(value: Double, decimals: Int = 1): String   // Locale.ROOT + '.'→',' (deterministicky, spojovník jako minus)
fun formatTemp(value: Double?): String; fun formatPercent(value: Double?): String
fun formatPressure(value: Double?): String; fun formatLux(value: Double?): String   // null → "—"
fun formatDuration(seconds: Long): String; fun formatAgo(ms: Long): String
fun clockDiffText(diffSeconds: Long): String                  // diff = Raspberry − telefon
fun stepValue(key: SettingKey, current: Double, up: Boolean): Double   // výsledek oříznutý na key.min..key.max
fun piErrorText(code: String): String; fun piErrorDetail(code: String): String
```

- [ ] **Krok 1: Napsat padající testy**

`DayStartsTest`:

```kotlin
val defaults = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0))
val shifted = DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(10, 5))
assertEquals(NIGHT, dayPartAt(LocalTime.of(6, 59), defaults)); assertEquals(DAY, dayPartAt(LocalTime.of(7, 0), defaults))
assertEquals(EVENING, dayPartAt(LocalTime.of(21, 59), defaults)); assertEquals(NIGHT, dayPartAt(LocalTime.of(22, 0), defaults))
// příklad ze spec §4.3
assertEquals(NIGHT, dayPartAt(LocalTime.of(10, 30), shifted)); assertEquals(EVENING, dayPartAt(LocalTime.of(19, 30), shifted))
assertEquals(EVENING, dayPartAt(LocalTime.of(3, 0), shifted)); assertEquals(DAY, dayPartAt(LocalTime.of(8, 0), shifted))
assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(10, 0), defaults))
assertEquals(DAY to LocalTime.of(7, 0), nextStart(LocalTime.of(23, 0), defaults))
assertEquals(EVENING to LocalTime.of(19, 0), nextStart(LocalTime.of(10, 5), shifted))
assertEquals(listOf(TimelineSegment(0, 420, NIGHT), TimelineSegment(420, 1140, DAY), TimelineSegment(1140, 1320, EVENING), TimelineSegment(1320, 1440, NIGHT)), timeline(defaults))
assertEquals(LocalTime.of(7, 5), parseHhMm("07:05")); assertNull(parseHhMm("7:5")); assertNull(parseHhMm("24:00"))
assertEquals("07:05", formatHhMm(LocalTime.of(7, 5)))
assertEquals(defaults, dayStartsOf(DEFAULTS))
```

`ThermostatTest`: (AUTO, DAY) → DAY, (AUTO, EVENING) → DAY, (AUTO, NIGHT) → NIGHT, (MANUAL, NIGHT) → MANUAL, (OFF, DAY) → `null`.

`LightTextsTest` (`on` znamená `LightState(true, listOf(100, 110, 255), jas, rucne)`):

| Vstup | Očekávané `LightTexts` |
|---|---|
| ručně zap., jas 60, DAY, do 19:00 | `("Svítí · ručně", "Jas 60 % · ruční volba platí do 19:00", "Svítí", "ručně · 60 %")` |
| ručně zap., jas 60, `manualUntil = null` | detail `"Jas 60 %"` |
| ručně vyp., DAY, do 19:00 | `("Zhasnuto · ručně", "Ruční volba platí do 19:00", "Zhasnuto", "ručně")` |
| auto zap., jas 80, DAY | `("Svítí · venku je šero", "Jas 80 %", "Svítí", "auto · 80 %")` |
| auto vyp., DAY | `("Zhasnuto · venku je dost světla", "", "Zhasnuto", "auto")` |
| auto zap., jas 60, EVENING | `("Svítí · večerní světlo", "Jas 60 %", "Svítí", "auto · 60 %")` |
| auto vyp., EVENING | `("Zhasnuto", "", "Zhasnuto", "auto")` |
| auto zap., jas 15, NIGHT | `("Noční světlo · pohyb", "Jas 15 %", "Svítí", "auto · 15 %")` |
| auto vyp., NIGHT | `("Zhasnuto · noc", "", "Zhasnuto", "auto")` |

(Mezi číslem a `%` je `\u00A0`.)

`FormatTest`:

```kotlin
assertEquals("22,4\u00A0°C", formatTemp(22.4)); assertEquals("-3,0\u00A0°C", formatTemp(-3.0)); assertEquals("—", formatTemp(null))
assertEquals("45\u00A0%", formatPercent(45.4)); assertEquals("1013\u00A0hPa", formatPressure(1013.25)); assertEquals("320\u00A0lx", formatLux(320.4)); assertEquals("—", formatLux(null))
assertEquals("45\u00A0s", formatDuration(45)); assertEquals("2\u00A0min 12\u00A0s", formatDuration(132)); assertEquals("2\u00A0min", formatDuration(120))
assertEquals("1\u00A0h 5\u00A0min", formatDuration(3_900)); assertEquals("více než den", formatDuration(90_000))   // Review Focus 3
assertEquals("před 40\u00A0s", formatAgo(40_400)); assertEquals("před 2\u00A0min", formatAgo(150_000))
assertEquals("před 1\u00A0h 5\u00A0min", formatAgo(3_900_000)); assertEquals("před více než dnem", formatAgo(90_000_000))
assertEquals("Hodiny Raspberry jdou o 2\u00A0min 12\u00A0s pozadu.", clockDiffText(-132))
assertEquals("Hodiny Raspberry jdou o více než den napřed.", clockDiffText(200_000))
```

`StepsTest` (tolerance 1e-9):
- Cíle: `stepValue(TARGET_DAY, 23.0, true) == 23.5` a `stepValue(TARGET_DAY, 35.0, true) == 35.0`.
- „Nikdo doma“: `stepValue(NOBODY_HOME_AFTER, 30.0, false) == 29.0` a `stepValue(NOBODY_HOME_SETBACK, 3.0, false) == 2.5`.
- Noční světlo: `stepValue(NIGHT_LIGHT_SECONDS, 30.0, true) == 35.0` a `stepValue(NIGHT_LIGHT_SECONDS, 5.0, false) == 5.0`.
- Práh šera (krok pod 10 je 1, pod 100 je 5, pod 1000 je 50, jinak 500; směrem dolů platí krok pásma pod hodnotou): z 50 nahoru je 55, ze 100 dolů 95, z 9 nahoru 10, z 1000 dolů 950, z 1000 nahoru 1500, z 1 dolů 1 a z 10000 nahoru 10000.

`PiErrorsTest`:

| Kód | `piErrorText` | `piErrorDetail` |
|---|---|---|
| `bme280` | „Čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá“ | „Venkovní teplota, vlhkost a tlak chybí.“ |
| `bh1750` | „Čidlo osvětlení (BH1750) neodpovídá“ | „Hodnota osvětlení chybí.“ |
| `displej` | „Displej domku nefunguje“ | „Domek běží dál, jen bez displeje.“ |
| `svetlo` | „LED světlo nefunguje“ | „Světlo nemusí reagovat.“ |
| `nastaveni` | „Raspberry nemůže načíst nebo uložit nastavení“ | „Změny nastavení se možná neuloží.“ |
| `xyz` | „Raspberry hlásí chybu: xyz“ | „“ |

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.domain.*"`.
- [ ] **Krok 3: Implementovat funkce z bloku Rozhraní.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: pravidla úseků dne, platný cíl, texty a formátování`

---

### Úkol 4: Kontrakt zdroje dat a pravidla Raspberry

**Soubory:**
- Vytvořit: `data/source/HouseDataSource.kt`, `data/source/TimeSource.kt`, `sim/RaspberryRules.kt`
- Test: `sim/RaspberryRulesTest.kt`

**Rozhraní:**
- Používá: model z úkolu 2.
- Produkuje:

```kotlin
enum class SourceKind { BLUETOOTH, SIMULATION }
data class FoundDevice(val address: String, val name: String, val rssi: Int)
data class ReceivedStatus(val status: HouseStatus, val receivedAtMs: Long, val receivedAtWall: LocalDateTime)
sealed interface WriteResult {
    data object Ok : WriteResult; data class Rejected(val gattStatus: Int) : WriteResult
    data object NotConnected : WriteResult; data object Timeout : WriteResult }
sealed interface ConnectionState {
    data object Idle : ConnectionState; data object NoBluetooth : ConnectionState
    data object PermissionsMissing : ConnectionState; data object BluetoothOff : ConnectionState
    data object LocationOff : ConnectionState; data object Scanning : ConnectionState
    data class ChoosingDevice(val devices: List<FoundDevice>) : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState; data object Preparing : ConnectionState
    data class Connected(val address: String, val mtu: Int, val rssi: Int?) : ConnectionState
    data class WaitingToReconnect(val attempt: Int, val retryAtMs: Long) : ConnectionState
    data object DisconnectedByUser : ConnectionState; data object MissingService : ConnectionState
    data object Simulated : ConnectionState }
interface HouseDataSource {
    val connection: StateFlow<ConnectionState>
    val status: StateFlow<ReceivedStatus?>
    val settings: StateFlow<HouseSettings?>
    fun start(); fun stop()
    suspend fun writeSettings(changes: List<SettingChange>): WriteResult
    suspend fun sendCommand(command: HouseCommand): WriteResult }
interface BleControls { fun chooseDevice(address: String); fun disconnectByUser(); fun connectByUser(); fun retry() }
enum class SimulationAction(val label: String) {
    HEAT_SENSOR("Zahřát čidlo"), COVER_LIGHT_SENSOR("Zakrýt čidlo světla"), MOTION("Pohyb"),
    TOGGLE_SENSOR_FAULT("Porucha BME280"), PAUSE_DATA("Výpadek dat") }
interface SimulationControls { fun perform(action: SimulationAction) }
fun interface TimeSource { fun nowMs(): Long }
fun applySettingsPatch(current: HouseSettings, patch: JsonObject): HouseSettings?   // null = zápis neplatný, nic se nemění
fun parseCommand(json: JsonObject): HouseCommand?                                    // null = příkaz neplatný
```

Pravidla v `applySettingsPatch` (spec §4.1 a §4.2):
- Každý klíč musí být `SettingKey.json`.
- DECIMAL je JSON číslo (ne text, ne bool) v rozsahu `min..max` včetně krajů.
- WHOLE navíc musí mít obsah `-?\d+`, takže `30.5` i `30.0` jsou neplatné.
- TIME je text `^([01]\d|2[0-3]):[0-5]\d$`.
- MODE je jeden z textů `auto`, `rucni`, `vypnuto`.
- FLAG je JSON `true` nebo `false`.
- Po sloučení musí být všechny tři začátky různé. Na jejich pořadí nezáleží.

Pravidla v `parseCommand`:
- Objekt má právě jeden klíč.
- `"svetlo"` je buď text z `LightAction.json`, nebo neprázdný objekt s klíči z {`barva`, `jas`}. `barva` je pole tří celých čísel 0–255, `jas` celé číslo 0–100.
- `"cas"` je text, který přečte `parsePiTime`.

- [ ] **Krok 1: Napsat padající testy** (`fun patch(s: String) = HouseJson.parseToJsonElement(s).jsonObject`)

```kotlin
assertEquals(DEFAULTS.copy(targetDay = 24.5), applySettingsPatch(DEFAULTS, patch("""{"cil_den":24.5}""")))
assertEquals(DEFAULTS.copy(nightLightSeconds = 60, mode = ThermostatMode.MANUAL), applySettingsPatch(DEFAULTS, patch("""{"nocni_svetlo_s":60,"rezim_termostatu":"rucni"}""")))
assertEquals(DEFAULTS.copy(nightStart = "10:05"), applySettingsPatch(DEFAULTS, patch("""{"zacatek_noc":"10:05"}""")))
assertEquals(DEFAULTS.copy(lightOnMotion = false), applySettingsPatch(DEFAULTS, patch("""{"svetlo_pri_pohybu":false}""")))
listOf("""{"neznamy":1}""", """{"cil_den":true}""", """{"cil_den":"23"}""", """{"cil_den":35.5}""", """{"nocni_svetlo_s":30.5}""",
       """{"nikdo_doma_po_min":0}""", """{"zacatek_noc":"19:00"}""", """{"zacatek_den":"7:00"}""", """{"rezim_termostatu":"turbo"}""",
       """{"svetlo_pri_pohybu":1}""").forEach { assertNull(it, applySettingsPatch(DEFAULTS, patch(it))) }
assertEquals(HouseCommand.Light(LightAction.TOGGLE), parseCommand(patch("""{"svetlo":"prepnout"}""")))
assertEquals(HouseCommand.LightLook(Rgb(0, 0, 255), 50), parseCommand(patch("""{"svetlo":{"barva":[0,0,255],"jas":50}}""")))
assertEquals(HouseCommand.LightLook(brightness = 20), parseCommand(patch("""{"svetlo":{"jas":20}}""")))
assertEquals(HouseCommand.SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0)), parseCommand(patch("""{"cas":"2026-10-20T14:03:00"}""")))
listOf("""{"svetlo":"blikat"}""", """{"svetlo":{}}""", """{"svetlo":{"barva":[0,0,256]}}""", """{"svetlo":{"jas":101}}""",
       """{"svetlo":"auto","cas":"2026-10-20T14:03:00"}""", """{"cas":"zitra"}""").forEach { assertNull(it, parseCommand(patch(it))) }
```

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.sim.RaspberryRulesTest"`.
- [ ] **Krok 3: Implementovat `HouseDataSource.kt`, `TimeSource.kt` a `RaspberryRules.kt`.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: rozhraní zdroje dat a pravidla zápisů Raspberry`

---

### Úkol 5: Model simulace

**Soubory:**
- Vytvořit: `sim/HouseSimulation.kt`
- Test: `sim/HouseSimulationTest.kt`

**Rozhraní:**
- Používá: model (úkol 2), `dayPartAt`, `dayStartsOf`, `activeTargetSlot` (úkol 3), `applySettingsPatch`, `parseCommand` (úkol 4).
- Produkuje:

```kotlin
enum class SimEvent { MOTION, LIGHT, DAY_PART }
val SIM_DEFAULT_SETTINGS: HouseSettings   // výchozí hodnoty ze spec §4.1 + lightOnMotion = true (shodné s DEFAULTS v testech)
class HouseSimulation(start: LocalDateTime, initialInside: Double = 21.5, settings: HouseSettings = SIM_DEFAULT_SETTINGS) {
    val settings: HouseSettings
    val clock: LocalDateTime
    var sensorFault: Boolean
    fun advance(seconds: Double): Set<SimEvent>
    fun status(): HouseStatus
    fun writeSettings(patch: JsonObject): Boolean
    fun command(json: JsonObject): Boolean
    fun heatSensor(); fun coverLightSensor(); fun triggerMotion() }
```

Konstanty modelu (rozhodnuto, `t` = sekundy od startu simulace):
- **Venku:** `21.5 + 0.4·sin(2π·t/600)`. `heatSensor()` přidá 5 °C, které lineárně klesnou na 0 za 30 s.
- **Vlhkost:** `45 + 2·sin(2π·t/900)`. **Tlak:** `1013.2 + 0.3·sin(2π·t/1200)`.
- **Osvětlení:** ve dne `300 + 40·sin(2π·t/300)`, večer 20, v noci 0.5. `coverLightSensor()` ho na 30 s omezí na nejvýš 5.
- **Vnitřní teplota:** při topení +0.05 °C/s, při chlazení −0.05 °C/s, jinak `inside += (outside − inside)·(1 − e^(−0.003·dt))`.
- **Hystereze 0.3 °C:** topit začne pod `cíl − 0.3` a přestane při `≥ cíl`. Chladit začne nad `cíl + 0.3` a přestane při `≤ cíl`.
- **Platný cíl:** podle `activeTargetSlot`, při „nikdo doma“ snížený o `nobodyHomeSetback`. V režimu Vypnuto je `cil` null a domek netopí ani nechladí.
- **Pohyb a „nikdo doma“:** `triggerMotion()` drží `pohyb` 3 s. Na začátku se za poslední pohyb bere start simulace. „Nikdo doma“ platí, když od posledního pohybu uplynulo aspoň `nobodyHomeAfterMin` minut.
- **Automatika světla:**
  - den: bílá `[255,255,255]` s jasem 80, jen když `lux < prah_lux`;
  - večer: teplá `[255,170,60]` s jasem 60;
  - noc: zhasnuto. Po pohybu, je-li `lightOnMotion`, se rozsvítí noční světlo `[255,120,30]` s jasem 15 na `nightLightSeconds`.
- **Ruční volba světla:**
  - příkazy `zapnout`, `vypnout`, `prepnout` a `LightLook` platí do změny úseku, `auto` ruční volbu zruší;
  - `zapnout` bez dřívější ruční barvy zapne bílou se 100 %;
  - `LightLook` doplní chybějící složku z právě svítícího světla, jinak bílou nebo 100 %;
  - `prepnout` převrátí to, co je právě vidět.
- **Vypnutí `lightOnMotion`** během nočního světla ho hned zhasne.
- **Příkaz `cas`** přestaví `clock` (fyzika běží dál).
- **Porucha BME280** (`sensorFault`): `t_venku`, `vlhkost` a `tlak` jsou null a `chyby = ["bme280"]`. Fyzika dál počítá se skutečnou venkovní teplotou.
- **Formát Stavu:** čísla zaokrouhlená na 2 desetinná místa, `cas` přes `formatPiTime`.
- **Události:** `MOTION` při začátku pohybu, `LIGHT` při změně zap/barvy/jasu/rucne a `DAY_PART` při změně úseku.

- [ ] **Krok 1: Napsat padající testy** (`val tenAm = LocalDateTime.of(2026, 10, 20, 10, 0)`, `fun HouseSimulation.run(s: Int) = repeat(s) { advance(1.0) }`)

**Teplota a termostat**
- `heatingRaisesInsideAboutThreeDegreesPerMinute`: `HouseSimulation(tenAm, 22.0, SIM_DEFAULT_SETTINGS.copy(targetDay = 25.0))`, po `run(1)` je `heating`, po dalších `run(59)` je `insideTemp ≥ 24.8`.
- `heatingUsesHysteresis`:
  - start 22.8, cíl 23: po `advance(1.0)` netopí;
  - start 22.6: topí;
  - po `run(12)` netopí a `insideTemp` je v rozmezí 22.9..23.2.
- `coolingLowersInside`: start 25.0, `targetDay = 22.0`. Po `run(30)` chladí a `insideTemp ≤ 23.6`.
- `offModeDriftsTowardOutside`: `mode = OFF`, start 25.0. Po `run(600)` je `insideTemp < 24.0`, `target == null` a netopí ani nechladí.
- `nightUsesNightTarget`: start ve 23:00. `status().dayPart == NIGHT` a `status().target == 20.0`.
- `nobodyHomeLowersTarget`:
  - `nobodyHomeAfterMin = 1`, po `run(61)` platí `nobodyHome` a `target == 20.0`;
  - po `triggerMotion()` a `advance(1.0)` platí `!nobodyHome` a `target == 23.0`.

**Světlo**
- `darkDayTurnsWhiteLightOn`: `coverLightSensor()` a `advance(1.0)` vrátí `SimEvent.LIGHT`, světlo `on`, barva `[255,255,255]`, `!manual`.
- `brightDayKeepsLightOff`: po `advance(1.0)` je `!light.on`.
- `eveningLightIsWarm`: start ve 20:00, světlo svítí barvou `[255,170,60]` s jasem 60.
- `nightMotionLightsNightLightForConfiguredSeconds`: start ve 23:00, `nightLightSeconds = 5`.
  - Po `triggerMotion()` a `advance(1.0)` svítí `[255,120,30]` s jasem 15.
  - Po `run(5)` je zhasnuto.
- `noNightLightWhenDisabled`: s `lightOnMotion = false` pohyb světlo nerozsvítí.
- `disablingLightOnMotionTurnsNightLightOff`: noční světlo svítí. `writeSettings({"svetlo_pri_pohybu":false})` vrátí `true` a `status().light.on` je hned `false`.
- `manualChoiceLastsUntilNextDayPart`: start v 18:59:50.
  - `command({"svetlo":"zapnout"})` zapne světlo ručně.
  - Po `run(15)` je `!manual` a svítí teplá večerní barva.
- `toggleFlipsWhatIsShown`: ve dne je zhasnuto. První `prepnout` dá on+manual, druhé off+manual.
- `colorCommandTurnsLightOnManually`: `{"svetlo":{"barva":[0,0,255]}}` zapne světlo ručně s barvou `[0,0,255]` a jasem 100.

**Hodiny, poruchy a odmítnutí**
- `clockCommandMovesClock`: po `{"cas":"2026-10-20T23:30:00"}` a `advance(1.0)` je `dayPart == NIGHT` a `time` začíná `"2026-10-20T23:30:0"`.
- `sensorFaultNullsBmeValues`: `outsideTemp`, `humidity` a `pressure` jsou null, `errors == listOf("bme280")` a `insideTemp != null`.
- `invalidWritesAndCommandsChangeNothing`:
  - `writeSettings({"zacatek_noc":"19:00"})` vrátí `false` a `settings == SIM_DEFAULT_SETTINGS`;
  - `command({"svetlo":"blikat"})` vrátí `false`.

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.sim.HouseSimulationTest"`.
- [ ] **Krok 3: Implementovat `HouseSimulation`** podle konstant výše.
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: model simulovaného domku`

---

### Úkol 6: Simulovaný zdroj dat

**Soubory:**
- Vytvořit: `sim/SimulatedHouseDataSource.kt`
- Test: `sim/SimulatedHouseDataSourceTest.kt`

**Rozhraní:**
- Používá: `HouseDataSource`, `SimulationControls`, `TimeSource` (úkol 4), `HouseSimulation` (úkol 5).
- Produkuje: `class SimulatedHouseDataSource(scope: CoroutineScope, time: TimeSource, wallClock: () -> LocalDateTime, writeLatencyMs: Long = 60) : HouseDataSource, SimulationControls` a `const val SIM_REJECTED_STATUS = 128`.

Chování:
- `start()`:
  - vytvoří `HouseSimulation(wallClock())`;
  - nastaví `connection = Simulated` a `settings = SIM_DEFAULT_SETTINGS`;
  - hned pošle Stav a pak každých 1000 ms zavolá `advance(1.0)` a pošle Stav (`ReceivedStatus(sim.status(), time.nowMs(), wallClock())`).
- Zápisy a příkazy:
  - po `delay(writeLatencyMs)` se provedou v modelu;
  - úspěch: `Ok`, okamžitě nový Stav, u zápisu i nové `settings`;
  - neúspěch: `Rejected(128)`.
- `perform`:
  - `HEAT_SENSOR`, `COVER_LIGHT_SENSOR` a `MOTION` zavolají příslušnou metodu modelu a hned pošlou Stav;
  - `TOGGLE_SENSOR_FAULT` přepne `sensorFault`;
  - `PAUSE_DATA` na 10000 ms přestane posílat Stav (model běží dál).
- `stop()` zruší úlohy a nastaví `connection = Idle`.
- Model používá jen jedna korutina najednou (`Mutex`).

- [ ] **Krok 1: Napsat padající testy**

V `runTest` se zdroj vytvoří s `backgroundScope`, `time = { testScheduler.currentTime }` a `wallClock = { LocalDateTime.of(2026, 10, 20, 10, 0).plusNanos(testScheduler.currentTime * 1_000_000) }`.

- `emitsStatusEverySecond`: `start()` a `runCurrent()`, pak `connection.value == Simulated`. Po `advanceTimeBy(1_000)` a `runCurrent()` je `receivedAtMs` aspoň o 1000 větší.
- `validWriteUpdatesSettings`: `writeSettings(listOf(TARGET_DAY.change(24.0)))` vrátí `Ok` a `settings.value!!.targetDay == 24.0`.
- `invalidWriteIsRejectedWith128`: `writeSettings(listOf(NIGHT_START.change("19:00")))` vrátí `Rejected(128)` a `settings` se nezmění.
- `pauseDataStopsStatusForTenSeconds`:
  - po `perform(PAUSE_DATA)` se po `advanceTimeBy(9_500)` `receivedAtMs` nezmění;
  - po dalších `advanceTimeBy(1_600)` je větší.
- `commandEmitsStatusImmediately`: `sendCommand(Light(ON))` vrátí `Ok` a hned platí `status.value!!.status.light.on` i `.manual`.
- `stopEndsEmission`: po `stop()` je `connection == Idle` a za 2 s nepřijde nový Stav.

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.sim.SimulatedHouseDataSourceTest"`.
- [ ] **Krok 3: Implementovat `SimulatedHouseDataSource`.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: simulovaný zdroj dat`

---

### Úkol 7: Pomocníci BLE a fronta GATT

**Soubory:**
- Vytvořit: `ble/BleUuids.kt`, `ble/BlePermissions.kt`, `ble/BleLogic.kt`, `ble/GattOperationQueue.kt`
- Test: `ble/BleLogicTest.kt`, `ble/BlePermissionsTest.kt`, `ble/GattOperationQueueTest.kt`

**Rozhraní:**
- Používá: `FoundDevice` (úkol 4).
- Produkuje:

```kotlin
object BleUuids {   // hodnoty přesně podle spec §4.1
    val HOUSE_SERVICE: UUID; val STATUS: UUID; val SETTINGS: UUID; val COMMAND: UUID; val CCCD: UUID
    const val DEVICE_NAME = "Termostat"; const val REQUESTED_MTU = 517 }
object BlePermissions { fun required(sdkInt: Int): List<String>; fun needsLocationServices(sdkInt: Int): Boolean }
fun reconnectDelayMs(attempt: Int): Long
sealed interface ScanDecision { data class Connect(val device: FoundDevice) : ScanDecision; data class Choose(val devices: List<FoundDevice>) : ScanDecision }
fun decideAfterScanWindow(found: Collection<FoundDevice>): ScanDecision   // víc zařízení → seřazená podle rssi sestupně
class ScanThrottle(minIntervalMs: Long = 6_000) { fun delayBeforeStart(nowMs: Long): Long; fun onStarted(nowMs: Long) }
sealed interface GattResult {
    data class Success(val bytes: ByteArray? = null, val number: Int = 0) : GattResult
    data class Failure(val status: Int) : GattResult
    data object Timeout : GattResult; data object NotConnected : GattResult; data object StartFailed : GattResult }
class GattOperationQueue(defaultTimeoutMs: Long = 5_000) {
    suspend fun run(name: String, timeoutMs: Long = defaultTimeoutMs, start: () -> Boolean): GattResult
    fun complete(result: GattResult)   // volá BLE callback; bez běžící operace se ignoruje
    fun close() }                       // běžící operace → NotConnected; další run → NotConnected bez volání start
```

Fronta pouští vždy jen jednu operaci (`Mutex`). Čekání na callback zajišťuje `CompletableDeferred` s `withTimeout`. Když `start()` vrátí `false`, výsledek je `StartFailed` a fronta pokračuje další operací.

- [ ] **Krok 1: Napsat padající testy**

```kotlin
assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map(::reconnectDelayMs))
val a = FoundDevice("AA", "Termostat", -70); val b = FoundDevice("BB", "Termostat", -50)
assertEquals(ScanDecision.Connect(a), decideAfterScanWindow(listOf(a)))
assertEquals(ScanDecision.Choose(listOf(b, a)), decideAfterScanWindow(listOf(a, b)))
val t = ScanThrottle(); assertEquals(0L, t.delayBeforeStart(10_000)); t.onStarted(10_000)
assertEquals(4_000L, t.delayBeforeStart(12_000)); assertEquals(0L, t.delayBeforeStart(16_000))
assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), BlePermissions.required(31))
assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(30))
assertTrue(BlePermissions.needsLocationServices(30)); assertFalse(BlePermissions.needsLocationServices(31))
```

`GattOperationQueueTest` (`runTest`):
- `runsOneOperationAtATime`:
  - dvě `async { q.run(…) { started += it; true } }`; po `runCurrent()` je `started == ["a"]`;
  - po `q.complete(Success(number = 1))` je `started == ["a", "b"]` a `a.await() == Success(number = 1)`;
  - po `q.complete(Failure(128))` je `b.await() == Failure(128)`.
- `timesOutAndFreesQueue`: po `advanceTimeBy(5_001)` je výsledek `Timeout` a další `run` hned zavolá `start`.
- `startFailureDoesNotBlock`: `q.run("a") { false } == StartFailed` a následující `run` se spustí.
- `closeFailsRunningAndLaterOperations`: běžící operace po `close()` vrátí `NotConnected`. Další `run` vrátí `NotConnected` a jeho `start` se nezavolá.
- `lateCompleteIsIgnored`: `complete(Success())` bez běžící operace nevyhodí výjimku a další `run` čeká na svůj vlastní `complete`.

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.ble.*"`.
- [ ] **Krok 3: Implementovat.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: fronta GATT operací a pomocníci BLE`

---

### Úkol 8: BLE klient a zdroj dat

**Soubory:**
- Vytvořit: `ble/HouseGattClient.kt`, `ble/BleHouseDataSource.kt`

**Rozhraní:**
- Používá: úkoly 2, 4 a 7.
- Produkuje:

```kotlin
sealed interface PrepareResult { data class Ready(val mtu: Int) : PrepareResult; data class Failed(val status: Int) : PrepareResult; data object MissingService : PrepareResult }
@SuppressLint("MissingPermission")   // oprávnění kontroluje BleHouseDataSource před každým použitím
class HouseGattClient(context: Context, device: BluetoothDevice,
                      onStatus: (ByteArray) -> Unit, onSettings: (ByteArray) -> Unit, onLinkLost: (status: Int) -> Unit) {
    suspend fun connectAndPrepare(onLinkUp: () -> Unit): PrepareResult
    suspend fun write(characteristic: UUID, payload: ByteArray): GattResult
    suspend fun readRssi(): Int?
    fun close() }
class BleHouseDataSource(context: Context, scope: CoroutineScope, time: TimeSource, wallClock: () -> LocalDateTime) : HouseDataSource, BleControls
```

- [ ] **Krok 1: `HouseGattClient`**

**Připojení:** `device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`. Na `onConnectionStateChange(STATE_CONNECTED, GATT_SUCCESS)` se čeká nejvýš 15000 ms. Pak se zavolá `onLinkUp()`.

**Příprava přes `GattOperationQueue`, krok po kroku, každý krok s limitem 10000 ms:**
1. `requestMtu(517)` → `onMtuChanged`. Selhání nebo timeout tady záměrně nevadí, pokračuje se s MTU 23. Je to odchylka od spec §5.3: některé telefony callback nepošlou, když už je MTU vyjednané (Android 14+ vyjednává 517 sám).
2. `discoverServices()`. Pak se ověří, že existuje `HOUSE_SERVICE` a charakteristiky `STATUS`, `SETTINGS` a `COMMAND`. Když chybí, výsledek je `MissingService`.
3. Pro `STATUS` a pak pro `SETTINGS`: `setCharacteristicNotification(ch, true)` a zápis `ENABLE_NOTIFICATION_VALUE` do CCCD → `onDescriptorWrite`.
4. `readCharacteristic(SETTINGS)` → `onSettings`, pak `readCharacteristic(STATUS)` → `onStatus`.

Jakýkoli neúspěch v krocích 2–4 vrátí `Failed(status)`.

**Rozdíly API.** Přepsat obě varianty callbacků a v deprecated verzích pracovat jen při `Build.VERSION.SDK_INT < 33` (přes `characteristic.value`):
- `onCharacteristicChanged(gatt, ch, value)` a deprecated `onCharacteristicChanged(gatt, ch)`;
- `onCharacteristicRead(gatt, ch, value, status)` a deprecated `onCharacteristicRead(gatt, ch, status)`.

Zápisy na SDK ≥ 33 jdou přes `gatt.writeCharacteristic(ch, value, WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS` a `gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS`. Na starších verzích přes `ch.writeType = WRITE_TYPE_DEFAULT; ch.value = value; gatt.writeCharacteristic(ch)` a obdobně pro deskriptor. Přijatá data se vždy kopírují (`copyOf()`).

**Notifikace** jdou mimo frontu přímo do `onStatus` a `onSettings` podle UUID.

**`write`:** `onCharacteristicWrite` se statusem 0 dá `Success`, jiný status `Failure(status)`.

**`readRssi`:** `readRemoteRssi()` → `onReadRemoteRssi`.

**Výpadek a uzavření:**
- `onConnectionStateChange` s chybou nebo s `STATE_DISCONNECTED`: `queue.close()`, `gatt.close()` a pak buď selže čekání na připojení, nebo se zavolá `onLinkLost(status)`. Platí i pro statusy 133 a 147.
- `close()`: `queue.close()` a `gatt.close()`. Musí jít zavolat opakovaně.

- [ ] **Krok 2: `BleHouseDataSource`**

**Řídicí smyčka**, korutina ve `scope`. Probouzí ji `Channel<Unit>(CONFLATED)`, do kterého posílají `retry()`, `connectByUser()`, `chooseDevice()` a zapnutí Bluetooth.

```
loop:
  prerequisites() → NoBluetooth | PermissionsMissing | BluetoothOff | LocationOff(jen když adresa není známá a SDK ≤ 30)
     když něco chybí: connection = to; čekat na probuzení; znovu
  když userDisconnected: connection = DisconnectedByUser; čekat na probuzení; znovu
  address = knownAddress ?: scanAndChoose()          // Scanning → 2 s okno → Connect | ChoosingDevice(+ čekání na chooseDevice)
  attempt++; connection = Connecting(attempt)
  client = HouseGattClient(...); when (client.connectAndPrepare { connection = Preparing }) {
     Ready → attempt = 0; connection = Connected(address, mtu, null); runSession(client)   // vrátí se po ztrátě spojení
     MissingService → client.close(); knownAddress = null; connection = MissingService; čekat na probuzení; continue
     Failed → client.close() }
  když ne userDisconnected: retryAt = now + reconnectDelayMs(max(attempt, 1))
     connection = WaitingToReconnect(attempt, retryAt); čekat do retryAt nebo na probuzení
```

**`prerequisites()`:**
- NoBluetooth: chybí `FEATURE_BLUETOOTH_LE` nebo adaptér;
- PermissionsMissing: `BlePermissions.required(SDK_INT)` přes `ContextCompat.checkSelfPermission`;
- BluetoothOff: `!adapter.isEnabled`;
- LocationOff: `LocationManagerCompat.isLocationEnabled`.

**`scanAndChoose()`:**
- `ScanThrottle` (případně počkat), `startScan` s `ScanFilter.setDeviceName("Termostat")` a `SCAN_MODE_LOW_LATENCY`;
- výsledky se sbírají do mapy podle adresy s aktualizací RSSI;
- od prvního výsledku běží okno 2000 ms, pak `decideAfterScanWindow`;
- `Connect` zastaví hledání. `Choose` nechá hledání běžet, průběžně aktualizuje `ChoosingDevice(devices)` a zastaví se až po `chooseDevice`;
- `onScanFailed` se zaloguje a hledá se znovu přes throttle.

**`runSession`:** souběžně
- každých 5000 ms `readRssi()` → `Connected.copy(rssi = …)`;
- hlídání dat: když poslední Stav přišel před víc než 15000 ms, zavolá se `client.close()`;
- čekání na `onLinkLost`.

**Notifikace a zápisy:**
- `onStatus`: `decodeStatus` → `_status.value = ReceivedStatus(it, time.nowMs(), wallClock())`, nebo `Log.w("Domek", "Neplatný Stav (N B)")`;
- `onSettings`: obdobně do `_settings`;
- zápisy jen v `Connected`, jinak `NotConnected`. Výsledek se převede: `Success` → `Ok`, `Failure(s)` → `Rejected(s)`, `Timeout` → `Timeout`, `NotConnected` a `StartFailed` → `NotConnected`.

**Ovládání a život zdroje:**
- `start()` zaregistruje `BluetoothAdapter.ACTION_STATE_CHANGED` přes `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)`. `STATE_ON` probudí smyčku, `STATE_TURNING_OFF` zavře klienta. Pak spustí smyčku.
- `stop()` zastaví hledání, zavře klienta, odregistruje receiver, zruší smyčku a nastaví `Idle`.
- `disconnectByUser()` nastaví `userDisconnected = true` a zavře klienta.
- `connectByUser()` nastaví `userDisconnected = false`, smaže `knownAddress` (nové hledání) a probudí smyčku.

- [ ] **Krok 3: Ověřit build a testy**: `./gradlew assembleDebug testDebugUnitTest` skončí `BUILD SUCCESSFUL`. Na zařízení se ověří v úkolu 21.
- [ ] **Krok 4: Commit**: `feat: BLE klient a zdroj dat s frontou, znovupřipojením a hlídáním`

---

### Úkol 9: Historie grafu

**Soubory:**
- Vytvořit: `viewmodel/ChartHistory.kt`
- Test: `viewmodel/ChartHistoryTest.kt`

**Rozhraní:**
- Používá: `ReceivedStatus` (úkol 4).
- Produkuje:

```kotlin
data class ChartSample(val atMs: Long, val wallTime: LocalDateTime, val inside: Double?, val outside: Double?, val target: Double?, val heating: Boolean, val cooling: Boolean)
enum class ChartWindow(val label: String, val durationMs: Long?) { FIVE_MIN("5 min", 300_000), FIFTEEN_MIN("15 min", 900_000), ALL("Vše", null) }
class ChartHistory(maxAgeMs: Long = 21_600_000) { val samples: List<ChartSample>; fun add(sample: ChartSample); fun clear() }   // add zahodí vzorky starší než maxAgeMs vůči novému
fun ReceivedStatus.toChartSample(): ChartSample
fun List<ChartSample>.inWindow(nowMs: Long, window: ChartWindow): List<ChartSample>
fun downsample(samples: List<ChartSample>, maxPoints: Int): List<ChartSample>   // časové koše; z každého první a extrémy `inside`
fun segments(samples: List<ChartSample>, value: (ChartSample) -> Double?, gapMs: Long = 5_000): List<List<ChartSample>>
data class Band(val fromMs: Long, val toMs: Long, val heating: Boolean)
fun bands(samples: List<ChartSample>, gapMs: Long = 5_000): List<Band>   // konec pruhu = čas dalšího vzorku, u posledního jeho čas
fun temperatureRange(samples: List<ChartSample>): ClosedFloatingPointRange<Double>   // min/max ze tří řad ±0.5, rozpětí ≥ 2 (rozšíření kolem středu), bez dat 18.0..26.0
```

- [ ] **Krok 1: Napsat padající testy** (`fun s(sec: Int, inside: Double? = 22.0, heating: Boolean = false, cooling: Boolean = false, outside: Double? = 21.0, target: Double? = 23.0)`)
  - `dropsSamplesOlderThanSixHours`: `add(s(0))` a `add(s(6 * 3600 + 1))` nechají jen jeden vzorek.
  - `windowKeepsRecentSamples`: pro `(0..600).map { s(it) }` vrátí `.inWindow(600_000, FIVE_MIN).first().atMs` hodnotu `300_000`. Okno `ALL` vrátí všechny vzorky.
  - `downsampleKeepsBoundsAndExtremes`: 10 000 vzorků se špičkou `inside = 30.0` na indexu 5000. `downsample(…, 400)` vrátí nejvýš 400 vzorků, zachová první a poslední a obsahuje špičku.
  - `segmentsSplitAtGapsAndNulls`: pro `[s(0), s(1), s(10), s(11, inside = null), s(12)]` vrátí `segments(…) { it.inside }` časy `[[0, 1000], [10000], [12000]]`.
  - `bandsMergeConsecutiveSamples`: `[s(0, heating), s(1, heating), s(2), s(3, cooling = true), s(4, cooling = true)]` dá `[Band(0, 2000, true), Band(3000, 4000, false)]`.
  - `temperatureRangeHasMarginAndMinimumSpan`: pro `s(0, inside = 22.0, outside = 22.2, target = 22.1)` vyjde `21.1..23.1`.
  - `temperatureRangeWithoutData` (Review Focus 5): prázdný seznam i vzorky se samými null dají `18.0..26.0`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.viewmodel.ChartHistoryTest"`.
- [ ] **Krok 3: Implementovat.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: historie a výpočty grafu teplot`

---

### Úkol 10: ViewModel – stav a zdroje

**Soubory:**
- Vytvořit: `viewmodel/HouseUiState.kt`, `viewmodel/HouseActions.kt`, `viewmodel/HouseViewModel.kt`
- Test: `testing/FakeHouseDataSource.kt`, `viewmodel/HouseViewModelTest.kt`

**Rozhraní:**
- Používá: úkoly 2–9.
- Produkuje:

```kotlin
data class LightDraft(val color: Rgb? = null, val brightness: Int? = null, val setAtMs: Long)
data class HouseUiState(
    val sourceKind: SourceKind = SourceKind.BLUETOOTH, val connection: ConnectionState = ConnectionState.Idle,
    val received: ReceivedStatus? = null, val settings: HouseSettings? = null,
    val drafts: Map<SettingKey, JsonPrimitive> = emptyMap(), val pendingKeys: Set<SettingKey> = emptySet(),
    val lightDraft: LightDraft? = null, val chart: List<ChartSample> = emptyList(), val chartWindow: ChartWindow = ChartWindow.ALL,
    val lastMotionAt: LocalDateTime? = null, val savedDayStarts: DayStarts? = null,
    val nowMs: Long = 0, val phoneNow: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0))
val HouseUiState.status: HouseStatus?
val HouseUiState.statusAgeMs: Long?          // nowMs − received.receivedAtMs
val HouseUiState.isStale: Boolean            // statusAgeMs > 3000
val HouseUiState.controlsEnabled: Boolean    // settings != null && (connection is Connected || connection == Simulated)
fun HouseUiState.settingValue(key: SettingKey): JsonPrimitive?   // draft, jinak Nastavení
fun HouseUiState.number(key: SettingKey): Double?
val HouseUiState.dayStarts: DayStarts?       // ze settingValue tří začátků
val HouseUiState.piTime: LocalDateTime?      // parsePiTime(status.time)
val HouseUiState.clockDiffSeconds: Long?     // piTime − received.receivedAtWall (v sekundách)
val HouseUiState.shownLight: LightState?     // status.light; lightDraft ho přepíše a zapne ručně
interface HouseActions {
    fun selectSource(kind: SourceKind); fun changeSetting(key: SettingKey, value: Double); fun setMode(mode: ThermostatMode)
    fun setLightOnMotion(enabled: Boolean); fun setDayStart(part: DayPart, time: LocalTime); fun setDayStartNow(part: DayPart)
    fun restoreDayStarts(); fun lightAction(action: LightAction); fun setLightColor(color: Rgb); fun setLightBrightness(percent: Int)
    fun syncClock(); fun setChartWindow(window: ChartWindow); fun chooseDevice(address: String); fun disconnect(); fun connect()
    fun retryConnection(); fun simulation(action: SimulationAction) }
class HouseViewModel(createSource: (SourceKind, CoroutineScope) -> HouseDataSource, time: TimeSource, wallClock: () -> LocalDateTime) : ViewModel(), HouseActions {
    val uiState: StateFlow<HouseUiState>
    val messages: Flow<String>               // Channel(BUFFERED).receiveAsFlow()
    companion object { fun factory(context: Context): ViewModelProvider.Factory } }
```

**Chování v tomto úkolu:**
- **Start:** v `init` se vytvoří a spustí zdroj BLUETOOTH a sbírají se jeho `connection`, `status` a `settings`.
- **Tik:** každých 500 ms obnoví `nowMs` a `phoneNow`.
- **Nový Stav:**
  - zapíše se do `ChartHistory` a `chart`;
  - při přechodu `motion` z false na true (nebo když je true v prvním Stavu) se do `lastMotionAt` uloží `receivedAtWall`.
- **`selectSource`:** zastaví starý zdroj, vymaže `received`, `settings`, graf, drafty a `savedDayStarts`, pak vytvoří a spustí nový zdroj.
- **Předávání akcí:**
  - `chooseDevice`, `disconnect`, `connect` a `retryConnection` jdou na `BleControls` (když zdroj tohle rozhraní implementuje);
  - `simulation` jde na `SimulationControls`;
  - `setChartWindow` jen změní stav.
- **Zápisové akce** (`changeSetting`, `setMode`, `setLightOnMotion`, `setDayStart*`, `restoreDayStarts`, `lightAction`, `setLight*`, `syncClock`) mají zatím prázdné tělo. Doplní je úkol 11.
- **`onCleared()`:** `source.stop()`.
- **`factory`:**
  - `createSource` podle druhu vytvoří `BleHouseDataSource(context.applicationContext, scope, time, LocalDateTime::now)`, nebo `SimulatedHouseDataSource(scope, time, LocalDateTime::now)`;
  - `time = TimeSource { SystemClock.elapsedRealtime() }`.

**Testovací pomocník `FakeHouseDataSource`** implementuje `HouseDataSource`, `BleControls` a `SimulationControls`:
- `MutableStateFlow` pro tři toky;
- záznamy `started`, `stopped`, `writtenSettings: MutableList<List<SettingChange>>`, `sentCommands`, `chosen`, `controlCalls` a `simActions`;
- nastavitelné `nextWriteResult` a `nextCommandResult` (výchozí `Ok`);
- volitelnou `commandGate: CompletableDeferred<Unit>?`, na které první `sendCommand` počká, a pak ji vynuluje.

- [ ] **Krok 1: Napsat padající testy**

Nastavení: `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`, `time = { testScheduler.currentTime }` a `wallClock = { LocalDateTime.of(2026, 10, 20, 10, 2, 15).plusNanos(testScheduler.currentTime * 1_000_000) }`. Na konci každého testu `vm.viewModelScope.cancel()`.

- `startsBluetoothSourceOnCreate`: `fake.started` a `sourceKind == BLUETOOTH`.
- `staleAfterThreeSeconds`: Stav s `receivedAtMs = 0`. Po `advanceTimeBy(2_900)` a `runCurrent()` data ještě nejsou stará. Po dalších 300 ms jsou stará a `statusAgeMs == 3_200L`.
- `everyStatusGoesToChart`: tři Stavy s různými časy dají `chart.size == 3`.
- `switchingSourceStopsOldClearsChartStartsNew`: pro dva fakey `selectSource(SIMULATION)` zastaví první, spustí druhý, `chart` a `received` jsou prázdné a `sourceKind == SIMULATION`.
- `lastMotionOnRisingEdge`:
  - Stavy `motion=false`, pak `true` (přijatý v T1), pak zase `true` (v T2);
  - výsledek je `lastMotionAt == T1`.
- `clockDifferenceFromCasAndPhoneTime`: `cas "2026-10-20T10:00:00"` přijatý v `2026-10-20T10:02:15` dá `clockDiffSeconds == -135`.
- `controlsEnabledOnlyWithSettingsAndConnection`:
  - `Connected` s Nastavením: `true`;
  - `Connected` bez Nastavení: `false`;
  - `WaitingToReconnect` s Nastavením: `false`.
- `bleAndSimulationActionsAreForwarded`:
  - `chooseDevice("AA")` nastaví `fake.chosen == "AA"`;
  - `disconnect`, `connect` a `retryConnection` se zaznamenají;
  - `simulation(MOTION)` se zaznamená.

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.viewmodel.HouseViewModelTest"`.
- [ ] **Krok 3: Implementovat.**
- [ ] **Krok 4: Spustit testy**: musí skončit `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: HouseViewModel – stav, stáří dat, graf a přepínání zdroje`

---

### Úkol 11: ViewModel – zápisy a hlášky

**Soubory:**
- Vytvořit: `viewmodel/Messages.kt`
- Upravit: `viewmodel/HouseViewModel.kt`
- Test: `viewmodel/MessagesTest.kt`, `viewmodel/HouseViewModelWritesTest.kt`

**Rozhraní:**
- Používá: úkoly 2–4 a 10.
- Produkuje:

```kotlin
fun describeValue(key: SettingKey, value: JsonPrimitive): String      // "23,5 °C", "30 min", "19:00", "Ruční", "zapnuto"/"vypnuto"
fun settingsWriteMessage(changes: List<SettingChange>, current: HouseSettings?, result: WriteResult): String?   // Ok → null
fun commandMessage(command: HouseCommand, result: WriteResult): String?
```

**Texty hlášek (spec §7, přesně):**
- `NotConnected`: „Domek není připojený, změna se neodeslala.“
- `Timeout`: „Raspberry neodpovědělo. Změna se možná neprovedla.“
- `Rejected` Nastavení:
  - shodné začátky: „Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo.“ Po sloučení změn do `current` se hledá první změněný začátek, který se shoduje s jiným;
  - hodnota mimo `key.min..key.max`: „Raspberry změnu odmítlo: denní cíl 36,0 °C je mimo rozsah 5–35 °C. Nic se nezměnilo.“;
  - `LIGHT_ON_MOTION`: „Raspberry změnu odmítlo: volbu rozsvěcení při pohybu nezná, nejspíš má starší program. Nic se nezměnilo.“;
  - jinak: „Raspberry změnu odmítlo: denní cíl 23,5 °C (kód 128). Nic se nezměnilo.“ Víc změn se spojí čárkou s mezerou.
- `Rejected` příkaz: „Raspberry odmítlo příkaz přepnout světlo (kód 128).“

V rozsahu se používá pomlčka „–“ a před jednotkou `\u00A0`.

**Chování zápisů ve ViewModelu:**
- **`changeSetting(key, value)`:**
  - hodnota se ořízne na `key.min..key.max`;
  - hned se nastaví `drafts[key]` a `pendingKeys += key`;
  - zápis `listOf(key.change(value))` odejde 600 ms po poslední změně klíče.
- **`setMode`, `setLightOnMotion`, `setDayStart`:** totéž, ale zápis jde hned.
- **Souběh zápisů téhož klíče:** když zápis klíče běží a draft se mezitím změní, po jeho úspěchu se pošle znovu poslední hodnota.
- **Po `Ok`:** draft zůstane, dokud `settings.valueOf(key)` nebude rovno draftu, nejdéle 3000 ms. Pak draft i pending zmizí.
- **Po jiném výsledku:** draft i pending hned zmizí a do `messages` jde `settingsWriteMessage(…)`.
- **`setDayStart(part, time)`:** když `savedDayStarts == null`, uloží se do něj `dayStartsOf(settings)` (bez draftů). Pak se pošle `formatHhMm(time)`.
- **`setDayStartNow(part)`:** jen pokud je Stav a nejsou stará data. Pošle `setDayStart(part, piTime!!.toLocalTime())` oříznutý na minuty.
- **`restoreDayStarts()`:** pošle tři změny jedním zápisem. Po `Ok` se `savedDayStarts = null`.
- **`lightAction(a)`:** `sendCommand(Light(a))`. Hláška přes `commandMessage`.
- **`setLightColor` a `setLightBrightness`:**
  - jas se ořízne na 1..100;
  - nastaví se `lightDraft`;
  - požadavky se slučují do jednoho čekajícího: nový jas přepíše jen jas, nová barva jen barvu. Jediný konzument si sloučený požadavek vezme, vyprázdní ho a pošle `LightLook` jen se složkami, které v něm jsou. Během odesílání se tak mezilehlé hodnoty zahodí, ale barva se neztratí kvůli jasu;
  - `lightDraft` zmizí, když Stav ukáže stejnou barvu nebo jas, nejpozději 3000 ms po poslední změně.
- **`syncClock()`:** `sendCommand(SetClock(wallClock().withNano(0)))`.

- [ ] **Krok 1: Napsat padající testy**

`MessagesTest`:
- `describeValue(TARGET_DAY, JsonPrimitive(23.5)) == "23,5\u00A0°C"`;
- `describeValue(NOBODY_HOME_AFTER, JsonPrimitive(30)) == "30\u00A0min"`;
- `describeValue(MODE, JsonPrimitive("rucni")) == "Ruční"`;
- `describeValue(LIGHT_ON_MOTION, JsonPrimitive(false)) == "vypnuto"`;
- všechny texty z výčtu výše:
  - shoda začátků: `NIGHT_START` 19:00 proti `DEFAULTS`;
  - mimo rozsah: `TARGET_DAY` 36.0;
  - `LIGHT_ON_MOTION`;
  - obecné odmítnutí: `TARGET_DAY` 23.5 s kódem 128;
  - `NotConnected` a `Timeout`;
  - příkaz `TOGGLE` s kódem 128;
  - `Ok` dá `null`.

`HouseViewModelWritesTest` (fake: `connection = Connected("AA", 517, -60)`, `settings = DEFAULTS`):
- `stepperChangesAreDebouncedIntoOneWrite`:
  - postupně `changeSetting(TARGET_DAY, 23.5)`, po 300 ms `24.0`, po dalších 300 ms `24.5`;
  - po dalších `advanceTimeBy(599)` není žádný zápis;
  - po `advanceTimeBy(2)` je `writtenSettings == listOf(listOf(TARGET_DAY.change(24.5)))`.
- `draftShownUntilSettingsConfirm`:
  - hned po změně je `number(TARGET_DAY) == 23.5`;
  - po zápisu `Ok` platí pořád 23.5;
  - po `fake.settings.value = DEFAULTS.copy(targetDay = 23.5)` jsou `drafts` i `pendingKeys` prázdné.
- `draftDroppedThreeSecondsAfterUnconfirmedWrite`: Nastavení zůstane 23.0. Za 3 s po zápisu je `number(TARGET_DAY) == 23.0`.
- `rejectedWriteRevertsAndExplains`:
  - `nextWriteResult = Rejected(128)`, `setDayStart(NIGHT, 19:00)`;
  - zpráva je „Raspberry změnu odmítlo: začátek noci 19:00 je stejný jako začátek večera. Nic se nezměnilo.“;
  - `settingValue(NIGHT_START) == JsonPrimitive("22:00")`.
- `rapidTapsWhileDisconnectedGiveOneMessage` (Review Focus 4):
  - `nextWriteResult = NotConnected` a pět změn během 500 ms;
  - po 700 ms je `writtenSettings.size == 1`, `messages == ["Domek není připojený, změna se neodeslala."]` a `drafts` jsou prázdné.
- `brightnessDragIsConflated`:
  - nastaví se `commandGate`;
  - `setLightBrightness(10)`, `runCurrent()`, pak `20`, `30` a `40`;
  - po otevření brány a `runCurrent()` je `sentCommands == [LightLook(brightness = 10), LightLook(brightness = 40)]`.
- `colorAndBrightnessQueuedTogetherAreMerged`:
  - nastaví se `commandGate`;
  - `setLightBrightness(10)`, `runCurrent()`, pak `setLightColor(Rgb(0, 0, 255))` a `setLightBrightness(40)`;
  - po otevření brány je `sentCommands == [LightLook(brightness = 10), LightLook(Rgb(0, 0, 255), 40)]`.
- `dayStartNowUsesRaspberryTime` (Review Focus 3):
  - čerstvý Stav s `cas "2026-10-20T10:05:42"`;
  - `setDayStartNow(NIGHT)` zapíše `[NIGHT_START.change("10:05")]` a `savedDayStarts == dayStartsOf(DEFAULTS)`.
- `dayStartNowIgnoredWhenStale`: když je Stav 4 s starý, nic se nezapíše.
- `restoreWritesOriginalStartsAtOnce`:
  - po „Teď“ a změně Nastavení na noc od 10:05 se zavolá `restoreDayStarts()`;
  - poslední zápis je `[DAY_START "07:00", EVENING_START "19:00", NIGHT_START "22:00"]` a `savedDayStarts == null`.
- `syncClockSendsPhoneTimeWithSeconds`: při `wallClock 2026-10-20T14:03:00.250` je `sentCommands.last() == SetClock(LocalDateTime.of(2026, 10, 20, 14, 3, 0))`.
- `commandRejectionExplains`: `nextCommandResult = Rejected(128)`, `lightAction(TOGGLE)` dá zprávu „Raspberry odmítlo příkaz přepnout světlo (kód 128).“

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.viewmodel.*"`.
- [ ] **Krok 3: Implementovat `Messages.kt` a zápisové akce.**
- [ ] **Krok 4: Spustit všechny testy**: `./gradlew testDebugUnitTest` skončí `BUILD SUCCESSFUL`.
- [ ] **Krok 5: Commit**: `feat: zápisy s drafty, slučováním a srozumitelnými hláškami`

---

### Úkol 12: Téma, komponenty a screenshoty

**Soubory:**
- Vytvořit: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/components/StatusPill.kt`, `ui/components/ValueStepper.kt`, `ui/components/Cards.kt`, `ui/components/TwoPane.kt`
- Test: `ui/components/StatusPillTest.kt`, `screenshots/ScreenshotSupport.kt`, `screenshots/UiFixtures.kt`, `screenshots/ComponentsScreenshotTest.kt`

**Rozhraní:**
- Používá: `HouseUiState` a odvozené hodnoty (úkol 10), `stepValue` a formátování (úkol 3).
- Produkuje:

```kotlin
@Composable fun DomekTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)   // bez dynamických barev
data class DomekColors(val heat: Color, val heatContainer: Color, val cool: Color, val coolContainer: Color,
    val night: Color, val day: Color, val evening: Color, val okContainer: Color, val onOkContainer: Color,
    val staleContainer: Color, val onStaleContainer: Color, val connectingContainer: Color, val onConnectingContainer: Color,
    val simulationContainer: Color, val onSimulationContainer: Color, val neutralContainer: Color, val onNeutralContainer: Color,
    val chartOutside: Color, val chartTarget: Color)
val LocalDomekColors: ProvidableCompositionLocal<DomekColors>
fun Modifier.staleDim(stale: Boolean): Modifier   // stará data: hodnoty zešednou (alpha 0.45), spec §6.5
enum class PillTone { OK, NEUTRAL, CONNECTING, STALE, ERROR, SIMULATION }
data class PillModel(val text: String, val tone: PillTone)
fun pillFor(state: HouseUiState): PillModel
@Composable fun StatusPill(model: PillModel, onClick: () -> Unit)
enum class StepperSize { LARGE, COMPACT }
@Composable fun ValueStepper(value: Double, key: SettingKey, format: (Double) -> String, onChange: (Double) -> Unit,
    enabled: Boolean, pending: Boolean, size: StepperSize = StepperSize.COMPACT, label: String? = null)
@Composable fun DomekCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit)
enum class BannerTone { STALE, ERROR, WARNING }
@Composable fun Banner(tone: BannerTone, title: String, text: String, actionLabel: String? = null, onAction: () -> Unit = {})
@Composable fun TwoPane(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit)
```

**Barvy světlého motivu** (z náhledů, spec §9):
- **Plochy a text:** background `#F3F6F7`, surface `#FFFFFF`, surfaceContainer `#EAF0F2`, onSurface `#161C1F`, onSurfaceVariant `#59656B`, outline `#D3DCE0`.
- **Hlavní barva:** primary `#0D6A70`, primaryContainer `#CBEDED`, onPrimaryContainer `#002F33`.
- **Topení a chlazení:** heat `#DF522E` / container `#FFE3D9`, cool `#2A77E2` / container `#DCE9FF`.
- **Denní doby:** night `#3E4090`, day `#F5C451`, evening `#EF8A4C`.
- **Štítky (container / on):**

  | Štítek | container | on |
  |---|---|---|
  | ok | `#DCF2E4` | `#116137` |
  | stale | `#FFE7C2` | `#7F4700` |
  | connecting | `#D9ECF7` | `#0B4F75` |
  | error | `#FFDAD6` | `#8C0009` |
  | simulace | `#ECE0FF` | `#4B3487` |
  | neutral | `#E3E9EC` | `#4A565C` |

- **Graf:** venku `#9AA4AA`, cíl `#2B3237`.

Tmavé odstíny volí implementace (spec §12).

**Texty a priority `pillFor`** (spec §6.0, `N` s `\u00A0s`):

| Stav spojení | Text | Tón |
|---|---|---|
| NoBluetooth | „Bez Bluetooth“ | ERROR |
| PermissionsMissing | „Chybí oprávnění“ | ERROR |
| BluetoothOff | „Bluetooth vypnutý“ | ERROR |
| LocationOff | „Poloha vypnutá“ | ERROR |
| MissingService | „Neznámé zařízení“ | ERROR |
| WaitingToReconnect | „Odpojeno · znovu za N s“ (N = strop((retryAtMs − nowMs)/1000), nejméně 0) | ERROR |
| DisconnectedByUser | „Odpojeno“ | ERROR |
| Idle, Scanning | „Hledám…“ | NEUTRAL |
| ChoosingDevice | „Vyber domek“ | NEUTRAL |
| Connecting, Preparing | „Připojuji…“ | CONNECTING |
| Connected | „Stará data · N s“ (N = stáří/1000), když jsou data stará, jinak „Připojeno“ | STALE, OK |
| Simulated | „Stará data · N s“ jako u Connected, jinak „Simulace“ | STALE, SIMULATION |

**`ValueStepper`:**
- tlačítka −/+ jsou kruhová, obrysová, 48 dp ve velikosti LARGE a 38 dp v COMPACT;
- klepnutí zavolá `onChange(stepValue(key, value, up))`;
- podržení opakuje po 400 ms každých 100 ms;
- klepnutí na číslo otevře `AlertDialog` s polem pro číslo, které přijme „23,5“ i „23.5“, ořízne hodnotu na rozsah a má tlačítka „Uložit“ a „Zrušit“;
- `pending` zobrazí indikátor průběhu 12 dp vedle čísla;
- `enabled = false` znamená 38% průhlednost a žádné klikání.

**`TwoPane`:**
- při `wide` je to `Row` se dvěma posuvnými sloupci v poměru 1 : 1.15;
- jinak jeden posuvný sloupec: nejdřív `left`, pak `right`;
- odsazení 12 dp, mezery 12 dp.

**Screenshoty (Robolectric):**
- `ScreenshotSupport.kt` obsahuje `const val PHONE = "w384dp-h832dp-xxhdpi"` a `const val PHONE_LANDSCAPE = "w832dp-h384dp-land-xxhdpi"`.
- `fun ComposeContentTestRule.saveScreenshot(name: String)` převede `onRoot().captureToImage().asAndroidBitmap()` na pixely a uloží je přes `javax.imageio.ImageIO` do `app/build/screenshots/<name>.png`.
- Třídy screenshotů mají `@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = PHONE)`.

**`UiFixtures.kt`:**
- `object NoActions : HouseActions` se samými prázdnými těly;
- `val DEMO_WALL = LocalDateTime.of(2026, 10, 20, 10, 2, 15)`;
- `fun demoStatus()` je `EXAMPLE.copy(time = "2026-10-20T10:00:03", insideTemp = 22.4, outsideTemp = 21.8, humidity = 45.0, pressure = 1013.2, lux = 320.0, target = 23.0, heating = true, light = LightState(true, listOf(100, 110, 255), 60, true))`;
- `fun demoChart(endMs: Long, endWall: LocalDateTime)` vrátí 300 vzorků po 1 s s příběhem z náhledu:
  - vnitřní teplota 22.0, za 60–120 s topí až na 25.0, kolem 25 drží do 180 s, za 180–260 s chladí na 21.0 a za 270–300 s topí na 22.4;
  - cíl je 22 v 0–60 s, 25 v 60–180 s, 21 v 180–270 s a 23 v 270–300 s;
  - venku 21.8 ± 0.05;
- `fun demoState()`:
  - `connection = Connected("B8:27:EB:4A:19:C2", 517, -61)` a `received = ReceivedStatus(demoStatus(), 300_000, DEMO_WALL)`;
  - `settings = DEFAULTS` a `chart = demoChart(300_000, DEMO_WALL)`;
  - `nowMs = 300_400`, `phoneNow = DEMO_WALL` a `lastMotionAt = DEMO_WALL.minusSeconds(40)`.

**Když Robolectric na JDK 25 selže** (chyba JVM nebo ASM, např. `Unsupported class file major version`), přidat do `app/build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
```

Když screenshoty nepůjdou ani potom, úkol skončí jako BLOCKED s chybovým výstupem. Kroky „screenshot“ v dalších úkolech se pak nahradí ruční kontrolou na telefonu v úkolu 21.

- [ ] **Krok 1: Napsat padající testy.** `StatusPillTest` má jednu kontrolu na každý řádek tabulky. `ComponentsScreenshotTest.components` vykreslí v `DomekTheme`:
  - všechny tóny `StatusPill`;
  - `ValueStepper` v obou velikostech (jeden s `pending`, jeden zakázaný);
  - `Banner` ve všech tónech.

  Výsledek uloží jako `components`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.ui.*" --tests "cz.vsb.mitr.domek.screenshots.*"`.
- [ ] **Krok 3: Implementovat soubory z bloku Rozhraní.**
- [ ] **Krok 4: Spustit testy a prohlédnout `app/build/screenshots/components.png`** (Read tool). Všechny prvky musí být vidět, nic nesmí být oříznuté a barvy musí odpovídat tabulce.
- [ ] **Krok 5: Commit**: `feat: téma, komponenty a screenshoty z Robolectricu`

---

### Úkol 13: Kostra aplikace a aktivita

**Soubory:**
- Vytvořit: `ui/DomekApp.kt`
- Upravit: `MainActivity.kt`
- Test: `screenshots/AppScreenshotTest.kt`

**Rozhraní:**
- Používá: úkoly 10 a 12.
- Produkuje:

```kotlin
enum class DomekTab(val title: String) { OVERVIEW("Přehled"), THERMOSTAT("Termostat"), LIGHT("Světlo"), SETTINGS("Nastavení") }
data class SystemActions(val requestPermissions: () -> Unit, val enableBluetooth: () -> Unit, val openAppSettings: () -> Unit,
                         val openLocationSettings: () -> Unit, val permissionsPermanentlyDenied: Boolean)
@Composable fun DomekApp(state: HouseUiState, actions: HouseActions, system: SystemActions, messages: Flow<String>)
```

**`DomekApp`:**
- `BoxWithConstraints`, kde `wide = maxWidth >= 600.dp`.
- `Scaffold` s `TopAppBar`. Titulek je „Chytrý domek“ na Přehledu, jinak `tab.title`. Vpravo je `StatusPill(pillFor(state))`, jehož klepnutí přepne na Nastavení.
- Při `!wide` je dole `NavigationBar`, při `wide` vlevo `NavigationRail`. Ikony (Rounded) jsou `SpaceDashboard`, `Thermostat`, `Lightbulb` a `Settings`.
- Záložka je v `rememberSaveable`.
- `SnackbarHost`: `LaunchedEffect` sbírá `messages` a volá `showSnackbar(message, actionLabel = "OK", duration = Long)`.
- Obsah záložek je zatím `Text(tab.title)`. Úkoly 14–19 ho nahradí obrazovkami.

**`MainActivity`:**
- `enableEdgeToEdge()` a `window.addFlags(FLAG_KEEP_SCREEN_ON)`;
- `viewModels { HouseViewModel.factory(applicationContext) }`;
- `registerForActivityResult(RequestMultiplePermissions())`:
  - po výsledku se zavolá `retryConnection()`;
  - když je nějaké oprávnění zamítnuté a `shouldShowRequestPermissionRationale` je `false`, `permissionsPermanentlyDenied = true` (stav v aktivitě přes `mutableStateOf`);
- `StartActivityForResult` s `BluetoothAdapter.ACTION_REQUEST_ENABLE`;
- otevírání nastavení přes `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (s `package:`) a `Settings.ACTION_LOCATION_SOURCE_SETTINGS`;
- `onResume` zavolá `retryConnection()`;
- obsah je `DomekTheme { DomekApp(uiState via collectAsStateWithLifecycle, vm, system, vm.messages) }`.

- [ ] **Krok 1: Napsat padající screenshot test.** Do `UiFixtures.kt` doplnit `val NoSystem = SystemActions({}, {}, {}, {}, false)`. `appPortrait` vykreslí `DomekApp(demoState(), NoActions, NoSystem, emptyFlow())` a uloží ho jako `app-prehled`. `appLandscape` totéž s `qualifiers = PHONE_LANDSCAPE` uloží jako `app-sirka`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.AppScreenshotTest"`.
- [ ] **Krok 3: Implementovat `DomekApp` a `MainActivity`.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.**
  - Na výšku: nahoře „Chytrý domek“ se zeleným štítkem „Připojeno“, dole čtyři záložky a vybraný Přehled.
  - Na šířku: boční lišta místo dolní.
  - Pak spustit `./gradlew assembleDebug`.
- [ ] **Krok 5: Commit**: `feat: kostra aplikace, navigace a napojení aktivity`

---

### Úkol 14: Obrazovky spojení a výběr domku

**Soubory:**
- Vytvořit: `ui/connection/ConnectionScreen.kt`, `ui/connection/DevicePicker.kt`
- Upravit: `ui/DomekApp.kt`
- Test: `screenshots/ConnectionScreenshotTest.kt`

**Rozhraní:**
- Používá: úkoly 4, 12 a 13.
- Produkuje: `@Composable fun ConnectionScreen(state: HouseUiState, actions: HouseActions, system: SystemActions)` a `@Composable fun DevicePickerSheet(devices: List<FoundDevice>, onChoose: (String) -> Unit)`.

**Texty podle stavu** (spec §6.5). Kromě NoBluetooth mají všechny stavy i obrysové tlačítko „Spustit simulaci“ → `selectSource(SIMULATION)`.

| Stav | Ikona / prvky | Nadpis | Text | Tlačítko |
|---|---|---|---|---|
| Idle, Scanning | pulzující `BluetoothSearching`, `LinearProgressIndicator` | „Hledám domek „Termostat““ | „Raspberry musí být zapnuté a v dosahu. Párování není potřeba.“ | — |
| ChoosingDevice | jako hledání + `DevicePickerSheet` | | | |
| Connecting, Preparing | | „Připojuji se k domku…“ | „Pokus č. N“ jen u Connecting | — |
| WaitingToReconnect | | „Spojení se přerušilo“ | „Další pokus za N s.“ | — |
| DisconnectedByUser | | „Odpojeno“ | — | „Připojit“ → `connect()` |
| PermissionsMissing | | „Aplikace potřebuje přístup k Bluetooth“ | „Bez něj nenajde domek v okolí.“ | „Povolit“ |
| PermissionsMissing, trvale zamítnuto | | jako výše | „Oprávnění je zamítnuté. Povol ho v nastavení aplikace.“ | „Otevřít nastavení“ |
| BluetoothOff | | „Bluetooth je vypnutý“ | — | „Zapnout Bluetooth“ |
| LocationOff | | „Zapni polohu“ | „Android 11 a starší potřebuje zapnutou polohu, aby našel Bluetooth zařízení.“ | „Otevřít nastavení polohy“ |
| NoBluetooth | | „Telefon nemá Bluetooth LE“ | „Aplikace může běžet jen v simulaci.“ | „Spustit simulaci“ |
| MissingService | | „Tohle zařízení není domek“ | „Zařízení „Termostat“ nemá službu Domek. Zkontroluj program na Raspberry.“ | „Zkusit znovu“ → `retryConnection()` |

`DevicePickerSheet` je `ModalBottomSheet` s nadpisem „V dosahu je víc domků“ a řádky „Termostat · AA:BB:CC:DD:EE:FF · signál −61 dBm“. Klepnutí na řádek zavolá `chooseDevice`.

**Napojení do `DomekApp`:**
- Přehled, Termostat a Světlo ukazují `ConnectionScreen`, dokud je `state.received == null` (spec §6.0). Nastavení se zobrazuje vždy.
- `DevicePickerSheet` otevírá přímo `DomekApp`, a to vždy, když je `connection` ChoosingDevice, bez ohledu na záložku a na to, jestli už jsou data. Po „Připojit“ totiž můžou zůstat stará data.

- [ ] **Krok 1: Napsat padající screenshot testy.** Každý stav se uloží jako `spojeni-<stav>`: `scanning`, `choosing` (dvě zařízení), `permissions`, `permissions-denied`, `bt-off`, `waiting`, `missing-service`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.ConnectionScreenshotTest"`.
- [ ] **Krok 3: Implementovat.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.** Texty musí odpovídat tabulce a obrazovka hledání náhledu „Hledání domku“.
- [ ] **Krok 5: Commit**: `feat: obrazovky spojení a výběr domku`

---

### Úkol 15: Graf teplot

**Soubory:**
- Vytvořit: `ui/chart/TemperatureChart.kt`
- Test: `screenshots/ChartScreenshotTest.kt`

**Rozhraní:**
- Používá: úkol 9 a `LocalDomekColors` (úkol 12).
- Produkuje: `@Composable fun TemperatureChartCard(samples: List<ChartSample>, window: ChartWindow, nowMs: Long, onWindowChange: (ChartWindow) -> Unit, modifier: Modifier = Modifier)`.

**Karta:**
- nahoře titulek „Teploty“ a segmentový přepínač „5 min · 15 min · Vše“;
- `Canvas` vysoký 180 dp;
- pod ním legenda: Uvnitř, Venku, Cíl, Topí, Chladí.

**Osa X:**
- začíná u `nowMs − durationMs`, v okně Vše u prvního vzorku, a končí v `nowMs`;
- popisky jsou tři: začátek, střed a konec, ve formátu HH:MM času telefonu.

**Kreslení:**
- vzorky projdou `inWindow`, pak `downsample(…, maxPoints = 2 × šířka v px)`;
- `bands` se kreslí jako pruhy přes celou výšku (heatContainer a coolContainer);
- vodorovná mřížka je po 1 °C, po 2 °C při rozpětí nad 6 °C, popisky „24°“;
- řady se kreslí po `segments`:
  - uvnitř barvou primary, 2.5 dp, s tečkou na posledním bodě;
  - venku barvou chartOutside, 1.5 dp;
  - cíl jako schody barvou chartTarget, 1.5 dp, `dashPathEffect(8f, 6f)`;
- bez vzorků je místo grafu text „Graf se začne kreslit s prvními daty.“

- [ ] **Krok 1: Napsat padající screenshot testy.**
  - `chartDemo`: vykreslí `demoChart`, okno Vše, a uloží ho jako `graf-demo`;
  - `chartEmpty`: bez vzorků, uloží jako `graf-prazdny`;
  - `chartAllNull` (Review Focus 5): 60 vzorků se samými null nespadne a uloží se jako `graf-null`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.ChartScreenshotTest"`.
- [ ] **Krok 3: Implementovat.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.**
  - `graf-demo` odpovídá grafu z náhledu: pruhy topení a chlazení, schody cíle, šedá venkovní čára.
  - `graf-prazdny` ukazuje text místo grafu.
- [ ] **Krok 5: Commit**: `feat: graf teplot`

---

### Úkol 16: Přehled

**Soubory:**
- Vytvořit: `ui/overview/OverviewScreen.kt`, `ui/overview/HeroCard.kt`, `ui/overview/SensorTiles.kt`, `ui/overview/OverviewBanners.kt`, `ui/overview/SimulationCard.kt`
- Upravit: `ui/DomekApp.kt`
- Test: `screenshots/OverviewScreenshotTest.kt`

**Rozhraní:**
- Používá: úkoly 3, 9–15.
- Produkuje: `@Composable fun OverviewScreen(state: HouseUiState, actions: HouseActions, wide: Boolean, onOpenLight: () -> Unit)`.

**Rozvržení přes `TwoPane`:**
- `left`: bannery, `HeroCard`, `SensorTiles` a `SimulationCard` (jen v simulaci);
- `right`: `TemperatureChartCard`.

Na úzké obrazovce tak graf vyjde mezi kartu termostatu a dlaždice, ve stejném pořadí jako na náhledu.

Při starých datech dostanou `HeroCard`, `SensorTiles` i graf `Modifier.staleDim(isStale)`. Totéž platí pro souhrn na Termostatu a náhled na Světle (úkoly 17 a 18).

**Bannery** (spec §6.1 a §6.5), seřazené stará data → chyby → hodiny:
- **Stará data:** nadpis „Data jsou ‹formatDuration(stáří/1000)› stará“. Text při Connected nebo Simulated je „Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti.“, jinak „Spojení se přerušilo, znovu se připojuji…“.
- **Chyby:** pro každý kód Banner ERROR s `piErrorText` a `piErrorDetail`.
- **Hodiny:** při |`clockDiffSeconds`| > 120 Banner WARNING s `clockDiffText`, textem „Na hodinách závisí denní doba.“ a akcí „Srovnat“ → `syncClock()`.

**`HeroCard`:**
- **Horní řádek:** ikona úseku (`LightMode`, `WbTwilight`, `Bedtime`), „Den · 10:00“ (`DayPart.title` a HH:MM z `piTime`) a štítek „Režim Auto“ (`ThermostatMode.label`).
- **Uprostřed:** popisek „Uvnitř“, velká teplota (60 sp) a štítek: Topí (heat), Chladí (cool), V klidu (neutrální), nebo Vypnuto v režimu OFF.
- **Pozadí:** při topení svislý přechod heatContainer → surface, při chlazení coolContainer → surface.
- **„Nikdo doma“:** štítek „Nikdo doma · cíl −3,0 °C, platí 20,0 °C“ (snížení z Nastavení, platný cíl ze Stavu).
- **Krokové tlačítko:**
  - `slot = activeTargetSlot(status.mode, status.dayPart)`;
  - `ValueStepper(LARGE, value = number(slot.key) ?: status.target, key = slot.key, label = slot.label, enabled = controlsEnabled, pending = slot.key in pendingKeys)`;
  - v režimu OFF místo něj text „Termostat je vypnutý“ a tlačítko „Zapnout (Auto)“ → `setMode(AUTO)`.

**`SensorTiles`** (mřížka 3×2, spec §6.1 bod 5):
- **Venku, Vlhkost, Tlak:** hodnota, při null „—“ s podtitulem „chyba čidla“.
- **Osvětlení:** podtitul „šero · práh 50“ nebo „světlo · práh 50“ podle `prah_lux`, při null „—“ s „čidlo chybí“.
- **Pohyb:** „Ano“ (dlaždice zvýrazněná primaryContainer) nebo „Ne“, podtitul `formatAgo(phoneNow − lastMotionAt)`, nebo „zatím ne“.
- **Světlo:** tečka ve skutečné barvě (při zapnutém světle), `tileValue` a `tileDetail` z `lightTexts`. Klepnutí zavolá `onOpenLight`.

**`SimulationCard`:**
- nadpis „Ovládání simulace“, text „Nahrazuje zásahy do skutečného domku.“;
- `FlowRow` s pěti tlačítky `SimulationAction.label`;
- „Porucha BME280“ je `FilterChip` vybraný, když `errors` obsahuje `"bme280"`.

- [ ] **Krok 1: Napsat padající screenshot testy.** Každý stav se uloží jako `prehled-<stav>`:
  - `demo` (náhled 1);
  - `chladi`: cooling = true, inside 24.1, target 22.0;
  - `vypnuto`: mode OFF, target null;
  - `nikdo-doma`: nobodyHome, target 20.0;
  - `stara-data`: nowMs = received + 7 000;
  - `chyby` (Review Focus 5): errors `["bme280", "bh1750"]` a null hodnoty;
  - `hodiny`: cas o 3 h pozadu;
  - `simulace`: sourceKind SIMULATION, connection Simulated.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.OverviewScreenshotTest"`.
- [ ] **Krok 3: Implementovat a nahradit placeholder Přehledu v `DomekApp`.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.**
  - `prehled-demo` odpovídá náhledu 1: červeně tónovaná karta, Topí, „Denní cíl 23,0 °C“, graf a šest dlaždic.
  - Ostatní stavy ukazují správné bannery a texty.
- [ ] **Krok 5: Commit**: `feat: obrazovka Přehled`

---

### Úkol 17: Termostat

**Soubory:**
- Vytvořit: `ui/thermostat/ThermostatScreen.kt`
- Upravit: `ui/DomekApp.kt`
- Test: `screenshots/ThermostatScreenshotTest.kt`

**Rozhraní:**
- Produkuje: `@Composable fun ThermostatScreen(state: HouseUiState, actions: HouseActions, wide: Boolean)`.

**Rozvržení přes `TwoPane`** (spec §6.2): `left` je souhrn a režim, `right` cíle a „nikdo doma“.

**Souhrn:** „Uvnitř“, „Platný cíl“ (`status.target`) a štítek stavu (jako v HeroCard).

**Režim:**
- `SingleChoiceSegmentedButtonRow` Auto / Ruční / Vypnuto. Hodnota bere ohled na draft (`settingValue(MODE)`) a změna volá `setMode`.
- Vysvětlivky:
  - Auto: „Ve dne a večer drží denní cíl, v noci noční. Teď je ‹den/večer/noc›, takže platí ‹denní/noční›.“;
  - Ruční: „Drží ruční cíl bez ohledu na denní dobu.“;
  - Vypnuto: „Termostat netopí ani nechladí.“

**Cílové teploty:**
- řádky „Denní“ („den a večer“), „Noční“ („noc“) a „Ruční“ („režim Ruční“), každý s `ValueStepper` COMPACT;
- aktivní řádek (`activeTargetSlot(režim s draftem, status.dayPart)`) má pozadí primaryContainer a štítek „platí teď“;
- vysvětlivka: „5–35 °C po 0,5 °C. Podržením tlačítka se hodnota mění rychleji, klepnutím na číslo jde napsat přímo.“

**Nikdo doma:**
- stavový řádek: „Nikdo doma · cíl je snížený o 3,0 °C“, „Doma je někdo · poslední pohyb před 40 s“, nebo „Pohyb zatím nezaznamenán“;
- „Snížit cíl po“ („minutách bez pohybu“) s `NOBODY_HOME_AFTER` ve tvaru „30 min“;
- „Snížit o“ („0–10 °C“) s `NOBODY_HOME_SETBACK`.

- [ ] **Krok 1: Napsat padající screenshot testy.** Stavy se uloží jako `termostat-demo`, `termostat-rucni` (Nastavení `mode = MANUAL`) a `termostat-nikdo-doma`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.ThermostatScreenshotTest"`.
- [ ] **Krok 3: Implementovat a zapojit do `DomekApp`.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.** Musí odpovídat náhledu 2 a zvýrazněný musí být správný řádek.
- [ ] **Krok 5: Commit**: `feat: obrazovka Termostat`

---

### Úkol 18: Světlo

**Soubory:**
- Vytvořit: `ui/light/LightScreen.kt`, `ui/light/LightPresets.kt`, `ui/light/ColorDialog.kt`
- Upravit: `ui/DomekApp.kt`
- Test: `ui/light/LightPresetsTest.kt`, `screenshots/LightScreenshotTest.kt`

**Rozhraní:**
- Produkuje:

```kotlin
data class LightPreset(val name: String, val rgb: Rgb)
val LIGHT_PRESETS: List<LightPreset>
fun presetFor(rgb: Rgb): LightPreset?
fun hsvToRgb(hue: Float, saturation: Float, value: Float = 1f): Rgb   // čistý Kotlin, roundToInt
@Composable fun LightScreen(state: HouseUiState, actions: HouseActions, wide: Boolean)
@Composable fun ColorDialog(initial: Rgb, onConfirm: (Rgb) -> Unit, onDismiss: () -> Unit)
```

**Předvolby** (v tomto pořadí):

| Název | RGB |
|---|---|
| bílá | 255,255,255 |
| teplá bílá | 255,214,160 |
| žlutá | 255,200,60 |
| oranžová | 255,138,61 |
| červená | 244,67,54 |
| růžová | 255,95,162 |
| fialová | 155,92,255 |
| modrá | 100,110,255 |
| azurová | 46,197,232 |
| zelená | 61,214,140 |
| limetková | 181,229,80 |

**Rozvržení přes `TwoPane`** (spec §6.3): `left` je náhled a tlačítka, `right` barva, jas a automatika.

**Náhled:**
- kruh 150 dp: při zapnutém světle radiální přechod `shownLight.rgb` s průhledností `0.25 + 0.75·jas/100`, jinak šedý; uprostřed ikona `Lightbulb`;
- `title` a `detail` z `lightTexts(shownLight, status.dayPart, manualUntil)`, kde při ruční volbě `manualUntil = nextStart(piTime.toLocalTime(), dayStarts).second`, jinak `null`.

**Tlačítka:** Zapnout, Vypnout a Přepnout (`FilledTonalButton`) a pod nimi obrysové „Vrátit automatiku“.

**Barva:**
- nadpis s názvem vybrané předvolby, nebo „vlastní“;
- mřížka 6 sloupců s 11 předvolbami a duhovým kolečkem „vlastní“, které otevře `ColorDialog`;
- kroužek je kolem předvolby, která odpovídá svítící barvě, a když žádná neodpovídá, kolem „vlastní“.

**Jas:**
- `Slider` 1–100, který při změně volá `setLightBrightness`;
- vysvětlivka: „Výběr barvy nebo jasu světlo rovnou ručně zapne.“

**Automatika:**
- `Switch` „Rozsvítit při pohybu“ („v noci na chvíli rozsvítí noční světlo“). Když je `settingValue(LIGHT_ON_MOTION)` null, je zakázaný s podtitulem „Raspberry tuto volbu zatím nepodporuje“;
- „Noční světlo svítí“ („5–600 s“) s krokovým tlačítkem;
- „Práh šera“ („teď venku 320 lx“) s krokovým tlačítkem.

**`ColorDialog`:**
- posuvník odstínu 0–360 s duhovou stopou a posuvník sytosti 0–100 %;
- náhledové kolečko;
- tlačítka „Použít“ a „Zrušit“;
- výchozí hodnoty přes `android.graphics.Color.colorToHSV`.

- [ ] **Krok 1: Napsat padající testy.**

`LightPresetsTest`:
- `hsvToRgb(0f, 1f) == Rgb(255, 0, 0)`, `hsvToRgb(120f, 1f) == Rgb(0, 255, 0)`, `hsvToRgb(240f, 1f) == Rgb(0, 0, 255)`;
- `hsvToRgb(0f, 0f) == Rgb(255, 255, 255)` a `hsvToRgb(60f, 0.5f) == Rgb(255, 255, 128)`;
- `presetFor(Rgb(100, 110, 255))?.name == "modrá"` a `presetFor(Rgb(1, 2, 3)) == null`.

Screenshoty se uloží jako `svetlo-<stav>`:
- `demo` (ruční modrá 60 %);
- `auto-den-zhasnuto`;
- `nepodporuje` (Nastavení `lightOnMotion = null`);
- `dialog-barvy`.

- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.ui.light.*" --tests "cz.vsb.mitr.domek.screenshots.LightScreenshotTest"`.
- [ ] **Krok 3: Implementovat a zapojit do `DomekApp`.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.** Musí odpovídat náhledu 3 a zakázaný přepínač musí mít správnou poznámku.
- [ ] **Krok 5: Commit**: `feat: obrazovka Světlo a výběr barvy`

---

### Úkol 19: Nastavení

**Soubory:**
- Vytvořit: `ui/settings/SettingsScreen.kt`, `ui/settings/DayTimeline.kt`, `ui/settings/TimePickerDialog.kt`
- Upravit: `ui/DomekApp.kt`
- Test: `screenshots/SettingsScreenshotTest.kt`

**Rozhraní:**
- Produkuje: `@Composable fun SettingsScreen(state: HouseUiState, actions: HouseActions, wide: Boolean)` a `@Composable fun DayTimeline(starts: DayStarts?, now: LocalTime?)`.

**Rozvržení přes `TwoPane`** (spec §6.4): `left` je Denní doba, `right` Čas Raspberry a Zdroj dat.

**Denní doba:**
- nadpis „Denní doba“ a vpravo „teď je ‹den/večer/noc›“;
- `DayTimeline`: pruh přes 24 h s úseky z `timeline()` (barvy night, day, evening), značka času Raspberry s popiskem HH:MM nad ní a pod pruhem hodiny 0 6 12 18 24;
- řádky „Den od“, „Večer od“ a „Noc od“: ikona, čas jako obrysové tlačítko, které otevře `TimePickerDialog` → `setDayStart`, a tlačítko „Teď“ → `setDayStartNow` (povolené jen s `controlsEnabled`, se Stavem a bez starých dat);
- vysvětlivka: „„Teď“ posune začátek na aktuální čas Raspberry. Původní časy pak vrátí tlačítko níž.“;
- obrysové tlačítko „Vrátit původní časy“, povolené při `savedDayStarts != null`;
- bez dat jsou místo časů „—“ a ovládání je zakázané.

**Čas Raspberry:**
- řádky „Raspberry“ (`piTime`) a „Telefon“ (`phoneNow`) ve vzoru `d. M. yyyy H:mm:ss`;
- při |`clockDiffSeconds`| > 60 varovný řádek s `clockDiffText`;
- tlačítko „Nastavit čas podle telefonu“ → `syncClock()`.

**Zdroj dat:**
- přepínače „Raspberry přes Bluetooth“ („domek „Termostat““) a „Simulace“ („vymyšlená data, funguje bez Raspberry“) → `selectSource`;
- při Connected řádek „B8:27:EB:4A:19:C2 · signál −61 dBm · MTU 517“, při `rssi == null` „signál —“;
- u Bluetooth tlačítko „Připojit“ při DisconnectedByUser, jinak „Odpojit“.

**`TimePickerDialog`:** `AlertDialog` s `TimePicker(rememberTimePickerState(h, m, is24Hour = true))` a tlačítky „Uložit“ a „Zrušit“.

- [ ] **Krok 1: Napsat padající screenshot testy.** Stavy se uloží jako `nastaveni-<stav>`:
  - `demo`;
  - `bez-dat`: `received = null`, `settings = null`, Scanning;
  - `po-ted`: noc od 10:05, `savedDayStarts` vyplněné;
  - `simulace`.
- [ ] **Krok 2: Spustit testy, musí selhat**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.SettingsScreenshotTest"`.
- [ ] **Krok 3: Implementovat a zapojit do `DomekApp`.**
- [ ] **Krok 4: Spustit testy a prohlédnout PNG.**
  - Musí odpovídat náhledu 4.
  - Pruh po „Teď“ ukazuje noc od 10:05 do 19:00.
- [ ] **Krok 5: Commit**: `feat: obrazovka Nastavení`

---

### Úkol 20: Rozvržení na šířku

**Soubory:**
- Upravit: obrazovky z úkolů 16–19 podle potřeby
- Test: `screenshots/LandscapeScreenshotTest.kt`

- [ ] **Krok 1: Napsat screenshot testy** pro všechny čtyři záložky s `qualifiers = PHONE_LANDSCAPE`. Výstupy se uloží jako `sirka-prehled`, `sirka-termostat`, `sirka-svetlo` a `sirka-nastaveni`.
- [ ] **Krok 2: Spustit**: `./gradlew testDebugUnitTest --tests "cz.vsb.mitr.domek.screenshots.LandscapeScreenshotTest"`.
- [ ] **Krok 3: Prohlédnout PNG a opravit.** Vlevo je boční lišta, dva sloupce podle `TwoPane` a nic není oříznuté ani přes sebe (srov. náhled „Na šířku“). Opravy dělat v rozvržení obrazovek a po nich znovu spustit testy.
- [ ] **Krok 4: Commit**: `feat: dvousloupcové rozvržení na šířku a na tabletu`

---

### Úkol 21: Závěrečné ověření a návod k testování na telefonu

**Soubory:**
- Vytvořit: `docs/testovani-na-telefonu.md`

- [ ] **Krok 1: Spustit vše**: `./gradlew testDebugUnitTest assembleDebug`. Očekávaný výsledek je `BUILD SUCCESSFUL` a soubor `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Krok 2: Napsat `docs/testovani-na-telefonu.md`** (česky, odškrtávací seznam):
  1. **Instalace:** z Android Studia (Run) nebo `adb install -r app/build/outputs/apk/debug/app-debug.apk`. První spuštění vyžádá oprávnění, po „Povolit“ začne hledání.
  2. **Simulace:** „Spustit simulaci“. Přes kartu Ovládání simulace projít všech 5 scénářů ze spec §1 a výpadek dat (stará data → obnovení).
  3. **S Raspberry:**
     - připojení (štítek Připojeno, MTU 517 v Nastavení);
     - 5 scénářů ze spec §1;
     - odmítnutý zápis (Noc od = Večer od → hláška);
     - „Srovnat“ čas.
  4. **Výpadky:**
     - ukončit program na Raspberry: Stará data a za 15 s znovupřipojení;
     - vypnout Raspberry: Odpojeno · znovu za N s, po zapnutí se aplikace připojí sama;
     - vypnout a zapnout Bluetooth na telefonu;
     - otočit telefon během spojení: spojení i graf zůstanou.
  5. **Raspberry bez `svetlo_pri_pohybu`:** přepínač je zašedlý s poznámkou.
  6. **Volitelně nRF Connect:** v záložce Client ověřit, že zápis z aplikace přišel jako očekávaný JSON.
- [ ] **Krok 3 (jen když je telefon připojený přes `adb` a uživatel to schválil):**
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk` a `adb shell am start -n cz.vsb.mitr.domek/.MainActivity`;
  - v simulaci projít Přehled, Termostat, Světlo a Nastavení a pořídit screenshoty `adb exec-out screencap -p > <scratchpad>/telefon-<záložka>.png`;
  - porovnat s náhledy a nalezené rozdíly opravit.
- [ ] **Krok 4: Commit**: `docs: návod k testování na telefonu`
