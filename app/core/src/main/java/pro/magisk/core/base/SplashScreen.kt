/**
 * Splash-screen lifecycle integration.
 *
 * [SplashController] manages the one-shot initialisation that runs
 * before the main UI is created: it waits for a shell, initialises
 * [Config], and sets up shortcuts. Once done it hands off to the host
 * activity via [SplashScreenHost.onCreateUi].
 */
package pro.magisk.core.base

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import pro.magisk.core.utils.RootUtils
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell

/** Interface that an activity must implement to work with [SplashController]. */
interface SplashScreenHost : IActivityExtension {
    val splashController: SplashController<*>

    fun onCreateUi(savedInstanceState: Bundle?)
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

    fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = activity.installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !splashShown }

        if (splashShown) {
            doCreateUi(savedInstanceState)
        } else {
            Shell.getShell(Shell.EXECUTOR) {
                activity.initializeApp()
                activity.runOnUiThread {
                    splashShown = true
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        doCreateUi(savedInstanceState)
                    } else {
                        shouldCreateUiOnResume = true
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

    /** One-time startup initialisation (shell, config, shortcuts). */
    private fun T.initializeApp() {
        Shortcuts.setupDynamic(this)
        RootUtils.Connection.await()
    }
}
