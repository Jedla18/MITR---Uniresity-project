package cz.vsb.mitr.domek.ble

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface GattResult {
    data class Success(val bytes: ByteArray? = null, val number: Int = 0) : GattResult {
        // Pole se ve výchozím equals a hashCode porovnávají podle reference, dvě stejná čtení by si pak nebyla rovna.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && number == other.number && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * number + bytes.contentHashCode()
    }

    data class Failure(val status: Int) : GattResult

    data object Timeout : GattResult

    data object NotConnected : GattResult

    // BLE volání operaci nepřijalo (vrátilo false), na callback se proto nečeká.
    data object StartFailed : GattResult
}

// Android snese jen jednu rozdělanou GATT operaci, proto každá počká na callback té předchozí (spec 5.3).
// complete() a close() volají BLE callbacky z binder vláken, takže stav pro ně drží atomické proměnné.
class GattOperationQueue(private val defaultTimeoutMs: Long = 5_000) {
    private class Pending(val name: String) {
        val result = CompletableDeferred<GattResult>()
    }

    private val mutex = Mutex()
    private val current = AtomicReference<Pending?>(null)
    private val closed = AtomicBoolean(false)

    // Název operace, která čeká na callback (pro ladění); mimo běh operace null.
    val runningOperation: String? get() = current.get()?.name

    // Limit se počítá od startu operace, ne od zařazení do fronty. Výjimka ze start() se předá volajícímu
    // a fronta zůstane použitelná. Spuštěná operace drží místo i po zrušení volajícího (viz awaitCallback).
    suspend fun run(name: String, timeoutMs: Long = defaultTimeoutMs, start: () -> Boolean): GattResult =
        mutex.withLock {
            val pending = Pending(name)
            // Nejdřív zveřejnit a teprve potom se ptát na closed: souběžné close() pak operaci buď samo dokončí,
            // nebo ji tady zastaví kontrola. Zveřejnění před start() navíc zachrání callback, který dorazí
            // ještě před návratem ze start().
            current.set(pending)
            try {
                when {
                    closed.get() -> GattResult.NotConnected
                    !start() -> GattResult.StartFailed
                    else -> awaitCallback(pending, timeoutMs)
                }
            } finally {
                current.compareAndSet(pending, null)
            }
        }

    // Čekání na callback se zrušením volajícího nepřeruší: Android na spuštěný požadavek odpoví, i když na něj už nikdo
    // nečeká, a tu odpověď by dostala další operace. Místo se uvolní až po callbacku, close() nebo vypršení limitu
    // a CancellationException přijde teprve potom.
    private suspend fun awaitCallback(pending: Pending, timeoutMs: Long): GattResult {
        val result = withContext(NonCancellable) { withTimeoutOrNull(timeoutMs) { pending.result.await() } }
        currentCoroutineContext().ensureActive()
        return result ?: GattResult.Timeout
    }

    // Bez čekající operace se výsledek zahodí.
    // Pozdní callback po timeoutu připadne operaci, která zrovna běží; volající po timeoutu spojení zavírá.
    fun complete(result: GattResult) {
        current.getAndSet(null)?.result?.complete(result)
    }

    // Definitivní: běžící operace skončí jako NotConnected, další run() také a jeho start() se nezavolá.
    // Nové spojení dostane novou frontu.
    fun close() {
        closed.set(true)
        current.getAndSet(null)?.result?.complete(GattResult.NotConnected)
    }
}
