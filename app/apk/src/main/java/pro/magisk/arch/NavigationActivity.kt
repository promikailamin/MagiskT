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
import timber.log.Timber

/** Base Activity for screens that use a NavHostFragment for navigation. */
abstract class NavigationActivity<Binding : ViewDataBinding> : UIActivity<Binding>() {

    abstract val navHostId: Int

    private val navHostFragment by lazy {
        supportFragmentManager.findFragmentById(navHostId) as NavHostFragment
    }

    protected val currentFragment get() =
        navHostFragment.childFragmentManager.fragments.getOrNull(0) as? BaseFragment<*>

    val navigation: NavController get() = navHostFragment.navController

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the current fragment handle key events first
        return if (binded && currentFragment?.onKeyEvent(event) == true) true else super.dispatchKeyEvent(event)
    }

    init {
        onBackPressedDispatcher.addCallback(this) {
            if (binded) {
                Timber.d("onBackPressed: fragment=${currentFragment?.javaClass?.simpleName}")
                if (currentFragment?.onBackPressed() == false) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    companion object {
        fun navigate(directions: NavDirections, navigation: NavController, cr: ContentResolver) {
            val anim = AccessibilityUtils.isAnimationEnabled(cr)
            Timber.d("navigate: actionId=${directions.actionId} anim=$anim")
            // Skip custom navOptions (which force-no-anim) when animations are disabled
            if (anim) {
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
