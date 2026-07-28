/**
 * RecyclerView items for the module list screen.
 *
 * - [InstallModule]: a placeholder item that triggers the "Install from storage" flow.
 * - [LocalModuleRvItem]: wraps a [LocalModule] with observable enable/remove properties
 *   and shows compatibility notices (Zygisk vs Riru, Zygisk unloaded).
 */
package pro.magisk.ui.module

import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.Info
import pro.magisk.core.model.module.LocalModule
import pro.magisk.core.utils.TextHolder
import pro.magisk.core.utils.asText
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.databinding.RvItem
import pro.magisk.databinding.set
import pro.magisk.core.R as CoreR

/** A clickable "Install from storage" item at the top of the module list. */
object InstallModule : RvItem(), DiffItem<InstallModule> {
    override val layoutRes = R.layout.item_module_download
}

/** An installed module item with enable/remove toggles and compatibility notices. */
class LocalModuleRvItem(
    override val item: LocalModule
) : ObservableRvItem(), DiffItem<LocalModuleRvItem>, ItemWrapper<LocalModule> {

    override val layoutRes = R.layout.item_module_md2

    val showNotice: Boolean
    val showAction: Boolean
    val noticeText: TextHolder

    init {
        val isZygisk = item.isZygisk
        val isRiru = item.isRiru
        val zygiskUnloaded = isZygisk && item.zygiskUnloaded

        // Show a compatibility notice if the module targets the wrong environment
        showNotice = zygiskUnloaded ||
            (Info.isZygiskEnabled && isRiru) ||
            (!Info.isZygiskEnabled && isZygisk)
        showAction = item.hasAction && !showNotice
        noticeText =
            when {
                zygiskUnloaded -> CoreR.string.zygisk_module_unloaded.asText()
                isRiru -> CoreR.string.suspend_text_riru.asText(CoreR.string.zygisk.asText())
                else -> CoreR.string.suspend_text_zygisk.asText(CoreR.string.zygisk.asText())
            }
    }

    @get:Bindable
    var isEnabled = item.enable
        set(value) = set(value, field, { field = it }, BR.enabled) {
            item.enable = value
        }

    @get:Bindable
    var isRemoved = item.remove
        set(value) = set(value, field, { field = it }, BR.removed) {
            item.remove = value
        }

    val isUpdated = item.updated

    fun delete() {
        isRemoved = !isRemoved
    }

    override fun itemSameAs(other: LocalModuleRvItem): Boolean = item.id == other.item.id
}
