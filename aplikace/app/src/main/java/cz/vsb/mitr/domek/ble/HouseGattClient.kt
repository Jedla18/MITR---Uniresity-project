package cz.vsb.mitr.domek.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Domek"

// Časové limity (spec 5.3): samotné připojení, každý krok přípravy spojení a běžná operace.
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val PREPARE_STEP_TIMEOUT_MS = 10_000L
private const val OPERATION_TIMEOUT_MS = 5_000L

// Výchozí MTU BLE; platí, dokud telefon nepotvrdí větší.
private const val DEFAULT_MTU = 23

sealed interface PrepareResult {
    data class Ready(val mtu: Int) : PrepareResult

    data class Failed(val status: Int) : PrepareResult

    data object MissingService : PrepareResult
}

// Jedno spojení GATT s domkem (spec 5.3). Každý pokus o připojení dostane nového klienta, a tím i novou frontu.
// Callbacky chodí na binder vláknech a jen dokončí čekající operaci nebo předají data dál.
@SuppressLint("MissingPermission") // oprávnění kontroluje BleHouseDataSource před každým použitím
class HouseGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onStatus: (ByteArray) -> Unit,
    private val onSettings: (ByteArray) -> Unit,
    // Navázané spojení skončilo, ať výpadkem, nebo přes close(). Volá se nejvýš jednou.
    private val onLinkLost: (status: Int) -> Unit,
) {
    private enum class Operation { MTU, SERVICES, DESCRIPTOR_WRITE, READ, WRITE, RSSI }

    private val queue = GattOperationQueue(OPERATION_TIMEOUT_MS)
    private val operationLock = Mutex()
    private val runningOperation = AtomicReference<Operation?>(null)
    private val closed = AtomicBoolean(false)

    // GATT_SUCCESS, když se spojení navázalo, jinak chybový status, se kterým skončilo dřív.
    private val connected = CompletableDeferred<Int>()

    @Volatile
    private var gatt: BluetoothGatt? = null

    // Charakteristiky služby Domek; prázdné, dokud příprava spojení neskončí úspěchem.
    @Volatile
    private var characteristics: Map<UUID, BluetoothGattCharacteristic> = emptyMap()

    suspend fun connectAndPrepare(onLinkUp: () -> Unit): PrepareResult {
        val gatt = openGatt() ?: return PrepareResult.Failed(BluetoothGatt.GATT_FAILURE)
        val connectStatus = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
        if (connectStatus != BluetoothGatt.GATT_SUCCESS) {
            val reason = connectStatus?.let { "status $it" } ?: "bez odpovědi do $CONNECT_TIMEOUT_MS ms"
            Log.w(TAG, "Připojení k ${device.address} selhalo: $reason")
            return PrepareResult.Failed(connectStatus ?: BluetoothGatt.GATT_FAILURE)
        }
        onLinkUp()
        return prepare(gatt)
    }

    suspend fun write(characteristic: UUID, payload: ByteArray): GattResult {
        val target = characteristics[characteristic] ?: return GattResult.NotConnected
        return runOperation(Operation.WRITE) { writeCharacteristic(it, target, payload) }
    }

    // Přečtené Nastavení předá dál callback (readFinished), stejně jako při přípravě spojení.
    suspend fun readSettings() {
        val target = characteristics[BleUuids.SETTINGS] ?: return
        runOperation(Operation.READ) { it.readCharacteristic(target) }
    }

    suspend fun readRssi(): Int? =
        (runOperation(Operation.RSSI) { it.readRemoteRssi() } as? GattResult.Success)?.number

    // Lze volat opakovaně a z libovolného vlákna. Navázané spojení skončí přes onLinkLost(GATT_SUCCESS),
    // stejně jako když se telefon odpojí sám.
    fun close() = end(BluetoothGatt.GATT_SUCCESS, gatt)

    // Náhrada connectGatt(BluetoothGattConnectionSettings, …) existuje až od Androidu 17 (API 37).
    @Suppress("DEPRECATION")
    private fun openGatt(): BluetoothGatt? {
        if (closed.get()) return null
        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connectGatt: chybí oprávnění", e)
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "connectGatt: Bluetooth není zapnutý", e)
            null
        }
        if (gatt == null) return null
        this.gatt = gatt
        // close() nebo výpadek mohly přijít dřív, než se connectGatt vrátil; spojení by pak zůstalo otevřené.
        if (closed.get()) {
            closeGatt(gatt)
            return null
        }
        return gatt
    }

    private suspend fun prepare(gatt: BluetoothGatt): PrepareResult {
        // Odchylka od spec 5.3: bez potvrzeného MTU se pokračuje s 23. Některé telefony onMtuChanged nepošlou,
        // když je MTU už vyjednané (Android 14+ si 517 vyjedná sám).
        val mtuResult = runOperation(Operation.MTU, PREPARE_STEP_TIMEOUT_MS) { it.requestMtu(BleUuids.REQUESTED_MTU) }
        val mtu = if (mtuResult is GattResult.Success) {
            mtuResult.number
        } else {
            Log.i(TAG, "MTU nepotvrzeno ($mtuResult), pokračuje se s $DEFAULT_MTU")
            DEFAULT_MTU
        }
        // Vyjednané menší MTU by každý Stav uřízlo, takže je to chyba spojení (docs/ble-rozhrani.md, Připojení a MTU 517).
        if (mtuResult is GattResult.Success && !mtuCarriesWholeJson(mtu)) {
            Log.w(TAG, "Vyjednané MTU $mtu je menší než 515, celý Stav se do notifikace nevejde")
            return PrepareResult.Failed(BluetoothGatt.GATT_FAILURE)
        }

        val discovery = runOperation(Operation.SERVICES, PREPARE_STEP_TIMEOUT_MS) { it.discoverServices() }
        if (discovery !is GattResult.Success) return failed("hledání služeb", discovery)
        var found = houseCharacteristics(gatt)
        // Android si pamatuje služby zařízení a po výměně programu na Raspberry může vidět starou strukturu
        // (docs/ble-rozhrani.md, Ztráta spojení a obnova). Proto se mezipaměť jednou obnoví a služby se hledají znovu.
        if (found == null && refreshServiceCache(gatt)) {
            val again = runOperation(Operation.SERVICES, PREPARE_STEP_TIMEOUT_MS) { it.discoverServices() }
            if (again !is GattResult.Success) return failed("hledání služeb po obnově mezipaměti", again)
            found = houseCharacteristics(gatt)
        }
        if (found == null) {
            Log.w(TAG, "Zařízení ${device.address} nemá službu Domek se všemi charakteristikami")
            return PrepareResult.MissingService
        }

        for ((name, uuid) in listOf("Stav" to BleUuids.STATUS, "Nastavení" to BleUuids.SETTINGS)) {
            val result = runOperation(Operation.DESCRIPTOR_WRITE, PREPARE_STEP_TIMEOUT_MS) {
                enableNotifications(it, found.getValue(uuid))
            }
            if (result !is GattResult.Success) return failed("zapnutí notifikací ($name)", result)
        }
        // Přečtená data předá dál už callback (readFinished).
        for ((name, uuid) in listOf("Nastavení" to BleUuids.SETTINGS, "Stav" to BleUuids.STATUS)) {
            val result = runOperation(Operation.READ, PREPARE_STEP_TIMEOUT_MS) {
                it.readCharacteristic(found.getValue(uuid))
            }
            if (result !is GattResult.Success) return failed("čtení ($name)", result)
        }
        characteristics = found
        return PrepareResult.Ready(mtu)
    }

    // null, když služba Domek nebo některá z jejích tří charakteristik chybí.
    private fun houseCharacteristics(gatt: BluetoothGatt): Map<UUID, BluetoothGattCharacteristic>? {
        val service = gatt.getService(BleUuids.HOUSE_SERVICE) ?: return null
        val required = listOf(BleUuids.STATUS, BleUuids.SETTINGS, BleUuids.COMMAND)
        val found = required.mapNotNull { uuid -> service.getCharacteristic(uuid)?.let { uuid to it } }.toMap()
        return found.takeIf { it.size == required.size }
    }

    // BluetoothGatt.refresh() je skrytá metoda, proto přes reflexi. Kde ji systém nepustí nebo selže, vrátí false.
    private fun refreshServiceCache(gatt: BluetoothGatt): Boolean =
        try {
            gatt.javaClass.getMethod("refresh").invoke(gatt) == true
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "Mezipaměť služeb nejde obnovit", e)
            false
        } catch (e: RuntimeException) {
            Log.i(TAG, "Mezipaměť služeb nejde obnovit", e)
            false
        }

    private fun failed(step: String, result: GattResult): PrepareResult.Failed {
        Log.w(TAG, "Příprava spojení: $step skončilo $result")
        return PrepareResult.Failed((result as? GattResult.Failure)?.status ?: BluetoothGatt.GATT_FAILURE)
    }

    // Fronta pouští operace po jedné. Zámek navíc drží operaci až po případné zavření spojení: na operaci bez
    // odpovědi může Android odpovědět později a ta odpověď by dokončila další operaci. Jakmile tedy operace
    // vyprší, spojení se zavře dřív, než může začít další (kromě MTU, viz prepare a finish).
    private suspend fun runOperation(
        operation: Operation,
        timeoutMs: Long = OPERATION_TIMEOUT_MS,
        start: (BluetoothGatt) -> Boolean,
    ): GattResult = operationLock.withLock {
        val gatt = gatt ?: return@withLock GattResult.NotConnected
        val result = try {
            queue.run(operation.name, timeoutMs) {
                runningOperation.set(operation)
                // Nejen SecurityException: zápisy pro Android 13+ hodí RuntimeException, když spadne proces
                // Bluetooth, a výjimka by jinak přes frontu ukončila řídicí smyčku nebo shodila aplikaci.
                try {
                    start(gatt)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "$operation nejde spustit", e)
                    false
                }
            }
        } finally {
            runningOperation.set(null)
        }
        if (result == GattResult.Timeout && operation != Operation.MTU) {
            Log.w(TAG, "$operation bez odpovědi do $timeoutMs ms, spojení se zavře")
            close()
        }
        result
    }

    // setCharacteristicNotification jen přihlásí příjem v telefonu; notifikace zapne až zápis do CCCD.
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val cccd = characteristic.getDescriptor(BleUuids.CCCD) ?: return false
        return gatt.setCharacteristicNotification(characteristic, true) &&
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    // Android 13 předává hodnotu v parametru; starší verze ji čtou ze sdíleného objektu charakteristiky.
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.setValue(value)
            gatt.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.setValue(value)
            gatt.writeDescriptor(descriptor)
        }

    // Jediný konec spojení, ať ho způsobil Android, nebo aplikace; proběhne nejvýš jednou.
    private fun end(status: Int, gatt: BluetoothGatt?) {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        gatt?.let(::closeGatt)
        // Spojení, které ještě nevzniklo, jen ukončí čekání na připojení, a to vždy jako neúspěch.
        val failure = if (status == BluetoothGatt.GATT_SUCCESS) BluetoothGatt.GATT_FAILURE else status
        if (!connected.complete(failure)) onLinkLost(status)
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "BluetoothGatt.close: chybí oprávnění", e)
        }
    }

    // Callback dokončí jen operaci svého druhu. Pozdní onMtuChanged po timeoutu MTU tak nedokončí hledání služeb.
    private fun finish(operation: Operation, result: GattResult) {
        if (claim(operation)) queue.complete(result)
    }

    private fun claim(operation: Operation): Boolean {
        if (runningOperation.compareAndSet(operation, null)) return true
        Log.w(TAG, "Callback $operation nepatří k běžící operaci (${runningOperation.get()}), zahazuje se")
        return false
    }

    // Přečtená data jdou dál přímo z callbacku, ve stejném pořadí jako notifikace, takže je nepřepíšou starší.
    private fun readFinished(characteristic: UUID, value: ByteArray, status: Int) {
        if (!claim(Operation.READ)) return
        if (status == BluetoothGatt.GATT_SUCCESS) deliver(characteristic, value)
        queue.complete(resultOf(status))
    }

    private fun deliver(characteristic: UUID, value: ByteArray) {
        if (closed.get()) return
        when (characteristic) {
            BleUuids.STATUS -> onStatus(value.copyOf())
            BleUuids.SETTINGS -> onSettings(value.copyOf())
        }
    }

    private fun resultOf(status: Int, number: Int = 0): GattResult =
        if (status == BluetoothGatt.GATT_SUCCESS) GattResult.Success(number = number) else GattResult.Failure(status)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED ->
                    connected.complete(BluetoothGatt.GATT_SUCCESS)
                // Chyba (i 133 a 147) nebo odpojení: BluetoothGatt se vždy zavře, další pokus dostane nového klienta.
                status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Spojení s ${device.address} skončilo: status $status, stav $newState")
                    end(status, gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            finish(Operation.MTU, resultOf(status, mtu))

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            finish(Operation.SERVICES, resultOf(status))

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(Operation.DESCRIPTOR_WRITE, resultOf(status))

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(Operation.WRITE, resultOf(status))

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) =
            finish(Operation.RSSI, resultOf(status, rssi))

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = readFinished(characteristic.uuid, value, status)

        // Starší verze jen pro Android 12 a nižší: novější volá přetížení s hodnotou a data by se zpracovala dvakrát.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                readFinished(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = deliver(characteristic.uuid, value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { deliver(characteristic.uuid, it) }
            }
        }
    }
}
