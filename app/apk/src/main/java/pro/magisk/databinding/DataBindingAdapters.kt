/**
 * Custom DataBinding adapters used throughout Magisk Manager's layouts.
 *
 * Covers:
 * - Visibility helpers (gone, invisible, goneUnless, invisibleUnless)
 * - Markdown rendering via Markwon
 * - Toolbar, ImageView, Button, and Chip bindings
 * - RecyclerView scrolling (auto-scroll-to-last with AdapterDataObserver)
 * - MD2-specific: margin, stroke, tint, textColour attr resolution
 * - Slider / Spinner / IndeterminateCheckBox two-way binding
 * - Policy slider <-> [SuPolicy] conversion via [InverseMethod]
 */
package pro.magisk.databinding

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import androidx.databinding.InverseMethod
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import pro.magisk.R
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.core.utils.TextHolder
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.widget.IndeterminateCheckBox
import kotlin.math.roundToInt

@BindingAdapter("gone")
fun set_gone(view: View, gone: Boolean) {
    view.isGone = gone
}

@BindingAdapter("invisible")
fun set_invisible(view: View, invisible: Boolean) {
    view.isInvisible = invisible
}

@BindingAdapter("goneUnless")
fun set_gone_unless(view: View, goneUnless: Boolean) {
    set_gone(view, goneUnless.not())
}

@BindingAdapter("invisibleUnless")
fun set_invisible_unless(view: View, invisibleUnless: Boolean) {
    set_invisible(view, invisibleUnless.not())
}

@BindingAdapter("markdownText")
fun set_markdown_text(tv: TextView, markdown: Spanned) {
    ServiceLocator.markwon.setParsedMarkdown(tv, markdown)
}

@BindingAdapter("onNavigationClick")
fun set_on_navigation_clicked_listener(view: Toolbar, listener: View.OnClickListener) {
    view.setNavigationOnClickListener(listener)
}

@BindingAdapter("srcCompat")
fun setImageResource(view: ImageView, @DrawableRes res_id: Int) {
    view.setImageResource(res_id)
}

@BindingAdapter("srcCompat")
fun setImageResource(view: ImageView, drawable: Drawable) {
    view.setImageDrawable(drawable)
}

@BindingAdapter("onTouch")
fun setOnTouchListener(view: View, listener: View.OnTouchListener) {
    view.setOnTouchListener(listener)
}

@BindingAdapter("scroll_to_last")
fun set_scroll_to_last(view: RecyclerView, shouldScrollToLast: Boolean) {

    fun scroll_to_last() = UiThreadHandler.handler.postDelayed({
        view.scrollToPosition(view.adapter?.itemCount?.minus(1) ?: 0)
    }, 30)

    fun wait(callback: () -> Unit) {
        UiThreadHandler.handler.postDelayed(callback, 1000)
    }

    fun RecyclerView.Adapter<*>.setListener() {
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                scroll_to_last()
            }
        }
        registerAdapterDataObserver(observer)
        view.setTag(R.id.recyclerScrollListener, observer)
    }

    fun RecyclerView.Adapter<*>.removeListener() {
        val observer =
            view.getTag(R.id.recyclerScrollListener) as? RecyclerView.AdapterDataObserver ?: return
        unregisterAdapterDataObserver(observer)
    }

    fun try_set_listener(): Unit = view.adapter?.setListener() ?: wait { try_set_listener() }

    if (shouldScrollToLast) {
        try_set_listener()
    } else {
        view.adapter?.removeListener()
    }
}

@BindingAdapter("isEnabled")
fun setEnabled(view: View, isEnabled: Boolean) {
    view.isEnabled = isEnabled
}

@BindingAdapter("error")
fun TextInputLayout.setErrorString(error: String) {
    val new_error = error.let { if (it.isEmpty()) null else it }
    if (this.error == null && new_error == null) return
    this.error = new_error
}

// md2

@BindingAdapter(
    "android:layout_marginLeft",
    "android:layout_marginTop",
    "android:layout_marginRight",
    "android:layout_marginBottom",
    "android:layout_marginStart",
    "android:layout_marginEnd",
    requireAll = false
)
fun View.setMargins(
    marginLeft: Int?,
    marginTop: Int?,
    marginRight: Int?,
    marginBottom: Int?,
    marginStart: Int?,
    marginEnd: Int?
) = updateLayoutParams<ViewGroup.MarginLayoutParams> {
    marginLeft?.let { leftMargin = it }
    marginTop?.let { topMargin = it }
    marginRight?.let { rightMargin = it }
    marginBottom?.let { bottomMargin = it }
    marginStart?.let { this.marginStart = it }
    marginEnd?.let { this.marginEnd = it }
}

@BindingAdapter("nestedScrollingEnabled")
fun RecyclerView.setNestedScrolling(enabled: Boolean) {
    isNestedScrollingEnabled = enabled
}

@BindingAdapter("isSelected")
fun View.isSelected(isSelected: Boolean) {
    this.isSelected = isSelected
}

@BindingAdapter("divider_vertical", "dividerHorizontal", requireAll = false)
fun RecyclerView.setDividers(divider_vertical: Drawable?, dividerHorizontal: Drawable?) {
    if (dividerHorizontal != null) {
        DividerItemDecoration(context, LinearLayoutManager.HORIZONTAL).apply {
            setDrawable(dividerHorizontal)
        }.let { addItemDecoration(it) }
    }
    if (divider_vertical != null) {
        DividerItemDecoration(context, LinearLayoutManager.VERTICAL).apply {
            setDrawable(divider_vertical)
        }.let { addItemDecoration(it) }
    }
}

@BindingAdapter("icon")
fun Button.setIconRes(res: Int) {
    (this as MaterialButton).setIconResource(res)
}

@BindingAdapter("icon")
fun Button.setIcon(drawable: Drawable) {
    (this as MaterialButton).icon = drawable
}

@BindingAdapter("strokeWidth")
fun MaterialCardView.setCardStrokeWidthBound(stroke: Float) {
    strokeWidth = stroke.roundToInt()
}

@BindingAdapter("onMenuClick")
fun Toolbar.setOnMenuClickListener(listener: Toolbar.OnMenuItemClickListener) {
    setOnMenuItemClickListener(listener)
}

@BindingAdapter("onCloseClicked")
fun Chip.setOnCloseClickedListenerBinding(listener: View.OnClickListener) {
    setOnCloseIconClickListener(listener)
}

@BindingAdapter("progressAnimated")
fun ProgressBar.setProgressAnimated(new_progress: Int) {
    val animator = tag as? ValueAnimator
    animator?.cancel()

    ValueAnimator.ofInt(progress, new_progress).apply {
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { progress = it.animatedValue as Int }
        tag = this
    }.start()
}

@BindingAdapter("android:text")
fun TextView.setTextSafe(text: Int) {
    if (text == 0) this.text = null else setText(text)
}

@BindingAdapter("android:onLongClick")
fun View.setOnLongClickListenerBinding(listener: () -> Unit) {
    setOnLongClickListener {
        listener()
        true
    }
}

@BindingAdapter("strikeThrough")
fun TextView.setStrikeThroughEnabled(use_strike_through: Boolean) {
    paintFlags = if (use_strike_through) {
        paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    } else {
        paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }
}

@BindingAdapter("spanCount")
fun RecyclerView.setSpanCount(count: Int) {
    when (val lama = layoutManager) {
        is GridLayoutManager -> lama.spanCount = count
        is StaggeredGridLayoutManager -> lama.spanCount = count
    }
}

@BindingAdapter("state")
fun set_state(view: IndeterminateCheckBox, state: Boolean?) {
    if (view.state != state)
        view.state = state
}

@InverseBindingAdapter(attribute = "state")
fun get_state(view: IndeterminateCheckBox) = view.state

@BindingAdapter("stateAttrChanged")
fun set_listeners(
    view: IndeterminateCheckBox,
    attr_change: InverseBindingListener
) {
    view.setOnStateChangedListener { _, _ ->
        attr_change.onChange()
    }
}

@BindingAdapter("cardBackgroundColorAttr")
fun CardView.setCardBackgroundColorAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    setCardBackgroundColor(tv.data)
}

@BindingAdapter("tint")
fun ImageView.setTint(color: Int) {
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color))
}

@BindingAdapter("tintAttr")
fun ImageView.setTintAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tv.data))
}

@BindingAdapter("textColorAttr")
fun TextView.setTextColorAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    setTextColor(tv.data)
}

@BindingAdapter("android:text")
fun TextView.setText(text: TextHolder) {
    this.text = text.get_text(context.resources)
}

@BindingAdapter("items", "layout")
fun Spinner.setAdapter(items: Array<Any>, layout_res: Int) {
    adapter = ArrayAdapter(context, layout_res, items)
}

@BindingAdapter("labelFormatter")
fun Slider.setLabelFormatter(formatter: (Float) -> Int) {
    setLabelFormatter { value -> resources.getString(formatter(value)) }
}

@InverseBindingAdapter(attribute = "android:value")
fun Slider.getValueBinding() = value

@BindingAdapter("android:valueAttrChanged")
fun Slider.setListener(attr_change: InverseBindingListener) {
    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) = Unit
        override fun onStopTrackingTouch(slider: Slider) = attr_change.onChange()
    })
}

@InverseMethod("slider_value_to_policy")
fun policy_to_slider_value(policy: Int): Float {
    return when (policy) {
        SuPolicy.DENY -> 1f
        SuPolicy.RESTRICT -> 2f
        SuPolicy.ALLOW -> 3f
        else -> 1f
    }
}

fun slider_value_to_policy(value: Float): Int {
    return when (value) {
        1f -> SuPolicy.DENY
        2f -> SuPolicy.RESTRICT
        3f -> SuPolicy.ALLOW
        else -> SuPolicy.DENY
    }
}
