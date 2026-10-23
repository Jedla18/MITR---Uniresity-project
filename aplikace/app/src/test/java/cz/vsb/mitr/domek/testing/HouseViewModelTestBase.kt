package cz.vsb.mitr.domek.testing

import androidx.lifecycle.viewModelScope
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.viewmodel.HouseViewModel
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// Společný základ testů HouseViewModelu: virtuální čas, hodiny telefonu, které běží s ním, a ViewModely,
// které se po testu vždy zastaví.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class HouseViewModelTestBase {
    // Hodiny telefonu v čase 0 virtuálního času testu; dál běží spolu s ním.
    protected val startedAt: LocalDateTime = LocalDateTime.of(2026, 10, 20, 10, 2, 15)
    private val viewModels = mutableListOf<HouseViewModel>()

    // Tik po 500 ms nikdy nedoběhne, takže na konci runTest by čekal donekonečna. Proto se ViewModely zastaví vždy,
    // i když test selže dřív. S immediateMain spouští hlavní dispečer korutiny hned, jako Dispatchers.Main.immediate
    // na zařízení, takže se ukáže pořadí, ve kterém se věci opravdu stanou uvnitř jednoho volání.
    protected fun vmTest(immediateMain: Boolean = false, body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(if (immediateMain) UnconfinedTestDispatcher(testScheduler) else StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            viewModels.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }

    protected fun TestScope.phoneTime(): LocalDateTime = startedAt.plusNanos(testScheduler.currentTime * 1_000_000)

    protected fun TestScope.timeSource() = TimeSource { testScheduler.currentTime }

    // Stav přijatý právě teď, tedy s časem příjmu podle virtuálních hodin.
    protected fun TestScope.receivedNow(status: HouseStatus = EXAMPLE) = ReceivedStatus(status, testScheduler.currentTime, phoneTime())

    // Každý ViewModel z testu musí vzniknout tady, jinak by ho vmTest na konci nezastavil.
    protected fun TestScope.newViewModel(
        wallClock: () -> LocalDateTime = { phoneTime() },
        time: TimeSource = timeSource(),
        createSource: (SourceKind, CoroutineScope) -> HouseDataSource,
    ): HouseViewModel = HouseViewModel(createSource, time, wallClock).also(viewModels::add)
}
