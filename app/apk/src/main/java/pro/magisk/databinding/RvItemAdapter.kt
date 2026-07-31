/**
 * Generic RecyclerView adapter for DataBinding-based item rendering.
 *
 * Supports:
 * - Variable-layout items via [RvItem.layoutRes] for view-type
 * - Extra bindings (e.g. ViewModel, click handlers) via a [SparseArray]
 * - Automatic observation of [ObservableList] for live updates
 * - LifecycleOwner resolution from the RecyclerView tree
 *
 * A [BindingAdapter] extension on [RecyclerView] wires the adapter declaratively in XML.
 */
package pro.magisk.databinding

import android.annotation.SuppressLint
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.BindingAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableList
import androidx.databinding.ObservableList.OnListChangedCallback
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import pro.magisk.BR

/** Adapter that renders [RvItem] subclasses via DataBinding. */
class RvItemAdapter<T: RvItem>(
    val items: List<T>,
    val extra_bindings: SparseArray<*>?
) : RecyclerView.Adapter<RvItemAdapter.ViewHolder>() {

    private var lifecycleOwner: LifecycleOwner? = null
    private var recycler_view: RecyclerView? = null
    private val observer by lazy(LazyThreadSafetyMode.NONE) { ListObserver<T>() }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        lifecycleOwner = rv.findViewTreeLifecycleOwner()
        recycler_view = rv
        if (items is ObservableList)
            items.addOnListChangedCallback(observer)
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        lifecycleOwner = null
        recycler_view = null
        if (items is ObservableList)
            items.removeOnListChangedCallback(observer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, layout_res: Int): ViewHolder {
        val inflator = LayoutInflater.from(parent.context)
        return ViewHolder(DataBindingUtil.inflate(inflator, layout_res, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.setVariable(BR.item, item)
        extra_bindings?.let {
            for (i in 0 until it.size()) {
                holder.binding.setVariable(it.keyAt(i), it.valueAt(i))
            }
        }
        holder.binding.lifecycleOwner = lifecycleOwner
        holder.binding.executePendingBindings()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = items[position].layout_res

    class ViewHolder(val binding: ViewDataBinding) : RecyclerView.ViewHolder(binding.root)

    /** Observes [ObservableList] changes and dispatches appropriate notify* calls. */
    inner class ListObserver<T: RvItem> : OnListChangedCallback<ObservableList<T>>() {

        @SuppressLint("NotifyDataSetChanged")
        override fun onChanged(sender: ObservableList<T>) {
            notifyDataSetChanged()
        }

        override fun onItemRangeChanged(
            sender: ObservableList<T>,
            positionStart: Int,
            itemCount: Int
        ) {
            notifyItemRangeChanged(positionStart, itemCount)
        }

        override fun onItemRangeInserted(
            sender: ObservableList<T>?,
            positionStart: Int,
            itemCount: Int
        ) {
            notifyItemRangeInserted(positionStart, itemCount)
        }

        override fun onItemRangeMoved(
            sender: ObservableList<T>?,
            from_position: Int,
            toPosition: Int,
            itemCount: Int
        ) {
            for (i in 0 until itemCount) {
                notifyItemMoved(from_position + i, toPosition + i)
            }
        }

        override fun onItemRangeRemoved(
            sender: ObservableList<T>?,
            positionStart: Int,
            itemCount: Int
        ) {
            notifyItemRangeRemoved(positionStart, itemCount)
        }
    }
}

/** Helper to build a [SparseArray] of extra DataBinding variables. */
inline fun bind_extra(body: (SparseArray<Any?>) -> Unit) = SparseArray<Any?>().also(body)

/** DataBinding adapter: sets a [RvItemAdapter] on a RecyclerView. */
@BindingAdapter("items", "extra_bindings", requireAll = false)
fun <T: RvItem> RecyclerView.setAdapter(items: List<T>?, extra_bindings: SparseArray<*>?) {
    if (items != null) {
        val rva = (adapter as? RvItemAdapter<*>)
        if (rva == null || rva.items !== items || rva.extra_bindings !== extra_bindings) {
            adapter = RvItemAdapter(items, extra_bindings)
        }
    }
}
