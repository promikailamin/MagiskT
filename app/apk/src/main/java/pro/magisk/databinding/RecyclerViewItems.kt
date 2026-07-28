/**
 * Core RecyclerView item abstractions for the DataBinding-based list system.
 *
 * - [RvItem]: base class that every list item must extend, providing a layout resource.
 * - [ObservableRvItem]: adds observable property support via [ObservableHost] for DataBinding.
 * - [ItemWrapper]: marks an item that wraps a data model of type [E].
 * - [DiffItem]: enables efficient RecyclerView diffing with default [itemSameAs] / [contentSameAs].
 */
package pro.magisk.databinding

import androidx.databinding.PropertyChangeRegistry
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView

/** Base class for all RecyclerView items. Subclasses must provide a [layoutRes]. */
abstract class RvItem {
    abstract val layoutRes: Int
}

/** [RvItem] with observable properties for DataBinding two-way binding. */
abstract class ObservableRvItem : RvItem(), ObservableHost {
    override var callbacks: PropertyChangeRegistry? = null
}

/** Wraps a data model of type [E]; used for diff comparisons. */
interface ItemWrapper<E> {
    val item: E
}

/** Supports [DiffUtil]-based RecyclerView diffing. */
interface DiffItem<T : Any> {

    fun itemSameAs(other: T): Boolean {
        if (this === other) return true
        return when (this) {
            is ItemWrapper<*> -> item == (other as ItemWrapper<*>).item
            is Comparable<*> -> compareValues(this, other as Comparable<*>) == 0
            else -> this == other
        }
    }

    fun contentSameAs(other: T) = true
}
