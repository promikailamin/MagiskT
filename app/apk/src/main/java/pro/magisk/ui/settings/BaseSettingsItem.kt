/**
 * Type hierarchy for settings list items.
 *
 * Supports:
 * - [Value]: items that hold a typed value
 *   - [Toggle]: boolean on/off switch
 *   - [Input]: text input via dialog
 *   - [Selector]: single-choice from a list
 * - [Blank]: a clickable action item with no value binding
 * - [Section]: a section header
 *
 * The [Handler] interface lets the hosting ViewModel intercept presses (e.g. for auth).
 */
package pro.magisk.ui.settings

import android.content.Context
import android.content.res.Resources
import android.view.View
import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.ktx.activity
import pro.magisk.core.utils.TextHolder
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.databinding.set
import pro.magisk.view.MagiskDialog

/** Corner treatment of a settings card within its group. */
enum class CardGroupStyle { SINGLE, FIRST, MIDDLE, LAST }

/** Base sealed class for all settings list item types. */
sealed class BaseSettingsItem : ObservableRvItem() {

    interface Handler {
        fun onItemPressed(view: View, item: BaseSettingsItem, andThen: () -> Unit)
        fun onItemAction(view: View, item: BaseSettingsItem)
    }

    override val layoutRes get() = R.layout.item_settings

    open val icon: Int get() = 0
    open val title: TextHolder get() = TextHolder.EMPTY
    @get:Bindable
    open val description: TextHolder get() = TextHolder.EMPTY
    @get:Bindable
    var isEnabled = true
        set(value) = set(value, field, { field = it }, BR.enabled, BR.description)

    /**
     * Whether the values this item binds have been resolved yet.
     *
     * Items that have to wait on a slow source (the shell-backed settings DB, the
     * locale config, a system setting query) start out unloaded so the list can be
     * shown right away, and flip this once their values arrive.
     */
    @get:Bindable
    var loaded = true
        protected set(value) = set(value, field, { field = it }, BR.loaded)

    /**
     * Whether a freshly resolved value is waiting to be published to the bound views.
     *
     * Set on a background thread when a value actually changes, cleared by
     * [onValueLoaded] on the main thread. Lets the host skip the main-thread hop
     * entirely for items whose refresh found nothing new.
     */
    @Volatile
    var pendingNotify = true
        protected set

    /** Corner treatment applied by the host list when grouping items into cards. */
    var groupStyle = CardGroupStyle.SINGLE

    open fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            handler.onItemAction(view, this)
        }
    }

    /**
     * Resolve the values this item binds. Always called on a background thread, so
     * implementations are free to block; called once per item.
     */
    open fun loadValue() {}

    /**
     * Re-read the values this item binds. Called on a background thread whenever the
     * screen is resumed, since the backing state may have changed meanwhile.
     */
    open fun refresh() {}

    /**
     * Called on the main thread once [loadValue] or [refresh] is done, so the bound
     * views can pick up the values that were read in the background. Does nothing
     * when [pendingNotify] is clear, so no-op refreshes never rebind the list.
     */
    open fun onValueLoaded() {
        val notify = pendingNotify
        pendingNotify = false
        loaded = true
        if (notify) {
            notifyPropertyChanged(BR.checked)
            notifyPropertyChanged(BR.description)
        }
    }

    open val showSwitch get() = false
    @get:Bindable
    open val isChecked get() = false
    fun onToggle(view: View, handler: Handler, checked: Boolean) =
        set(checked, isChecked, { onPressed(view, handler) })

    /** Base for items that hold a typed [value]. */
    @Suppress("UNCHECKED_CAST")
    abstract class Value<T> : BaseSettingsItem() {

        private object Unresolved

        private var resolved: Any? = Unresolved

        init {
            // The value is unknown until it is loaded, keep the item inert until then
            loaded = false
            isEnabled = false
        }

        /**
         * Reads the value from its backing store. Called on a background thread by
         * [loadValue], or synchronously in the rare case that the value is read
         * before the background load got to it.
         */
        protected abstract fun readValue(): T

        /** Writes [value] to the backing store. */
        protected abstract fun writeValue(value: T)

        /** Shown until [readValue] returns, so that binding the item never blocks. */
        protected open val placeholder: T? = null

        var value: T
            get() = if (resolved === Unresolved) placeholder ?: readValue() else resolved as T
            set(value) {
                resolved = value
                writeValue(value)
            }

        override fun loadValue() {
            val new = readValue()
            if (resolved != new) pendingNotify = true
            resolved = new
        }

        override fun onValueLoaded() {
            isEnabled = true
            super.onValueLoaded()
        }

        /** Update the cached value without writing it back to the backing store. */
        protected fun setResolved(value: T) {
            if (resolved != value) pendingNotify = true
            resolved = value
        }
    }

    /** Boolean toggle with a switch widget. */
    abstract class Toggle : Value<Boolean>() {

        override val showSwitch get() = true
        override val isChecked get() = value

        override fun onPressed(view: View, handler: Handler) {
            notifyPropertyChanged(BR.checked)
            handler.onItemPressed(view, this) {
                value = !value
                notifyPropertyChanged(BR.checked)
                handler.onItemAction(view, this)
            }
        }
    }

    /** Text input item that shows a dialog with a custom view. */
    abstract class Input : Value<String>() {

        @get:Bindable
        abstract val inputResult: String?

        override fun onPressed(view: View, handler: Handler) {
            handler.onItemPressed(view, this) {
                MagiskDialog(view.activity).apply {
                    setTitle(title.getText(view.resources))
                    setView(getView(view.context))
                    setButton(MagiskDialog.ButtonType.POSITIVE) {
                        text = android.R.string.ok
                        onClick {
                            inputResult?.let { result ->
                                doNotDismiss = false
                                value = result
                                handler.onItemAction(view, this@Input)
                                return@onClick
                            }
                            doNotDismiss = true
                        }
                    }
                    setButton(MagiskDialog.ButtonType.NEGATIVE) {
                        text = android.R.string.cancel
                    }
                }.show()
            }
        }

        abstract fun getView(context: Context): View
    }

    /** Single-select item that shows a list dialog. */
    abstract class Selector : Value<Int>() {

        open val entryRes get() = -1
        open val descriptionRes get() = entryRes
        open fun entries(res: Resources) = res.getArrayOrEmpty(entryRes)
        open fun descriptions(res: Resources) = res.getArrayOrEmpty(descriptionRes)

        override val description = object : TextHolder() {
            override fun getText(resources: Resources): String {
                // The entries may be slow to resolve, so hold off until they are in
                if (!loaded) return ""
                return descriptions(resources).getOrElse(value) { "" }
            }
        }

        private fun Resources.getArrayOrEmpty(id: Int): Array<String> =
            runCatching { getStringArray(id) }.getOrDefault(emptyArray())

        override fun onPressed(view: View, handler: Handler) {
            handler.onItemPressed(view, this) {
                MagiskDialog(view.activity).apply {
                    setTitle(title.getText(view.resources))
                    setButton(MagiskDialog.ButtonType.NEGATIVE) {
                        text = android.R.string.cancel
                    }
                    setListItems(entries(view.resources)) {
                        if (value != it) {
                            value = it
                            notifyPropertyChanged(BR.description)
                            handler.onItemAction(view, this@Selector)
                        }
                    }
                }.show()
            }
        }
    }

    /** Clickable action item with no value (e.g. Theme, Language, Systemless Hosts). */
    abstract class Blank : BaseSettingsItem()

    /** Section header in the settings list. */
    abstract class Section : BaseSettingsItem() {
        override val layoutRes = R.layout.item_settings_section
    }
}
