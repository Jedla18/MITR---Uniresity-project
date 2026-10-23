package cz.vsb.mitr.domek.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.vsb.mitr.domek.ble.BleHouseDataSource
import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.data.model.HouseCommand
import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.model.LightAction
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingChange
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.model.SettingKind
import cz.vsb.mitr.domek.data.model.ThermostatMode
import cz.vsb.mitr.domek.data.model.change
import cz.vsb.mitr.domek.data.model.rgbOf
import cz.vsb.mitr.domek.data.model.storesLightLook
import cz.vsb.mitr.domek.data.model.valueOf
import cz.vsb.mitr.domek.data.source.BleControls
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.HouseDataSource
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.data.source.SimulationControls
import cz.vsb.mitr.domek.data.source.SourceKind
import cz.vsb.mitr.domek.data.source.TimeSource
import cz.vsb.mitr.domek.data.source.WriteResult
import cz.vsb.mitr.domek.domain.METEO_FAULT
import cz.vsb.mitr.domek.domain.dayStartsOf
import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.sim.SimulatedHouseDataSource
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Tik UI (spec 5.5): po této době se přepočítá stáří dat a čas telefonu.
private const val TICK_MS = 500L

// Krokové tlačítko zapíše až po této době od posledního stisku (spec 7).
private const val STEP_WRITE_DELAY_MS = 600L

// Nejdéle tak dlouho po úspěšném zápisu se ukazuje draft, který Raspberry zatím nepotvrdilo (spec 7).
private const val DRAFT_CONFIRM_MS = 3_000L

// Posuvník smí poslat nejvýš jeden zápis barvy nebo jasu za 100 až 200 ms (docs/ble-rozhrani.md, Poznámky pro Android). Každou
// změnu barvy nebo jasu si Raspberry ukládá do souboru, takže rychlejší tah by zbytečně zatěžoval jeho kartu.
private const val LOOK_SPACING_MS = 150L

// Drží zdroj dat (Raspberry přes Bluetooth, nebo simulaci), skládá z něj jediný UI stav (spec 5.5) a posílá do něj
// zápisy a příkazy (spec 7).
class HouseViewModel(
    private val createSource: (SourceKind, CoroutineScope) -> HouseDataSource,
    private val time: TimeSource,
    private val wallClock: () -> LocalDateTime,
) : ViewModel(), HouseActions {
    private val _uiState = MutableStateFlow(HouseUiState(nowMs = time.nowMs(), phoneNow = wallClock()))
    val uiState: StateFlow<HouseUiState> = _uiState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    // Vše níže se mění jen z hlavního vlákna (viewModelScope, relace a akce z UI), proto bez synchronizace.
    private val history = ChartHistory()
    private val log = HouseLog()

    private lateinit var source: HouseDataSource

    // Relace patří právě běžícímu zdroji: sběr jeho toků, zápisy s jejich časovači a odesílání vzhledu světla.
    // Přepnutí zdroje ji zruší celou, takže nic z ní nedoběhne do nového zdroje.
    private lateinit var session: CoroutineScope

    // Zápisy Nastavení podle klíče: odložený zápis z krokových tlačítek, zápis, který draft klíče doručuje,
    // a čekání zapsaného draftu na potvrzení.
    private val stepWrites = mutableMapOf<SettingKey, Job>()
    private val writers = mutableMapOf<SettingKey, Job>()
    private val confirmations = mutableMapOf<SettingKey, Job>()

    // Vzhled světla, který ještě neodešel. Posílá ho jediný odesílatel, kterého budí lookRequests.
    private var pendingLook: HouseCommand.LightLook? = null
    private val lookRequests = Channel<Unit>(Channel.CONFLATED)
    private var lightDraftExpiry: Job? = null

    init {
        startSource(SourceKind.BLUETOOTH)
        viewModelScope.launch {
            while (true) {
                refreshClock()
                delay(TICK_MS)
            }
        }
    }

    // Zdroj dostává přímo viewModelScope: BLE zdroj počítá s hlavním vláknem a se SupervisorJob.
    private fun startSource(kind: SourceKind) {
        val newSource = createSource(kind, viewModelScope)
        source = newSource
        newSource.start()
        // Zápisy a jejich časovače běží na Main bez immediate: tělo korutiny začne až po návratu z launch(), stejně jako
        // v testech, takže zápis je v mapě writers dřív, než začne zjišťovat, které klíče jsou jeho.
        val newSession = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext.job) + Dispatchers.Main)
        session = newSession
        // Toky zdroje se naopak sbírají hned, aby po přepnutí zdroje UI nevykreslilo snímek bez jeho dat.
        newSession.launch(Dispatchers.Main.immediate) {
            launch { newSource.connection.collect(::onConnection) }
            launch { newSource.settings.collect(::onSettings) }
            launch { newSource.status.filterNotNull().collect(::onStatus) }
        }
        newSession.launch { sendLightLooks(newSource) }
    }

    private fun onConnection(connection: ConnectionState) {
        log.connection(_uiState.value.connection, connection, now())
        _uiState.update { it.copy(connection = connection, log = log.entries) }
    }

    // Deník porovnává s předchozím přečteným Nastavením, takže první Nastavení po spuštění nebo přepnutí zdroje nic nepíše.
    private fun onSettings(settings: HouseSettings?) {
        val previous = _uiState.value.settings
        if (previous != null && settings != null) log.settings(previous, settings, now())
        _uiState.update { it.copy(settings = settings, log = log.entries).withoutConfirmedLightDraft() }
        if (_uiState.value.lightDraft == null) lightDraftExpiry?.cancel()
    }

    private fun refreshClock() {
        val now = now()
        _uiState.update { it.copy(nowMs = now.ms, phoneNow = now.wall) }
        // Stáří dat roste jen s časem, proto se začátek výpadku dat hledá při tiku. Konec přinese nový Stav.
        log.dataAge(_uiState.value.isStale, now)
        publishLog()
    }

    // Nový Stav hned obnoví i hodiny: Stav přijatý mezi tiky je novější než nowMs, takže by jeho stáří vyšlo záporné.
    private fun onStatus(received: ReceivedStatus) {
        history.add(received.toChartSample())
        val chart = history.samples
        val nowMs = time.nowMs()
        val phoneNow = wallClock()
        log.status(_uiState.value.received, received, _uiState.value.settings)
        _uiState.update { state ->
            // Hrana pohybu false → true. Za hranu se bere i první Stav po spuštění nebo přepnutí zdroje, který pohyb už ukazuje.
            val motionStarted = received.status.motion && state.received?.status?.motion != true
            state.copy(
                received = received,
                chart = chart,
                lastMotionAt = if (motionStarted) received.receivedAtWall else state.lastMotionAt,
                log = log.entries,
                nowMs = nowMs,
                phoneNow = phoneNow,
            ).withoutConfirmedLightDraft()
        }
        if (_uiState.value.lightDraft == null) lightDraftExpiry?.cancel()
    }

    override fun selectSource(kind: SourceKind) {
        if (kind == _uiState.value.sourceKind) return
        // Nejdřív se zruší celá relace, aby odložený zápis ani čekající vzhled světla nedošly do nového zdroje.
        session.cancel()
        stepWrites.clear()
        writers.clear()
        confirmations.clear()
        pendingLook = null
        source.stop()
        history.clear()
        log.clear()
        // Zastavený zdroj si může nechat poslední Stav i Nastavení, proto vše, co z něj stav ViewModelu odvozuje, maže ViewModel sám.
        // Zůstává jen volba okna grafu, ta se zdroje netýká.
        _uiState.update { HouseUiState(sourceKind = kind, chartWindow = it.chartWindow, nowMs = it.nowMs, phoneNow = it.phoneNow) }
        startSource(kind)
    }

    override fun setChartWindow(window: ChartWindow) {
        _uiState.update { it.copy(chartWindow = window) }
    }

    // Ovládání Bluetooth a simulace se předává jen zdroji, který ho umí; jinak se ignoruje.
    override fun chooseDevice(address: String) {
        (source as? BleControls)?.chooseDevice(address)
    }

    override fun disconnect() {
        (source as? BleControls)?.disconnectByUser()
    }

    override fun connect() {
        (source as? BleControls)?.connectByUser()
    }

    override fun retryConnection() {
        (source as? BleControls)?.retry()
    }

    // Návrat do aplikace: oprávnění nebo polohu mohl uživatel změnit v systému a zdroj to sám nezjistí. Zařízení bez služby Domek
    // se ale znovu zkouší jen tlačítkem „Zkusit znovu“, jinak by se po každém návratu připojilo k témuž zařízení (spec 5.3).
    fun recheckPrerequisites() {
        if (_uiState.value.connection != ConnectionState.MissingService) retryConnection()
    }

    override fun simulation(action: SimulationAction) {
        val controls = source as? SimulationControls ?: return
        // Tlačítko poruchu přepíná. Zapne ji, když ji Stav zatím nehlásí, stejně jako říká štítek na kartě simulace.
        val faultReported = _uiState.value.status?.errors?.contains(METEO_FAULT) == true
        // Řádek tlačítka jde do Deníku dřív, než simulace akci provede: nový Stav s jejími následky přijde na zařízení ještě
        // uvnitř perform() a následky mají v Deníku stát nad svou příčinou.
        log.simulation(action, faultReported, now())
        publishLog()
        controls.perform(action)
    }

    // Zápisy Nastavení (spec 7)

    override fun changeSetting(key: SettingKey, value: Double) {
        // NaN (třeba z přímého zadání čísla) nemá rozumnou hodnotu a zaokrouhlení by na něm spadlo.
        if (value.isNaN()) return
        putDraft(key.change(key.rounded(value.coerceIn(key.min, key.max))))
        stepWrites.remove(key)?.cancel()
        stepWrites[key] = session.launch {
            delay(STEP_WRITE_DELAY_MS)
            stepWrites.remove(key)
            write(key)
        }
    }

    override fun setMode(mode: ThermostatMode) = writeNow(SettingKey.MODE.change(mode.json))

    override fun setLightOnMotion(enabled: Boolean) = writeNow(SettingKey.LIGHT_ON_MOTION.change(enabled))

    override fun setDayStart(part: DayPart, time: LocalTime) {
        // Původní časy jsou ty z Raspberry (bez draftů) před první změnou začátků v tomto běhu (spec 6.4).
        if (_uiState.value.savedDayStarts == null) {
            _uiState.update { it.copy(savedDayStarts = it.settings?.let(::dayStartsOf)) }
        }
        writeNow(part.startKey.change(formatHhMm(time)))
    }

    // „Teď“ je čas Raspberry z posledního Stavu (spec 6.4): úseky dne se řídí jeho hodinami, ne hodinami telefonu.
    override fun setDayStartNow(part: DayPart) {
        val state = _uiState.value
        val piTime = state.piTime ?: return
        if (state.isStale) return
        setDayStart(part, piTime.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
    }

    override fun restoreDayStarts() {
        val saved = _uiState.value.savedDayStarts ?: return
        val changes = DayPart.entries.map { it.startKey.change(formatHhMm(saved.startOf(it))) }
        changes.forEach(::putDraft)
        // Všechny tři jedním zápisem: po jednom by Raspberry mohlo odmítnout dočasnou shodu dvou začátků.
        startWriter(changes.map { it.key }) { changedMeanwhile ->
            // Začátek změněný během zápisu je nová změna, pro kterou původní časy platí dál.
            if (changedMeanwhile.isEmpty()) _uiState.update { it.copy(savedDayStarts = null) }
        }
    }

    // Draft se ukáže hned. Nová hodnota zruší čekání na potvrzení té předchozí, aby ji jeho konec nesmazal.
    private fun putDraft(change: SettingChange) {
        confirmations.remove(change.key)?.cancel()
        _uiState.update { it.copy(drafts = it.drafts + (change.key to change.value), pendingKeys = it.pendingKeys + change.key) }
    }

    private fun dropDraft(key: SettingKey) {
        stepWrites.remove(key)?.cancel()
        _uiState.update { it.copy(drafts = it.drafts - key, pendingKeys = it.pendingKeys - key) }
    }

    private fun writeNow(change: SettingChange) {
        putDraft(change)
        stepWrites.remove(change.key)?.cancel()
        write(change.key)
    }

    // Na klíč běží nejvýš jeden zápis. Dokud běží, další nezačne: běžící po úspěchu pošle nejnovější draft sám.
    private fun write(key: SettingKey) {
        if (writers[key]?.isActive != true) startWriter(listOf(key))
    }

    // Zápis převezme doručení draftů daných klíčů a pošle je, až doběhnou zápisy, kterým klíče patřily dosud.
    private fun startWriter(keys: List<SettingKey>, afterOk: (changedMeanwhile: List<SettingKey>) -> Unit = {}) {
        val earlier = keys.mapNotNull { writers[it] }.filter { it.isActive }.distinct()
        val writer = session.launch {
            earlier.joinAll()
            writeDrafts(keys, afterOk)
        }
        keys.forEach { writers[it] = writer }
    }

    private suspend fun writeDrafts(keys: List<SettingKey>, afterOk: (changedMeanwhile: List<SettingKey>) -> Unit) {
        val self = currentCoroutineContext().job
        fun owns(key: SettingKey) = writers[key] === self
        try {
            val changes = keys.filter(::owns).mapNotNull { key -> _uiState.value.drafts[key]?.let { SettingChange(key, it) } }
            if (changes.isEmpty()) return
            // Už před odpovědí: notifikace Nastavení může přijít dřív než odpověď na zápis.
            log.appWrote(changes.map { SettingsField.Key(it.key) }, time.nowMs())
            val result = source.writeSettings(changes)
            // Klíč, který mezitím převzal jiný zápis, patří i s draftem jemu.
            val owned = changes.filter { owns(it.key) }
            if (result != WriteResult.Ok) {
                owned.forEach { dropDraft(it.key) }
                showMessage(settingsWriteMessage(changes, _uiState.value.settings, result))
                // Odmítnutý zápis nic nezměnil; přečtené Nastavení ukáže, co opravdu platí (docs/ble-rozhrani.md). Čte se
                // mimo tento zápis, aby nová hodnota téhož klíče zadaná během čtení neuvízla za klíčem, který zápis drží.
                if (result is WriteResult.Rejected) session.launch { source.refreshSettings() }
                return
            }
            // Odložený zápis by poslal hodnotu, která právě odešla, nebo hned odejde.
            owned.forEach { stepWrites.remove(it.key)?.cancel() }
            val drafts = _uiState.value.drafts
            val (unchanged, changed) = owned.partition { drafts[it.key] == it.value }
            unchanged.forEach(::awaitConfirmation)
            afterOk(changed.map { it.key })
            // Co se během zápisu změnilo, odejde hned dalším zápisem, bez nového čekání (spec 7).
            if (changed.isNotEmpty()) startWriter(changed.map { it.key })
        } finally {
            keys.filter(::owns).forEach { writers.remove(it) }
        }
    }

    // Po úspěchu zůstane draft, dokud ho Raspberry nepotvrdí v Nastavení, nejdéle však 3 s (spec 7).
    private fun awaitConfirmation(change: SettingChange) {
        confirmations.remove(change.key)?.cancel()
        confirmations[change.key] = session.launch {
            withTimeoutOrNull(DRAFT_CONFIRM_MS) { _uiState.first { it.settings?.valueOf(change.key) == change.value } }
            dropDraft(change.key)
        }
    }

    // Světlo a hodiny (spec 7)

    override fun lightAction(action: LightAction) {
        // Příkaz s barvou nebo jasem (starší program) světlo zapne, takže vzhled, který ještě neodešel, by ho po povelu znovu
        // rozsvítil. Zápis uložené barvy a jasu světlo nezapne, ten po povelu odejde a nese poslední hodnotu posuvníku.
        if (!_uiState.value.storesLightLook) {
            pendingLook = null
            clearLightDraft()
        }
        sendCommand(HouseCommand.Light(action))
    }

    override fun setLightColor(color: Rgb) =
        requestLook(color = Rgb(color.r.coerceIn(0, 255), color.g.coerceIn(0, 255), color.b.coerceIn(0, 255)))

    // Jas 0 % aplikace neposílá, zhasnutí obstarává tlačítko Vypnout (spec 11).
    override fun setLightBrightness(percent: Int) = requestLook(brightness = percent.coerceIn(1, 100))

    // Srovnání je v Deníku akce zapsaná před odesláním, aby stála pod tím, co způsobí; chyba přijde jako hláška.
    override fun syncClock() {
        val phoneTime = wallClock().withNano(0)
        log.clockSync(phoneTime, now())
        publishLog()
        sendCommand(HouseCommand.SetClock(phoneTime))
    }

    // Barva a jas se slučují do jednoho čekajícího požadavku: nový jas přepíše jen jas, nová barva jen barvu.
    private fun requestLook(color: Rgb? = null, brightness: Int? = null) {
        pendingLook = HouseCommand.LightLook(color ?: pendingLook?.color, brightness ?: pendingLook?.brightness)
        val setAtMs = time.nowMs()
        _uiState.update { state ->
            val draft = state.lightDraft
            state.copy(lightDraft = LightDraft(color ?: draft?.color, brightness ?: draft?.brightness, setAtMs))
        }
        lightDraftExpiry?.cancel()
        lightDraftExpiry = session.launch {
            delay(DRAFT_CONFIRM_MS)
            _uiState.update { it.copy(lightDraft = null) }
        }
        lookRequests.trySend(Unit)
    }

    // Jediný odesílatel vzhledu, takže najednou běží nejvýš jeden zápis a mezi dvěma uplyne aspoň LOOK_SPACING_MS.
    // Hodnoty, které mezitím přepsal novější požadavek, se zahodí a odejde jen ta poslední (spec 7). Raspberry s uloženou
    // barvou a jasem je dostane zápisem Nastavení, starší program příkazem světla, který světlo zároveň ručně zapne (spec 3).
    private suspend fun sendLightLooks(source: HouseDataSource) {
        while (true) {
            lookRequests.receive()
            val look = pendingLook ?: continue
            pendingLook = null
            // Barva a jas jsou položky Nastavení, i když je starší program uloží z příkazu, takže i jejich změna je z aplikace.
            val fields = listOfNotNull(look.color?.let { SettingsField.LightColor }, look.brightness?.let { SettingsField.LightBrightness })
            log.appWrote(fields, time.nowMs())
            val stored = _uiState.value.storesLightLook
            val result = if (stored) source.writeStoredLook(look.color, look.brightness) else source.sendCommand(look)
            if (result != WriteResult.Ok) {
                // Po chybě platí zase skutečné světlo a uložená barva a jas. Čekající vzhled by nejspíš dopadl stejně a přidal
                // další hlášku.
                pendingLook = null
                clearLightDraft()
                showMessage(if (stored) storedLookMessage(look.color, look.brightness, result) else commandMessage(look, result))
                // Odmítnutý zápis nic nezměnil; přečtené Nastavení ukáže, co opravdu platí (docs/ble-rozhrani.md). Čte se mimo
                // odesílatele, aby další vzhled nečekal na konec čtení.
                if (stored && result is WriteResult.Rejected) session.launch { source.refreshSettings() }
            }
            delay(LOOK_SPACING_MS)
        }
    }

    private fun clearLightDraft() {
        lightDraftExpiry?.cancel()
        _uiState.update { it.copy(lightDraft = null) }
    }

    private fun sendCommand(command: HouseCommand) {
        session.launch { showMessage(commandMessage(command, source.sendCommand(command))) }
    }

    // Hláška jde do snackbaru i do Deníku.
    private fun showMessage(text: String?) {
        if (text == null) return
        messageChannel.trySend(text)
        log.message(text, now())
        publishLog()
    }

    private fun now() = LogTime(wallClock(), time.nowMs())

    // Tik se ptá Deníku každých 500 ms, stav se ale mění, jen když vznikl nový seznam (HouseLog ho vytváří při každé změně).
    private fun publishLog() {
        if (_uiState.value.log !== log.entries) _uiState.update { it.copy(log = log.entries) }
    }

    // ViewModel před onCleared() zruší viewModelScope, takže stop() zdroje nesmí počítat s korutinami.
    override fun onCleared() {
        source.stop()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            // ApplicationContext, aby továrna ani zdroj nedržely aktivitu.
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val time = TimeSource { SystemClock.elapsedRealtime() }
                    HouseViewModel(
                        createSource = { kind, scope ->
                            when (kind) {
                                SourceKind.BLUETOOTH -> BleHouseDataSource(appContext, scope, time, LocalDateTime::now)
                                SourceKind.SIMULATION -> SimulatedHouseDataSource(scope, time, LocalDateTime::now)
                            }
                        },
                        time = time,
                        wallClock = LocalDateTime::now,
                    )
                }
            }
        }
    }
}

private val DayPart.startKey: SettingKey
    get() = when (this) {
        DayPart.DAY -> SettingKey.DAY_START
        DayPart.EVENING -> SettingKey.EVENING_START
        DayPart.NIGHT -> SettingKey.NIGHT_START
    }

// Stejné zaokrouhlení jako u stepValue: draft se s Nastavením z Raspberry porovnává podle textu čísla.
private fun SettingKey.rounded(value: Double): Double =
    if (kind == SettingKind.WHOLE) value.roundToInt().toDouble() else (value * 10).roundToInt() / 10.0

// Raspberry potvrdí rozpracovanou volbu světla, až ji ukazuje (spec 3 v 2026-10-23-denik-a-vylepseni-design.md). Uloženou barvu
// a jas musí mít Nastavení a svítící světlo jimi musí svítit i ve Stavu, jinak by náhled do dalšího Stavu ukázal starou barvu.
// Zhasnuté světlo výběr neukáže a noční světlo jen do 10 %. Volbu staršího programu potvrdí Stav, ve kterém jí světlo svítí.
private fun HouseUiState.withoutConfirmedLightDraft(): HouseUiState {
    val draft = lightDraft ?: return this
    val light = status?.light ?: return this
    val confirmed = if (storesLightLook) {
        settings?.shows(draft) == true && recoloredBy(light, draft) == light
    } else {
        light.shows(draft)
    }
    return if (confirmed) copy(lightDraft = null) else this
}

// Stav potvrdí draft světla staršího programu, až světlo svítí v barvě a s jasem, které uživatel vybral.
private fun LightState.shows(draft: LightDraft): Boolean =
    on && (draft.color == null || draft.color == rgb) && (draft.brightness == null || draft.brightness == brightness)

// Nastavení potvrdí draft uložené barvy a jasu, až je má uložené.
private fun HouseSettings.shows(draft: LightDraft): Boolean =
    (draft.color == null || lightColor?.let(::rgbOf) == draft.color) && (draft.brightness == null || draft.brightness == lightBrightness)
