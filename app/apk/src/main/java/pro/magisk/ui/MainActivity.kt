/**
 * Main activity — the primary entry point for the Magisk Manager app.
 *
 * Orchestrates the bottom-navigation layout with Jetpack Navigation, manages the
 * toolbar (up-indicator / back-arrow), handles splash screen transitions, and
 * shows one-shot dialogs for unsupported configurations, environment issues, and
 * stub-APK home-screen shortcut requests.
 */
package pro.magisk.ui

import android.Manifest
import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.navigation.NavDirections
import pro.magisk.MainDirections
import pro.magisk.R
import pro.magisk.arch.BaseViewModel
import pro.magisk.arch.NavigationActivity
import pro.magisk.arch.view_model
import pro.magisk.core.Config
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.base.SplashController
import pro.magisk.core.base.SplashScreenHost
import pro.magisk.core.is_running_as_stub
import pro.magisk.core.ktx.toast
import pro.magisk.core.model.module.LocalModule
import pro.magisk.databinding.ActivityMainMd2Binding
import pro.magisk.ui.home.HomeFragmentDirections
import pro.magisk.ui.theme.Theme
import pro.magisk.view.MagiskDialog
import pro.magisk.view.Shortcuts
import java.io.File
import pro.magisk.core.R as CoreR

class MainViewModel : BaseViewModel()

class MainActivity : NavigationActivity<ActivityMainMd2Binding>(), SplashScreenHost {

    override val layout_res = R.layout.activity_main_md2
    override val view_model by view_model<MainViewModel>()
    override val nav_host_id: Int = R.id.main_nav_host
    override val splash_controller = SplashController(this)
    override val snackbar_view: View
        get() {
            val fragment_override = current_fragment?.snackbar_view
            return fragment_override ?: super.snackbar_view
        }
    override val snackbar_anchor_view: View?
        get() {
            val fragment_anchor = current_fragment?.snackbar_anchor_view
            return when {
                fragment_anchor?.isVisible == true -> fragment_anchor
                binding.mainNavigation.isVisible -> return binding.mainNavigation
                else -> null
            }
        }

    private var is_root_fragment = true

    override fun onCreate(saved_instance_state: Bundle?) {
        setTheme(Theme.selected.theme_res)
        splash_controller.pre_on_create()
        super.onCreate(saved_instance_state)
        splash_controller.onCreate(saved_instance_state)
    }

    override fun onResume() {
        super.onResume()
        splash_controller.onResume()
    }

    @SuppressLint("InlinedApi")
    override fun on_create_ui(saved_instance_state: Bundle?) {
        setContentView()
        show_unsupported_message()
        ask_for_home_shortcut()

        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Track navigation destination changes to update toolbar and bottom-nav state
        navigation.addOnDestinationChangedListener { _, destination, _ ->
            is_root_fragment = when (destination.id) {
                R.id.homeFragment,
                R.id.modulesFragment,
                R.id.superuserFragment,
                R.id.logFragment -> true
                else -> false
            }

            request_navigation_hidden(!is_root_fragment)

            // Sync the checked bottom-nav item with the current destination
            binding.mainNavigation.menu.forEach {
                if (it.itemId == destination.id) {
                    it.isChecked = true
                }
            }
        }

        binding.mainNavigation.setOnItemSelectedListener {
            get_screen(it.itemId)?.navigate()
            true
        }
        // Reselection listener is intentionally a no-op (Google bug workaround)
        binding.mainNavigation.setOnItemReselectedListener {
            // https://issuetracker.google.com/issues/124538620
        }
        binding.mainNavigation.menu.apply {
            findItem(R.id.superuserFragment)?.isEnabled = Info.show_super_user
            findItem(R.id.modulesFragment)?.isEnabled = Info.env.isActive && LocalModule.loaded()
        }

        val section =
            if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES)
                Const.Nav.SETTINGS
            else
                intent.getStringExtra(Const.Key.OPEN_SECTION)

        get_screen(section)?.navigate()

        if (!is_root_fragment) {
            request_navigation_hidden(requiresAnimation = saved_instance_state == null)
        }
    }

    internal fun request_navigation_hidden(hide: Boolean = true, requiresAnimation: Boolean = true) {
        val bottom_view = binding.mainNavigation
        if (requiresAnimation) {
            bottom_view.isVisible = true
            bottom_view.isHidden = hide
        } else {
            bottom_view.isGone = hide
        }
    }

    private fun get_screen(name: String?): NavDirections? {
        return when (name) {
            Const.Nav.SUPERUSER -> MainDirections.actionSuperuserFragment()
            Const.Nav.MODULES -> MainDirections.actionModuleFragment()
            Const.Nav.SETTINGS -> HomeFragmentDirections.actionHomeFragmentToSettingsFragment()
            else -> null
        }
    }

    private fun get_screen(id: Int): NavDirections? {
        return when (id) {
            R.id.homeFragment -> MainDirections.actionHomeFragment()
            R.id.modulesFragment -> MainDirections.actionModuleFragment()
            R.id.superuserFragment -> MainDirections.actionSuperuserFragment()
            R.id.logFragment -> MainDirections.actionLogFragment()
            else -> null
        }
    }

    @SuppressLint("InlinedApi")
    override fun show_invalid_state_message(): Unit = runOnUiThread {
        MagiskDialog(this).apply {
            setTitle(CoreR.string.unsupport_nonroot_stub_title)
            setMessage(CoreR.string.unsupport_nonroot_stub_msg)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = CoreR.string.install
                onClick {
                    with_permission(REQUEST_INSTALL_PACKAGES) {
                        if (!it) {
                            toast(CoreR.string.install_unknown_denied, Toast.LENGTH_SHORT)
                            show_invalid_state_message()
                        }
                    }
                }
            }
            setCancelable(false)
            show()
        }
    }

    /** Shows dialogs for known unsupported configurations. */
    private fun show_unsupported_message() {
        // Magisk version too old or unsupported
        if (Info.env.is_unsupported) {
            MagiskDialog(this).apply {
                setTitle(CoreR.string.unsupport_magisk_title)
                setMessage(CoreR.string.unsupport_magisk_msg, Const.Version.MIN_VERSION)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        // Another su binary is present on the PATH alongside magisk
        if (!Info.is_emulator && Info.env.isActive && System.getenv("PATH")
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

    /** Prompts the user to pin a home-screen shortcut when running as stub. */
    private fun ask_for_home_shortcut() {
        if (is_running_as_stub && !Config.asked_home &&
            ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Config.asked_home = true
            MagiskDialog(this).apply {
                setTitle(CoreR.string.add_shortcut_title)
                setMessage(CoreR.string.add_shortcut_msg)
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        Shortcuts.add_home_icon(this@MainActivity)
                    }
                }
                setCancelable(true)
            }.show()
        }
    }
}
