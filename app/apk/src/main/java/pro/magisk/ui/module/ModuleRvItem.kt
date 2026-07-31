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
    override val layout_res = R.layout.item_module_download
}

/** An installed module item with enable/remove toggles and compatibility notices. */
class LocalModuleRvItem(
    override val item: LocalModule
) : ObservableRvItem(), DiffItem<LocalModuleRvItem>, ItemWrapper<LocalModule> {

    override val layout_res = R.layout.item_module_md2

    val show_notice: Boolean
    val show_action: Boolean
    val notice_text: TextHolder

    init {
        val is_zygisk = item.is_zygisk
        val is_riru = item.is_riru
        val zygisk_unloaded = is_zygisk && item.zygisk_unloaded

        // Show a compatibility notice if the module targets the wrong environment
        show_notice = zygisk_unloaded ||
            (Info.is_zygisk_enabled && is_riru) ||
            (!Info.is_zygisk_enabled && is_zygisk)
        show_action = item.has_action && !show_notice
        notice_text =
            when {
                zygisk_unloaded -> CoreR.string.zygisk_module_unloaded.asText()
                is_riru -> CoreR.string.suspend_text_riru.asText(CoreR.string.zygisk.asText())
                else -> CoreR.string.suspend_text_zygisk.asText(CoreR.string.zygisk.asText())
            }
    }

    @get:Bindable
    var isEnabled = item.enable
        set(value) = set(value, field, { field = it }, BR.enabled) {
            item.enable = value
        }

    @get:Bindable
    var removed = item.remove
        set(value) = set(value, field, { field = it }, BR.removed) {
            item.remove = value
        }

    val updated = item.updated

    fun delete() {
        removed = !removed
    }

    override fun item_same_as(other: LocalModuleRvItem): Boolean = item.id == other.item.id
}
