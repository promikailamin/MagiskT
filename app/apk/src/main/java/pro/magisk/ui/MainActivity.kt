/**
 * Main activity — the primary entry point for the Magisk Manager app.
 *
 * Orchestrates the bottom-navigation layout with Jetpack Navigation, manages the
 * toolbar (up-indicator / back-arrow), handles splash screen transitions, and
 * shows one-shot dialogs for unsupported configurations and environment issues.
 */
package pro.magisk.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.navigation.NavDirections
import pro.magisk.MainDirections
import pro.magisk.R
import pro.magisk.arch.BaseViewModel
import pro.magisk.arch.NavigationActivity
import pro.magisk.arch.viewModel
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.base.SplashController
import pro.magisk.core.base.SplashScreenHost
import pro.magisk.core.model.module.LocalModule
import pro.magisk.databinding.ActivityMainMd2Binding
import pro.magisk.ui.theme.Theme
import pro.magisk.view.MagiskDialog
import java.io.File
import timber.log.Timber
import pro.magisk.core.R as CoreR

class MainViewModel : BaseViewModel()

class MainActivity : NavigationActivity<ActivityMainMd2Binding>(), SplashScreenHost {

    override val layoutRes = R.layout.activity_main_md2
    override val viewModel by viewModel<MainViewModel>()
    override val navHostId: Int = R.id.main_nav_host
    override val splashController = SplashController(this)
    override val snackbarView: View
        get() {
            val fragmentOverride = currentFragment?.snackbarView
            return fragmentOverride ?: super.snackbarView
        }
    override val snackbarAnchorView: View?
        get() {
            val fragmentAnchor = currentFragment?.snackbarAnchorView
            return when {
                fragmentAnchor?.isVisible == true -> fragmentAnchor
                binding.mainNavigation.isVisible -> return binding.mainNavigation
                else -> null
            }
        }

    private var isRootFragment = true

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Theme.selected.themeRes)
        super.onCreate(savedInstanceState)
        splashController.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        splashController.onResume()
    }

    @SuppressLint("InlinedApi")
    override fun onCreateUi(savedInstanceState: Bundle?) {
        setContentView()
        showUnsupportedMessage()

        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Track navigation destination changes to update toolbar and bottom-nav state
        navigation.addOnDestinationChangedListener { _, destination, _ ->
            isRootFragment = when (destination.id) {
                R.id.homeFragment,
                R.id.modulesFragment,
                R.id.logFragment,
                R.id.settingsFragment -> true
                else -> false
            }

            Timber.d("destination changed -> %s (root=%s)", destination.route, isRootFragment)

            requestNavigationHidden(!isRootFragment)

            // Sync the checked bottom-nav item with the current destination
            binding.mainNavigation.menu.forEach {
                if (it.itemId == destination.id) {
                    it.isChecked = true
                }
            }
        }

        binding.mainNavigation.setOnItemSelectedListener {
            getScreen(it.itemId)?.navigate()
            true
        }
        // Reselection listener is intentionally a no-op (Google bug workaround)
        binding.mainNavigation.setOnItemReselectedListener {
            // https://issuetracker.google.com/issues/124538620
        }
        binding.mainNavigation.menu.apply {
            findItem(R.id.modulesFragment)?.isEnabled = Info.env.isActive && LocalModule.loaded()
        }

        val section =
            if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES)
                Const.Nav.SETTINGS
            else
                intent.getStringExtra(Const.Key.OPEN_SECTION)

        getScreen(section)?.navigate()

        if (!isRootFragment) {
            requestNavigationHidden(requiresAnimation = savedInstanceState == null)
        }
    }

    internal fun requestNavigationHidden(hide: Boolean = true, requiresAnimation: Boolean = true) {
        val bottomView = binding.mainNavigation
        if (requiresAnimation) {
            bottomView.isVisible = true
            bottomView.isHidden = hide
        } else {
            bottomView.isGone = hide
        }
    }

    private fun getScreen(name: String?): NavDirections? {
        return when (name) {
            Const.Nav.MODULES -> MainDirections.actionModuleFragment()
            Const.Nav.SETTINGS -> MainDirections.actionSettingsFragment()
            else -> null
        }
    }

    private fun getScreen(id: Int): NavDirections? {
        return when (id) {
            R.id.homeFragment -> MainDirections.actionHomeFragment()
            R.id.modulesFragment -> MainDirections.actionModuleFragment()
            R.id.logFragment -> MainDirections.actionLogFragment()
            R.id.settingsFragment -> MainDirections.actionSettingsFragment()
            else -> null
        }
    }

    /** Shows dialogs for known unsupported configurations. */
    private fun showUnsupportedMessage() {
        // Magisk version too old or unsupported
        if (Info.env.isUnsupported) {
            MagiskDialog(this).apply {
                setTitle(CoreR.string.unsupport_magisk_title)
                setMessage(CoreR.string.unsupport_magisk_msg, Const.Version.MIN_VERSION)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        // Another su binary is present on the PATH alongside magisk
        if (!Info.isEmulator && Info.env.isActive && System.getenv("PATH")
                ?.split(':')
                ?.filterNot { File("$it/magisk").exists() }
                ?.any { File("$it/su").exists() } == true) {
            MagiskDialog(this).apply {
                setTitle(CoreR.string.unsupport_general_title)
                setMessage(CoreR.string.unsupport_other_su_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        // Running as a system app (can cause issues)
        if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            MagiskDialog(this).apply {
                setTitle(CoreR.string.unsupport_general_title)
                setMessage(CoreR.string.unsupport_system_app_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        // Running from external storage
        if (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE != 0) {
            MagiskDialog(this).apply {
                setTitle(CoreR.string.unsupport_general_title)
                setMessage(CoreR.string.unsupport_external_storage_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }
    }
}
