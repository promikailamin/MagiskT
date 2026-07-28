/**
 * A line of Magisk daemon log text displayed in the log viewer.
 *
 * Each log line is a separate RecyclerView item for smooth scrolling in the
 * horizontal-scroll Magisk log view.
 */
package pro.magisk.ui.log

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.ObservableRvItem

/** A single line from the Magisk daemon log. */
class LogRvItem(
    override val item: String
) : ObservableRvItem(), DiffItem<LogRvItem>, ItemWrapper<String> {
    override val layoutRes = R.layout.item_log_textview
}
