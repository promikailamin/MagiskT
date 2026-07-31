/**
 * ViewModel for the DenyList management screen.
 *
 * Loads all installed applications (excluding self) and cross-references them with the
 * current denylist from `magisk --denylist ls`. Supports filtering by search query,
 * system-app visibility, and OS-app visibility. Filtering is done via [FilterList]
 * with background diff computation.
 */
package pro.magisk.ui.deny

import android.annotation.SuppressLint
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.ktx.concurrentMap
import pro.magisk.databinding.bind_extra
import pro.magisk.databinding.filterList
import pro.magisk.databinding.set
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.withContext

/** ViewModel for the Zygisk DenyList — app/process whitelist management. */
class DenyListViewModel : AsyncLoadViewModel() {

    var is_show_system = false
        set(value) {
            field = value
            do_query(query)
        }

    var is_show_o_s = false
        set(value) {
            field = value
            do_query(query)
        }

    var query = ""
        set(value) {
            field = value
            do_query(value)
        }

    val items = filterList<DenyListRvItem>(viewModelScope)
    val extra_bindings = bind_extra {
        it.put(BR.view_model, this)
    }

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @SuppressLint("InlinedApi")
    override suspend fun do_load_work() {
        loading = true
        val apps = withContext(Dispatchers.Default) {
            val pm = AppContext.packageManager
            val deny_list = Shell.cmd("magisk --denylist ls").exec().out
                .map { CmdlineListItem(it) }
            val apps = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES).run {
                asFlow()
                    .filter { AppContext.packageName != it.packageName }
                    .concurrentMap { AppProcessInfo(it, pm, deny_list) }
                    .filter { it.processes.isNotEmpty() }
                    .concurrentMap { DenyListRvItem(it) }
                    .toCollection(ArrayList(size))
            }
            apps.sort()
            apps
        }
        items.set(apps)
        do_query(query)
    }

    /** Applies the current filter (system/OS visibility + search query) to the item list. */
    private fun do_query(s: String) {
        items.filter {
            fun filter_system() = is_show_system || !it.info.is_system_app()

            fun filter_o_s() = (is_show_system && is_show_o_s) || it.info.is_app()

            fun filter_query(): Boolean {
                fun in_name() = it.info.label.contains(s, true)
                fun in_package() = it.info.packageName.contains(s, true)
                fun in_processes() = it.processes.any { p -> p.process.name.contains(s, true) }
                return in_name() || in_package() || in_processes()
            }

            (it.isChecked || (filter_system() && filter_o_s())) && filter_query()
        }
        loading = false
    }
}
