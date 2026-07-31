/**
 * Module list screen — displays installed Magisk modules and the "Install from storage" button.
 *
 * Observes the ViewModel's [ModuleViewModel.data] LiveData for incoming module ZIP URIs
 * (selected via the file picker) and triggers the install confirmation dialog.
 */
package pro.magisk.ui.module

import android.os.Bundle
import android.view.View
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.core.utils.MediaStoreUtils.display_name
import pro.magisk.databinding.FragmentModuleMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addInvalidateItemDecorationsObserver
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Fragment that shows installed Magisk modules and handles local module installs. */
class ModuleFragment : BaseFragment<FragmentModuleMd2Binding>() {

    override val layout_res = R.layout.fragment_module_md2
    override val view_model by view_model<ModuleViewModel>()

    override fun onStart() {
        super.onStart()
        activity?.title = resources.getString(CoreR.string.modules)
        view_model.data.observe(this) {
            it ?: return@observe
            val display_name = runCatching { it.display_name }.getOrNull() ?: return@observe
            view_model.request_install_local_module(it, display_name)
            view_model.data.value = null
        }
    }

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)

        binding.moduleList.apply {
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
            post { addInvalidateItemDecorationsObserver() }
        }
    }

    override fun on_pre_bind(binding: FragmentModuleMd2Binding) = Unit

}
