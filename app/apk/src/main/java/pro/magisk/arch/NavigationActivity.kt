/**
 * Activity with Jetpack Navigation integration.
 *
 * Bridges the app's [UIActivity] with a [NavHostFragment], providing:
 * - Access to the current [BaseFragment] for key-event and back-press delegation
 * - A navOptions-aware navigation helper that respects the user's animation preference
 */
package pro.magisk.arch

import android.content.ContentResolver
import android.view.KeyEvent
import androidx.databinding.ViewDataBinding
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import pro.magisk.utils.AccessibilityUtils
import androidx.activity.addCallback

/** Base Activity for screens that use a NavHostFragment for navigation. */
abstract class NavigationActivity<Binding : ViewDataBinding> : UIActivity<Binding>() {

    abstract val nav_host_id: Int

    private val nav_host_fragment by lazy {
        supportFragmentManager.findFragmentById(nav_host_id) as NavHostFragment
    }

    protected val current_fragment get() =
        nav_host_fragment.childFragmentManager.fragments.getOrNull(0) as? BaseFragment<*>

    val navigation: NavController get() = nav_host_fragment.navController

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the current fragment handle key events first
        return if (binded && current_fragment?.on_key_event(event) == true) true else super.dispatchKeyEvent(event)
    }

    init {
        onBackPressedDispatcher.addCallback(this) {
            if (binded) {
                if (current_fragment?.onBackPressed() == false) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    companion object {
        fun navigate(directions: NavDirections, navigation: NavController, cr: ContentResolver) {
            // Skip custom navOptions (which force-no-anim) when animations are disabled
            if (AccessibilityUtils.is_animation_enabled(cr)) {
                navigation.navigate(directions)
            } else {
                navigation.navigate(directions, navOptions {})
            }
        }
    }

    fun NavDirections.navigate() {
        navigate(this, navigation, contentResolver)
    }
}
