package cz.vsb.mitr.domek.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult

// Texty, ze kterých se při vykreslení uřízl konec (tři tečky nebo ořez): poslední viditelný znak je před koncem textu. Hledá
// se v nesloučeném stromu, kde má rozložení každý text zvlášť.
fun SemanticsNodeInteractionsProvider.cutOffTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return@mapNotNull null
            val text = layout.layoutInput.text.text
            text.takeIf { layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < text.length }
        }
