package cz.vsb.mitr.domek.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import cz.vsb.mitr.domek.ui.DomekTab

// Prvky kostry aplikace v testech Compose. Nespoléhají na obsah záložek, který úkoly 14 až 19 postupně nahradí.

// Záložka dole nebo na liště. Jen ona má roli záložky, takže ji nepomýlí jiný klikací prvek se stejným textem.
fun SemanticsNodeInteractionsProvider.tab(tab: DomekTab): SemanticsNodeInteraction =
    onNode(hasText(tab.title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

// Titulek v horní liště je nejvýš položený nadpis. Pořadí uzlů ve stromu na to nestačí: Scaffold řadí obsah záložky
// před horní lištu, takže by první nadpis patřil obsahu, jakmile nějaký nadpis má (třeba obrazovka spojení).
fun SemanticsNodeInteractionsProvider.title(): SemanticsNodeInteraction {
    val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    val topmost = headings.fetchSemanticsNodes().withIndex().minByOrNull { it.value.boundsInRoot.top }
        ?: error("Na obrazovce není žádný nadpis")
    return headings[topmost.index]
}
