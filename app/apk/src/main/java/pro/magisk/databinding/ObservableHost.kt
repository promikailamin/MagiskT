/**
 * Observable interface for DataBinding with thread-safe listener management.
 *
 * Adapted from Teanity's `Notifyable` interface. Provides [notifyChange] and
 * [notifyPropertyChanged] helpers that are safe to call from any thread.
 *
 * Also provides convenience `set()` inline functions that combine the null-safety,
 * change-detection, and notification-boilerplate for `@Bindable` properties.
 *
 * @see [androidx.databinding.Observable]
 */
package pro.magisk.databinding

import androidx.databinding.Observable
import androidx.databinding.PropertyChangeRegistry

/**
 * Interface that allows user to be observed via DataBinding or manually by assigning listeners.
 */
interface ObservableHost : Observable {

    var callbacks: PropertyChangeRegistry?

    /**
     * Notifies all observers that something has changed. By default implementation this method is
     * synchronous, hence observers will never be notified in undefined order. Observers might
     * choose to refresh the view completely, which is beyond the scope of this function.
     */
    fun notifyChange() {
        synchronized(this) {
            callbacks ?: return
        }.notifyCallbacks(this, 0, null)
    }

    /**
     * Notifies all observers about field with [fieldId] has been changed. This will happen
     * synchronously before or after [notifyChange] has been called. It will never be called during
     * the execution of aforementioned method.
     */
    fun notifyPropertyChanged(field_id: Int) {
        synchronized(this) {
            callbacks ?: return
        }.notifyCallbacks(this, field_id, null)
    }

    override fun addOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        synchronized(this) {
            callbacks ?: PropertyChangeRegistry().also { callbacks = it }
        }.add(callback)
    }

    override fun removeOnPropertyChangedCallback(callback: Observable.OnPropertyChangedCallback) {
        synchronized(this) {
            callbacks ?: return
        }.remove(callback)
    }
}

/** Registers a one-shot or persistent callback for a specific [fieldId] change. */
fun ObservableHost.addOnPropertyChangedCallback(
    field_id: Int,
    removeAfterChanged: Boolean = false,
    callback: () -> Unit
) {
    addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (field_id == propertyId) {
                callback()
                if (removeAfterChanged)
                    removeOnPropertyChangedCallback(this)
            }
        }
    })
}

/**
 * Injects boilerplate implementation for `@Bindable` field setters.
 *
 * Usage:
 * ```kotlin
 * @get:Bindable
 * var myField = defaultValue
 *     set(value) = set(value, field, { field = it }, BR.myField) {
 *         doSomething(it)
 *     }
 * ```
 */
inline fun <reified T> ObservableHost.set(
    new: T, old: T, setter: (T) -> Unit, field_id: Int, afterChanged: (T) -> Unit = {}) {
    if (old != new) {
        setter(new)
        notifyPropertyChanged(field_id)
        afterChanged(new)
    }
}

/** Like [set] but notifies multiple field IDs. */
inline fun <reified T> ObservableHost.set(
    new: T, old: T, setter: (T) -> Unit, vararg fieldIds: Int, afterChanged: (T) -> Unit = {}) {
    if (old != new) {
        setter(new)
        fieldIds.forEach { notifyPropertyChanged(it) }
        afterChanged(new)
    }
}
