package cz.vsb.mitr.domek.ble

import cz.vsb.mitr.domek.data.source.FoundDevice

// Notifikace unese MTU − 3 B záhlaví a JSON domku má nejvýš 512 B (docs/ble-rozhrani.md, Připojení a MTU 517).
private const val MAX_JSON_BYTES = 512
private const val ATT_HEADER_BYTES = 3

fun mtuCarriesWholeJson(mtu: Int): Boolean = mtu - ATT_HEADER_BYTES >= MAX_JSON_BYTES

// Odstupy mezi pokusy o připojení (spec 5.3): 1, 2, 4 a 8 s, potom pořád 10 s.
private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
private const val RECONNECT_MAX_DELAY_MS = 10_000L

// Pokusy se počítají od 1. Nižší číslo se bere jako první pokus, aby chybný čítač nezastavil znovupřipojování.
fun reconnectDelayMs(attempt: Int): Long =
    RECONNECT_DELAYS_MS.getOrElse(attempt.coerceAtLeast(1) - 1) { RECONNECT_MAX_DELAY_MS }

sealed interface ScanDecision {
    data class Connect(val device: FoundDevice) : ScanDecision

    data class Choose(val devices: List<FoundDevice>) : ScanDecision
}

// Co dělat po 2 s okně hledání (spec 5.3). Každé zařízení tu má být jednou (podle adresy) a aspoň jedno musí být.
// Řazení je stabilní, takže zařízení se stejným signálem zůstanou v pořadí, v jakém přišla.
fun decideAfterScanWindow(found: Collection<FoundDevice>): ScanDecision {
    require(found.isNotEmpty()) { "Okno hledání skončilo bez nálezu, není z čeho vybírat" }
    return if (found.size == 1) {
        ScanDecision.Connect(found.first())
    } else {
        ScanDecision.Choose(found.sortedByDescending { it.rssi })
    }
}

// Android povolí nejvýš 5 spuštění hledání za 30 s (spec 5.3), proto se hledání nesmí restartovat rychleji.
class ScanThrottle(private val minIntervalMs: Long = 6_000) {
    // null = hledání ještě nestartovalo. Odstup se pak nepočítá ani od času 0.
    private var lastStartMs: Long? = null

    // Za jak dlouho smí hledání začít; 0, když smí hned.
    fun delayBeforeStart(nowMs: Long): Long {
        val last = lastStartMs ?: return 0L
        return maxOf(0L, last + minIntervalMs - nowMs)
    }

    fun onStarted(nowMs: Long) {
        lastStartMs = nowMs
    }
}
