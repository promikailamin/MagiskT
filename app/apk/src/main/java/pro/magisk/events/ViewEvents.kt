/**
 * Concrete [ViewEvent] subclasses used throughout the app.
 *
 * Each event implements an executor interface ([ActivityExecutor], [ContextExecutor]) so the
 * receiving Activity/Fragment can dispatch it to the right scope without boilerplate.
 */
package pro.magisk.events

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.navigation.NavDirections
import com.google.android.material.snackbar.Snackbar
import pro.magisk.arch.ActivityExecutor
import pro.magisk.arch.ContextExecutor
import pro.magisk.arch.NavigationActivity
import pro.magisk.arch.UIActivity
import pro.magisk.arch.ViewEvent
import pro.magisk.core.base.ContentResultCallback
import pro.magisk.core.base.relaunch
import pro.magisk.core.utils.TextHolder
import pro.magisk.core.utils.asText
import pro.magisk.view.MagiskDialog
import pro.magisk.view.Shortcuts

/** Requests a runtime permission from the user. */
class PermissionEvent(
    private val permission: String,
    private val callback: (Boolean) -> Unit
) : ViewEvent(), ActivityExecutor {

    override fun invoke(activity: UIActivity<*>) =
        activity.withPermission(permission, callback)
}

/** Triggers the system back button. */
class BackPressEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        activity.onBackPressedDispatcher.onBackPressed()
    }
}

/** Finishes the current activity. */
class DieEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        activity.finish()
    }
}

/** Inflates the content view and optionally sets an accessibility delegate. */
class ShowUIEvent(private val delegate: View.AccessibilityDelegate?)
    : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        activity.setContentView()
        activity.setAccessibilityDelegate(delegate)
    }
}

/** Triggers a full activity recreate (e.g. after theme change). */
class RecreateEvent : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        activity.relaunch()
    }
}

/** Requests biometric / device-auth before proceeding. */
class AuthEvent(
    private val callback: () -> Unit
) : ViewEvent(), ActivityExecutor {

    override fun invoke(activity: UIActivity<*>) {
        activity.withAuthentication { if (it) callback() }
    }
}

/** Opens the system file-picker (ActivityResultContract-based). */
class GetContentEvent(
    private val type: String,
    private val callback: ContentResultCallback
) : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        activity.getContent(type, callback)
    }
}

/** Navigates to a new destination, optionally popping the back-stack first. */
class NavigationEvent(
    private val directions: NavDirections,
    private val pop: Boolean
) : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        (activity as? NavigationActivity<*>)?.apply {
            if (pop) navigation.popBackStack()
            directions.navigate()
        }
    }
}

/** Requests a home-screen shortcut to be added. */
class AddHomeIconEvent : ViewEvent(), ContextExecutor {
    override fun invoke(context: Context) {
        Shortcuts.addHomeIcon(context)
    }
}

/** Displays a Snackbar with the given message, length, and optional customisation. */
class SnackbarEvent(
    private val msg: TextHolder,
    private val length: Int = Snackbar.LENGTH_SHORT,
    private val builder: Snackbar.() -> Unit = {}
) : ViewEvent(), ActivityExecutor {

    constructor(
        @StringRes res: Int,
        length: Int = Snackbar.LENGTH_SHORT,
        builder: Snackbar.() -> Unit = {}
    ) : this(res.asText(), length, builder)

    constructor(
        msg: String,
        length: Int = Snackbar.LENGTH_SHORT,
        builder: Snackbar.() -> Unit = {}
    ) : this(msg.asText(), length, builder)

    override fun invoke(activity: UIActivity<*>) {
        activity.showSnackbar(msg.getText(activity.resources), length, builder)
    }
}

/** Shows a [MagiskDialog] built from the provided [DialogBuilder]. */
class DialogEvent(
    private val builder: DialogBuilder
) : ViewEvent(), ActivityExecutor {
    override fun invoke(activity: UIActivity<*>) {
        MagiskDialog(activity).apply(builder::build).show()
    }
}

/** Implemented by objects that configure a [MagiskDialog] declaratively. */
interface DialogBuilder {
    fun build(dialog: MagiskDialog)
}
