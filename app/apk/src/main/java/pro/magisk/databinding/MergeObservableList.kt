/**
 * An [ObservableList] that merges multiple sub-lists (plain or observable) into one
 * flat view. Changes to any observable sub-list are automatically forwarded.
 *
 * Used by the module and Superuser screens to combine a static header/helper item
 * with a dynamic diff-based list.
 */
package pro.magisk.databinding

import androidx.databinding.ListChangeRegistry
import androidx.databinding.ObservableList
import androidx.databinding.ObservableList.OnListChangedCallback
import java.util.AbstractList

@Suppress("UNCHECKED_CAST")
class MergeObservableList<T> : AbstractList<T>(), ObservableList<T> {

    private val lists: MutableList<List<T>> = mutableListOf()
    private val listeners = ListChangeRegistry()
    private val callback = Callback<T>()

    override fun addOnListChangedCallback(callback: OnListChangedCallback<out ObservableList<T>>) {
        listeners.add(callback)
    }

    override fun removeOnListChangedCallback(callback: OnListChangedCallback<out ObservableList<T>>) {
        listeners.remove(callback)
    }

    override fun get(index: Int): T {
        if (index < 0)
            throw IndexOutOfBoundsException()
        var idx = index
        for (list in lists) {
            val size = list.size
            if (idx < size) {
                return list[idx]
            }
            idx -= size
        }
        throw IndexOutOfBoundsException()
    }

    override val size: Int
        get() = lists.fold(0) { i, it -> i + it.size }

    fun insert_item(obj: T): MergeObservableList<T> {
        val idx = size
        lists.add(listOf(obj))
        ++modCount
        listeners.notifyInserted(this, idx, 1)
        return this
    }

    fun insert_list(list: List<T>): MergeObservableList<T> {
        val idx = size
        lists.add(list)
        ++modCount
        (list as? ObservableList<T>)?.addOnListChangedCallback(callback)
        if (list.isNotEmpty())
            listeners.notifyInserted(this, idx, list.size)
        return this
    }

    fun remove_item(obj: T): Boolean {
        var idx = 0
        for ((i, list) in lists.withIndex()) {
            if (list !is ObservableList<*>) {
                if (obj == list[0]) {
                    lists.removeAt(i)
                    ++modCount
                    listeners.notifyRemoved(this, idx, 1)
                    return true
                }
            }
            idx += list.size
        }
        return false
    }

    fun remove_list(list_to_remove: List<T>): Boolean {
        var idx = 0
        for ((i, list) in lists.withIndex()) {
            if (list_to_remove === list) {
                (list as? ObservableList<T>)?.removeOnListChangedCallback(callback)
                lists.removeAt(i)
                ++modCount
                listeners.notifyRemoved(this, idx, list.size)
                return true
            }
            idx += list.size
        }
        return false
    }

    override fun clear() {
        val sz = size
        for (list in lists) {
            if (list is ObservableList) {
                list.removeOnListChangedCallback(callback)
            }
        }
        ++modCount
        lists.clear()
        if (sz > 0)
            listeners.notifyRemoved(this, 0, sz)
    }

    /** Translates a position within a sub-list to the flat merged index. */
    private fun sub_index_to_index(sub_list: List<*>, index: Int): Int {
        if (index < 0)
            throw IndexOutOfBoundsException()
        var idx = 0
        for (list in lists) {
            if (sub_list === list) {
                return idx + index
            }
            idx += list.size
        }
        throw IllegalArgumentException()
    }

    /** Forwards change notifications from observable sub-lists with translated indices. */
    inner class Callback<T> : OnListChangedCallback<ObservableList<T>>() {
        override fun onChanged(sender: ObservableList<T>) {
            ++modCount
            listeners.notifyChanged(this@MergeObservableList)
        }

        override fun onItemRangeChanged(
            sender: ObservableList<T>,
            positionStart: Int,
            itemCount: Int
        ) {
            listeners.notifyChanged(this@MergeObservableList,
                sub_index_to_index(sender, positionStart), itemCount)
        }

        override fun onItemRangeInserted(
            sender: ObservableList<T>,
            positionStart: Int,
            itemCount: Int
        ) {
            ++modCount
            listeners.notifyInserted(this@MergeObservableList,
                sub_index_to_index(sender, positionStart), itemCount)
        }

        override fun onItemRangeMoved(
            sender: ObservableList<T>,
            from_position: Int,
            toPosition: Int,
            itemCount: Int
        ) {
            val idx = sub_index_to_index(sender, 0)
            listeners.notifyMoved(this@MergeObservableList,
                idx + from_position, idx + toPosition, itemCount)
        }

        override fun onItemRangeRemoved(
            sender: ObservableList<T>,
            positionStart: Int,
            itemCount: Int
        ) {
            ++modCount
            listeners.notifyRemoved(this@MergeObservableList,
                sub_index_to_index(sender, positionStart), itemCount)
        }
    }
}
