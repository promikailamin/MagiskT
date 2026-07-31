/**
 * RecyclerView items for the DenyList screen.
 *
 * - [DenyListRvItem]: represents an app with expandable per-process toggles.
 *   The checkbox state is tri-state (all/some/none) computed from sub-process states.
 * - [ProcessRvItem]: represents a single process entry within an app's denylist.
 *
 * Changes to process states are immediately applied via `magisk --denylist add/rm`.
 */
package pro.magisk.ui.deny

import android.view.View
import android.view.ViewGroup
import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.startAnimations
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.databinding.addOnPropertyChangedCallback
import pro.magisk.databinding.set
import com.topjohnwu.superuser.Shell
import kotlin.math.roundToInt

/** An app entry with a checkbox (tri-state) and an expandable list of processes. */
class DenyListRvItem(
    val info: AppProcessInfo
) : ObservableRvItem(), DiffItem<DenyListRvItem>, Comparable<DenyListRvItem> {

    override val layout_res get() = R.layout.item_hide_md2

    val processes = info.processes.map { ProcessRvItem(it) }

    @get:Bindable
    var expanded = false
        set(value) = set(value, field, { field = it }, BR.expanded)

    var items_checked = 0
        set(value) = set(value, field, { field = it }, BR.checked_percent)

    val isChecked get() = items_checked != 0

    @get:Bindable
    val checked_percent get() = (items_checked.toFloat() / processes.size * 100).roundToInt()

    private var _state: Boolean? = false
        set(value) = set(value, field, { field = it }, BR.state)

    @get:Bindable
    var state: Boolean?
        get() = _state
        set(value) = set(value, _state, { _state = it }, BR.state) {
            if (value == true) {
                // Enable all default or visible processes
                processes
                    .filterNot { it.isEnabled }
                    .filter { expanded || it.default_selection }
                    .forEach { it.toggle() }
            } else {
                // Remove the entire package from denylist
                Shell.cmd("magisk --denylist rm ${info.packageName}").submit()
                processes.filter { it.isEnabled }.forEach {
                    if (it.process.is_isolated) {
                        it.toggle()
                    } else {
                        it.isEnabled = !it.isEnabled
                        notifyPropertyChanged(BR.enabled)
                    }
                }
            }
        }

    init {
        processes.forEach { it.addOnPropertyChangedCallback(BR.enabled) { recalculate_checked() } }
        addOnPropertyChangedCallback(BR.expanded) { recalculate_checked() }
        recalculate_checked()
    }

    fun toggle_expand(v: View) {
        (v.parent as? ViewGroup)?.startAnimations()
        expanded = !expanded
    }

    /** Recalculates the checked count and tri-state from sub-process states. */
    private fun recalculate_checked() {
        items_checked = processes.count { it.isEnabled }
        _state = if (expanded) {
            when (items_checked) {
                0 -> false
                processes.size -> true
                else -> null
            }
        } else {
            val default_processes = processes.filter { it.default_selection }
            when (default_processes.count { it.isEnabled }) {
                0 -> false
                default_processes.size -> true
                else -> null
            }
        }
    }

    override fun compareTo(other: DenyListRvItem) = comparator.compare(this, other)

    companion object {
        private val comparator = compareBy<DenyListRvItem>(
            { it.items_checked == 0 },
            { it.info }
        )
    }

}

/** A single process entry within an app's denylist. */
class ProcessRvItem(
    val process: ProcessInfo
) : ObservableRvItem(), DiffItem<ProcessRvItem> {

    override val layout_res get() = R.layout.item_hide_process_md2

    val display_name = if (process.is_isolated) "(isolated) ${process.name}" else process.name

    @get:Bindable
    var isEnabled
        get() = process.isEnabled
        set(value) = set(value, process.isEnabled, { process.isEnabled = it }, BR.enabled) {
            val arg = if (it) "add" else "rm"
            val (name, pkg) = process
            Shell.cmd("magisk --denylist $arg $pkg \'$name\'").submit()
        }

    fun toggle() {
        isEnabled = !isEnabled
    }

    val default_selection get() =
        process.is_isolated || process.is_app_zygote || process.name == process.packageName

    override fun item_same_as(other: ProcessRvItem) =
        process.name == other.process.name && process.packageName == other.process.packageName

    override fun content_same_as(other: ProcessRvItem) =
        process.isEnabled == other.process.isEnabled
}
