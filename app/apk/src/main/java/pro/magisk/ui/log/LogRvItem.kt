package pro.magisk.ui.log

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.ObservableRvItem

class LogRvItem(
    override val item: String
) : ObservableRvItem(), DiffItem<LogRvItem>, ItemWrapper<String> {
    override val layoutRes = R.layout.item_log_textview
}
