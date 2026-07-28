/**
 * A simple RecyclerView item that displays a centred text message.
 *
 * Used for empty-state views (e.g. "No Superuser policies", "No log data").
 */
package pro.magisk.view

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.RvItem

/** A list item that shows a static text resource. */
class TextItem(override val item: Int) : RvItem(), DiffItem<TextItem>, ItemWrapper<Int> {
    override val layoutRes = R.layout.item_text
}
