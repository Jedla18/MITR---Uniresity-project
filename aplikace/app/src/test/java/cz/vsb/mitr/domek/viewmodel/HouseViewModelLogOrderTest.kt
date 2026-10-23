package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.sim.SimulatedHouseDataSource
import cz.vsb.mitr.domek.testing.FakeHouseDataSource
import cz.vsb.mitr.domek.testing.HouseViewModelTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

// Na zařízení sbírá ViewModel Stav přes Dispatchers.Main.immediate, takže tlačítko simulace dojde až k novému Stavu ještě
// uvnitř svého volání. Řádek tlačítka je příčina, v Deníku (nejnovější nahoře) proto musí stát pod svými následky.
// Simulace začíná v 10:02:15, tedy ve dne, a hned topí.
@OptIn(ExperimentalCoroutinesApi::class)
class HouseViewModelLogOrderTest : HouseViewModelTestBase() {
    private fun TestScope.simulationViewModel(): HouseViewModel {
        val vm = newViewModel { kind, scope ->
            when (kind) {
                SourceKind.BLUETOOTH -> FakeHouseDataSource()
                SourceKind.SIMULATION -> SimulatedHouseDataSource(scope = scope, time = timeSource(), wallClock = { phoneTime() })
            }
        }
        vm.selectSource(SourceKind.SIMULATION)
        return vm
    }

    private fun HouseViewModel.titles() = uiState.value.log.map { it.event.title }

    @Test
    fun motionIsAboveTheButtonThatCausedIt() = vmTest(immediateMain = true) {
        val vm = simulationViewModel()

        vm.simulation(SimulationAction.MOTION)

        assertEquals(listOf("Pohyb", "Simulace: pohyb", "Simulace spuštěna"), vm.titles())
    }

    // Zakryté čidlo ve dne znamená šero, takže se rozsvítí světlo v automatice.
    @Test
    fun lightIsAboveTheCoveredSensor() = vmTest(immediateMain = true) {
        val vm = simulationViewModel()

        vm.simulation(SimulationAction.COVER_LIGHT_SENSOR)

        assertEquals(listOf("Světlo se rozsvítilo", "Simulace: zakryté čidlo světla", "Simulace spuštěna"), vm.titles())
    }

    // Porucha BME280 zastaví topení: nahoře následek poruchy, pod ním porucha, pod ní tlačítko.
    @Test
    fun faultAndWhatItStoppedAreAboveTheButton() = vmTest(immediateMain = true) {
        val vm = simulationViewModel()

        vm.simulation(SimulationAction.TOGGLE_SENSOR_FAULT)

        assertEquals(
            listOf(
                "Přestalo topit",
                "Porucha: čidlo teploty, vlhkosti a tlaku (BME280) neodpovídá",
                "Simulace: porucha BME280 zapnutá",
                "Simulace spuštěna",
            ),
            vm.titles(),
        )
    }
}
