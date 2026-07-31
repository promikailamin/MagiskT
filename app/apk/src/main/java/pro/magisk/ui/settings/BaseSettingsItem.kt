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

/** Base sealed class for all settings list item types. */
sealed class BaseSettingsItem : ObservableRvItem() {

    interface Handler {
        fun on_item_pressed(view: View, item: BaseSettingsItem, andThen: () -> Unit)
        fun on_item_action(view: View, item: BaseSettingsItem)
    }

    override val layout_res get() = R.layout.item_settings

    open val icon: Int get() = 0
    open val title: TextHolder get() = TextHolder.EMPTY
    @get:Bindable
    open val description: TextHolder get() = TextHolder.EMPTY
    @get:Bindable
    var isEnabled = true
        set(value) = set(value, field, { field = it }, BR.enabled, BR.description)

    open fun on_pressed(view: View, handler: Handler) {
        handler.on_item_pressed(view, this) {
            handler.on_item_action(view, this)
        }
    }
    open fun refresh() {}

    open val show_switch get() = false
    @get:Bindable
    open val isChecked get() = false
    fun on_toggle(view: View, handler: Handler, checked: Boolean) =
        set(checked, isChecked, { on_pressed(view, handler) })

    /** Base for items that hold a typed [value]. */
    abstract class Value<T> : BaseSettingsItem() {
        abstract var value: T
            protected set
    }

    /** Boolean toggle with a switch widget. */
    abstract class Toggle : Value<Boolean>() {

        override val show_switch get() = true
        override val isChecked get() = value

        override fun on_pressed(view: View, handler: Handler) {
            notifyPropertyChanged(BR.checked)
            handler.on_item_pressed(view, this) {
                value = !value
                notifyPropertyChanged(BR.checked)
                handler.on_item_action(view, this)
            }
        }
    }

    /** Text input item that shows a dialog with a custom view. */
    abstract class Input : Value<String>() {

        @get:Bindable
        abstract val input_result: String?

        override fun on_pressed(view: View, handler: Handler) {
            handler.on_item_pressed(view, this) {
                MagiskDialog(view.activity).apply {
                    setTitle(title.get_text(view.resources))
                    setView(get_view(view.context))
                    setButton(MagiskDialog.ButtonType.POSITIVE) {
                        text = android.R.string.ok
                        onClick {
                            input_result?.let { result ->
                                do_not_dismiss = false
                                value = result
                                handler.on_item_action(view, this@Input)
                                return@onClick
                            }
                            do_not_dismiss = true
                        }
                    }
                    setButton(MagiskDialog.ButtonType.NEGATIVE) {
                        text = android.R.string.cancel
                    }
                }.show()
            }
        }

        abstract fun get_view(context: Context): View
    }

    /** Single-select item that shows a list dialog. */
    abstract class Selector : Value<Int>() {

        open val entry_res get() = -1
        open val description_res get() = entry_res
        open fun entries(res: Resources) = res.getArrayOrEmpty(entry_res)
        open fun descriptions(res: Resources) = res.getArrayOrEmpty(description_res)

        override val description = object : TextHolder() {
            override fun get_text(resources: Resources): String {
                return descriptions(resources).getOrElse(value) { "" }
            }
        }

        private fun Resources.getArrayOrEmpty(id: Int): Array<String> =
            runCatching { getStringArray(id) }.getOrDefault(emptyArray())

        override fun on_pressed(view: View, handler: Handler) {
            handler.on_item_pressed(view, this) {
                MagiskDialog(view.activity).apply {
                    setTitle(title.get_text(view.resources))
                    setButton(MagiskDialog.ButtonType.NEGATIVE) {
                        text = android.R.string.cancel
                    }
                    set_list_items(entries(view.resources)) {
                        if (value != it) {
                            value = it
                            notifyPropertyChanged(BR.description)
                            handler.on_item_action(view, this@Selector)
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
        override val layout_res = R.layout.item_settings_section
    }
}
