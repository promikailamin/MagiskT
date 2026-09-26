/**
 * Concrete [BaseSettingsItem] instances for all settings screen options.
 *
 * Organised in sections: Customization, App, Magisk, Developer options.
 * Each object encapsulates its own value binding (toggle, selector, blank action, etc.)
 * and the behaviour triggered on press/action.
 */
package pro.magisk.ui.settings

import android.content.res.Resources
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.Config
import pro.magisk.core.Info
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import pro.magisk.core.utils.LocaleSetting
import pro.magisk.core.utils.TextHolder
import pro.magisk.core.utils.asText
import pro.magisk.core.R as CoreR
import kotlin.reflect.KMutableProperty0

// --- Customization

object Customization : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_customization.asText()
}

object Language : BaseSettingsItem.Selector() {
    private val names: Array<String> get() = LocaleSetting.available.names
    private val tags: Array<String> get() = LocaleSetting.available.tags

    override var value
        get() = tags.indexOf(Config.locale)
        set(value) {
            Config.locale = tags[value]
        }

    override val title = CoreR.string.language.asText()

    override fun entries(res: Resources) = names
    override fun descriptions(res: Resources) = names
}

object LanguageSystem : BaseSettingsItem.Blank() {
    override val title = CoreR.string.language.asText()
    override val description: TextHolder
        get() {
            val locale = LocaleSetting.instance.appLocale
            return locale?.getDisplayName(locale)?.asText() ?: CoreR.string.system_default.asText()
        }
}

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- App

object App : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_section_app.asText()
}


object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

object AppManager : BaseSettingsItem.Blank() {
    override val title = CoreR.string.app_manager_title.asText()
    override val description = CoreR.string.app_manager_summary.asText()
}

object RandNameToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_random_name_title.asText()
    override val description = CoreR.string.settings_random_name_description.asText()
    override var value by Config::randName
}

object CleanRam : BaseSettingsItem.Blank() {
    override val title = "Clean Device Ram".asText()
    override val description = "This will unload all unnecessary items from your ram that loaded before.".asText()
}

// --- Magisk

object Magisk : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_section_magisk.asText()
}

object Zygisk : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.zygisk.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_zygisk_summary.asText()
    override var value
        get() = Config.zygisk
        set(value) {
            Config.zygisk = value
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isZygiskEnabled
}

object DenyList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_denylist_title.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_denylist_summary.asText()

    override var value
        get() = Config.denyList
        set(value) {
            Config.denyList = value
            Shell.cmd("magisk --denylist ${if (value) "enable" else "disable"}").submit()
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isDenylistEnforced
}

object DenyListConfig : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_denylist_config_title.asText()
    override val description = CoreR.string.settings_denylist_config_summary.asText()
}

// --- Developer options

object Developer : BaseSettingsItem.Section() {
    override val title = CoreR.string.settings_section_developer.asText()
}

/** Toggle backed by a system global setting / property via shell commands. */
abstract class SystemSettingToggle(
    private val getCmd: String,
    private val setCmd: (Boolean) -> String,
    private val setting: KMutableProperty0<Boolean>
) : BaseSettingsItem.Toggle() {

    private val shell = Shell.getShell()

    override var value
        get() = setting.get()
        set(value) {
            setting.set(value)
            Shell.cmd(setCmd(value)).submit()
        }

    override fun refresh() {
        val current = runCatching { fastCmd(shell, getCmd) }.getOrNull()
        if (current != null) {
            val new = current == "1"
            if (value != new) {
                setting.set(new)
                notifyPropertyChanged(BR.checked)
            }
        }
    }
}

object DeveloperOptions : SystemSettingToggle(
    getCmd = "settings get global development_settings_enabled",
    setCmd = { "settings put global development_settings_enabled ${if (it) "1" else "0"}" },
    setting = Config::devOptions
) {
    override val title = CoreR.string.settings_developer_options_title.asText()
    override val description = CoreR.string.settings_developer_options_summary.asText()
}

object UsbDebugging : SystemSettingToggle(
    getCmd = "settings get global adb_enabled",
    setCmd = { "settings put global adb_enabled ${if (it) "1" else "0"}" },
    setting = Config::usbDebugging
) {
    override val title = CoreR.string.settings_usb_debugging_title.asText()
    override val description = CoreR.string.settings_usb_debugging_summary.asText()
}

object UsbSecurityBypass : SystemSettingToggle(
    getCmd = "getprop persist.security.adbinput",
    setCmd = { "setprop persist.security.adbinput ${if (it) "1" else "0"}" },
    setting = Config::usbSecurityBypass
) {
    override val title = CoreR.string.settings_usb_security_bypass_title.asText()
    override val description = CoreR.string.settings_usb_security_bypass_summary.asText()
}

object PlayProtect : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_play_protect_title.asText()
    override val description = CoreR.string.settings_play_protect_summary.asText()

    override var value
        get() = Config.playProtect
        set(value) {
            Config.playProtect = value
            Shell.cmd("settings put global package_verifier_user_consent ${if (value) "1" else "-1"}").submit()
        }

    override fun refresh() {
        val current = runCatching { fastCmd(Shell.getShell(), "settings get global package_verifier_user_consent") }.getOrNull()
        if (current != null) {
            val new = current == "1"
            if (value != new) {
                Config.playProtect = new
                notifyPropertyChanged(BR.checked)
            }
        }
    }
}

object MountNamespaceMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.mount_namespace_mode.asText()
    override val entryRes = CoreR.array.namespace
    override val descriptionRes = CoreR.array.namespace_summary
    override var value by Config::suMntNamespaceMode
}
