/**
 * Custom Material Design dialog used throughout Magisk Manager.
 *
 * Provides a DataBinding-backed layout with observable properties for title, message,
 * icon, and up to three buttons (positive / neutral / negative with icons).
 * Supports list-item selection ([setListItems]) and arbitrary custom views ([setView]).
 * The standard `setContentView` calls are deprecated in favour of [setView].
 */
package pro.magisk.view

import android.app.Activity
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.Bindable
import androidx.databinding.PropertyChangeRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.UIActivity
import pro.magisk.databinding.DialogMagiskBaseBinding
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.ObservableHost
import pro.magisk.databinding.RvItem
import pro.magisk.databinding.bind_extra
import pro.magisk.databinding.set
import pro.magisk.databinding.setAdapter
import pro.magisk.view.MagiskDialog.DialogClickListener

typealias DialogButtonClickListener = (DialogInterface) -> Unit

/** DataBinding-backed dialog with configurable title, message, icon, and up to three buttons. */
class MagiskDialog(
    context: Activity, theme: Int = 0
) : AppCompatDialog(context, theme) {

    private val binding: DialogMagiskBaseBinding =
        DialogMagiskBaseBinding.inflate(LayoutInflater.from(context))
    private val data = Data()

    val activity: UIActivity<*> get() = ownerActivity as UIActivity<*>

    init {
        binding.setVariable(BR.data, data)
        setCancelable(true)
        setOwnerActivity(context)
    }

    /** Observable data model bound to the dialog layout. */
    inner class Data : ObservableHost {
        override var callbacks: PropertyChangeRegistry? = null

        @get:Bindable
        var icon: Drawable? = null
            set(value) = set(value, field, { field = it }, BR.icon)

        @get:Bindable
        var title: CharSequence = ""
            set(value) = set(value, field, { field = it }, BR.title)

        @get:Bindable
        var message: CharSequence = ""
            set(value) = set(value, field, { field = it }, BR.message)

        val button_positive = ButtonViewModel()
        val button_neutral = ButtonViewModel()
        val button_negative = ButtonViewModel()
    }

    enum class ButtonType {
        POSITIVE, NEUTRAL, NEGATIVE
    }

    interface Button {
        var icon: Int
        var text: Any
        var isEnabled: Boolean
        var do_not_dismiss: Boolean

        fun onClick(listener: DialogButtonClickListener)
    }

    /** Observable button view-model with DataBinding properties. */
    inner class ButtonViewModel : Button, ObservableHost {
        override var callbacks: PropertyChangeRegistry? = null

        @get:Bindable
        override var icon = 0
            set(value) = set(value, field, { field = it }, BR.icon, BR.gone)

        @get:Bindable
        var message: String = ""
            set(value) = set(value, field, { field = it }, BR.message, BR.gone)

        override var text: Any
            get() = message
            set(value) {
                message = when (value) {
                    is Int -> context.get_text(value)
                    else -> value
                }.toString()
            }

        @get:Bindable
        val gone get() = icon == 0 && message.isEmpty()

        @get:Bindable
        override var isEnabled = true
            set(value) = set(value, field, { field = it }, BR.enabled)

        override var do_not_dismiss = false

        private var on_click_action: DialogButtonClickListener = {}

        override fun onClick(listener: DialogButtonClickListener) {
            on_click_action = listener
        }

        fun clicked() {
            on_click_action(this@MagiskDialog)
            if (!do_not_dismiss) {
                dismiss()
            }
        }
    }

    override fun onCreate(saved_instance_state: Bundle?) {
        super.onCreate(saved_instance_state)
        super.setContentView(binding.root)

        val default = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, javaClass.canonicalName)
        val surface_color = MaterialColors.getColor(context, R.attr.colorSurfaceSurfaceVariant, default)
        val material_shape_drawable = MaterialShapeDrawable(context, null, androidx.appcompat.R.attr.alertDialogStyle, com.google.android.material.R.style.MaterialAlertDialog_MaterialComponents)
        material_shape_drawable.initializeElevationOverlay(context)
        material_shape_drawable.fillColor = ColorStateList.valueOf(surface_color)
        material_shape_drawable.elevation = context.resources.getDimension(R.dimen.margin_generic)
        material_shape_drawable.setCornerSize(context.resources.getDimension(R.dimen.l_50))

        val inset = context.resources.getDimensionPixelSize(com.google.android.material.R.dimen.appcompat_dialog_background_inset)
        window?.apply {
            setBackgroundDrawable(InsetDrawable(material_shape_drawable, inset, inset, inset, inset))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun setTitle(@StringRes titleId: Int) { data.title = context.getString(titleId) }

    override fun setTitle(title: CharSequence?) { data.title = title ?: "" }

    fun setMessage(@StringRes msgId: Int, vararg args: Any) {
        data.message = context.getString(msgId, *args)
    }

    fun setMessage(message: CharSequence) { data.message = message }

    fun setIcon(@DrawableRes drawableRes: Int) {
        data.icon = AppCompatResources.getDrawable(context, drawableRes)
    }

    fun setIcon(drawable: Drawable) { data.icon = drawable }

    fun setButton(button_type: ButtonType, builder: Button.() -> Unit) {
        val button = when (button_type) {
            ButtonType.POSITIVE -> data.button_positive
            ButtonType.NEUTRAL -> data.button_neutral
            ButtonType.NEGATIVE -> data.button_negative
        }
        button.apply(builder)
    }

    /** A single list item in a dialog selection list. */
    class DialogItem(
        override val item: CharSequence,
        val position: Int
    ) : RvItem(), DiffItem<DialogItem>, ItemWrapper<CharSequence> {
        override val layout_res = R.layout.item_list_single_line
    }

    fun interface DialogClickListener {
        fun onClick(position: Int)
    }

    /** Replaces the dialog body with a RecyclerView of selectable items. */
    fun set_list_items(
        list: Array<out CharSequence>,
        listener: DialogClickListener
    ) = setView(
        RecyclerView(context).also {
            it.isNestedScrollingEnabled = false
            it.layoutManager = LinearLayoutManager(context)

            val items = list.mapIndexed { i, cs -> DialogItem(cs, i) }
            val extra_bindings = bind_extra { sa ->
                sa.put(BR.listener, DialogClickListener { pos ->
                    listener.onClick(pos)
                    dismiss()
                })
            }
            it.setAdapter(items, extra_bindings)
        }
    )

    fun setView(view: View) {
        binding.dialogBaseContainer.removeAllViews()
        binding.dialogBaseContainer.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Clears all button configurations (title, icon, listeners). */
    fun reset_buttons() {
        ButtonType.values().forEach {
            setButton(it) {
                text = ""
                icon = 0
                isEnabled = true
                do_not_dismiss = false
                onClick {}
            }
        }
    }

    @Deprecated("Please use setView(view)", level = DeprecationLevel.ERROR)
    override fun setContentView(layoutResID: Int) {}
    @Deprecated("Please use setView(view)", level = DeprecationLevel.ERROR)
    override fun setContentView(view: View) {}
    @Deprecated("Please use setView(view)", level = DeprecationLevel.ERROR)
    override fun setContentView(view: View, params: ViewGroup.LayoutParams?) {}
}
