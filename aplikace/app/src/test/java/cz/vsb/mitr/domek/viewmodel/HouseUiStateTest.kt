package cz.vsb.mitr.domek.viewmodel

import cz.vsb.mitr.domek.DEFAULTS
import cz.vsb.mitr.domek.EXAMPLE
import cz.vsb.mitr.domek.WITHOUT_STORED_LOOK
import cz.vsb.mitr.domek.data.model.HouseStatus
import cz.vsb.mitr.domek.data.model.LightState
import cz.vsb.mitr.domek.data.model.Rgb
import cz.vsb.mitr.domek.data.model.SettingKey
import cz.vsb.mitr.domek.data.source.ReceivedStatus
import cz.vsb.mitr.domek.domain.DayStarts
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseUiStateTest {
    private val phoneTime = LocalDateTime.of(2026, 10, 20, 10, 2, 15)

    private fun received(atMs: Long = 0, status: HouseStatus = EXAMPLE) = ReceivedStatus(status, atMs, phoneTime)

    // Stáří a stará data

    @Test
    fun statusAgeIsNowMinusReceivedAndNeverNegative() {
        assertEquals(3_200L, HouseUiState(received = received(atMs = 1_000), nowMs = 4_200).statusAgeMs)
        // Stav přijatý mezi tiky je novější než nowMs.
        assertEquals(0L, HouseUiState(received = received(atMs = 5_000), nowMs = 4_000).statusAgeMs)
    }

    @Test
    fun dataAreStaleOnlyAfterMoreThanThreeSeconds() {
        val state = HouseUiState(received = received(atMs = 1_000), nowMs = 4_000)
        assertFalse(state.isStale)
        assertTrue(state.copy(nowMs = 4_001).isStale)
    }

    @Test
    fun withoutStatusThereIsNoAgeAndNothingIsStale() {
        val state = HouseUiState(nowMs = 60_000)
        assertNull(state.status)
        assertNull(state.statusAgeMs)
        assertFalse(state.isStale)
    }

    // Hodnoty Nastavení

    @Test
    fun settingValueIsTheDraftOtherwiseTheSettings() {
        val state = HouseUiState(settings = DEFAULTS)
        assertEquals(JsonPrimitive(23.0), state.settingValue(SettingKey.TARGET_DAY))
        assertEquals(23.0, state.number(SettingKey.TARGET_DAY)!!, 0.0)

        val drafted = state.copy(drafts = mapOf(SettingKey.TARGET_DAY to JsonPrimitive(23.5)))
        assertEquals(JsonPrimitive(23.5), drafted.settingValue(SettingKey.TARGET_DAY))
        assertEquals(23.5, drafted.number(SettingKey.TARGET_DAY)!!, 0.0)
        // Draft jednoho klíče nezasáhne ostatní.
        assertEquals(JsonPrimitive(20.0), drafted.settingValue(SettingKey.TARGET_NIGHT))
    }

    @Test
    fun settingValueIsMissingWithoutSettingsOrKey() {
        assertNull(HouseUiState().settingValue(SettingKey.TARGET_DAY))
        assertNull(HouseUiState().number(SettingKey.TARGET_DAY))
        // Raspberry se starším programem klíč nocni_svetlo_pri_pohybu neposílá.
        assertNull(HouseUiState(settings = DEFAULTS.copy(lightOnMotion = null)).settingValue(SettingKey.LIGHT_ON_MOTION))
    }

    @Test
    fun numberIsMissingForValuesThatAreNotNumbers() {
        val state = HouseUiState(settings = DEFAULTS)
        assertEquals(JsonPrimitive("auto"), state.settingValue(SettingKey.MODE))
        assertNull(state.number(SettingKey.MODE))
        assertNull(state.number(SettingKey.DAY_START))
        assertNull(state.number(SettingKey.LIGHT_ON_MOTION))
        assertEquals(30.0, state.number(SettingKey.NIGHT_LIGHT_SECONDS)!!, 0.0)
    }

    @Test
    fun dayStartsFollowDraftsAndSettings() {
        val state = HouseUiState(settings = DEFAULTS)
        assertEquals(DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(22, 0)), state.dayStarts)

        val drafted = state.copy(drafts = mapOf(SettingKey.NIGHT_START to JsonPrimitive("10:05")))
        assertEquals(DayStarts(LocalTime.of(7, 0), LocalTime.of(19, 0), LocalTime.of(10, 5)), drafted.dayStarts)
    }

    @Test
    fun dayStartsAreMissingWithoutSettingsOrWithUnreadableTime() {
        assertNull(HouseUiState().dayStarts)
        val unreadable = HouseUiState(settings = DEFAULTS, drafts = mapOf(SettingKey.NIGHT_START to JsonPrimitive("25:99")))
        assertNull(unreadable.dayStarts)
    }

    // Čas Raspberry

    @Test
    fun piTimeAndClockDifference() {
        val state = HouseUiState(received = received())
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0, 0), state.piTime)
        // Raspberry jde pozadu o 2 min 15 s.
        assertEquals(-135L, state.clockDiffSeconds)

        val ahead = HouseUiState(received = ReceivedStatus(EXAMPLE, 0, LocalDateTime.of(2026, 10, 20, 9, 59, 30)))
        assertEquals(30L, ahead.clockDiffSeconds)
    }

    @Test
    fun piTimeIsMissingWithoutStatusOrWithUnreadableCas() {
        assertNull(HouseUiState().piTime)
        assertNull(HouseUiState().clockDiffSeconds)

        val unreadable = HouseUiState(received = received(status = EXAMPLE.copy(time = "dnes")))
        assertNull(unreadable.piTime)
        assertNull(unreadable.clockDiffSeconds)
    }

    // Uložená barva a jas světla

    // Raspberry, které v Nastavení posílá barvu i jas světla, je umí uložit. Bez nich (starší program) světlo mění jen příkaz.
    @Test
    fun theLookIsStoredOnlyWhenTheSettingsHaveBothTheColorAndTheBrightness() {
        assertTrue(HouseUiState(settings = DEFAULTS).storesLightLook)
        assertFalse(HouseUiState(settings = DEFAULTS.copy(lightColor = null)).storesLightLook)
        assertFalse(HouseUiState(settings = DEFAULTS.copy(lightBrightness = null)).storesLightLook)
        assertFalse(HouseUiState(settings = WITHOUT_STORED_LOOK).storesLightLook)
        assertFalse(HouseUiState().storesLightLook)
    }

    // Zobrazené světlo

    @Test
    fun shownLightIsTheStatusLightWithoutDraft() {
        assertEquals(EXAMPLE.light, HouseUiState(received = received()).shownLight)
    }

    @Test
    fun shownLightIsMissingWithoutStatus() {
        assertNull(HouseUiState(lightDraft = LightDraft(brightness = 40, setAtMs = 0)).shownLight)
    }

    // S uloženou barvou a jasem se výběr jen uloží (spec 3 v 2026-10-23-denik-a-vylepseni-design.md). Svítící světlo se hned
    // přebarví a zůstane, jak bylo: ruční ruční, automatické automatické.
    @Test
    fun withTheStoredLookADraftRecolorsAShiningManualLight() {
        val state = HouseUiState(
            received = received(status = EXAMPLE.copy(light = LightState(on = true, color = listOf(100, 110, 255), brightness = 60, manual = true))),
            settings = DEFAULTS.copy(lightColor = listOf(100, 110, 255), lightBrightness = 60),
        )

        val colored = state.copy(lightDraft = LightDraft(color = Rgb(255, 0, 0), setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 0, 0), brightness = 60, manual = true), colored.shownLight)

        val both = state.copy(lightDraft = LightDraft(Rgb(255, 0, 0), 30, setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 0, 0), brightness = 30, manual = true), both.shownLight)
    }

    @Test
    fun withTheStoredLookADraftRecolorsAShiningAutomaticLightAndKeepsItAutomatic() {
        val evening = HouseUiState(
            received = received(
                status = EXAMPLE.copy(
                    dayPartText = "vecer",
                    light = LightState(on = true, color = listOf(255, 255, 255), brightness = 100, manual = false),
                ),
            ),
            settings = DEFAULTS,
        )

        val colored = evening.copy(lightDraft = LightDraft(color = Rgb(0, 0, 255), setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 100, manual = false), colored.shownLight)

        val dimmed = evening.copy(lightDraft = LightDraft(brightness = 40, setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 255, 255), brightness = 40, manual = false), dimmed.shownLight)
    }

    // Noční světlo (automatika v noci) svítí nejvýš na 10 % (docs/ble-rozhrani.md, Světlo v automatice), ruční světlo v noci ne.
    @Test
    fun withTheStoredLookTheNightLightShowsTheDraftBrightnessCappedAtTenPercent() {
        val warm = listOf(255, 147, 41)
        fun night(light: LightState) = HouseUiState(
            received = received(status = EXAMPLE.copy(dayPartText = "noc", light = light)),
            settings = DEFAULTS.copy(lightColor = warm, lightBrightness = 80),
        )
        val nightLight = night(LightState(on = true, color = warm, brightness = 10, manual = false))

        val brighter = nightLight.copy(lightDraft = LightDraft(brightness = 40, setAtMs = 0))
        assertEquals(LightState(on = true, color = warm, brightness = 10, manual = false), brighter.shownLight)
        val dimmer = nightLight.copy(lightDraft = LightDraft(brightness = 5, setAtMs = 0))
        assertEquals(LightState(on = true, color = warm, brightness = 5, manual = false), dimmer.shownLight)
        val colored = nightLight.copy(lightDraft = LightDraft(color = Rgb(255, 0, 0), setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 0, 0), brightness = 10, manual = false), colored.shownLight)

        val manual = night(LightState(on = true, color = warm, brightness = 80, manual = true))
            .copy(lightDraft = LightDraft(brightness = 40, setAtMs = 0))
        assertEquals(LightState(on = true, color = warm, brightness = 40, manual = true), manual.shownLight)
    }

    // Zhasnuté světlo zůstane zhasnuté: barva a jas se jen uloží pro příští rozsvícení.
    @Test
    fun withTheStoredLookADraftLeavesASwitchedOffLightOff() {
        val manualOff = LightState(on = false, color = listOf(0, 0, 0), brightness = 0, manual = true)
        listOf(EXAMPLE.light, manualOff).forEach { off ->
            val state = HouseUiState(
                received = received(status = EXAMPLE.copy(light = off)),
                settings = DEFAULTS,
                lightDraft = LightDraft(Rgb(0, 0, 255), 40, setAtMs = 0),
            )

            assertEquals(off, state.shownLight)
        }
    }

    // Bez uložené barvy a jasu (starší program) výběr barvy nebo jasu světlo rovnou ručně zapne (spec 6.3), takže ho draft
    // přepíše a zapne.
    @Test
    fun lightDraftOverridesTheStatusLightAndSwitchesItOnManually() {
        val state = HouseUiState(received = received())
        val colored = state.copy(lightDraft = LightDraft(color = Rgb(0, 0, 255), setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 100, manual = true), colored.shownLight)

        val dimmed = state.copy(lightDraft = LightDraft(brightness = 40, setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 255, 255), brightness = 40, manual = true), dimmed.shownLight)

        val both = state.copy(lightDraft = LightDraft(Rgb(255, 128, 0), 70, setAtMs = 0))
        assertEquals(LightState(on = true, color = listOf(255, 128, 0), brightness = 70, manual = true), both.shownLight)
    }

    // Bez uložené barvy (starší program na Raspberry) rozsvítí samotný jas zhasnuté světlo bíle, ne černě: černou hlásí
    // Raspberry u světla, které nesvítí, ale rozsvícené světlo černé není (EXAMPLE má zhasnuté světlo s barvou 0,0,0).
    @Test
    fun brightnessOnlyDraftOfASwitchedOffLightIsWhiteNeverBlack() {
        val off = HouseUiState(received = received(), lightDraft = LightDraft(brightness = 40, setAtMs = 0))
        assertEquals(listOf(0, 0, 0), off.status!!.light.color)

        assertEquals(LightState(on = true, color = listOf(255, 255, 255), brightness = 40, manual = true), off.shownLight)
    }

    // Bez uloženého jasu rozsvítí samotná barva zhasnuté světlo naplno: jas 0 % hlásí jen světlo, které nesvítí.
    @Test
    fun colorOnlyDraftOfASwitchedOffLightIsAtFullBrightness() {
        val off = HouseUiState(received = received(), lightDraft = LightDraft(color = Rgb(0, 0, 255), setAtMs = 0))
        assertEquals(0, off.status!!.light.brightness)

        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 100, manual = true), off.shownLight)
    }

    // Svítící světlo si při změně barvy nechá svůj jas, ať je ruční, nebo automatické.
    @Test
    fun colorOnlyDraftOfAShiningLightKeepsItsBrightness() {
        val shining = HouseUiState(
            received = received(status = EXAMPLE.copy(light = LightState(on = true, color = listOf(100, 110, 255), brightness = 60, manual = false))),
            lightDraft = LightDraft(color = Rgb(255, 0, 0), setAtMs = 0),
        )

        assertEquals(LightState(on = true, color = listOf(255, 0, 0), brightness = 60, manual = true), shining.shownLight)
    }

    // Svítící světlo si při změně jasu nechá svou barvu, ať je ruční, nebo automatické.
    @Test
    fun brightnessOnlyDraftOfAShiningLightKeepsItsColor() {
        val shining = HouseUiState(
            received = received(status = EXAMPLE.copy(light = LightState(on = true, color = listOf(100, 110, 255), brightness = 60, manual = false))),
            lightDraft = LightDraft(brightness = 30, setAtMs = 0),
        )

        assertEquals(LightState(on = true, color = listOf(100, 110, 255), brightness = 30, manual = true), shining.shownLight)
    }

    // Příkaz světla svítí uloženou barvou a jasem (barva_svetla, jas_svetla). Co draft nemění, vezme se z Nastavení, pokud
    // tu část má, ať světlo svítí, nebo ne (docs/ble-rozhrani.md, Příkaz). S oběma klíči by se výběr jen uložil, proto tu
    // Nastavení má vždy jen jeden z nich.
    @Test
    fun brightnessOnlyDraftUsesTheStoredColor() {
        val off = HouseUiState(
            received = received(),
            settings = DEFAULTS.copy(lightColor = listOf(255, 147, 41), lightBrightness = null),
            lightDraft = LightDraft(brightness = 40, setAtMs = 0),
        )

        assertEquals(LightState(on = true, color = listOf(255, 147, 41), brightness = 40, manual = true), off.shownLight)
    }

    @Test
    fun colorOnlyDraftUsesTheStoredBrightness() {
        val off = HouseUiState(
            received = received(),
            settings = DEFAULTS.copy(lightColor = null, lightBrightness = 70),
            lightDraft = LightDraft(color = Rgb(0, 0, 255), setAtMs = 0),
        )

        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 70, manual = true), off.shownLight)
    }

    // Noční světlo svítí nejvýš na 10 %, ruční volba z příkazu ale svítí uloženým jasem.
    @Test
    fun colorOnlyDraftOfTheNightLightUsesTheStoredBrightness() {
        val nightLight = LightState(on = true, color = listOf(255, 147, 41), brightness = 10, manual = false)
        val state = HouseUiState(
            received = received(status = EXAMPLE.copy(dayPartText = "noc", light = nightLight)),
            settings = DEFAULTS.copy(lightColor = null, lightBrightness = 80),
            lightDraft = LightDraft(color = Rgb(255, 0, 0), setAtMs = 0),
        )

        assertEquals(LightState(on = true, color = listOf(255, 0, 0), brightness = 80, manual = true), state.shownLight)
    }

    // Draft s barvou nic nedopočítává, ani bílou: zhasnuté světlo se rozsvítí ve vybrané barvě.
    @Test
    fun colorDraftOfASwitchedOffLightKeepsTheChosenColor() {
        val chosen = HouseUiState(received = received(), lightDraft = LightDraft(color = Rgb(0, 0, 255), brightness = 40, setAtMs = 0))

        assertEquals(LightState(on = true, color = listOf(0, 0, 255), brightness = 40, manual = true), chosen.shownLight)
    }

    // Zvolená barva a jas pro kartu Barva a jas a pro dialog vlastní barvy (spec 3)

    private val warmStored = DEFAULTS.copy(lightColor = listOf(255, 147, 41), lightBrightness = 30)

    // Karta ukazuje uloženou barvu a jas i u zhasnutého světla, které hlásí černou a 0 %.
    @Test
    fun theChosenLookIsTheStoredOneEvenWhileTheLightIsOff() {
        val state = HouseUiState(received = received(), settings = warmStored)

        assertEquals(ChosenLook(Rgb(255, 147, 41), 30), state.chosenLook)
    }

    // Rozpracovaná volba má přednost a chybějící část je ta uložená.
    @Test
    fun theChosenLookPrefersTheDraftAndFillsTheRestFromTheStoredLook() {
        val state = HouseUiState(received = received(), settings = warmStored)

        assertEquals(ChosenLook(Rgb(0, 0, 255), 30), state.copy(lightDraft = LightDraft(color = Rgb(0, 0, 255), setAtMs = 0)).chosenLook)
        assertEquals(ChosenLook(Rgb(255, 147, 41), 70), state.copy(lightDraft = LightDraft(brightness = 70, setAtMs = 0)).chosenLook)
    }

    // Noční světlo svítí nejvýš na 10 %, posuvník ale ukazuje uložený jas, kterým se světlo rozsvítí jindy.
    @Test
    fun theChosenLookOfTheNightLightIsTheStoredOne() {
        val nightLight = LightState(on = true, color = listOf(255, 147, 41), brightness = 10, manual = false)
        val state = HouseUiState(received = received(status = EXAMPLE.copy(dayPartText = "noc", light = nightLight)), settings = warmStored)

        assertEquals(ChosenLook(Rgb(255, 147, 41), 30), state.chosenLook)
    }

    // Bez uložené barvy a jasu (starší program) je zvolené to, čím světlo svítí, i s rozpracovanou volbou, která ho rozsvítí.
    @Test
    fun withoutTheStoredLookTheChosenLookIsTheShownLight() {
        val shining = HouseUiState(
            received = received(status = EXAMPLE.copy(light = LightState(on = true, color = listOf(100, 110, 255), brightness = 60, manual = true))),
            settings = WITHOUT_STORED_LOOK,
        )
        assertEquals(ChosenLook(Rgb(100, 110, 255), 60), shining.chosenLook)

        // Zhasnuté světlo žádnou barvu nesvítí a hlásí jas 0 %.
        val off = HouseUiState(received = received(), settings = WITHOUT_STORED_LOOK)
        assertEquals(ChosenLook(null, 0), off.chosenLook)
        // Samotný jas zhasnuté světlo rozsvítí bíle.
        assertEquals(ChosenLook(Rgb(255, 255, 255), 40), off.copy(lightDraft = LightDraft(brightness = 40, setAtMs = 0)).chosenLook)
    }

    @Test
    fun theChosenLookIsMissingWithoutStatus() {
        assertNull(HouseUiState(settings = DEFAULTS).chosenLook)
    }
}
