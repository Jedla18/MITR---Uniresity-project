package cz.vsb.mitr.domek.data.model

// Vlastní typ místo Compose Color, aby model zůstal čistý Kotlin a šel testovat v JVM.
data class Rgb(val r: Int, val g: Int, val b: Int)

// Barva ze složek, jak je nese JSON Stavu i Nastavení. Raspberry může poslat míň nebo víc složek, kreslení ale potřebuje vždy
// platné RGB: chybějící složka je 0 a hodnoty mimo 0 až 255 se oříznou.
fun rgbOf(channels: List<Int>): Rgb {
    fun channel(index: Int) = (channels.getOrNull(index) ?: 0).coerceIn(0, 255)
    return Rgb(channel(0), channel(1), channel(2))
}
