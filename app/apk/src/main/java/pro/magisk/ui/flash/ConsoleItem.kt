package pro.magisk.ui.flash

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.RvItem

class ConsoleItem(
    override val item: String
) : RvItem(), DiffItem<ConsoleItem>, ItemWrapper<String> {
    override val layoutRes = R.layout.item_console_md2
}
