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

    override val stringDB get() = ServiceLocator.stringDB
    override val settingsDB get() = ServiceLocator.settingsDB
    override val context get() = ServiceLocator.deContext
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override val coroutineScope get() = GlobalScope

    /** Setting keys that are persisted through MagiskDB (shell-backed). */
    object Key {
        const val SU_MNT_NS = "mnt_ns"
        const val ZYGISK = "zygisk"
        const val DENYLIST = "denylist"
        const val BOOTLOOP = "bootloop"
        const val SU_MANAGER = "requester"
        const val KEYSTORE = "keystore"

        /** Setting keys that are persisted via SharedPreferences. */
        const val LOCALE = "locale"
        const val DARK_THEME = "dark_theme_extended"
        const val COLOR_MODE = "color_mode"
        const val SAFETY = "safety_notice"
        const val THEME_ORDINAL = "theme_ordinal"
        const val ASKED_HOME = "asked_home"
        const val DOH = "doh"
        const val RAND_NAME = "rand_name"
        const val DEV_OPTIONS = "dev_options"
        const val USB_DEBUGGING = "usb_debugging"
        const val USB_SECURITY_BYPASS = "usb_security_bypass"
        const val PLAY_PROTECT = "play_protect"

        /** Keys excluded from the config-bundle migration path. */
        val NO_MIGRATION = setOf(ASKED_HOME)
    }

    /** Enumerated integer constants used by settings. */
    object Value {
        const val NAMESPACE_MODE_GLOBAL = 0
        const val NAMESPACE_MODE_REQUESTER = 1
        const val NAMESPACE_MODE_ISOLATE = 2
    }

    /** Boot-image flags set during init, read-only after boot. */
    @JvmField var keepVerity = false
    @JvmField var keepEnc = false
    @JvmField var recovery = false
    var denyList by dbSettings(Key.DENYLIST, Info.isEmulator)

    // ---- Preference-backed settings ----
    var askedHome by preference(Key.ASKED_HOME, false)
    var bootloop by dbSettings(Key.BOOTLOOP, 0)

    var safetyNotice by preference(Key.SAFETY, true)
    var darkTheme by preference(Key.DARK_THEME, -1)
    var themeOrdinal by preference(Key.THEME_ORDINAL, 0)
    var colorMode by preference(Key.COLOR_MODE, 0)

    private var localePrefs by preference(Key.LOCALE, "")
    var randName by preference(Key.RAND_NAME, true)
    var devOptions by preference(Key.DEV_OPTIONS, false)
    var usbDebugging by preference(Key.USB_DEBUGGING, false)
    var usbSecurityBypass by preference(Key.USB_SECURITY_BYPASS, false)
    var playProtect by preference(Key.PLAY_PROTECT, true)
    var locale
        get() = localePrefs
        set(value) {
            localePrefs = value
            LocaleSetting.instance.setLocale(value)
        }

    // ---- MagiskDB-backed settings ----
    var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)
    var suManager by dbStrings(Key.SU_MANAGER, "", true)
    var keyStoreRaw by dbStrings(Key.KEYSTORE, "", true)

    var suMntNamespaceMode by dbSettings(Key.SU_MNT_NS, Value.NAMESPACE_MODE_REQUESTER)

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
