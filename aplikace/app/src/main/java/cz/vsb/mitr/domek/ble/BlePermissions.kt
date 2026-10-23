package cz.vsb.mitr.domek.ble

import android.Manifest
import android.os.Build

// Oprávnění pro hledání a připojení (spec 5.3). Verze Androidu je parametr, aby šly obě větve zkoušet v JVM testu;
// z android.* se tu čtou jen konstanty, které překladač vloží přímo do kódu.
object BlePermissions {
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    // Android 11 a starší vrátí výsledky hledání BLE jen při zapnuté poloze v systému.
    fun needsLocationServices(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S
}
