package cz.vsb.mitr.domek.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import cz.vsb.mitr.domek.data.model.decodeSettings
import cz.vsb.mitr.domek.data.model.decodeStatus
import cz.vsb.mitr.domek.data.model.storedLookPatch
import cz.vsb.mitr.domek.data.model.toJsonObject
import cz.vsb.mitr.domek.data.source.BleControls
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.FoundDevice
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.data.source.WriteResult
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Domek"

// Okno hledání, čtení RSSI a hlídání dat (spec 5.3).
private const val SCAN_WINDOW_MS = 2_000L
private const val RSSI_INTERVAL_MS = 5_000L
private const val DATA_TIMEOUT_MS = 15_000L

// Kolik znaků nečitelných dat se zapíše do logu.
private const val LOG_PREVIEW_CHARS = 80

// Limit spuštění hledání platí pro celou aplikaci a ViewModel vytváří nový zdroj při každém přepnutí mezi
// Raspberry a simulací, proto je throttle jeden pro celý proces. Používá se jen z hlavního vlákna.
private val scanThrottle = ScanThrottle()

// Vybraný domek platí do konce běhu aplikace (spec 5.3). Ze stejného důvodu jako throttle si ho pamatuje proces, ne zdroj: po
// přepnutí na simulaci a zpět se nový zdroj připojí k témuž domku bez nového hledání a výběru. Používá se jen z hlavního vlákna.
private object KnownDevice {
    var address: String? = null
    var device: BluetoothDevice? = null
}

// Zdroj dat z Raspberry přes Bluetooth LE (spec 5.3): hledání, připojení, znovupřipojení a hlídání dat.
// Řídí ho jedna smyčka ve scope (ViewModel, hlavní vlákno). Vlastní stav zdroje mění jen hlavní vlákno;
// BLE callbacky z binder vláken jen nastavují StateFlow, dokončují deferredy a posílají do kanálů.
@SuppressLint("MissingPermission") // oprávnění ověří missingPrerequisite() před hledáním i připojením
class BleHouseDataSource(
    context: Context,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val wallClock: () -> LocalDateTime,
) : HouseDataSource, BleControls {
    // Zdroj žije ve ViewModelu, proto nesmí držet aktivitu.
    private val context: Context = context.applicationContext
    private val adapter: BluetoothAdapter? = this.context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _status = MutableStateFlow<ReceivedStatus?>(null)
    private val _settings = MutableStateFlow<HouseSettings?>(null)

    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val status: StateFlow<ReceivedStatus?> = _status.asStateFlow()
    override val settings: StateFlow<HouseSettings?> = _settings.asStateFlow()

    // Probouzí smyčku (retry, ruční připojení, výběr zařízení, změna Bluetooth); víc probuzení se slije v jedno.
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val scanFilters = listOf(ScanFilter.Builder().setDeviceName(BleUuids.DEVICE_NAME).build())
    private val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private var loopJob: Job? = null
    private var client: HouseGattClient? = null
    private var activeScan: ScanCallback? = null

    // Vybraný domek platí do konce běhu aplikace (KnownDevice); zapomene ho jen ruční Připojit nebo chybějící služba
    // (spec 5.3). knownDevice je objekt z výsledku hledání: na rozdíl od getRemoteDevice(adresa), které vytvoří vždy
    // veřejnou adresu, nese typ adresy z reklamy.
    private var knownAddress: String? by KnownDevice::address
    private var knownDevice: BluetoothDevice? by KnownDevice::device
    private var chosenAddress: String? = null
    private var userDisconnected = false
    private var attempt = 0

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> wake()
                // Spojení se zavře hned; smyčka pak ukáže vypnutý Bluetooth a po zapnutí se připojí znovu.
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    client?.close()
                    wake()
                }
            }
        }
    }

    override fun start() {
        if (loopJob != null) return
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loopJob = scope.launch { runLoop() }
    }

    override fun stop() {
        val job = loopJob ?: return
        loopJob = null
        // Smyčka se zruší jako první, aby po zavření klienta už nenastavila žádný další stav.
        job.cancel()
        activeScan?.let(::stopScan)
        client?.close()
        client = null
        context.unregisterReceiver(bluetoothStateReceiver)
        _connection.value = ConnectionState.Idle
    }

    override suspend fun writeSettings(changes: List<SettingChange>): WriteResult =
        write(BleUuids.SETTINGS, changes.toJsonObject().toString())

    override suspend fun writeStoredLook(color: Rgb?, brightness: Int?): WriteResult =
        write(BleUuids.SETTINGS, storedLookPatch(color, brightness).toString())

    override suspend fun sendCommand(command: HouseCommand): WriteResult =
        write(BleUuids.COMMAND, command.toJsonObject().toString())

    override suspend fun refreshSettings() {
        val client = client
        if (client == null || _connection.value !is ConnectionState.Connected) return
        // Ve scope zdroje ze stejného důvodu jako zápis: timeout čtení musí vidět klient, aby spojení zavřel.
        scope.async { client.readSettings() }.await()
    }

    override fun chooseDevice(address: String) {
        chosenAddress = address
        wake()
    }

    override fun disconnectByUser() {
        // Příznak se nastaví před zavřením, aby smyčka konec relace brala jako odpojení uživatelem.
        userDisconnected = true
        client?.close()
        wake()
    }

    override fun connectByUser() {
        userDisconnected = false
        forgetDevice()
        attempt = 0
        wake()
    }

    override fun retry() = wake()

    private fun wake() {
        wakeups.trySend(Unit)
    }

    private fun forgetDevice() {
        knownAddress = null
        knownDevice = null
    }

    private suspend fun runLoop() {
        while (true) {
            val missing = missingPrerequisite()
            if (missing != null) {
                _connection.value = missing
                wakeups.receive()
                continue
            }
            if (userDisconnected) {
                _connection.value = ConnectionState.DisconnectedByUser
                wakeups.receive()
                continue
            }
            val address = knownAddress
            if (address == null) {
                // Hledání trvá, předpoklady i odpojení uživatelem se proto před připojením ověří znovu.
                val chosen = scanAndChoose()
                knownDevice = chosen
                knownAddress = chosen?.address
                continue
            }
            attempt++
            _connection.value = ConnectionState.Connecting(attempt)
            if (!connectAndRunSession(address)) {
                // Zařízení bez služby Domek se nepřipojuje dokola (spec 5.3); další pokus začne novým hledáním.
                forgetDevice()
                _connection.value = ConnectionState.MissingService
                wakeups.receive()
                continue
            }
            if (!userDisconnected) {
                val retryAtMs = time.nowMs() + reconnectDelayMs(maxOf(attempt, 1))
                _connection.value = ConnectionState.WaitingToReconnect(attempt, retryAtMs)
                withTimeoutOrNull(retryAtMs - time.nowMs()) { wakeups.receive() }
            }
        }
    }

    // Jeden pokus o připojení, a když se povede, celá relace až do konce spojení.
    // Vrátí false, když zařízení nemá službu Domek.
    private suspend fun connectAndRunSession(address: String): Boolean {
        // getRemoteDevice(adresa) jen jako záloha, když objekt zařízení z hledání chybí.
        val device = knownDevice?.takeIf { it.address == address }
            ?: adapter?.getRemoteDevice(address)
            ?: return true
        val linkEnded = CompletableDeferred<Int>()
        val client = HouseGattClient(context, device, ::receiveStatus, ::receiveSettings) { linkEnded.complete(it) }
        this.client = client
        try {
            return when (val prepared = client.connectAndPrepare { _connection.value = ConnectionState.Preparing }) {
                is PrepareResult.Ready -> {
                    attempt = 0
                    _connection.value = ConnectionState.Connected(address, prepared.mtu, rssi = null)
                    runSession(client, linkEnded)
                    true
                }
                PrepareResult.MissingService -> false
                is PrepareResult.Failed -> {
                    Log.i(TAG, "Pokus $attempt o připojení k $address selhal (status ${prepared.status})")
                    true
                }
            }
        } finally {
            client.close()
            if (this.client === client) this.client = null
        }
    }

    // Relace běží, dokud spojení neskončí: výpadkem, timeoutem operace i přes close() (viz HouseGattClient).
    private suspend fun runSession(client: HouseGattClient, linkEnded: Deferred<Int>) {
        val startedAtMs = time.nowMs()
        coroutineScope {
            val signal = launch { readSignalPeriodically(client) }
            val watchdog = launch { watchData(client, startedAtMs) }
            Log.i(TAG, "Spojení skončilo (status ${linkEnded.await()})")
            signal.cancel()
            watchdog.cancel()
        }
    }

    private suspend fun readSignalPeriodically(client: HouseGattClient) {
        while (true) {
            client.readRssi()?.let { rssi ->
                _connection.update { if (it is ConnectionState.Connected) it.copy(rssi = rssi) else it }
            }
            delay(RSSI_INTERVAL_MS)
        }
    }

    // Hlídání dat (spec 5.3): spojení, kterým 15 s nepřišel Stav, se zavře a naváže znovu. Měří se nejdřív
    // od začátku relace, jinak by po delším výpadku zavřelo nové spojení hned.
    private suspend fun watchData(client: HouseGattClient, sessionStartMs: Long) {
        while (true) {
            val lastDataMs = maxOf(sessionStartMs, _status.value?.receivedAtMs ?: sessionStartMs)
            val silentMs = time.nowMs() - lastDataMs
            if (silentMs > DATA_TIMEOUT_MS) break
            delay(DATA_TIMEOUT_MS - silentMs + 1)
        }
        Log.w(TAG, "Hlídání dat: přes $DATA_TIMEOUT_MS ms bez Stavu, spojení se zavře")
        client.close()
    }

    private suspend fun write(characteristic: UUID, json: String): WriteResult {
        val client = client
        if (client == null || _connection.value !is ConnectionState.Connected) return WriteResult.NotConnected
        // Operace běží ve scope zdroje: zrušený volající (třeba odložený zápis ve ViewModelu) ji nepřeruší,
        // takže případný timeout vždy uvidí klient a spojení zavře.
        val result = scope.async { client.write(characteristic, json.encodeToByteArray()) }.await()
        return when (result) {
            is GattResult.Success -> WriteResult.Ok
            is GattResult.Failure -> WriteResult.Rejected(result.status)
            GattResult.Timeout -> WriteResult.Timeout
            GattResult.NotConnected, GattResult.StartFailed -> WriteResult.NotConnected
        }
    }

    private fun receiveStatus(bytes: ByteArray) {
        val status = decodeStatus(bytes) ?: return logUnreadable("Neplatný Stav", bytes)
        _status.value = ReceivedStatus(status, time.nowMs(), wallClock())
    }

    private fun receiveSettings(bytes: ByteArray) {
        _settings.value = decodeSettings(bytes) ?: return logUnreadable("Neplatné Nastavení", bytes)
    }

    // Nečitelná data se zahodí a poslední dobrá zůstanou (spec 5.2).
    private fun logUnreadable(what: String, bytes: ByteArray) {
        Log.w(TAG, "$what (${bytes.size} B): ${bytes.decodeToString().take(LOG_PREVIEW_CHARS)}")
    }

    private fun missingPrerequisite(): ConnectionState? {
        val adapter = adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return ConnectionState.NoBluetooth
        }
        val sdk = Build.VERSION.SDK_INT
        val granted = BlePermissions.required(sdk).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return ConnectionState.PermissionsMissing
        if (!adapter.isEnabled) return ConnectionState.BluetoothOff
        // Poloha je potřeba jen k hledání; ke známé adrese se telefon připojí i bez ní.
        if (knownAddress == null && BlePermissions.needsLocationServices(sdk) && !isLocationEnabled()) {
            return ConnectionState.LocationOff
        }
        return null
    }

    private fun isLocationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: true

    // Hledání skončí, když uživatel odpojil nebo chybí předpoklad (třeba vypnutý Bluetooth); důvod ukáže smyčka.
    private fun scanMustEnd(): Boolean = userDisconnected || missingPrerequisite() != null

    // Hledání domku (spec 5.3). Vrátí zařízení, ke kterému se připojit, nebo null, když hledání ztratilo smysl.
    private suspend fun scanAndChoose(): BluetoothDevice? {
        while (true) {
            _connection.value = ConnectionState.Scanning
            if (!awaitScanSlot()) return null
            val signals = Channel<ScanSignal>(Channel.UNLIMITED)
            val callback = ForwardingScanCallback(signals)
            scanThrottle.onStarted(time.nowMs())
            if (!startScan(callback)) continue
            val outcome = try {
                collectScanResults(signals)
            } finally {
                stopScan(callback)
            }
            when (outcome) {
                is ScanOutcome.Chosen -> return outcome.device
                ScanOutcome.Aborted -> return null
                ScanOutcome.Failed -> {} // hledá se znovu, jakmile to dovolí throttle
            }
        }
    }

    // Android povolí nejvýš 5 spuštění hledání za 30 s. Vrátí false, když hledání mezitím ztratilo smysl.
    private suspend fun awaitScanSlot(): Boolean {
        while (true) {
            val waitMs = scanThrottle.delayBeforeStart(time.nowMs())
            if (waitMs <= 0) return !scanMustEnd()
            if (withTimeoutOrNull(waitMs) { wakeups.receive() } != null && scanMustEnd()) return false
        }
    }

    // Na první nález se čeká bez omezení, potom ještě 2 s na další zařízení stejného jména (spec 5.3).
    private suspend fun collectScanResults(signals: ReceiveChannel<ScanSignal>): ScanOutcome {
        // Podle adresy vždy poslední nález: aktuální RSSI a objekt zařízení pro připojení.
        val found = LinkedHashMap<String, ScanSignal.Found>()
        var windowEndsAtMs = Long.MAX_VALUE
        while (found.isEmpty() || time.nowMs() < windowEndsAtMs) {
            val signal = if (found.isEmpty()) {
                nextScanSignal(signals)
            } else {
                withTimeoutOrNull(windowEndsAtMs - time.nowMs()) { nextScanSignal(signals) } ?: break
            }
            when (signal) {
                is ScanSignal.Found -> {
                    if (found.isEmpty()) windowEndsAtMs = time.nowMs() + SCAN_WINDOW_MS
                    found[signal.device.address] = signal
                }
                is ScanSignal.Failed -> return scanFailed(signal.errorCode)
                ScanSignal.Wakeup -> if (scanMustEnd()) return ScanOutcome.Aborted
            }
        }
        Log.i(TAG, "Hledání: nalezeno ${found.size}x ${BleUuids.DEVICE_NAME}")
        return when (val decision = decideAfterScanWindow(found.values.map { it.device })) {
            is ScanDecision.Connect -> ScanOutcome.Chosen(found.getValue(decision.device.address).bluetoothDevice)
            is ScanDecision.Choose -> awaitChoice(signals, found, decision.devices)
        }
    }

    // Víc domků: hledání běží dál, seznam se průběžně doplňuje a čeká se na výběr uživatele (spec 5.3).
    private suspend fun awaitChoice(
        signals: ReceiveChannel<ScanSignal>,
        found: MutableMap<String, ScanSignal.Found>,
        sorted: List<FoundDevice>,
    ): ScanOutcome {
        // Pořadí z konce okna zůstává a nová zařízení přibývají na konec, aby položky pod prstem neposkakovaly.
        val order = sorted.mapTo(mutableListOf()) { it.address }
        chosenAddress = null
        _connection.value = ConnectionState.ChoosingDevice(sorted)
        while (true) {
            when (val signal = nextScanSignal(signals)) {
                is ScanSignal.Found -> {
                    val address = signal.device.address
                    found[address] = signal
                    if (address !in order) order += address
                    _connection.value = ConnectionState.ChoosingDevice(order.map { found.getValue(it).device })
                }
                is ScanSignal.Failed -> return scanFailed(signal.errorCode)
                ScanSignal.Wakeup -> {
                    if (scanMustEnd()) return ScanOutcome.Aborted
                    chosenAddress?.let(found::get)?.let { return ScanOutcome.Chosen(it.bluetoothDevice) }
                }
            }
        }
    }

    // Probuzení má přednost, aby výběr zařízení nebo odpojení nečekaly za proudem výsledků hledání.
    private suspend fun nextScanSignal(signals: ReceiveChannel<ScanSignal>): ScanSignal = select {
        wakeups.onReceive { ScanSignal.Wakeup }
        signals.onReceive { it }
    }

    private fun scanFailed(errorCode: Int): ScanOutcome {
        Log.w(TAG, "Hledání selhalo (kód $errorCode), spustí se znovu")
        return ScanOutcome.Failed
    }

    private fun startScan(callback: ScanCallback): Boolean {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Hledání nejde spustit: Bluetooth není zapnutý")
            return false
        }
        return try {
            scanner.startScan(scanFilters, scanSettings, callback)
            activeScan = callback
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Hledání nejde spustit: chybí oprávnění", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Hledání nejde spustit: Bluetooth není zapnutý", e)
            false
        }
    }

    private fun stopScan(callback: ScanCallback) {
        if (activeScan !== callback) return
        activeScan = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Hledání nejde zastavit: chybí oprávnění", e)
        } catch (e: IllegalStateException) {
            // Hledání skončilo samo s vypnutím Bluetooth.
            Log.i(TAG, "Hledání nejde zastavit: Bluetooth není zapnutý")
        }
    }
}

private sealed interface ScanSignal {
    // FoundDevice zůstává bez typů Androidu; objekt zařízení jde vedle něj.
    data class Found(val device: FoundDevice, val bluetoothDevice: BluetoothDevice) : ScanSignal

    data class Failed(val errorCode: Int) : ScanSignal

    data object Wakeup : ScanSignal
}

private sealed interface ScanOutcome {
    data class Chosen(val device: BluetoothDevice) : ScanOutcome

    data object Aborted : ScanOutcome

    data object Failed : ScanOutcome
}

// Výsledky hledání jen předá smyčce; nalezená zařízení si eviduje smyčka sama.
private class ForwardingScanCallback(private val signals: SendChannel<ScanSignal>) : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val device = result.device
        // Jméno z reklamy: BluetoothDevice.getName() by na Androidu 12+ potřebovalo BLUETOOTH_CONNECT.
        val name = result.scanRecord?.deviceName ?: BleUuids.DEVICE_NAME
        signals.trySend(ScanSignal.Found(FoundDevice(device.address, name, result.rssi), device))
    }

    override fun onScanFailed(errorCode: Int) {
        signals.trySend(ScanSignal.Failed(errorCode))
    }
}
