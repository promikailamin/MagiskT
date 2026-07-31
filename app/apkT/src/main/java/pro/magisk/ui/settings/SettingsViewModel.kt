/**
 * ViewModel for the settings screen. Manages DenyList toggle state, language/theme preferences,
 * hosts file creation, and authenticates sensitive operations via biometric prompt.
 */
package pro.magisk.ui.settings

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.ktx.toast
import pro.magisk.core.utils.RootUtils
import pro.magisk.ui.navigation.Route
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : BaseViewModel() {

    private val _denyListEnabled = MutableStateFlow(Config.denyList)
    val denyListEnabled: StateFlow<Boolean> = _denyListEnabled.asStateFlow()

    /** True when the stored zygisk pref differs from the actually active state. */
    val zygiskMismatch get() = Config.zygisk != Info.isZygiskEnabled

    /** Set by [MainActivity] to enable biometric authentication before sensitive actions. */
    var authenticate: (onSuccess: () -> Unit) -> Unit = { it() }

    fun navigateToDenyList() {
        navigateTo(Route.DenyList)
    }

    /** Pin a home-screen shortcut. */
    fun requestAddShortcut() {
        Shortcuts.addHomeIcon(AppContext)
    }

    /** Create the systemless hosts file via root. */
    fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }

    /** Toggle Magisk DenyList on/off. */
    fun toggleDenyList(enabled: Boolean) {
        _denyListEnabled.value = enabled
        Config.denyList = enabled
        Shell.cmd("magisk --denylist ${if (enabled) "enable" else "disable"}").submit()
    }

    /** Wrap an action with biometric authentication if enabled. */
    fun withAuth(action: () -> Unit) = authenticate(action)

    /** True when the stored denylist pref differs from the runtime state. */
    val denylistMismatch get() = Config.denyList != Info.isDenylistEnforced

    /** Show a snackbar indicating a reboot is required. */
    fun notifyZygiskChange() {
        if (zygiskMismatch) showSnackbar(R.string.reboot_apply_change)
    }
}
