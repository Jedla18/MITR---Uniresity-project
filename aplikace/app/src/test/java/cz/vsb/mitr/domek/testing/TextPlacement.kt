package cz.vsb.mitr.domek.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult

// Kontroly rozvržení textů pro snímky na šířku a na tabletu: nic se nesmí zalomit uprostřed slova ani ležet na jiném textu.
// Hledá se v nesloučeném stromu, kde má rozložení každý text zvlášť.

private fun SemanticsNodeInteractionsProvider.textNodes(): List<SemanticsNode> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val layouts = mutableListOf<TextLayoutResult>()
    config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    return layouts.firstOrNull()
}

// Velikost písma (sp), kterou má text na obrazovce. Uzel textu se musí hledat v nesloučeném stromu (useUnmergedTree = true).
fun SemanticsNodeInteraction.fontSizeSp(): Float =
    checkNotNull(fetchSemanticsNode().textLayout()) { "Uzel není text" }.layoutInput.style.fontSize.value

// Texty, ve kterých řádek končí uprostřed slova: poslední znak řádku i první znak dalšího jsou písmena nebo číslice. Zalomení na
// mezeře nebo za rozdělovníkem to není. Takhle se rozpadne popisek tlačítka, kterému se ubralo na šířce („Přepnou“ a „t“ pod ním).
fun SemanticsNodeInteractionsProvider.textsBrokenInsideAWord(): List<String> =
    textNodes().mapNotNull { node ->
        val layout = node.textLayout() ?: return@mapNotNull null
        val text = layout.layoutInput.text.text
        text.takeIf {
            (0 until layout.lineCount - 1).any { line ->
                val end = layout.getLineEnd(line)
                end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
            }
        }
    }

// Dvojice textů, jejichž rámečky se na obrazovce překrývají o víc než `toleranceDp` ve vodorovném i svislém směru. Rámeček textu
// bývá o kousek vyšší než písmena (velká teplota je vycentrovaná v rámečku o výšce písma), proto malé překrytí nevadí. Počítá se
// jen s viditelnou částí textů: boundsInRoot je ořezané okrajem posouvaného sloupce a text zcela pod okrajem má rámeček prázdný.
fun SemanticsNodeInteractionsProvider.overlappingTexts(toleranceDp: Float = 8f): List<String> {
    val shown = textNodes().mapNotNull { node ->
        val text = node.textLayout()?.layoutInput?.text?.text ?: return@mapNotNull null
        val bounds = node.boundsInRoot
        if (bounds.width > 0f && bounds.height > 0f) Triple(text, bounds, node.layoutInfo.density.density * toleranceDp) else null
    }
    return buildList {
        for (first in shown.indices) {
            for (second in first + 1 until shown.size) {
                val (firstText, firstBounds, tolerance) = shown[first]
                val (secondText, secondBounds, _) = shown[second]
                val overlap = firstBounds.intersect(secondBounds)
                if (overlap.width > tolerance && overlap.height > tolerance) add("„$firstText“ a „$secondText“")
            }
        }
    }
}
