/**
 * ViewModel for the module list screen.
 *
 * Loads installed modules from [LocalModule], exposes a merged observable list
 * (install-button item + module items), and handles the "install from storage"
 * file-picker flow via [GetContentEvent].
 */
package pro.magisk.ui.module

import android.net.Uri
import androidx.databinding.Bindable
import androidx.lifecycle.MutableLiveData
import pro.magisk.BR
import pro.magisk.MainDirections
import pro.magisk.R
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.base.ContentResultCallback
import pro.magisk.core.model.module.LocalModule
import pro.magisk.databinding.MergeObservableList
import pro.magisk.databinding.RvItem
import pro.magisk.databinding.bind_extra
import pro.magisk.databinding.diffList
import pro.magisk.databinding.set
import pro.magisk.dialog.LocalModuleInstallDialog
import pro.magisk.events.GetContentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import pro.magisk.core.R as CoreR

/** ViewModel for the module list — loading, display, and install flows. */
class ModuleViewModel : AsyncLoadViewModel() {

    val bottom_bar_barrier_ids = intArrayOf(R.id.module_remove)

    private val items_installed = diffList<LocalModuleRvItem>()

    val items = MergeObservableList<RvItem>()
    val extra_bindings = bind_extra {
        it.put(BR.view_model, this)
    }

    val data get() = uri

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    override suspend fun do_load_work() {
        loading = true
        val module_loaded = Info.env.isActive &&
                withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (module_loaded) {
            load_installed()
            if (items.isEmpty()) {
                items.insert_item(InstallModule)
                    .insert_list(items_installed)
            }
        }
        loading = false
    }

    private suspend fun load_installed() {
        withContext(Dispatchers.Default) {
            val installed = LocalModule.installed().map { LocalModuleRvItem(it) }
            items_installed.update(installed)
        }
    }

    fun install_pressed() = with_external_r_w {
        GetContentEvent("application/zip", UriCallback()).publish()
    }

    fun request_install_local_module(uri: Uri, display_name: String) {
        LocalModuleInstallDialog(this, uri, display_name).show()
    }

    @Parcelize
    class UriCallback : ContentResultCallback {
        override fun onActivityResult(result: Uri) {
            uri.value = result
        }
    }

    fun run_action(id: String, name: String) {
        MainDirections.actionActionFragment(id, name).navigate()
    }

    companion object {
        private val uri = MutableLiveData<Uri?>()
    }
}
