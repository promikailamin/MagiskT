/**
 * Diff-aware observable list implementations for RecyclerView.
 *
 * - [DiffObservableList] / [diffList]: an observable list that computes and dispatches
 *   [DiffUtil] diffs when updated.
 * - [FilterableDiffObservableList] / [filterList]: extends diff support with
 *   coroutine-based background filtering.
 *
 * Both implement [ObservableList] so the [RvItemAdapter] automatically receives
 * change notifications.
 */
package pro.magisk.databinding

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.databinding.ListChangeRegistry
import androidx.databinding.ObservableList
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.AbstractList

/** A [List] that supports DiffUtil-based efficient updates. */
interface DiffList<T : DiffItem<*>> : List<T> {
    fun calculate_diff(new_items: List<T>): DiffUtil.DiffResult

    @MainThread
    fun update(new_items: List<T>, diff_result: DiffUtil.DiffResult)

    @WorkerThread
    suspend fun update(new_items: List<T>)
}

/** A [List] that supports background filtering with diff animations. */
interface FilterList<T : DiffItem<*>> : List<T> {
    fun filter(filter: (T) -> Boolean)

    @MainThread
    fun set(new_items: List<T>)
}

fun <T : DiffItem<*>> diffList(): DiffList<T> = DiffObservableList()

fun <T : DiffItem<*>> filterList(scope: CoroutineScope): FilterList<T> =
    FilterableDiffObservableList(scope)

/** Observable list backed by DiffUtil calculations. */
private open class DiffObservableList<T : DiffItem<*>>
    : AbstractList<T>(), ObservableList<T>, DiffList<T>, ListUpdateCallback {

    protected var list: List<T> = emptyList()
    private val listeners = ListChangeRegistry()

    override val size: Int get() = list.size

    override fun get(index: Int) = list[index]

    override fun calculate_diff(new_items: List<T>): DiffUtil.DiffResult {
        return do_calculate_diff(list, new_items)
    }

    protected fun do_calculate_diff(old_items: List<T>, new_items: List<T>): DiffUtil.DiffResult {
        return DiffUtil.calculate_diff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old_items.size
            override fun getNewListSize() = new_items.size

            @Suppress("UNCHECKED_CAST")
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old_item = old_items[oldItemPosition]
                val new_item = new_items[newItemPosition]
                return (old_item as DiffItem<Any>).item_same_as(new_item)
            }

            @Suppress("UNCHECKED_CAST")
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old_item = old_items[oldItemPosition]
                val new_item = new_items[newItemPosition]
                return (old_item as DiffItem<Any>).content_same_as(new_item)
            }
        }, true)
    }

    @MainThread
    override fun update(new_items: List<T>, diff_result: DiffUtil.DiffResult) {
        list = ArrayList(new_items)
        diff_result.dispatchUpdatesTo(this)
    }

    @WorkerThread
    override suspend fun update(new_items: List<T>) {
        val diff_result = calculate_diff(new_items)
        withContext(Dispatchers.Main) {
            update(new_items, diff_result)
        }
    }

    override fun addOnListChangedCallback(listener: ObservableList.OnListChangedCallback<out ObservableList<T>>) {
        listeners.add(listener)
    }

    override fun removeOnListChangedCallback(listener: ObservableList.OnListChangedCallback<out ObservableList<T>>) {
        listeners.remove(listener)
    }

    override fun onChanged(position: Int, count: Int, payload: Any?) {
        listeners.notifyChanged(this, position, count)
    }

    override fun onMoved(from_position: Int, toPosition: Int) {
        listeners.notifyMoved(this, from_position, toPosition, 1)
    }

    override fun onInserted(position: Int, count: Int) {
        modCount += 1
        listeners.notifyInserted(this, position, count)
    }

    override fun onRemoved(position: Int, count: Int) {
        modCount += 1
        listeners.notifyRemoved(this, position, count)
    }
}

/** [DiffObservableList] that supports live filtering on a background coroutine. */
private class FilterableDiffObservableList<T : DiffItem<*>>(
    private val scope: CoroutineScope
) : DiffObservableList<T>(), FilterList<T> {

    private var sublist: List<T> = emptyList()
    private var job: Job? = null
    private var last_filter: ((T) -> Boolean)? = null

    override fun filter(filter: (T) -> Boolean) {
        last_filter = filter
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val old_list = sublist
            val new_list = list.filter(filter)
            val diff = do_calculate_diff(old_list, new_list)
            withContext(Dispatchers.Main) {
                sublist = new_list
                diff.dispatchUpdatesTo(this@FilterableDiffObservableList)
            }
        }
    }

    override fun get(index: Int): T {
        return sublist[index]
    }

    override val size: Int
        get() = sublist.size

    @MainThread
    override fun set(new_items: List<T>) {
        onRemoved(0, sublist.size)
        list = new_items
        sublist = emptyList()
        last_filter?.let { filter(it) }
    }
}
