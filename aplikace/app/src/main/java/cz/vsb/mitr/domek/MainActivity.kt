package cz.vsb.mitr.domek

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.vsb.mitr.domek.ble.BlePermissions
import cz.vsb.mitr.domek.ui.DomekApp
import cz.vsb.mitr.domek.ui.SystemActions
import cz.vsb.mitr.domek.ui.theme.DomekTheme
import cz.vsb.mitr.domek.viewmodel.HouseViewModel

// Aktivita jen spojuje ViewModel s obrazovkami a dělá to, co umí jedině ona: žádá o oprávnění, otevírá systémová
// nastavení a po návratu z nich nechá zdroj dat znovu ověřit, jestli se může připojit.
class MainActivity : ComponentActivity() {
    private val viewModel: HouseViewModel by viewModels { HouseViewModel.factory(applicationContext) }

    // Po zamítnutí s „Už se neptat“ systém další žádost nezobrazí, obrazovka proto nabídne nastavení aplikace. Hodnota se
    // ukládá, protože po otočení nebo zániku procesu by se jinak nabídlo „Povolit“, které nic neudělá. Internal je kvůli testu.
    internal var permissionsPermanentlyDenied by mutableStateOf(false)

    // Kolikrát v tomto běhu žádost o oprávnění skončila zamítnutím. Zamítnutí bez vysvětlení (shouldShowRequestPermissionRationale)
    // vrátí i dialog zavřený klepnutím mimo (Android 11+), který systém příště ukáže znovu. Za trvalé se proto bere až druhé
    // zamítnutí; ukládá se ze stejného důvodu jako příznak výš.
    private var permissionDenials = 0

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { granted -> !granted }.keys
            if (denied.isNotEmpty()) permissionDenials++
            permissionsPermanentlyDenied = permissionDenials >= 2 && denied.any { !shouldShowRequestPermissionRationale(it) }
            viewModel.retryConnection()
        }

    private val bluetoothEnableRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.retryConnection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        permissionsPermanentlyDenied = savedInstanceState?.getBoolean(STATE_PERMISSIONS_PERMANENTLY_DENIED) == true
        permissionDenials = savedInstanceState?.getInt(STATE_PERMISSION_DENIALS) ?: 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            DomekTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val system = SystemActions(
                    requestPermissions = ::requestPermissions,
                    enableBluetooth = ::enableBluetooth,
                    openAppSettings = ::openAppSettings,
                    openLocationSettings = ::openLocationSettings,
                    permissionsPermanentlyDenied = permissionsPermanentlyDenied,
                )
                DomekApp(state, viewModel, system, viewModel.messages)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PERMISSIONS_PERMANENTLY_DENIED, permissionsPermanentlyDenied)
        outState.putInt(STATE_PERMISSION_DENIALS, permissionDenials)
    }

    // Oprávnění nebo polohu mohl uživatel změnit v nastavení a zdroj to sám nezjistí (Bluetooth sleduje sám).
    override fun onResume() {
        super.onResume()
        viewModel.recheckPrerequisites()
    }

    private fun requestPermissions() {
        permissionRequest.launch(BlePermissions.required(Build.VERSION.SDK_INT).toTypedArray())
    }

    private fun enableBluetooth() {
        bluetoothEnableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private companion object {
        const val STATE_PERMISSIONS_PERMANENTLY_DENIED = "permissionsPermanentlyDenied"
        const val STATE_PERMISSION_DENIALS = "permissionDenials"
    }
}
