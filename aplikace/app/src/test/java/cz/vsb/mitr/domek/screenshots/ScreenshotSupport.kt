package cz.vsb.mitr.domek.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Galaxy A25 na výšku a na šířku (384 × 832 dp).
const val PHONE = "w384dp-h832dp-xxhdpi"
const val PHONE_LANDSCAPE = "w832dp-h384dp-land-xxhdpi"

// Stejná šířka, ale tak vysoko, aby se celý posouvaný obsah Přehledu vešel na jeden snímek a šlo prohlédnout i to,
// co je na telefonu pod dolní lištou.
const val PHONE_TALL = "w384dp-h1200dp-xxhdpi"

// Široká a zároveň vysoká obrazovka: obrazovky dostanou wide = true přímo, takže orientace nehraje roli, a celý obsah obou
// sloupců se vejde, jinak by se polohy prvků pod okrajem nedaly porovnat.
const val WIDE_TALL = "w832dp-h1200dp-xxhdpi"

// Samsung Galaxy Tab A8, tablet, na kterém se aplikace zkouší: 1200 × 1920 px při 240 dpi, tedy 800 × 1280 dp na výšku a
// 1280 × 800 dp na šířku.
const val TABLET = "w800dp-h1280dp-port-hdpi"
const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-hdpi"

// Screenshoty se s ničím automaticky neporovnávají, jsou k prohlédnutí proti náhledům. Testy běží ve složce modulu,
// takže soubory končí v app/build/screenshots.
fun ComposeContentTestRule.saveScreenshot(name: String) {
    val image = renderScreenshot(name)
    val file = File("build/screenshots/$name.png")
    file.parentFile?.mkdirs()
    check(ImageIO.write(image, "png", file)) { "Screenshot $name se nepodařilo uložit jako PNG" }
}

// captureToImage() v Robolectricu nefunguje: před snímkem pošle práci do hlavního vlákna a pak na něm spí, jenže
// Robolectric hlavní smyčku za běhu testu sám neobsluhuje, takže snímek po 2 s spadne na ComposeTimeoutException.
// Proto se kořenové pohledy Compose vykreslí do bitmapy přímo (GraphicsMode.NATIVE kreslí skutečným Skia).
//
// Kořenů může být víc: dialog nebo spodní panel (ModalBottomSheet) má vlastní okno, tedy vlastní kořen Compose. Kreslí
// se všechny, každý na svou pozici na obrazovce a v pořadí, v jakém vznikly, takže okna nad aplikací jsou nahoře.
fun ComposeContentTestRule.renderScreenshot(name: String): BufferedImage {
    val roots = onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
    check(roots.isNotEmpty()) { "Screenshot $name: Compose nemá žádný kořen, takže není co kreslit (chybí setContent?)" }
    val views = roots.map { (it.root as ViewRootForTest).view }
    // Okno aplikace je první a určuje velikost obrázku.
    val screen = views.first()
    val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val screenPosition = screen.positionOnScreen()
    views.forEach { view ->
        val position = view.positionOnScreen()
        canvas.save()
        canvas.translate((position[0] - screenPosition[0]).toFloat(), (position[1] - screenPosition[1]).toFloat())
        view.draw(canvas)
        canvas.restore()
    }
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    // Nevykreslená obrazovka je celá jedné barvy (průhledná nebo pozadí). Takový snímek je chyba, ne výsledek.
    check(pixels.any { it != pixels[0] }) {
        "Screenshot $name je jednobarevný (#${"%08X".format(pixels[0])}), obsah se asi nevykreslil"
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}

private fun View.positionOnScreen(): IntArray = IntArray(2).also { getLocationOnScreen(it) }
