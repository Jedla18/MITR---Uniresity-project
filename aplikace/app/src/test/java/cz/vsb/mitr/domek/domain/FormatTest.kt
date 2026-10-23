package cz.vsb.mitr.domek.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun temperatureHasOneDecimalAndDecimalComma() {
        assertEquals("22,4\u00A0°C", formatTemp(22.4))
        // Spojovník jako minus, ne Unicode minus.
        assertEquals("-3,0\u00A0°C", formatTemp(-3.0))
        assertEquals("—", formatTemp(null))
    }

    @Test
    fun otherValuesHaveNoDecimals() {
        assertEquals("45\u00A0%", formatPercent(45.4))
        assertEquals("1013\u00A0hPa", formatPressure(1013.25))
        assertEquals("320\u00A0lx", formatLux(320.4))
        assertEquals("—", formatPercent(null))
        assertEquals("—", formatPressure(null))
        assertEquals("—", formatLux(null))
    }

    // Minuty bez pohybu z Nastavení se ukazují vždy v minutách, na rozdíl od formatDuration, které od hodiny přechází na hodiny.
    @Test
    fun minutesAreWholeNumbersWithTheUnitSeparatedByANonBreakingSpace() {
        assertEquals("30${NBSP}min", formatMinutes(30.0))
        assertEquals("1${NBSP}min", formatMinutes(1.0))
        assertEquals("240${NBSP}min", formatMinutes(240.0))
        assertEquals("—", formatMinutes(null))
    }

    @Test
    fun formatDecimalUsesCommaAndTheGivenDecimals() {
        assertEquals("3,1", formatDecimal(3.14159))
        assertEquals("3,14", formatDecimal(3.14159, 2))
        assertEquals("3", formatDecimal(3.14159, 0))
        assertEquals("-0,5", formatDecimal(-0.5))
        assertEquals("0,0", formatDecimal(0.0))
        assertEquals("1234,5", formatDecimal(1234.5))
    }

    // Záporné číslo, které se zaokrouhlí na nulu, je nula bez znaménka: „-0,0“ by vypadalo jako chyba.
    @Test
    fun aNumberRoundedToZeroHasNoMinusSign() {
        assertEquals("0,0", formatDecimal(-0.04))
        assertEquals("0,0", formatDecimal(-0.0))
        assertEquals("-0,1", formatDecimal(-0.05))
        assertEquals("0", formatDecimal(-0.4, 0))
        assertEquals("-1", formatDecimal(-0.5, 0))
        assertEquals("0,00", formatDecimal(-0.004, 2))
        assertEquals("-0,01", formatDecimal(-0.006, 2))
        assertEquals("-10,0", formatDecimal(-10.0))
        assertEquals("-Infinity", formatDecimal(Double.NEGATIVE_INFINITY))
        assertEquals("0,0${NBSP}°C", formatTemp(-0.04))
    }

    @Test
    fun formattingDoesNotDependOnTheDefaultLocale() {
        // Telefon s jiným jazykem by bez Locale.ROOT psal třeba arabské nebo perské číslice.
        val saved = Locale.getDefault()
        try {
            listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "sv-SE", "de-DE", "en-US").forEach { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))

                assertEquals(tag, "-3,0\u00A0°C", formatTemp(-3.0))
                assertEquals(tag, "45\u00A0%", formatPercent(45.4))
                assertEquals(tag, "1013\u00A0hPa", formatPressure(1013.25))
                assertEquals(tag, "2\u00A0min 12\u00A0s", formatDuration(132))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun durationShowsTheTwoLargestUnits() {
        assertEquals("0\u00A0s", formatDuration(0))
        assertEquals("45\u00A0s", formatDuration(45))
        assertEquals("59\u00A0s", formatDuration(59))
        assertEquals("1\u00A0min", formatDuration(60))
        assertEquals("1\u00A0min 1\u00A0s", formatDuration(61))
        assertEquals("2\u00A0min 12\u00A0s", formatDuration(132))
        assertEquals("2\u00A0min", formatDuration(120))
        assertEquals("59\u00A0min 59\u00A0s", formatDuration(3_599))
        assertEquals("1\u00A0h", formatDuration(3_600))
        assertEquals("1\u00A0h 5\u00A0min", formatDuration(3_900))
        assertEquals("23\u00A0h 59\u00A0min", formatDuration(86_399))
    }

    @Test
    fun durationDropsSecondsOnceThereAreHours() {
        assertEquals("1\u00A0h", formatDuration(3_601))
        assertEquals("1\u00A0h 5\u00A0min", formatDuration(3_959))
    }

    @Test
    fun durationOfADayOrMoreIsSummarized() {
        assertEquals("více než den", formatDuration(90_000))
        assertEquals("více než den", formatDuration(86_400))
        assertEquals("více než den", formatDuration(Long.MAX_VALUE))
    }

    @Test
    fun negativeDurationIsShownAsZero() {
        assertEquals("0\u00A0s", formatDuration(-5))
    }

    @Test
    fun agoTruncatesToWholeUnits() {
        assertEquals("před 40\u00A0s", formatAgo(40_400))
        assertEquals("před 2\u00A0min", formatAgo(150_000))
        assertEquals("před 1\u00A0h 5\u00A0min", formatAgo(3_900_000))
        assertEquals("před více než dnem", formatAgo(90_000_000))
    }

    @Test
    fun agoDropsSecondsFromAMinuteUp() {
        assertEquals("před 0\u00A0s", formatAgo(0))
        assertEquals("před 0\u00A0s", formatAgo(999))
        assertEquals("před 59\u00A0s", formatAgo(59_999))
        assertEquals("před 1\u00A0min", formatAgo(60_000))
        assertEquals("před 1\u00A0min", formatAgo(119_999))
        assertEquals("před 59\u00A0min", formatAgo(3_599_999))
        assertEquals("před 1\u00A0h", formatAgo(3_600_000))
        assertEquals("před 1\u00A0h 1\u00A0min", formatAgo(3_665_000))
        assertEquals("před 23\u00A0h 59\u00A0min", formatAgo(86_399_999))
        assertEquals("před více než dnem", formatAgo(86_400_000))
        assertEquals("před více než dnem", formatAgo(Long.MAX_VALUE))
    }

    @Test
    fun negativeAgoIsShownAsZero() {
        // Záporné stáří by nemělo vzniknout; kdyby ano, ukáže se jako před 0 s, ne jako před -5 s.
        assertEquals("před 0\u00A0s", formatAgo(-5_000))
    }

    @Test
    fun clockDiffSaysWhichWayTheRaspberryIsOff() {
        assertEquals("Hodiny Raspberry jdou o${NBSP}2\u00A0min 12\u00A0s pozadu.", clockDiffText(-132))
        assertEquals("Hodiny Raspberry jdou o${NBSP}2\u00A0min 12\u00A0s napřed.", clockDiffText(132))
        assertEquals("Hodiny Raspberry jdou o${NBSP}1\u00A0h 5\u00A0min pozadu.", clockDiffText(-3_900))
        assertEquals("Hodiny Raspberry jdou o${NBSP}více než den napřed.", clockDiffText(200_000))
        assertEquals("Hodiny Raspberry jdou o${NBSP}více než den pozadu.", clockDiffText(-200_000))
    }
}
