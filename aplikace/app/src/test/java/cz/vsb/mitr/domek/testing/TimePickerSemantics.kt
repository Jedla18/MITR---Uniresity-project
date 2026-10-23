package cz.vsb.mitr.domek.testing

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick

// Prvky 24h výběru času z Material 3 v testech. Čísla na ciferníku mají popisek v jazyce zařízení („10 hours“ v testech) a
// klepnutí. Stejný popisek má i vybraná hodina v záhlaví, ale ta je přepínač s rolí, takže se od čísla na ciferníku pozná
// podle role.
private val isDialNumber = hasClickAction() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

fun hourOnDial(number: Int): SemanticsMatcher = hasContentDescription("$number hours") and isDialNumber

fun minuteOnDial(number: Int): SemanticsMatcher = hasContentDescription("$number minutes") and isDialNumber

// Po hodině by se ciferník sám přepnul na minuty, ale až po 100 ms skutečného času, které test nečeká. Minuty se proto
// vybírají klepnutím na minuty v záhlaví, které přepíná hned.
fun ComposeContentTestRule.pickMinute(number: Int) {
    onNode(hasContentDescription("Select minutes")).performClick()
    waitForIdle()
    onNode(minuteOnDial(number)).performClick()
}
