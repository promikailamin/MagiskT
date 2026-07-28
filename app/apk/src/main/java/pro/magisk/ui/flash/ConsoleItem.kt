/**
 * A single line of console output in the flash / action screens.
 *
 * Each line is rendered as a separate RecyclerView item for smooth, scrollable output.
 */
package pro.magisk.ui.flash

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.RvItem

/** A RecyclerView item representing one line of flash/action console output. */
class ConsoleItem(
    override val item: String
) : RvItem(), DiffItem<ConsoleItem>, ItemWrapper<String> {
    override val layoutRes = R.layout.item_console_md2
}
