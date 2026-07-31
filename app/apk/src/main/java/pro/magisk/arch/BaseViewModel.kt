/**
 * Base ViewModel for all screens in the app.
 *
 * Provides common infrastructure:
 * - Observable property support via [ObservableHost] for DataBinding
 * - Event publishing via [viewEvents] LiveData (see [ViewEvent])
 * - Permission-request helpers ([withExternalRW], [withInstallPermission], etc.)
 * - Convenience wrappers for navigation, back-press, and dialog display
 * - Save/restore state hooks ([onSaveState] / [onRestoreState])
 */
package pro.magisk.arch

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.databinding.PropertyChangeRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.navigation.NavDirections
import pro.magisk.core.R
import pro.magisk.databinding.ObservableHost
import pro.magisk.events.BackPressEvent
import pro.magisk.events.DialogBuilder
import pro.magisk.events.DialogEvent
import pro.magisk.events.NavigationEvent
import pro.magisk.events.PermissionEvent
import pro.magisk.events.SnackbarEvent

/** Shared base for all app ViewModels. */
abstract class BaseViewModel : ViewModel(), ObservableHost {

    override var callbacks: PropertyChangeRegistry? = null

    private val _viewEvents = MutableLiveData<ViewEvent>()
    val view_events: LiveData<ViewEvent> get() = _viewEvents

    open fun on_save_state(state: Bundle) {}
    open fun on_restore_state(state: Bundle) {}

    fun with_permission(permission: String, callback: (Boolean) -> Unit) {
        PermissionEvent(permission, callback).publish()
    }

    inline fun with_external_r_w(crossinline callback: () -> Unit) {
        with_permission(WRITE_EXTERNAL_STORAGE) {
            if (!it) {
                SnackbarEvent(R.string.external_rw_permission_denied).publish()
            } else {
                callback()
            }
        }
    }

    @SuppressLint("InlinedApi")
    inline fun with_install_permission(crossinline callback: () -> Unit) {
        with_permission(REQUEST_INSTALL_PACKAGES) {
            if (!it) {
                SnackbarEvent(R.string.install_unknown_denied).publish()
            } else {
                callback()
            }
        }
    }

    @SuppressLint("InlinedApi")
    inline fun with_post_notification_permission(crossinline callback: () -> Unit) {
        with_permission(POST_NOTIFICATIONS) {
            if (!it) {
                SnackbarEvent(R.string.post_notifications_denied).publish()
            } else {
                callback()
            }
        }
    }

    fun back() = BackPressEvent().publish()

    fun ViewEvent.publish() {
        _viewEvents.postValue(this)
    }

    fun DialogBuilder.show() {
        DialogEvent(this).publish()
    }

    fun NavDirections.navigate(pop: Boolean = false) {
        _viewEvents.postValue(NavigationEvent(this, pop))
    }

}
