package cz.vsb.mitr.domek

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.util.TypedValue
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.vsb.mitr.domek.screenshots.PHONE
import cz.vsb.mitr.domek.testing.tab
import cz.vsb.mitr.domek.testing.title
import cz.vsb.mitr.domek.ui.DomekTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Spuštění aktivity se skutečným ViewModelem a zdrojem Bluetooth. Robolectric nemá BLE, takže zdroj ohlásí „Bez Bluetooth“.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = PHONE)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnOverviewAndShowsConnectionState() {
        compose.title().assertTextEquals("Chytrý dům")
        compose.onNodeWithText("Bez Bluetooth").assertExists()
    }

    @Test
    fun keepsScreenOn() {
        val flags = compose.activity.window.attributes.flags
        assertTrue("okno nemá FLAG_KEEP_SCREEN_ON", flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun selectedTabSurvivesRecreationOfTheActivity() {
        compose.tab(DomekTab.LIGHT).performClick()

        compose.activityRule.scenario.recreate()

        compose.title().assertTextEquals("Světlo")
    }

    // Hodnota se čte uvnitř onActivity a ověřuje venku, aby případná chyba nespadla do vlákna aktivity.
    private fun permissionsPermanentlyDenied(): Boolean {
        var denied = false
        compose.activityRule.scenario.onActivity { denied = it.permissionsPermanentlyDenied }
        return denied
    }

    // Zamítnutí „Už se neptat“ platí dál, i když se aktivita vytvoří znovu (otočení, změna motivu, zánik procesu).
    // Jinak by obrazovka místo „Otevřít nastavení“ nabídla „Povolit“, které systém už nezobrazí.
    @Test
    fun permanentDenialSurvivesRecreationOfTheActivity() {
        compose.activityRule.scenario.onActivity { it.permissionsPermanentlyDenied = true }

        compose.activityRule.scenario.recreate()

        assertTrue("zamítnutí „Už se neptat“ se po znovuvytvoření aktivity ztratilo", permissionsPermanentlyDenied())
    }

    @Test
    fun permissionsAreNotPermanentlyDeniedUntilTheSystemSaysSo() {
        assertFalse("na začátku nic není zamítnuté", permissionsPermanentlyDenied())

        compose.activityRule.scenario.recreate()

        assertFalse("znovuvytvoření aktivity nesmí nic zamítnout", permissionsPermanentlyDenied())
    }

    // Robolectric nemá BLE. S funkcí Bluetooth LE zdroj po návratu do aplikace (onResume) zjistí, že chybí oprávnění, a obrazovka
    // spojení nabídne „Povolit“.
    private fun showPermissionsScreen() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Povolit").assertIsDisplayed()
    }

    // Klepnutí na „Povolit“ a systémová odpověď, že nic povoleno není. `rationale` je to, co pak systém říká o vysvětlení:
    // po prvním zamítnutí ho chce, po druhém už ne a dialog zavřený klepnutím mimo (Android 11+) na něm nic nezmění.
    private fun denyPermissions(rationale: Boolean) {
        compose.onNode(hasText("Povolit") and hasClickAction()).performClick()
        compose.activityRule.scenario.onActivity { activity ->
            val shadow = shadowOf(activity)
            val permissions = shadow.lastRequestedPermission.requestedPermissions
            permissions.forEach { shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(it, rationale) }
            // Odpověď jde stejnou cestou jako od systému (dispatchActivityResult), jinak by aktivita žádost nepovažovala za
            // skončenou a další by zahodila jako souběžnou. Klíče jsou PackageManager.EXTRA_REQUEST_PERMISSIONS_*, které
            // aplikace nevidí.
            val answer = Intent()
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", permissions)
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", IntArray(permissions.size) { PERMISSION_DENIED })
            shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_OK, answer)
        }
        compose.waitForIdle()
    }

    // Dialog zavřený klepnutím mimo vrací zamítnutí bez vysvětlení stejně jako „Už se neptat“. Systém by ho ale ukázal znovu,
    // takže „Povolit“ zůstává.
    @Test
    fun aDismissedPermissionDialogStillOffersAllow() {
        showPermissionsScreen()

        denyPermissions(rationale = false)

        compose.onNodeWithText("Povolit").assertIsDisplayed()
        compose.onNodeWithText("Otevřít nastavení").assertDoesNotExist()
    }

    @Test
    fun theSecondDenialWithoutRationaleOffersTheAppSettings() {
        showPermissionsScreen()

        denyPermissions(rationale = true)
        compose.onNodeWithText("Povolit").assertIsDisplayed()

        denyPermissions(rationale = false)
        compose.onNodeWithText("Otevřít nastavení").assertIsDisplayed()
        compose.onNodeWithText("Povolit").assertDoesNotExist()
    }

    // Počet zamítnutí přežije otočení telefonu stejně jako trvalé zamítnutí.
    @Test
    fun theDenialsCountAcrossRecreationOfTheActivity() {
        showPermissionsScreen()
        denyPermissions(rationale = false)

        compose.activityRule.scenario.recreate()
        denyPermissions(rationale = false)

        compose.onNodeWithText("Otevřít nastavení").assertIsDisplayed()
    }

    // Okno aplikace má od spuštění barvy motivu telefonu, takže v tmavém režimu před prvním snímkem nebliká bílá.
    @Test
    fun theWindowFollowsTheLightTheme() {
        assertTrue("světlý motiv má světlé okno", compose.activity.hasLightWindowTheme())
    }

    @Test
    @Config(qualifiers = "+night")
    fun theWindowIsDarkFromTheStartInTheDarkTheme() {
        assertFalse("tmavý motiv má tmavé okno", compose.activity.hasLightWindowTheme())
    }

    private fun Activity.hasLightWindowTheme(): Boolean {
        val value = TypedValue()
        check(theme.resolveAttribute(android.R.attr.isLightTheme, value, true)) { "motiv nemá isLightTheme" }
        return value.data != 0
    }
}
