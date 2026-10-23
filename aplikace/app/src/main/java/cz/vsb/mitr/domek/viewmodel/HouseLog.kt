package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.data.model.HouseSettings
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.data.source.SimulationAction
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.formatDuration
import java.time.LocalDateTime

// Deník drží jen posledních 200 událostí (spec 2.2).
private const val MAX_ENTRIES = 200

// Slučování a zápisy z aplikace (spec 2.3, Pravidla).
private const val MOTION_MERGE_MS = 60_000L
private const val SETTINGS_MERGE_MS = 10_000L
private const val APP_WRITE_MS = 10_000L

// Okamžik události: čas telefonu pro řádek Deníku a monotónní čas pro slučování a délky výpadků.
data class LogTime(val wall: LocalDateTime, val ms: Long)

// id zůstává i po sloučení s pozdější událostí, aby seznam řádek jen posunul. atMs a merge obrazovka nepotřebuje.
data class LogEntry(
    val id: Long,
    val at: LocalDateTime,
    val event: LogEvent,
    val atMs: Long = 0,
    val merge: LogMerge? = null,
)

// Co si řádek pamatuje, aby se s ním dala sloučit další událost téhož druhu.
sealed interface LogMerge {
    data class Motion(val count: Int, val firstAt: LocalDateTime) : LogMerge

    // before je Nastavení před první změnou, jeho hodnota zůstává v řádku jako stará.
    data class Setting(val field: SettingsField, val before: HouseSettings) : LogMerge
}

// Deník událostí (spec 2): jen v paměti, nejnovější nahoře. ViewModel mu předává, co se stalo, a seznam z něj publikuje.
// Mění se jen z hlavního vlákna, stejně jako zbytek stavu ViewModelu.
class HouseLog {
    // Při každé změně vznikne nový seznam: ViewModel ho publikuje přes StateFlow, takže ho pozdější změna nesmí měnit.
    var entries: List<LogEntry> = emptyList()
        private set

    private var nextId = 0L

    // Jestli je spojení, kterým chodí Stav (Bluetooth i simulace), a jestli je to simulace.
    private var live = false
    private var simulated = false

    // Od kdy se čeká na Stav: od začátku spojení, nebo od probuzení telefonu. Do 3 s od té chvíle data nejsou stará.
    private var awaitingSinceMs = 0L

    // Kdy ViewModel naposledy hlásil stáří dat (tik po 500 ms).
    private var lastAgeCheckMs: Long? = null

    // Kdy samo skončilo spojení, které se teď obnovuje.
    private var lostAtMs: Long? = null

    private var staleReported = false

    // Kdy začaly poruchy, které dosud trvají. Porucha z prvního Stavu tu není, její začátek se neví.
    private val faultsSinceMs = mutableMapOf<String, Long>()

    // Kdy aplikace naposledy zapsala položku Nastavení, zápisem klíče nebo příkazem světla s barvou či jasem.
    private val appWrites = mutableMapOf<SettingsField, Long>()

    // Události ze Stavu mají čas jeho přijetí. První Stav po spuštění nebo přepnutí zdroje (previous == null) není s čím
    // srovnat; po výpadku spojení je previous poslední Stav před výpadkem (spec 2.3, Pravidla).
    fun status(previous: ReceivedStatus?, current: ReceivedStatus, settings: HouseSettings?) {
        if (previous == null) return
        val time = LogTime(current.receivedAtWall, current.receivedAtMs)
        if (staleReported) {
            staleReported = false
            add(connectionEvent(LogKind.DATA_FRESH, "Data zase chodí", "výpadek ${seconds(time.ms - previous.receivedAtMs)}"), time)
        }
        faultEvents(previous.status.errors, current.status.errors, time.ms).forEach { add(it, time) }
        statusEvents(previous.status, current.status, settings).forEach { event ->
            if (event.kind == LogKind.MOTION) addMotion(event, time) else add(event, time)
        }
    }

    // Porucha je příčina, proto jde před události, které způsobila (třeba konec topení).
    private fun faultEvents(previous: List<String>, current: List<String>, nowMs: Long): List<LogEvent> {
        val ended = previous.distinct() - current.toSet()
        val started = current.distinct() - previous.toSet()
        val endEvents = ended.map { code -> faultEnded(code, faultsSinceMs.remove(code)?.let { (nowMs - it) / 1_000 }) }
        started.forEach { faultsSinceMs[it] = nowMs }
        return endEvents + started.map(::faultStarted)
    }

    // Pohyb nejvýš minutu po posledním pohybu nepřidá řádek, jen zvýší počet a posune čas, ale jen dokud je řádek pohybu
    // nejnovější. Jinak by sloučený řádek přeskočil, co se mezitím stalo.
    private fun addMotion(event: LogEvent, time: LogTime) {
        val newest = entries.firstOrNull()
        val run = newest?.merge as? LogMerge.Motion
        if (run == null || time.ms - newest.atMs > MOTION_MERGE_MS) {
            add(event, time, LogMerge.Motion(count = 1, firstAt = time.wall))
            return
        }
        val count = run.count + 1
        replaceNewest(event.copy(detail = "$count× od ${formatLogTime(run.firstAt)}"), time, run.copy(count = count))
    }

    // Nastavení se porovnává s předchozím přečteným; ViewModel první Nastavení po spuštění nebo přepnutí zdroje nepředává.
    // Změna téhož klíče nejvýš 10 s po jeho posledním záznamu záznam jen aktualizuje (stará hodnota v něm zůstane), ale jen
    // dokud je ten záznam nejnovější: tah posuvníku tak dá jeden řádek a změna, která něco způsobila, zůstane pod svým
    // následkem. Když se hodnota vrátí na původní, řádek zmizí.
    fun settings(previous: HouseSettings, current: HouseSettings, time: LogTime) {
        changedFields(previous, current).forEach { field ->
            val fromApp = appWrites[field]?.let { time.ms - it <= APP_WRITE_MS } == true
            val newest = entries.firstOrNull()
            val run = (newest?.merge as? LogMerge.Setting)?.takeIf { it.field == field }
            when {
                run == null || time.ms - newest.atMs > SETTINGS_MERGE_MS ->
                    add(settingsEvent(field, previous, current, fromApp), time, LogMerge.Setting(field, previous))
                field !in changedFields(run.before, current) -> entries = entries.drop(1)
                else -> replaceNewest(settingsEvent(field, run.before, current, fromApp), time, run)
            }
        }
    }

    fun appWrote(fields: Collection<SettingsField>, atMs: Long) {
        fields.forEach { appWrites[it] = atMs }
    }

    fun connection(previous: ConnectionState, current: ConnectionState, time: LogTime) {
        val carriesData = current.carriesData
        if (carriesData && !live) awaitingSinceMs = time.ms
        live = carriesData
        simulated = current == ConnectionState.Simulated
        val event = when {
            current is ConnectionState.Connected && previous !is ConnectionState.Connected -> {
                val lostAt = lostAtMs
                lostAtMs = null
                if (lostAt == null) {
                    connectionEvent(LogKind.CONNECTED, "Připojeno k${NBSP}domu Termostat", "${current.address} · MTU$NBSP${current.mtu}")
                } else {
                    connectionEvent(LogKind.CONNECTED, "Znovu připojeno", "výpadek ${seconds(time.ms - lostAt)}")
                }
            }
            // I uprostřed obnovování spojení: po ručním odpojení není co obnovovat a další spojení je zase první.
            // Stará data končí se spojením: „Data zase chodí“ by jinak po obnovení spojení měřilo celý výpadek.
            current == ConnectionState.DisconnectedByUser && previous != ConnectionState.DisconnectedByUser -> {
                lostAtMs = null
                staleReported = false
                connectionEvent(LogKind.DISCONNECTED, "Odpojeno ručně")
            }
            previous is ConnectionState.Connected && current !is ConnectionState.Connected -> {
                lostAtMs = time.ms
                staleReported = false
                connectionEvent(LogKind.CONNECTION_LOST, "Spojení přerušeno", "znovu se připojuji…")
            }
            current == ConnectionState.Simulated && previous != ConnectionState.Simulated ->
                connectionEvent(LogKind.SIMULATION_STARTED, "Simulace spuštěna")
            else -> null
        }
        event?.let { add(it, time) }
    }

    // Stáří Stavu hlásí ViewModel při každém tiku, zapíše se jen začátek. Bez spojení data stárnou samozřejmě a po jeho začátku
    // mají 3 s na první Stav, jinak by každé znovupřipojení ohlásilo stará data, dokud nepřijde první notifikace. Stejně po
    // spánku telefonu: tik ve spánku stojí (delay počítá jen dobu, kdy telefon nespí), monotónní čas ne, a Stav tehdy nepřišel.
    fun dataAge(stale: Boolean, time: LogTime) {
        val lastCheck = lastAgeCheckMs
        lastAgeCheckMs = time.ms
        if (lastCheck != null && time.ms - lastCheck > STALE_AFTER_MS) awaitingSinceMs = time.ms
        if (!stale || !live || staleReported || time.ms - awaitingSinceMs <= STALE_AFTER_MS) return
        staleReported = true
        val detail = if (simulated) "simulace přestala posílat stav" else "Raspberry přestalo posílat stav"
        add(connectionEvent(LogKind.DATA_STALE, "Data jsou stará", detail), time)
    }

    fun message(text: String, time: LogTime) = add(messageEvent(text), time)

    fun simulation(action: SimulationAction, faultReported: Boolean, time: LogTime) = add(simulationEvent(action, faultReported), time)

    fun clockSync(phoneTime: LocalDateTime, time: LogTime) = add(clockSyncEvent(phoneTime), time)

    // Po přepnutí zdroje začíná Deník znovu, stejně jako graf (spec 2.2).
    fun clear() {
        entries = emptyList()
        live = false
        simulated = false
        lastAgeCheckMs = null
        lostAtMs = null
        staleReported = false
        faultsSinceMs.clear()
        appWrites.clear()
    }

    private fun add(event: LogEvent, time: LogTime, merge: LogMerge? = null) {
        entries = (listOf(LogEntry(nextId++, time.wall, event, time.ms, merge)) + entries).take(MAX_ENTRIES)
    }

    // Slučuje se jen do nejnovějšího řádku; ten si nechá id, takže ho seznam jen překreslí.
    private fun replaceNewest(event: LogEvent, time: LogTime, merge: LogMerge) {
        entries = listOf(LogEntry(entries.first().id, time.wall, event, time.ms, merge)) + entries.drop(1)
    }
}

private fun connectionEvent(kind: LogKind, title: String, detail: String = "") = LogEvent(LogCategory.CONNECTION, kind, title, detail)

private val ConnectionState.carriesData: Boolean
    get() = this is ConnectionState.Connected || this == ConnectionState.Simulated

private fun seconds(ms: Long): String = formatDuration(ms / 1_000)
