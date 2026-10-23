package cz.vsb.mitr.domek.ble

import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationQueueTest {
    // Kdyby se rozbil časový limit fronty, čekající operace by se nedala zrušit a runTest by se při úklidu nikdy
    // nevrátil. Pravidlo z toho udělá selhání testu místo zaseknutého sestavení.
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val ok = GattResult.Success()

    // Bez await(): to by nechalo runTest posunout čas samo, takže by prošel i limit o milisekundu delší.
    private fun TestScope.assertTimesOutExactlyAfter(operation: Deferred<GattResult>, limitMs: Long) {
        advanceTimeBy(limitMs - 1)
        runCurrent()
        assertFalse("po ${limitMs - 1} ms ještě nemá vypršet", operation.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("po $limitMs ms má být vypršená", operation.isCompleted)
    }

    // Jedna operace po druhé

    @Test
    fun runsOneOperationAtATime() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val a = async { q.run("a") { started += "a"; true } }
        val b = async { q.run("b") { started += "b"; true } }
        runCurrent()
        assertEquals(listOf("a"), started)

        q.complete(GattResult.Success(number = 1))
        runCurrent()
        assertEquals(listOf("a", "b"), started)
        assertEquals(GattResult.Success(number = 1), a.await())

        q.complete(GattResult.Failure(128))
        assertEquals(GattResult.Failure(128), b.await())
    }

    @Test
    fun startsQueuedOperationsInTheOrderTheyWereQueued() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val jobs = listOf("a", "b", "c").map { name -> async { q.run(name) { started += name; true } } }
        runCurrent()

        repeat(3) {
            q.complete(ok)
            runCurrent()
        }

        assertEquals(listOf("a", "b", "c"), started)
        assertEquals(listOf(ok, ok, ok), jobs.awaitAll())
    }

    @Test
    fun reportsTheOperationThatIsRunning() = runTest {
        val q = GattOperationQueue()
        assertNull(q.runningOperation)
        val a = async { q.run("requestMtu") { true } }
        val b = async { q.run("discoverServices") { true } }
        runCurrent()
        assertEquals("requestMtu", q.runningOperation)

        q.complete(ok)
        runCurrent()
        assertEquals("discoverServices", q.runningOperation)

        q.complete(ok)
        runCurrent()
        assertNull(q.runningOperation)
        assertEquals(listOf(ok, ok), listOf(a, b).awaitAll())
    }

    // Časový limit

    @Test
    fun timesOutAndFreesQueue() = runTest {
        val q = GattOperationQueue()
        val a = async { q.run("a") { true } }
        runCurrent()

        assertTimesOutExactlyAfter(a, 5_000)
        assertEquals(GattResult.Timeout, a.await())
        assertNull(q.runningOperation)

        var started = false
        val b = async { q.run("b") { started = true; true } }
        runCurrent()
        assertTrue("další operace startuje hned", started)
        q.complete(ok)
        assertEquals(ok, b.await())
    }

    // Čekání ve frontě se do limitu operace nepočítá (spec 5.3: limit má každá operace na svůj callback).
    @Test
    fun timeLimitStartsWhenTheOperationStartsNotWhenItIsQueued() = runTest {
        val q = GattOperationQueue()
        val a = async { q.run("a") { true } }
        val b = async { q.run("b") { true } }
        runCurrent()
        advanceTimeBy(3_000)
        q.complete(ok)
        runCurrent()
        assertEquals(ok, a.await())

        // b začala v čase 3000, takže vyprší v 8000, ne v 5000.
        assertTimesOutExactlyAfter(b, 5_000)
        assertEquals(GattResult.Timeout, b.await())
    }

    @Test
    fun explicitTimeLimitOverridesTheDefault() = runTest {
        val q = GattOperationQueue()
        val a = async { q.run("a", timeoutMs = 10_000) { true } }
        runCurrent()

        assertTimesOutExactlyAfter(a, 10_000)
        assertEquals(GattResult.Timeout, a.await())
    }

    @Test
    fun constructorSetsTheDefaultTimeLimit() = runTest {
        val q = GattOperationQueue(defaultTimeoutMs = 1_000)
        val a = async { q.run("a") { true } }
        runCurrent()

        assertTimesOutExactlyAfter(a, 1_000)
        assertEquals(GattResult.Timeout, a.await())
    }

    // Operace, která se nespustila

    @Test
    fun startFailureDoesNotBlock() = runTest {
        val q = GattOperationQueue()

        assertEquals(GattResult.StartFailed, q.run("a") { false })
        assertEquals("selhání startu se nečeká", 0L, testScheduler.currentTime)
        assertNull(q.runningOperation)

        val b = async { q.run("b") { true } }
        runCurrent()
        q.complete(ok)
        assertEquals(ok, b.await())
    }

    @Test
    fun exceptionFromStartPropagatesAndFreesTheQueue() = runTest {
        val q = GattOperationQueue()

        val failure = runCatching { q.run("a") { error("boom") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("boom", failure?.message)
        assertNull(q.runningOperation)

        val b = async { q.run("b") { true } }
        runCurrent()
        q.complete(ok)
        assertEquals(ok, b.await())
    }

    // Zrušený volající

    // Spuštěný požadavek je u Androidu dál rozdělaný, takže jeho odpověď nesmí připadnout následující operaci.
    @Test
    fun cancelledCallerKeepsTheQueueUntilItsCallbackArrives() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val a = async { q.run("a") { started += "a"; true } }
        val b = async { q.run("b") { started += "b"; true } }
        runCurrent()

        a.cancel()
        runCurrent()
        assertEquals("b smí začít až po odpovědi na a", listOf("a"), started)
        assertEquals("a", q.runningOperation)
        assertFalse("a doběhne až s callbackem", a.isCompleted)

        q.complete(GattResult.Success(number = 1))
        runCurrent()
        assertTrue(a.isCompleted)
        assertTrue("a končí zrušením", runCatching { a.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("a", "b"), started)
        assertFalse("odpověď na a nesmí dokončit b", b.isCompleted)

        q.complete(GattResult.Failure(7))
        assertEquals(GattResult.Failure(7), b.await())
    }

    @Test
    fun cancelledCallerKeepsTheQueueUntilTheTimeLimit() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val a = async { q.run("a") { started += "a"; true } }
        val b = async { q.run("b") { started += "b"; true } }
        runCurrent()
        a.cancel()
        runCurrent()

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals("b smí začít až po limitu a", listOf("a"), started)
        assertFalse(a.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(a.isCompleted)
        assertTrue("a končí zrušením", runCatching { a.await() }.exceptionOrNull() is CancellationException)
        assertEquals(listOf("a", "b"), started)

        q.complete(ok)
        assertEquals(ok, b.await())
    }

    @Test
    fun closeEndsTheWaitOfACancelledCallerAtOnce() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val a = async { q.run("a") { started += "a"; true } }
        val b = async { q.run("b") { started += "b"; true } }
        runCurrent()
        a.cancel()
        runCurrent()
        assertFalse(a.isCompleted)

        q.close()
        runCurrent()

        assertTrue(a.isCompleted)
        assertEquals(GattResult.NotConnected, b.await())
        assertEquals("po close() se b nespouští", listOf("a"), started)
        assertEquals(0L, testScheduler.currentTime)
    }

    // Zrušení se projeví až po čekání a kód za run() se v zrušené korutině neprovede.
    @Test
    fun cancelledCallerEndsWithCancellationOnlyAfterTheWait() = runTest {
        val q = GattOperationQueue()
        var reachedCodeAfterRun = false
        val job = launch {
            q.run("a") { true }
            reachedCodeAfterRun = true
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertFalse("čeká na callback", job.isCompleted)

        q.complete(ok)
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertFalse("po zrušení se kód za run() nesmí provést", reachedCodeAfterRun)
    }

    // Fronta chrání jen spuštěné operace; kdo je zrušený ještě ve frontě, z ní prostě zmizí.
    @Test
    fun cancelledCallerThatIsStillQueuedNeverStarts() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val a = async { q.run("a") { started += "a"; true } }
        val b = async { q.run("b") { started += "b"; true } }
        val c = async { q.run("c") { started += "c"; true } }
        runCurrent()

        b.cancel()
        runCurrent()
        q.complete(ok)
        runCurrent()
        assertEquals(listOf("a", "c"), started)

        q.complete(ok)
        assertEquals(listOf(ok, ok), listOf(a, c).awaitAll())
        assertTrue(b.isCancelled)
    }

    // Zavření

    @Test
    fun closeFailsRunningAndLaterOperations() = runTest {
        val q = GattOperationQueue()
        val a = async { q.run("a") { true } }
        runCurrent()

        q.close()
        assertEquals(GattResult.NotConnected, a.await())

        var started = false
        assertEquals(GattResult.NotConnected, q.run("b") { started = true; true })
        assertFalse("po close() se start nevolá", started)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun closeFailsQueuedOperationsWithoutStartingThem() = runTest {
        val q = GattOperationQueue()
        val started = mutableListOf<String>()
        val jobs = listOf("a", "b", "c").map { name -> async { q.run(name) { started += name; true } } }
        runCurrent()

        q.close()

        assertEquals(List(3) { GattResult.NotConnected }, jobs.awaitAll())
        assertEquals(listOf("a"), started)
    }

    // close() může přijít, zatímco BLE volání ještě běží; operace nesmí čekat až do limitu.
    @Test
    fun closeDuringStartFailsTheOperationInsteadOfWaitingForTheTimeLimit() = runTest {
        val q = GattOperationQueue()

        assertEquals(GattResult.NotConnected, q.run("a") { q.close(); true })
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun closeIsRepeatableAndWorksWithNothingRunning() = runTest {
        val q = GattOperationQueue()

        q.close()
        q.close()

        assertEquals(GattResult.NotConnected, q.run("a") { true })
    }

    // Pozdní callback

    @Test
    fun lateCompleteIsIgnored() = runTest {
        val q = GattOperationQueue()
        q.complete(GattResult.Success())

        val a = async { q.run("a") { true } }
        runCurrent()
        assertFalse("pozdní výsledek nesmí dokončit operaci, která teprve začala", a.isCompleted)

        q.complete(GattResult.Success(number = 2))
        assertEquals(GattResult.Success(number = 2), a.await())
    }

    @Test
    fun resultAfterTheTimeoutIsIgnoredWhenNothingRuns() = runTest {
        val q = GattOperationQueue()
        val a = async { q.run("a") { true } }
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(GattResult.Timeout, a.await())

        q.complete(GattResult.Success(number = 1))

        val b = async { q.run("b") { true } }
        runCurrent()
        assertFalse(b.isCompleted)
        q.complete(GattResult.Failure(133))
        assertEquals(GattResult.Failure(133), b.await())
    }

    // Callback může dorazit dřív, než se vrátí volání, které operaci spustilo.
    @Test
    fun resultThatArrivesBeforeStartReturnsIsNotLost() = runTest {
        val q = GattOperationQueue()

        val result = q.run("a") { q.complete(GattResult.Success(number = 3)); true }

        assertEquals(GattResult.Success(number = 3), result)
        assertEquals(0L, testScheduler.currentTime)
    }

    // Callbacky z binder vláken (spec 5.3)

    @Test
    fun resultFromAnotherThreadWakesTheWaitingOperation() = runBlocking {
        val q = GattOperationQueue()

        repeat(200) { i ->
            val result = q.run("op$i") {
                thread { q.complete(GattResult.Success(number = i)) }
                true
            }
            assertEquals(GattResult.Success(number = i), result)
        }
    }

    @Test
    fun closeFromAnotherThreadFailsTheWaitingOperation() = runBlocking {
        val q = GattOperationQueue()

        val result = q.run("a") {
            thread { q.close() }
            true
        }

        assertEquals(GattResult.NotConnected, result)
    }

    // Výsledek

    @Test
    fun successComparesBytesByContent() {
        assertEquals(GattResult.Success(byteArrayOf(1, 2), 5), GattResult.Success(byteArrayOf(1, 2), 5))
        assertEquals(GattResult.Success(byteArrayOf(1, 2)).hashCode(), GattResult.Success(byteArrayOf(1, 2)).hashCode())
        assertEquals(GattResult.Success(), GattResult.Success())
        assertNotEquals(GattResult.Success(byteArrayOf(1, 2)), GattResult.Success(byteArrayOf(1, 3)))
        assertNotEquals(GattResult.Success(byteArrayOf(1, 2), 5), GattResult.Success(byteArrayOf(1, 2), 6))
        assertNotEquals(GattResult.Success(null), GattResult.Success(byteArrayOf()))
    }
}
