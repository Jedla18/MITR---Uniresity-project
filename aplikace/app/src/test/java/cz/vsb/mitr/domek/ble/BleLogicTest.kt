package cz.vsb.mitr.domek.ble

import cz.vsb.mitr.domek.data.source.FoundDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLogicTest {
    private val weak = FoundDevice("AA", "Termostat", -70)
    private val strong = FoundDevice("BB", "Termostat", -50)

    // Odstupy znovupřipojení

    @Test
    fun reconnectDelaysDoubleAndStopAtTenSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map(::reconnectDelayMs))
    }

    // Bitový posun by u velkého čísla pokusu přetekl a dal nesmysl.
    @Test
    fun reconnectDelayStaysAtTenSecondsForHugeAttempts() {
        assertEquals(10_000L, reconnectDelayMs(100))
        assertEquals(10_000L, reconnectDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun reconnectDelayTreatsAttemptsBelowOneAsTheFirst() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(1_000L, reconnectDelayMs(-3))
    }

    // Rozhodnutí po okně hledání

    @Test
    fun oneDeviceIsConnectedStraightAway() {
        assertEquals(ScanDecision.Connect(weak), decideAfterScanWindow(listOf(weak)))
    }

    @Test
    fun severalDevicesAreOfferedStrongestSignalFirst() {
        val expected = ScanDecision.Choose(listOf(strong, weak))

        assertEquals(expected, decideAfterScanWindow(listOf(weak, strong)))
        assertEquals(expected, decideAfterScanWindow(listOf(strong, weak)))
    }

    @Test
    fun sortsEveryDeviceBySignalNotJustTheFirstTwo() {
        val far = FoundDevice("CC", "Termostat", -90)
        val near = FoundDevice("DD", "Termostat", -40)
        val middle = FoundDevice("EE", "Termostat", -65)

        assertEquals(ScanDecision.Choose(listOf(near, middle, far)), decideAfterScanWindow(listOf(far, near, middle)))
    }

    // Seznam v nabídce se za běhu doplňuje, takže zařízení se stejným signálem nesmějí přeskakovat.
    @Test
    fun devicesWithTheSameSignalKeepTheirOrder() {
        val first = FoundDevice("CC", "Termostat", -60)
        val second = FoundDevice("DD", "Termostat", -60)

        assertEquals(ScanDecision.Choose(listOf(first, second)), decideAfterScanWindow(listOf(first, second)))
        assertEquals(ScanDecision.Choose(listOf(second, first)), decideAfterScanWindow(listOf(second, first)))
    }

    // Hledání si zařízení sbírá do mapy podle adresy a rozhodnutí dostane její values.
    @Test
    fun acceptsTheValuesOfAMap() {
        val found = linkedMapOf(weak.address to weak, strong.address to strong)

        assertEquals(ScanDecision.Choose(listOf(strong, weak)), decideAfterScanWindow(found.values))
    }

    @Test
    fun nothingFoundIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { decideAfterScanWindow(emptyList()) }
    }

    // Odstup mezi starty hledání

    @Test
    fun firstScanStartsImmediately() {
        assertEquals(0L, ScanThrottle().delayBeforeStart(10_000))
    }

    // Čas v testech začíná od 0, což nesmí vypadat jako „hledání právě začalo“.
    @Test
    fun firstScanIsNeverDelayedEvenAtTimeZero() {
        assertEquals(0L, ScanThrottle().delayBeforeStart(0))
    }

    @Test
    fun nextScanWaitsForTheRestOfSixSeconds() {
        val throttle = ScanThrottle()
        throttle.onStarted(10_000)

        assertEquals(6_000L, throttle.delayBeforeStart(10_000))
        assertEquals(4_000L, throttle.delayBeforeStart(12_000))
        assertEquals(1L, throttle.delayBeforeStart(15_999))
        assertEquals(0L, throttle.delayBeforeStart(16_000))
        assertEquals(0L, throttle.delayBeforeStart(30_000))
    }

    @Test
    fun intervalCountsFromTheLatestStart() {
        val throttle = ScanThrottle()
        throttle.onStarted(10_000)
        throttle.onStarted(16_000)

        assertEquals(6_000L, throttle.delayBeforeStart(16_000))
        assertEquals(2_000L, throttle.delayBeforeStart(20_000))
    }

    @Test
    fun askingDoesNotUseUpTheInterval() {
        val throttle = ScanThrottle()
        throttle.onStarted(10_000)

        repeat(3) { assertEquals(4_000L, throttle.delayBeforeStart(12_000)) }
    }

    @Test
    fun intervalCanBeChanged() {
        val throttle = ScanThrottle(minIntervalMs = 1_500)
        throttle.onStarted(0)

        assertEquals(1_000L, throttle.delayBeforeStart(500))
        assertEquals(0L, throttle.delayBeforeStart(1_500))
    }

    // Notifikace unese MTU − 3 B a JSON domku má nejvýš 512 B, takže celý se vejde až od MTU 515
    // (docs/ble-rozhrani.md, Připojení a MTU 517).
    @Test
    fun mtuMustCarryAWholeJsonValue() {
        assertTrue(mtuCarriesWholeJson(517))
        assertTrue(mtuCarriesWholeJson(515))
        assertFalse(mtuCarriesWholeJson(514))
        assertFalse(mtuCarriesWholeJson(23))
    }
}
