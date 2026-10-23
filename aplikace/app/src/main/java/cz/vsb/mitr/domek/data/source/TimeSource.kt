package cz.vsb.mitr.domek.data.source

// Monotónní čas v ms (v produkci SystemClock.elapsedRealtime()); přes rozhraní, aby šel v testech řídit.
fun interface TimeSource {
    fun nowMs(): Long
}
