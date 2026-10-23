package cz.vsb.mitr.domek.ui.overview

import androidx.compose.runtime.Composable
import cz.vsb.mitr.domek.data.source.ConnectionState
import cz.vsb.mitr.domek.domain.NBSP
import cz.vsb.mitr.domek.domain.clockDiffText
import cz.vsb.mitr.domek.domain.formatDuration
import cz.vsb.mitr.domek.domain.piErrorDetail
import cz.vsb.mitr.domek.domain.piErrorText
import cz.vsb.mitr.domek.ui.components.Banner
import cz.vsb.mitr.domek.ui.components.BannerTone
import cz.vsb.mitr.domek.viewmodel.HouseActions
import cz.vsb.mitr.domek.viewmodel.HouseUiState
import cz.vsb.mitr.domek.viewmodel.clockDiffSeconds
import cz.vsb.mitr.domek.viewmodel.isStale
import cz.vsb.mitr.domek.viewmodel.status
import cz.vsb.mitr.domek.viewmodel.statusAgeMs
import kotlin.math.abs

// Spec 6.1: upozornění na hodiny se na Přehledu ukáže při rozdílu nad 2 minuty (v Nastavení už nad 1 minutu).
private const val CLOCK_WARNING_AFTER_SECONDS = 120L

// Karty nad kartou termostatu: stará data, chyby z Raspberry a posun hodin, v tomto pořadí (spec 6.1 a 6.5). Každá karta
// je samostatný prvek sloupce, takže mezi nimi platí jeho mezery a bez karet po nich nic nezůstane.
@Composable
fun OverviewBanners(state: HouseUiState, actions: HouseActions) {
    val ageMs = state.statusAgeMs
    if (state.isStale && ageMs != null) {
        Banner(BannerTone.STALE, "Data jsou ${formatDuration(ageMs / 1000)} stará", staleDataText(state.connection))
    }
    state.status?.errors?.forEach { code ->
        Banner(BannerTone.ERROR, piErrorText(code), piErrorDetail(code))
    }
    val clockDiff = state.clockDiffSeconds
    if (clockDiff != null && abs(clockDiff) > CLOCK_WARNING_AFTER_SECONDS) {
        Banner(
            tone = BannerTone.WARNING,
            title = clockDiffText(clockDiff),
            text = "Na hodinách závisí denní doba.",
            actionLabel = "Srovnat",
            onAction = actions::syncClock,
        )
    }
}

// Text nesmí odporovat štítku spojení nahoře. Při spojení (i v simulaci) přestal posílat Raspberry. Po ručním odpojení se aplikace
// nepřipojuje sama, takže text říká, kde se domek znovu připojí (Nastavení, spec 6.4). Při hledání, připojování a čekání na další
// pokus se aplikace připojuje znovu. Vypnutý Bluetooth, chybějící oprávnění, vypnutá poloha, telefon bez BLE a zařízení bez služby
// Domek připojení brání, takže tam text nic neslibuje.
private fun staleDataText(connection: ConnectionState): String = when (connection) {
    is ConnectionState.Connected, ConnectionState.Simulated ->
        "Raspberry přestalo posílat stav. Hodnoty nemusí odpovídat skutečnosti."
    ConnectionState.DisconnectedByUser -> "Dům je odpojený. Připojit ho jde v${NBSP}Nastavení."
    ConnectionState.Idle, ConnectionState.Scanning, is ConnectionState.ChoosingDevice, is ConnectionState.Connecting,
    ConnectionState.Preparing, is ConnectionState.WaitingToReconnect -> "Spojení se přerušilo, znovu se připojuji…"
    ConnectionState.BluetoothOff, ConnectionState.PermissionsMissing, ConnectionState.LocationOff, ConnectionState.NoBluetooth,
    ConnectionState.MissingService -> "Dům teď není připojený."
}
