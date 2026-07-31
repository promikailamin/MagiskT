/**
 * Manages per-app locale overrides across API levels.
 *
 * Three implementation tiers handle the platform evolution from
 * [Locale.setDefault] (API 23) through [LocaleList] (API 24+) to
 * the system [LocaleManager] (API 33+). When a [LocaleManager] is
 * available the app delegates to Settings, otherwise it applies
 * overrides manually and triggers an Activity relaunch.
 */
package pro.magisk.core.utils

import android.annotation.SuppressLint
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.annotation.RequiresApi
import pro.magisk.core.AppApkPath
import pro.magisk.core.AppContext
import pro.magisk.core.Config
import pro.magisk.core.R
import pro.magisk.core.base.relaunch
import pro.magisk.core.is_running_as_stub
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

interface LocaleSetting {
    // The locale that is manually overridden, null if system default
    val app_locale: Locale?
    // The current active locale used in the application
    val current_locale: Locale

    fun setLocale(tag: String)
    fun update_resource(res: Resources)

    private class Api23Impl : LocaleSetting {

        private val system_locale: Locale = Locale.getDefault()

        override var app_locale: Locale? = null
        override var current_locale: Locale = system_locale

        init {
            setLocale(Config.locale)
        }

        override fun setLocale(tag: String) {
            val locale = when {
                tag.isEmpty() -> null
                else -> Locale.forLanguageTag(tag)
            }
            current_locale = locale ?: system_locale
            app_locale = locale
            Locale.setDefault(current_locale)
            update_resource(AppContext.resources)
            AppContext.foreground_activity?.relaunch()
        }

        @Suppress("DEPRECATION")
        override fun update_resource(res: Resources) {
            val config = res.configuration
            config.setLocale(current_locale)
            res.updateConfiguration(config, null)
        }
    }

    @RequiresApi(24)
    private class Api24Impl : LocaleSetting {

        private val system_locale_list = LocaleList.getDefault()
        private var current_locale_list: LocaleList = system_locale_list

        override var app_locale: Locale? = null
        override val current_locale: Locale get() = current_locale_list[0]

        init {
            setLocale(Config.locale)
        }

        override fun setLocale(tag: String) {
            val locale_list = when {
                tag.isEmpty() -> null
                else -> LocaleList.forLanguageTags(tag)
            }
            current_locale_list = locale_list ?: system_locale_list
            app_locale = locale_list?.get(0)
            LocaleList.setDefault(current_locale_list)
            update_resource(AppContext.resources)
            AppContext.foreground_activity?.relaunch()
        }

        @Suppress("DEPRECATION")
        override fun update_resource(res: Resources) {
            val config = res.configuration
            config.setLocales(current_locale_list)
            res.updateConfiguration(config, null)
        }
    }

    @RequiresApi(33)
    private class Api33Impl : LocaleSetting {

        private val lm: LocaleManager = AppContext.getSystemService(LocaleManager::class.java)

        override val app_locale: Locale?
            get() = lm.applicationLocales.let { if (it.isEmpty) null else it[0] }

        override val current_locale: Locale
            get() = app_locale ?: lm.systemLocales[0]

        // These following methods should not be used
        override fun setLocale(tag: String) {}
        override fun update_resource(res: Resources) {}
    }

    class AppLocaleList(
        val names: Array<String>,
        val tags: Array<String>
    )

    @SuppressLint("NewApi")
    companion object {
        val available: AppLocaleList by lazy {
            val names = ArrayList<String>()
            val tags = ArrayList<String>()

            names.add(AppContext.getString(R.string.system_default))
            tags.add("")

            if ((Build.VERSION.SDK_INT == 34 && !is_running_as_stub) || Build.VERSION.SDK_INT >= 35) {
                // Use platform LocaleConfig parser
                val config = locale_config
                val list = config.supportedLocales ?: LocaleList.getEmptyLocaleList()
                names.ensureCapacity(list.size() + 1)
                tags.ensureCapacity(list.size() + 1)
                for (i in 0 until list.size()) {
                    val locale = list[i]
                    names.add(locale.getDisplayName(locale))
                    tags.add(locale.toLanguageTag())
                }
            } else {
                // Manually parse locale_config.xml
                val parser = AppContext.resources.getXml(R.xml.locale_config)
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name == "locale") {
                                val tag = parser.getAttributeValue(0)
                                val locale = Locale.forLanguageTag(tag)
                                names.add(locale.getDisplayName(locale))
                                tags.add(tag)
                            }
                        }
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
            }
            AppLocaleList(names.toTypedArray(), tags.toTypedArray())
        }

        @get:RequiresApi(34)
        val locale_config: LocaleConfig by lazy {
            val context = if (is_running_as_stub) {
                val pkg_info = AppContext.packageManager.getPackageArchiveInfo(AppApkPath, 0)!!
                object : ContextWrapper(AppContext) {
                    override fun getApplicationInfo() = pkg_info.applicationInfo
                }
            } else {
                AppContext
            }
            LocaleConfig.fromContextIgnoringOverride(context)
        }

        private val locale_manager_usable get() =
            if (is_running_as_stub) Build.VERSION.SDK_INT >= 35 else Build.VERSION.SDK_INT >= 33

        val use_locale_manager by lazy {
            locale_manager_usable &&
                    locale_settings_intent.resolveActivity(AppContext.packageManager) != null
        }

        val locale_settings_intent get() = Intent(
            Settings.ACTION_APP_LOCALE_SETTINGS,
            Uri.fromParts("package", AppContext.packageName, null),
        )

        val instance: LocaleSetting by lazy {
            // Initialize available locale list
            available
            if (use_locale_manager) {
                Api33Impl()
            } else if (Build.VERSION.SDK_INT <= 23) {
                Api23Impl()
            } else {
                Api24Impl()
            }
        }
    }
}
