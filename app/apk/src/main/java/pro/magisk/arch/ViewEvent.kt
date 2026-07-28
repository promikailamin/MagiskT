/**
 * Event-passing infrastructure for ViewModel-to-UI communication.
 *
 * ViewModels publish [ViewEvent] subclasses through LiveData; Activities and Fragments
 * consume them via [pro.magisk.arch.ViewModelHolder.onEventDispatched]. This avoids the
 * "event-observed-on-config-change" problem inherent in plain LiveData events.
 *
 * Executor interfaces ([ContextExecutor], [ActivityExecutor], [FragmentExecutor]) allow
 * an event to scope itself to the correct lifecycle owner at dispatch time.
 */
package pro.magisk.arch

import android.content.Context

/**
 * Base class for all events dispatched from ViewModels to the UI layer.
 * Subclasses implement one or more executor interfaces to control where they run.
 */
abstract class ViewEvent

/** Event that can execute itself with a [Context]. */
interface ContextExecutor {
    operator fun invoke(context: Context)
}

/** Event that can execute itself with a [UIActivity]. */
interface ActivityExecutor {
    operator fun invoke(activity: UIActivity<*>)
}

/** Event that can execute itself with a [BaseFragment]. */
interface FragmentExecutor {
    operator fun invoke(fragment: BaseFragment<*>)
}
