/**
 * ViewModel for the settings screen.
 *
 * Builds the dynamic list of [BaseSettingsItem] objects based on device state
 * (rooted? Zygisk enabled? theme?).
 * Also implements [BaseSettingsItem.Handler] to delegate press/action events.
 */
package pro.magisk.ui.settings

import android.view.View
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.ktx.activity
import pro.magisk.core.ktx.toast
import pro.magisk.core.utils.LocaleSetting
import pro.magisk.core.utils.RootUtils
import pro.magisk.databinding.bindExtra
import pro.magisk.events.SnackbarEvent
import kotlinx.coroutines.launch
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd

/** ViewModel that builds and manages the settings item list. */
class SettingsViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val items = createItems()
    val extraBindings = bindExtra {
        it.put(BR.handler, this)
    }
    
    private val shell = Shell.getShell()

    /** Assembles the settings list based on current device and app state. */
    private fun createItems(): List<BaseSettingsItem> {
        val context = AppContext

        val list = mutableListOf(
            Customization,
            Theme, if (LocaleSetting.useLocaleManager) LanguageSystem else Language,
            App
        )
        list.add(RandNameToggle)

        if (Info.env.isActive) {
            list.add(Magisk)
            list.addAll(listOf(CleanRam, SystemlessHosts, AppManager))
            if (Const.Version.atLeast_24_0()) {
                list.addAll(listOf(Zygisk, DenyList, DenyListConfig))
            }

            list.add(Developer)
            list.addAll(listOf(DeveloperOptions, UsbDebugging, UsbSecurityBypass, PlayProtect))
            list.add(MountNamespaceMode)
        }

        applyGroupStyles(list)
        return list
    }

    /** Marks each run of non-section items with its card corner treatment. */
    private fun applyGroupStyles(items: List<BaseSettingsItem>) {
        val group = mutableListOf<BaseSettingsItem>()
        fun flush() {
            if (group.isEmpty()) return
            group.first().groupStyle =
                if (group.size == 1) CardGroupStyle.SINGLE else CardGroupStyle.FIRST
            for (i in 1 until group.size - 1) group[i].groupStyle = CardGroupStyle.MIDDLE
            if (group.size > 1) group.last().groupStyle = CardGroupStyle.LAST
            group.clear()
        }
        for (item in items) {
            if (item is BaseSettingsItem.Section) flush() else group.add(item)
        }
        flush()
    }

    override fun onItemPressed(view: View, item: BaseSettingsItem, doAction: () -> Unit) {
        doAction()
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        when (item) {
            Theme -> SettingsFragmentDirections.actionSettingsFragmentToThemeFragment().navigate()
            LanguageSystem -> view.activity.startActivity(LocaleSetting.localeSettingsIntent)
            CleanRam -> clean_ram()
            SystemlessHosts -> createHosts()
            AppManager -> SettingsFragmentDirections.actionSettingsFragmentToAppManagerFragment().navigate()
            DenyListConfig -> SettingsFragmentDirections.actionSettingsFragmentToDenyFragment().navigate()
            Zygisk -> if (Zygisk.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            DenyList -> if (DenyList.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            else -> Unit
        }
    }

    private fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(R.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }
    
    private fun clean_ram() {
        viewModelScope.launch {
            val v = fastCmd(shell, "sync && echo 3 > /proc/sys/vm/drop_caches")
            AppContext.toast("Device ram has been cleaned now!", Toast.LENGTH_SHORT)
        }
    }
}
