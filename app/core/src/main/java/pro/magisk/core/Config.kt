/**
 * Central configuration hub for Magisk.
 *
 * Exposes every user-facing setting as a delegated property
 * backed by either [android.content.SharedPreferences] (via
 * [PreferenceConfig]) or Magisk's own shell-backed key-value
 * store (via [DBConfig] / MagiskDB).
 *
 * The setting keys and allowed values are standardised in the
 * nested [Key] and [Value] objects so that callers never need
 * to hard-code strings.
 */
package pro.magisk.core

import android.os.Bundle
import androidx.core.content.edit
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.repository.DBConfig
import pro.magisk.core.repository.PreferenceConfig
import pro.magisk.core.utils.LocaleSetting
import kotlinx.coroutines.GlobalScope

object Config : PreferenceConfig, DBConfig {

    override val string_d_b get() = ServiceLocator.string_d_b
    override val settings_d_b get() = ServiceLocator.settings_d_b
    override val context get() = ServiceLocator.de_context
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override val coroutine_scope get() = GlobalScope

    /** Setting keys that are persisted through MagiskDB (shell-backed). */
    object Key {
        const val ROOT_ACCESS = "root_access"
        const val SU_MULTIUSER_MODE = "multiuser_mode"
        const val SU_MNT_NS = "mnt_ns"
        const val SU_BIOMETRIC = "su_biometric"
        const val ZYGISK = "zygisk"
        const val DENYLIST = "denylist"
        const val BOOTLOOP = "bootloop"
        const val SU_MANAGER = "requester"
        const val KEYSTORE = "keystore"

        /** Setting keys that are persisted via SharedPreferences. */
        const val SU_REQUEST_TIMEOUT = "su_request_timeout"
        const val SU_AUTO_RESPONSE = "su_auto_response"
        const val SU_NOTIFICATION = "su_notification"
        const val SU_REAUTH = "su_reauth"
        const val SU_TAPJACK = "su_tapjack"
        const val SU_RESTRICT = "su_restrict"
        const val LOCALE = "locale"
        const val DARK_THEME = "dark_theme_extended"
        const val COLOR_MODE = "color_mode"
        const val SAFETY = "safety_notice"
        const val THEME_ORDINAL = "theme_ordinal"
        const val ASKED_HOME = "asked_home"
        const val DOH = "doh"
        const val RAND_NAME = "rand_name"

        /** Keys excluded from the config-bundle migration path. */
        val NO_MIGRATION = setOf(ASKED_HOME, SU_REQUEST_TIMEOUT,
            SU_AUTO_RESPONSE, SU_REAUTH, SU_TAPJACK)
    }

    /** Enumerated integer constants used by settings. */
    object Value {
        const val ROOT_ACCESS_DISABLED = 0
        const val ROOT_ACCESS_APPS_ONLY = 1
        const val ROOT_ACCESS_ADB_ONLY = 2
        const val ROOT_ACCESS_APPS_AND_ADB = 3

        const val MULTIUSER_MODE_OWNER_ONLY = 0
        const val MULTIUSER_MODE_OWNER_MANAGED = 1
        const val MULTIUSER_MODE_USER = 2

        const val NAMESPACE_MODE_GLOBAL = 0
        const val NAMESPACE_MODE_REQUESTER = 1
        const val NAMESPACE_MODE_ISOLATE = 2

        const val NO_NOTIFICATION = 0
        const val NOTIFICATION_TOAST = 1
        const val NOTIFICATION_STATUS_BAR = 2

        const val SU_PROMPT = 0
        const val SU_AUTO_DENY = 1
        const val SU_AUTO_ALLOW = 2

        val TIMEOUT_LIST = longArrayOf(0, -1, 10, 20, 30, 60)
    }

    /** Boot-image flags set during init, read-only after boot. */
    @JvmField var keep_verity = false
    @JvmField var keep_enc = false
    @JvmField var recovery = false
    var deny_list by db_settings(Key.DENYLIST, Info.is_emulator)

    // ---- Preference-backed settings ----
    var asked_home by preference(Key.ASKED_HOME, false)
    var bootloop by db_settings(Key.BOOTLOOP, 0)

    var safety_notice by preference(Key.SAFETY, true)
    var dark_theme by preference(Key.DARK_THEME, -1)
    var theme_ordinal by preference(Key.THEME_ORDINAL, 0)
    var color_mode by preference(Key.COLOR_MODE, 0)

    private var locale_prefs by preference(Key.LOCALE, "")
    var rand_name by preference(Key.RAND_NAME, true)
    var locale
        get() = locale_prefs
        set(value) {
            locale_prefs = value
            LocaleSetting.instance.setLocale(value)
        }

    // ---- MagiskDB-backed settings ----
    var zygisk by db_settings(Key.ZYGISK, Info.is_emulator)
    var su_manager by db_strings(Key.SU_MANAGER, "", true)
    var key_store_raw by db_strings(Key.KEYSTORE, "", true)

    var su_default_timeout by preference_str_int(Key.SU_REQUEST_TIMEOUT, 10)
    var su_auto_response by preference_str_int(Key.SU_AUTO_RESPONSE, Value.SU_PROMPT)
    var su_notification by preference_str_int(Key.SU_NOTIFICATION, Value.NOTIFICATION_TOAST)
    var root_mode by db_settings(Key.ROOT_ACCESS, Value.ROOT_ACCESS_APPS_AND_ADB)
    var su_mnt_namespace_mode by db_settings(Key.SU_MNT_NS, Value.NAMESPACE_MODE_REQUESTER)
    var su_multiuser_mode by db_settings(Key.SU_MULTIUSER_MODE, Value.MULTIUSER_MODE_OWNER_ONLY)
    private var su_biometric by db_settings(Key.SU_BIOMETRIC, false)
    var su_auth
        get() = Info.isDeviceSecure && su_biometric
        set(value) {
            su_biometric = value
        }
    var su_re_auth by preference(Key.SU_REAUTH, false)
    var su_tapjack by preference(Key.SU_TAPJACK, true)
    var su_restrict by preference(Key.SU_RESTRICT, false)

    /** Serialises current prefs (minus [Key.NO_MIGRATION]) into a Bundle
     *  for cross-process hand-off (stub → real APK). */
    fun toBundle(): Bundle {
        val map = prefs.all - Key.NO_MIGRATION
        return Bundle().apply {
            for ((key, value) in map) {
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    /** Restores prefs from a Bundle. Only runs on first install
     *  (when [prefs] is empty) to avoid overwriting user changes. */
    @Suppress("DEPRECATION")
    private fun fromBundle(bundle: Bundle) {
        val keys = bundle.keySet().apply { removeAll(Key.NO_MIGRATION) }
        prefs.edit {
            for (key in keys) {
                when (val value = bundle.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
    }

    /** Initialise config from a previously saved bundle. */
    fun init(bundle: Bundle?) {
        if (bundle != null && prefs.all.isEmpty()) {
            fromBundle(bundle)
        }
    }
}
