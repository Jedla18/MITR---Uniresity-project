package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.viewmodel.LogCategory.CONNECTION
import cz.vsb.mitr.domek.viewmodel.LogCategory.MOTION
import cz.vsb.mitr.domek.viewmodel.LogCategory.OTHER
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// Seznam Deníku: čas událostí, nejnovější nahoře, slučování, limit a stav, který události potřebují (výpadky, poruchy).
// Čísla v komentářích jsou sekundy od 10:00:00.
class HouseLogTest {
    private val start = LocalDateTime.of(2026, 10, 20, 10, 0, 0)
    private val log = HouseLog()

    private val calm = EXAMPLE.copy(heating = false)
    private val moving = calm.copy(motion = true)
    private val connected = ConnectionState.Connected("B8:27:EB:4A:19:C2", 517, rssi = null)

    private fun at(seconds: Long) = atMs(seconds * 1_000)

    private fun atMs(ms: Long) = LogTime(start.plusNanos(ms * 1_000_000), ms)

    private var lastStatus: ReceivedStatus? = null

    // Stav přijatý v daném čase; s předchozím ho porovná stejně jako ViewModel.
    private fun status(seconds: Long, status: HouseStatus, settings: HouseSettings? = DEFAULTS) {
        val received = ReceivedStatus(status, seconds * 1_000, start.plusSeconds(seconds))
        log.status(lastStatus, received, settings)
        lastStatus = received
    }

    private fun events() = log.entries.map { it.event }

    private fun titles() = log.entries.map { it.event.title }

    // Stav

    @Test
    fun theFirstStatusGivesNoEvents() {
        status(0, moving.copy(heating = true, nobodyHome = true, errors = listOf("osvetleni")))

        assertEquals(emptyList<LogEntry>(), log.entries)
    }

    @Test
    fun statusEventsHaveTheReceiveTimeAndTheNewestIsOnTop() {
        status(0, calm)
        status(5, calm.copy(heating = true))
        status(9, calm.copy(heating = true, motion = true))

        assertEquals(listOf("Pohyb", "Začalo topit"), titles())
        assertEquals(listOf(start.plusSeconds(9), start.plusSeconds(5)), log.entries.map { it.at })
    }

    // Začala noc s nižším cílem, a proto přestalo topit: následek je v Deníku nad svou příčinou.
    @Test
    fun theEffectIsAboveItsCause() {
        status(0, calm.copy(dayPartText = "vecer", heating = true))
        status(1, calm.copy(dayPartText = "noc", target = 20.0, motion = true, light = calm.light.copy(on = true)))

        assertEquals(listOf("Přestalo topit", "Světlo se rozsvítilo", "Pohyb", "Začala noc"), titles())
    }

    // Sloučení pohybu

    @Test
    fun motionWithinAMinuteOfTheLastMotionRowOnlyCountsUp() {
        status(0, calm)
        status(10, moving)
        val id = log.entries.single().id
        status(13, calm)
        status(40, moving)
        status(43, calm)
        // Přesně minuta od posledního záznamu se ještě slučuje.
        status(100, moving)

        assertEquals(listOf(LogEvent(MOTION, LogKind.MOTION, "Pohyb", "3× od 10:00:10")), events())
        assertEquals(start.plusSeconds(100), log.entries.single().at)
        assertEquals(id, log.entries.single().id)
    }

    @Test
    fun motionMoreThanAMinuteAfterTheLastMotionRowIsANewRow() {
        status(0, calm)
        status(10, moving)
        status(13, calm)
        status(71, moving)

        assertEquals(listOf(LogEvent(MOTION, LogKind.MOTION, "Pohyb"), LogEvent(MOTION, LogKind.MOTION, "Pohyb")), events())
        assertNotEquals(log.entries[0].id, log.entries[1].id)
    }

    // Slučuje se jen do řádku, který je pořád nejnovější. Jinak by sloučený pohyb přeskočil, co se mezitím stalo.
    @Test
    fun motionAfterAnotherEventIsANewRow() {
        status(0, calm)
        status(10, moving)
        status(13, calm.copy(heating = true))
        status(20, moving.copy(heating = true))

        assertEquals(listOf("Pohyb", "Začalo topit", "Pohyb"), titles())
        assertEquals(listOf("", ""), log.entries.filter { it.event.kind == LogKind.MOTION }.map { it.event.detail })
    }

    // Sloučení Nastavení

    @Test
    fun changesOfOneKeyWithinTenSecondsKeepTheOriginalOldValue() {
        log.settings(DEFAULTS, DEFAULTS.copy(targetDay = 24.0), at(0))
        log.settings(DEFAULTS.copy(targetDay = 24.0), DEFAULTS.copy(targetDay = 24.5), at(5))
        // Přesně 10 s od posledního záznamu se ještě slučuje.
        log.settings(DEFAULTS.copy(targetDay = 24.5), DEFAULTS.copy(targetDay = 25.0), at(15))

        assertEquals(listOf("Denní cíl: 23,0 → 25,0${NBSP}°C"), titles())
        assertEquals(start.plusSeconds(15), log.entries.single().at)
    }

    @Test
    fun changeMoreThanTenSecondsLaterIsANewRow() {
        log.settings(DEFAULTS, DEFAULTS.copy(targetDay = 24.0), at(0))
        log.settings(DEFAULTS.copy(targetDay = 24.0), DEFAULTS.copy(targetDay = 25.0), at(11))

        assertEquals(listOf("Denní cíl: 24,0 → 25,0${NBSP}°C", "Denní cíl: 23,0 → 24,0${NBSP}°C"), titles())
    }

    @Test
    fun changesOfDifferentKeysAreNotMerged() {
        log.settings(DEFAULTS, DEFAULTS.copy(targetDay = 24.0), at(0))
        log.settings(DEFAULTS.copy(targetDay = 24.0), DEFAULTS.copy(targetDay = 24.0, targetNight = 19.0), at(2))

        assertEquals(listOf("Noční cíl: 20,0 → 19,0${NBSP}°C", "Denní cíl: 23,0 → 24,0${NBSP}°C"), titles())
    }

    // Cíl nahoru a do 10 s zase dolů, mezi tím začalo topit: dva řádky, každý pod svým následkem.
    @Test
    fun settingsChangeAfterAnotherEventIsANewRow() {
        status(0, calm)
        log.settings(DEFAULTS, DEFAULTS.copy(targetDay = 25.0), at(1))
        status(2, calm.copy(heating = true, target = 25.0))
        log.settings(DEFAULTS.copy(targetDay = 25.0), DEFAULTS, at(5))
        status(6, calm)

        assertEquals(
            listOf("Přestalo topit", "Denní cíl: 25,0 → 23,0${NBSP}°C", "Začalo topit", "Denní cíl: 23,0 → 25,0${NBSP}°C"),
            titles(),
        )
    }

    // „Teď“ a hned „Vrátit původní časy“, mezi tím nic: sloučený řádek by říkal „22:00 → 22:00“, takže zmizí.
    @Test
    fun aChangeBackToTheOriginalValueRemovesTheMergedRow() {
        val now = DEFAULTS.copy(nightStart = "10:05")
        log.settings(DEFAULTS, now, at(0))
        log.settings(now, DEFAULTS, at(4))

        assertEquals(emptyList<LogEntry>(), log.entries)

        // Další změna je zase nový řádek se správnou starou hodnotou.
        log.settings(DEFAULTS, DEFAULTS.copy(nightStart = "23:00"), at(6))
        assertEquals(listOf("Začátek noci: 22:00 → 23:00"), titles())
    }

    @Test
    fun aChangeBackAfterAnotherEventIsItsOwnRow() {
        val now = DEFAULTS.copy(nightStart = "10:05")
        log.settings(DEFAULTS, now, at(0))
        log.message("Dům není připojený, změna se neodeslala.", at(1))
        log.settings(now, DEFAULTS, at(4))

        assertEquals(
            listOf("Začátek noci: 10:05 → 22:00", "Dům není připojený, změna se neodeslala.", "Začátek noci: 22:00 → 10:05"),
            titles(),
        )
    }

    @Test
    fun mergedColorChangeKeepsTheOriginalColor() {
        val red = DEFAULTS.copy(lightColor = listOf(255, 0, 0))
        log.settings(DEFAULTS, red, at(0))
        log.settings(red, DEFAULTS.copy(lightColor = listOf(0, 0, 255)), at(3))

        assertEquals(listOf(listOf(Rgb(255, 255, 255), Rgb(0, 0, 255))), log.entries.map { it.event.lightColors })
    }

    // Několik změn najednou (třeba obnovení začátků úseků) dá řádek pro každou.
    @Test
    fun everyChangedKeyIsARow() {
        log.settings(DEFAULTS, DEFAULTS.copy(dayStart = "06:00", nightStart = "23:00"), at(0))

        assertEquals(listOf("Začátek noci: 22:00 → 23:00", "Začátek dne: 07:00 → 06:00"), titles())
    }

    // Změna z aplikace, nebo na Raspberry

    @Test
    fun changeOfAKeyTheAppWroteInTheLastTenSecondsIsFromTheApp() {
        log.appWrote(listOf(SettingsField.Key(SettingKey.MODE)), atMs = 0)
        log.settings(DEFAULTS, DEFAULTS.copy(mode = ThermostatMode.MANUAL), at(10))

        assertEquals("změna z${NBSP}aplikace", log.entries.single().event.detail)
    }

    @Test
    fun changeOfAKeyTheAppDidNotWriteLatelyIsOnTheRaspberry() {
        log.appWrote(listOf(SettingsField.Key(SettingKey.MODE)), atMs = 0)
        log.settings(DEFAULTS, DEFAULTS.copy(targetDay = 25.0), at(1))
        log.settings(DEFAULTS.copy(targetDay = 25.0), DEFAULTS.copy(targetDay = 25.0, mode = ThermostatMode.OFF), at(11))

        assertEquals(listOf("změna na Raspberry", "změna na Raspberry"), log.entries.map { it.event.detail })
    }

    @Test
    fun lightLookSentByTheAppMakesColorAndBrightnessChangesFromTheApp() {
        log.appWrote(listOf(SettingsField.LightColor, SettingsField.LightBrightness), atMs = 0)
        log.settings(DEFAULTS, DEFAULTS.copy(lightColor = listOf(255, 0, 0), lightBrightness = 60), at(1))

        assertEquals(listOf("změna z${NBSP}aplikace", "změna z${NBSP}aplikace"), log.entries.map { it.event.detail })
    }

    // Limit

    @Test
    fun keepsTheNewest200Entries() {
        repeat(205) { log.message("Hláška $it", at(it.toLong())) }

        assertEquals(200, log.entries.size)
        assertEquals("Hláška 204", log.entries.first().event.title)
        assertEquals("Hláška 5", log.entries.last().event.title)
    }

    // Spojení

    @Test
    fun firstConnectionNamesTheHouseItsAddressAndMtu() {
        log.connection(ConnectionState.Idle, ConnectionState.Scanning, at(0))
        log.connection(ConnectionState.Scanning, ConnectionState.Connecting(1), at(1))
        log.connection(ConnectionState.Connecting(1), ConnectionState.Preparing, at(2))
        log.connection(ConnectionState.Preparing, connected, at(3))

        assertEquals(
            listOf(LogEvent(CONNECTION, LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "B8:27:EB:4A:19:C2 · MTU${NBSP}517")),
            events(),
        )
        assertEquals(start.plusSeconds(3), log.entries.single().at)
    }

    @Test
    fun connectionThatEndedByItselfAndCameBackSaysHowLongItWasDown() {
        log.connection(ConnectionState.Idle, connected, at(0))
        log.connection(connected, ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), at(4))
        log.connection(ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), ConnectionState.Connecting(1), at(6))
        log.connection(ConnectionState.Connecting(1), ConnectionState.Preparing, at(12))
        log.connection(ConnectionState.Preparing, connected.copy(rssi = -61), at(13))

        assertEquals(
            listOf(
                LogEvent(CONNECTION, LogKind.CONNECTED, "Znovu připojeno", "výpadek 9${NBSP}s"),
                LogEvent(CONNECTION, LogKind.CONNECTION_LOST, "Spojení přerušeno", "znovu se připojuji…"),
                LogEvent(CONNECTION, LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "B8:27:EB:4A:19:C2 · MTU${NBSP}517"),
            ),
            events(),
        )
    }

    // Po ručním odpojení není co obnovovat, další spojení je zase první.
    @Test
    fun userDisconnectIsManualAndTheNextConnectionIsAFirstOne() {
        log.connection(ConnectionState.Idle, connected, at(0))
        log.connection(connected, ConnectionState.DisconnectedByUser, at(5))
        log.connection(ConnectionState.DisconnectedByUser, ConnectionState.Scanning, at(20))
        log.connection(ConnectionState.Scanning, connected, at(25))

        assertEquals(
            listOf(
                LogEvent(CONNECTION, LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "B8:27:EB:4A:19:C2 · MTU${NBSP}517"),
                LogEvent(CONNECTION, LogKind.DISCONNECTED, "Odpojeno ručně"),
                LogEvent(CONNECTION, LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "B8:27:EB:4A:19:C2 · MTU${NBSP}517"),
            ),
            events(),
        )
    }

    @Test
    fun userDisconnectWhileReconnectingEndsTheOutage() {
        log.connection(ConnectionState.Idle, connected, at(0))
        log.connection(connected, ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), at(4))
        log.connection(ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), ConnectionState.DisconnectedByUser, at(5))
        log.connection(ConnectionState.DisconnectedByUser, connected, at(30))

        val connectedTitle = "Připojeno k${NBSP}domu Termostat"
        assertEquals(listOf(connectedTitle, "Odpojeno ručně", "Spojení přerušeno", connectedTitle), titles())
    }

    @Test
    fun signalReadingsOfAConnectionAreNoEvents() {
        log.connection(ConnectionState.Idle, connected, at(0))
        log.connection(connected, connected.copy(rssi = -61), at(5))

        assertEquals(1, log.entries.size)
    }

    @Test
    fun simulationStart() {
        log.connection(ConnectionState.Idle, ConnectionState.Simulated, at(0))

        assertEquals(listOf(LogEvent(CONNECTION, LogKind.SIMULATION_STARTED, "Simulace spuštěna")), events())
    }

    // Stará data

    // ViewModel hlásí stáří při každém tiku; zapíše se jen začátek. Výpadek je doba mezi Stavy.
    @Test
    fun staleDataWhileConnectedAndItsEndWithTheLengthOfTheGap() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        status(1, calm)
        log.dataAge(stale = true, atMs(4_500))
        log.dataAge(stale = true, atMs(5_000))
        status(8, calm)
        log.dataAge(stale = false, atMs(8_500))

        assertEquals(
            listOf(
                LogEvent(CONNECTION, LogKind.DATA_FRESH, "Data zase chodí", "výpadek 7${NBSP}s"),
                LogEvent(CONNECTION, LogKind.DATA_STALE, "Data jsou stará", "Raspberry přestalo posílat stav"),
            ),
            events().dropLast(1),
        )
        assertEquals(start.plusNanos(4_500_000_000), log.entries[1].at)
    }

    // Bez spojení data stárnou samozřejmě; to už říká „Spojení přerušeno“.
    @Test
    fun noStaleDataWithoutAConnection() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        log.connection(connected, ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), at(1))
        log.dataAge(stale = true, at(5))

        assertEquals(listOf("Spojení přerušeno", "Připojeno k${NBSP}domu Termostat"), titles())
    }

    // Po novém spojení má Raspberry 3 s na první Stav, jinak by každé znovupřipojení hlásilo stará data.
    @Test
    fun staleDataCountFromTheReconnectionToo() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        log.connection(connected, ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), at(1))
        log.connection(ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), connected, at(20))
        log.dataAge(stale = true, at(23))
        assertEquals("Znovu připojeno", titles().first())

        log.dataAge(stale = true, atMs(23_500))
        assertEquals("Data jsou stará", titles().first())
    }

    @Test
    fun simulationDataGetStaleToo() {
        log.connection(ConnectionState.Idle, ConnectionState.Simulated, at(0))
        status(0, calm)
        log.dataAge(stale = true, at(4))

        assertEquals(LogEvent(CONNECTION, LogKind.DATA_STALE, "Data jsou stará", "simulace přestala posílat stav"), events().first())
    }

    // Tik ViewModelu stojí, když telefon spí, a Stav tehdy přijít nemohl. Po probuzení mají data zase 3 s, jako po připojení.
    @Test
    fun aTickAfterTheSleepOfThePhoneStartsTheGraceAgain() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        log.dataAge(stale = false, atMs(500))
        log.dataAge(stale = false, atMs(1_000))
        log.dataAge(stale = true, atMs(121_000))
        log.dataAge(stale = true, atMs(124_000))
        assertEquals(listOf("Připojeno k${NBSP}domu Termostat"), titles())

        log.dataAge(stale = true, atMs(124_500))
        assertEquals("Data jsou stará", titles().first())
    }

    // Výpadek spojení ukončí i stará data: po něm už nepřijde „Data zase chodí“ s délkou celého výpadku.
    @Test
    fun aLostConnectionEndsTheStaleData() {
        val waiting = ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 20_000)
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        log.dataAge(stale = true, atMs(3_500))
        log.connection(connected, waiting, at(15))
        log.connection(waiting, connected, at(720))
        status(721, calm)

        assertEquals(listOf("Znovu připojeno", "Spojení přerušeno", "Data jsou stará", "Připojeno k${NBSP}domu Termostat"), titles())
    }

    @Test
    fun aManualDisconnectEndsTheStaleData() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        log.dataAge(stale = true, atMs(3_500))
        log.connection(connected, ConnectionState.DisconnectedByUser, at(10))
        log.connection(ConnectionState.DisconnectedByUser, connected, at(60))
        status(61, calm)

        assertEquals("Připojeno k${NBSP}domu Termostat", titles().first())
        assertEquals(1, titles().count { it == "Data jsou stará" })
        assertEquals(0, titles().count { it == "Data zase chodí" })
    }

    // Poruchy

    @Test
    fun faultLastsFromTheStatusThatReportedItToTheFirstOneWithoutIt() {
        status(0, calm)
        status(10, calm.copy(errors = listOf("osvetleni")))
        status(53, calm)

        assertEquals(
            listOf(
                LogEvent(CONNECTION, LogKind.FAULT_ENDED, "Čidlo osvětlení zase funguje", "porucha trvala 43${NBSP}s"),
                LogEvent(
                    CONNECTION,
                    LogKind.FAULT,
                    "Porucha: čidlo osvětlení (TSL2561) neodpovídá",
                    "Hodnota osvětlení chybí. Ve dne se světlo samo nerozsvítí.",
                ),
            ),
            events(),
        )
    }

    @Test
    fun unknownFaultCodes() {
        status(0, calm)
        status(5, calm.copy(errors = listOf("kamera")))
        status(9, calm)

        assertEquals(listOf("Chyba kamera skončila", "Neznámá porucha: kamera"), titles())
        assertEquals("porucha trvala 4${NBSP}s", log.entries.first().event.detail)
    }

    // Porucha z prvního Stavu nemá známý začátek.
    @Test
    fun faultFromTheFirstStatusEndsWithoutALength() {
        status(0, calm.copy(errors = listOf("displej")))
        status(30, calm)

        assertEquals(listOf(LogEvent(CONNECTION, LogKind.FAULT_ENDED, "Displej zase funguje")), events())
    }

    // Porucha BME280 zastaví topení: porucha je příčina, takže stojí pod ním.
    @Test
    fun faultIsBelowWhatItCaused() {
        status(0, calm.copy(heating = true))
        status(1, calm.copy(errors = listOf("meteo"), outsideTemp = null))

        assertEquals(listOf("Přestalo topit", "Porucha: čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá"), titles())
    }

    // Akce aplikace

    @Test
    fun messagesSimulationActionsAndClockSyncAreEntriesAtTheirTime() {
        log.message("Raspberry neodpovědělo. Změna se možná neprovedla.", at(3))
        log.simulation(SimulationAction.HEAT_SENSOR, faultReported = false, at(4))
        log.clockSync(LocalDateTime.of(2026, 10, 20, 14, 3, 0), at(5))

        assertEquals(
            listOf(
                LogEvent(OTHER, LogKind.CLOCK, "Srovnání hodin", "podle telefonu 14:03:00"),
                LogEvent(OTHER, LogKind.SIMULATION, "Simulace: zahřáté čidlo", "venku o${NBSP}8${NBSP}°C víc na 30${NBSP}s"),
                LogEvent(CONNECTION, LogKind.MESSAGE, "Raspberry neodpovědělo. Změna se možná neprovedla."),
            ),
            events(),
        )
        assertEquals(listOf(5L, 4L, 3L).map(start::plusSeconds), log.entries.map { it.at })
    }

    // Vymazání

    @Test
    fun clearForgetsTheEntriesAndWhatTheyWereBasedOn() {
        log.connection(ConnectionState.Idle, connected, at(0))
        status(0, calm)
        status(1, calm.copy(errors = listOf("displej")))
        log.dataAge(stale = true, at(5))
        log.connection(connected, ConnectionState.WaitingToReconnect(attempt = 1, retryAtMs = 6_000), at(6))
        log.appWrote(listOf(SettingsField.Key(SettingKey.MODE)), atMs = 6_000)

        log.clear()
        assertEquals(emptyList<LogEntry>(), log.entries)

        log.connection(ConnectionState.Idle, connected, at(7))
        log.settings(DEFAULTS, DEFAULTS.copy(mode = ThermostatMode.OFF), at(8))
        lastStatus = null
        status(9, calm.copy(errors = listOf("displej")))
        status(10, calm)
        assertEquals(
            listOf(
                LogEvent(CONNECTION, LogKind.FAULT_ENDED, "Displej zase funguje"),
                LogEvent(LogCategory.THERMOSTAT, LogKind.SETTING, "Režim termostatu: Auto → Vypnuto", "změna na Raspberry"),
                LogEvent(CONNECTION, LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "B8:27:EB:4A:19:C2 · MTU${NBSP}517"),
            ),
            events(),
        )
    }
}
