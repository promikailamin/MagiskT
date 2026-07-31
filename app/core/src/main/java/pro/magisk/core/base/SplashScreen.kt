/**
 * Splash-screen lifecycle integration.
 *
 * [SplashController] manages the one-shot initialisation that runs
 * before the main UI is created: it waits for a shell, initialises
 * [Config] and [Notifications], validates the stub APK, and migrates
 / package state if needed. Once done it hands off to the host
 * activity via [SplashScreenHost.onCreateUi].
 */
package pro.magisk.core.base

import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import pro.magisk.StubApk
import pro.magisk.core.BuildConfig
import pro.magisk.core.BuildConfig.APP_PACKAGE_NAME
import pro.magisk.core.Config
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.is_running_as_stub
import pro.magisk.view.Notifications
import pro.magisk.core.utils.RootUtils
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell

/** Interface that an activity must implement to work with [SplashController]. */
interface SplashScreenHost : IActivityExtension {
    val splash_controller: SplashController<*>

    fun on_create_ui(saved_instance_state: Bundle?)
    fun show_invalid_state_message()
}

/**
 * Manages the one-time initialisation that runs before the main UI.
 *
 * On first launch it waits for a shell, runs [initializeApp],
 * sets up notifications and shortcuts, and hands off to
 * [SplashScreenHost.onCreateUi].
 */
class SplashController<T>(private val activity: T)
    where T: ComponentActivity, T: SplashScreenHost {

    companion object {
        private var splash_shown = false
    }

    private var should_create_ui_on_resume = false

    fun pre_on_create() {
        if (is_running_as_stub && !splash_shown) {
            activity.theme.applyStyle(R.style.StubSplashTheme, true)
        }
    }

    fun onCreate(saved_instance_state: Bundle?) {
        if (!is_running_as_stub) {
            val splash_screen = activity.installSplashScreen()
            splash_screen.setKeepOnScreenCondition { !splash_shown }
        }

        if (splash_shown) {
            do_create_ui(saved_instance_state)
        } else {
            Shell.getShell(Shell.EXECUTOR) {
                if (is_running_as_stub && !it.isRoot) {
                    activity.show_invalid_state_message()
                    return@getShell
                }
                activity.initializeApp()
                activity.runOnUiThread {
                    splash_shown = true
                    if (is_running_as_stub) {
                        activity.relaunch()
                    } else {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            do_create_ui(saved_instance_state)
                        } else {
                            should_create_ui_on_resume = true
                        }
                    }
                }
            }
        }
    }

    fun onResume() {
        if (should_create_ui_on_resume) {
            do_create_ui(null)
        }
    }

    private fun do_create_ui(saved_instance_state: Bundle?) {
        should_create_ui_on_resume = false
        activity.on_create_ui(saved_instance_state)
    }

    /** One-time startup initialisation (shell, config, stub validation). */
    private fun T.initializeApp() {
        val prev_pkg = launchPackage
        val prev_config = intent.getBundleExtra(Const.Key.PREV_CONFIG)
        val is_package_migration = prev_pkg != null && prev_config != null

        Config.init(prev_config)

        if (packageName != APP_PACKAGE_NAME) {
            runCatching {
                packageManager.getApplicationInfo(APP_PACKAGE_NAME, 0)
                Shell.cmd("(pm uninstall $APP_PACKAGE_NAME)& >/dev/null 2>&1").exec()
            }
        } else {
            if (Config.su_manager.isNotEmpty()) {
                Config.su_manager = ""
            }
            if (is_package_migration) {
                Shell.cmd("(pm uninstall $prev_pkg)& >/dev/null 2>&1").exec()
            }
        }

        if (is_package_migration) {
            runOnUiThread {
                StubApk.restart_process(this)
            }
            return
        }

        Notifications.setup()
        Shortcuts.setup_dynamic(this)

        RootUtils.Connection.await()
    }
}
