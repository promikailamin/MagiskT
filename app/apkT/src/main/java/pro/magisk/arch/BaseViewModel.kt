/**
 * Base class for all ViewModels in the app. Provides shared capabilities such as showing a
 * toast/snackbar and emitting navigation events that are collected by [CollectNavEvents].
 */
package pro.magisk.arch

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.ktx.toast
import pro.magisk.ui.navigation.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

abstract class BaseViewModel : ViewModel() {

    /** One-shot navigation events consumed by the Compose side. */
    private val _navEvents = MutableSharedFlow<Route>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<Route> = _navEvents

    /** Show a short toast. */
    fun showSnackbar(@StringRes resId: Int) {
        AppContext.toast(resId, Toast.LENGTH_SHORT)
    }

    /** Show a short toast with a dynamic string. */
    fun showSnackbar(msg: String) {
        AppContext.toast(msg, Toast.LENGTH_SHORT)
    }

    /** Request navigation to [route]. */
    fun navigateTo(route: Route) {
        _navEvents.tryEmit(route)
    }
}
