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
import pro.magisk.core.isRunningAsStub
import pro.magisk.view.Notifications
import pro.magisk.core.utils.RootUtils
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell

/** Interface that an activity must implement to work with [SplashController]. */
interface SplashScreenHost : IActivityExtension {
    val splashController: SplashController<*>

    fun onCreateUi(savedInstanceState: Bundle?)
    fun showInvalidStateMessage()
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
        private var splashShown = false
    }

    private var shouldCreateUiOnResume = false

    fun preOnCreate() {
        if (isRunningAsStub && !splashShown) {
            activity.theme.applyStyle(R.style.StubSplashTheme, true)
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        if (!isRunningAsStub) {
            val splashScreen = activity.installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !splashShown }
        }

        if (splashShown) {
            doCreateUi(savedInstanceState)
        } else {
            Shell.getShell(Shell.EXECUTOR) {
                if (isRunningAsStub && !it.isRoot) {
                    activity.showInvalidStateMessage()
                    return@getShell
                }
                activity.initializeApp()
                activity.runOnUiThread {
                    splashShown = true
                    if (isRunningAsStub) {
                        activity.relaunch()
                    } else {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            doCreateUi(savedInstanceState)
                        } else {
                            shouldCreateUiOnResume = true
                        }
                    }
                }
            }
        }
    }

    fun onResume() {
        if (shouldCreateUiOnResume) {
            doCreateUi(null)
        }
    }

    private fun doCreateUi(savedInstanceState: Bundle?) {
        shouldCreateUiOnResume = false
        activity.onCreateUi(savedInstanceState)
    }

    /** One-time startup initialisation (shell, config, stub validation). */
    private fun T.initializeApp() {
        val prevPkg = launchPackage
        val prevConfig = intent.getBundleExtra(Const.Key.PREV_CONFIG)
        val isPackageMigration = prevPkg != null && prevConfig != null

        Config.init(prevConfig)

        if (packageName != APP_PACKAGE_NAME) {
            runCatching {
                packageManager.getApplicationInfo(APP_PACKAGE_NAME, 0)
                Shell.cmd("(pm uninstall $APP_PACKAGE_NAME)& >/dev/null 2>&1").exec()
            }
        } else {
            if (Config.suManager.isNotEmpty()) {
                Config.suManager = ""
            }
            if (isPackageMigration) {
                Shell.cmd("(pm uninstall $prevPkg)& >/dev/null 2>&1").exec()
            }
        }

        if (isPackageMigration) {
            runOnUiThread {
                StubApk.restartProcess(this)
            }
            return
        }

        Notifications.setup()
        Shortcuts.setupDynamic(this)

        RootUtils.Connection.await()
    }
}
