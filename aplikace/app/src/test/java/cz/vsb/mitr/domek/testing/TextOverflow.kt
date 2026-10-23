package cz.vsb.mitr.domek.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult

// Texty, kterým rodič ubral výšku, takže se jejich poslední řádek nevejde do rámečku a vykreslí se oříznutý. Doplňuje
// cutOffTexts, které pozná jen tři tečky a ořezané řádky, ne text zalomený na víc řádků v pevně vysokém prvku (třeba popisek
// tlačítka při velkém písmu). Šířku nehlídá: didOverflowWidth vychází u každého textu, který se přizpůsobuje obsahu, jako
// splněné. Hledá se v nesloučeném stromu, kde má rozložení každý text zvlášť.
fun SemanticsNodeInteractionsProvider.verticallyClippedTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return@mapNotNull null
            layout.layoutInput.text.text.takeIf { layout.didOverflowHeight }
        }
