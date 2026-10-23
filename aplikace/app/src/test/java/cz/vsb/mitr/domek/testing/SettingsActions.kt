package cz.vsb.mitr.domek.testing

import cz.vsb.mitr.domek.data.model.DayPart
import cz.vsb.mitr.domek.domain.formatHhMm
import cz.vsb.mitr.domek.viewmodel.HouseActions
import java.time.LocalTime

// RecordingActions, který si pamatuje i akce denní doby. Je zvlášť, aby se sdílený RecordingActions nemusel měnit pro každou
// obrazovku; volání končí ve stejném seznamu, takže pořadí akcí z obou je vidět pohromadě.
class SettingsActions(private val recording: RecordingActions = RecordingActions()) : HouseActions by recording {
    val calls: MutableList<String> get() = recording.calls

    override fun setDayStart(part: DayPart, time: LocalTime) {
        calls += "setDayStart($part, ${formatHhMm(time)})"
    }

    override fun setDayStartNow(part: DayPart) {
        calls += "setDayStartNow($part)"
    }

    override fun restoreDayStarts() {
        calls += "restoreDayStarts"
    }
}
