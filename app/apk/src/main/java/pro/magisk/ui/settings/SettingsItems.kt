/**
 * Concrete [BaseSettingsItem] instances for all settings screen options.
 *
 * Organised in sections: Customization, App, Magisk, Superuser.
 * Each object encapsulates its own value binding (toggle, selector, blank action, etc.)
 * and the behaviour triggered on press/action.
 */
package pro.magisk.ui.settings

import android.content.res.Resources
import android.os.Build
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.Config
import pro.magisk.core.Const
import pro.magisk.core.Info
import com.topjohnwu.superuser.Shell
import pro.magisk.core.utils.LocaleSetting
import pro.magisk.core.utils.TextHolder
import pro.magisk.core.utils.asText
import pro.magisk.core.R as CoreR

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
            val locale = LocaleSetting.instance.app_locale
            return locale?.getDisplayName(locale)?.asText() ?: CoreR.string.system_default.asText()
        }
}

object Theme : BaseSettingsItem.Blank() {
    override val icon = R.drawable.ic_paint
    override val title = CoreR.string.section_theme.asText()
}

// --- App

object AppSettings : BaseSettingsItem.Section() {
    override val title = CoreR.string.home_app_title.asText()
}

object AddShortcut : BaseSettingsItem.Blank() {
    override val title = CoreR.string.add_shortcut_title.asText()
    override val description = CoreR.string.setting_add_shortcut_summary.asText()
}

object SystemlessHosts : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_hosts_title.asText()
    override val description = CoreR.string.settings_hosts_summary.asText()
}

object RandNameToggle : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_random_name_title.asText()
    override val description = CoreR.string.settings_random_name_description.asText()
    override var value by Config::rand_name
}

// --- Magisk

object Magisk : BaseSettingsItem.Section() {
    override val title = CoreR.string.magisk.asText()
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
    val mismatch get() = value != Info.is_zygisk_enabled
}

object DenyList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_denylist_title.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_denylist_summary.asText()

    override var value
        get() = Config.deny_list
        set(value) {
            Config.deny_list = value
            Shell.cmd("magisk --denylist ${if (value) "enable" else "disable"}").submit()
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.is_denylist_enforced
}

object DenyListConfig : BaseSettingsItem.Blank() {
    override val title = CoreR.string.settings_denylist_config_title.asText()
    override val description = CoreR.string.settings_denylist_config_summary.asText()
}

// --- Superuser

object Tapjack : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_tapjack_title.asText()
    override val description = CoreR.string.settings_su_tapjack_summary.asText()
    override var value by Config::su_tapjack
}

object Authentication : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_auth_title.asText()
    override var description = CoreR.string.settings_su_auth_summary.asText()
    override var value by Config::su_auth

    override fun refresh() {
        isEnabled = Info.isDeviceSecure
        if (!isEnabled) {
            description = CoreR.string.settings_su_auth_insecure.asText()
        }
    }
}

object Superuser : BaseSettingsItem.Section() {
    override val title = CoreR.string.superuser.asText()
}

object AccessMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_access.asText()
    override val entry_res = CoreR.array.su_access
    override var value by Config::root_mode
}

object MultiuserMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.multiuser_mode.asText()
    override val entry_res = CoreR.array.multiuser_mode
    override val description_res = CoreR.array.multiuser_summary
    override var value by Config::su_multiuser_mode

    override fun refresh() {
        isEnabled = Const.USER_ID == 0
    }
}

object MountNamespaceMode : BaseSettingsItem.Selector() {
    override val title = CoreR.string.mount_namespace_mode.asText()
    override val entry_res = CoreR.array.namespace
    override val description_res = CoreR.array.namespace_summary
    override var value by Config::su_mnt_namespace_mode
}

object AutomaticResponse : BaseSettingsItem.Selector() {
    override val title = CoreR.string.auto_response.asText()
    override val entry_res = CoreR.array.auto_response
    override var value by Config::su_auto_response
}

object RequestTimeout : BaseSettingsItem.Selector() {
    override val title = CoreR.string.request_timeout.asText()
    override val entry_res = CoreR.array.request_timeout

    private val entry_values = listOf(10, 15, 20, 30, 45, 60)
    override var value = entry_values.indexOfFirst { it == Config.su_default_timeout }
        set(value) {
            field = value
            Config.su_default_timeout = entry_values[value]
        }
}

object SUNotification : BaseSettingsItem.Selector() {
    override val title = CoreR.string.superuser_notification.asText()
    override val entry_res = CoreR.array.su_notification
    override var value by Config::su_notification
}

object Reauthenticate : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_reauth_title.asText()
    override val description = CoreR.string.settings_su_reauth_summary.asText()
    override var value by Config::su_re_auth

    override fun refresh() {
        isEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    }
}

object Restrict : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_su_restrict_title.asText()
    override val description = CoreR.string.settings_su_restrict_summary.asText()
    override var value by Config::su_restrict
}
