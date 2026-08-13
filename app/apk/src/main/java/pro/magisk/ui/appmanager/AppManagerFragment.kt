/**
 * App manager screen — lists all installed apps categorized by type.
 *
 * User apps are shown in white, system apps that can be disabled in a warning
 * color, and core apps that cannot be disabled in an error color. Tapping an
 * app opens a detail dialog with uninstall/disable actions.
 */
package pro.magisk.ui.appmanager

import android.os.Bundle
import android.view.View
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.viewModel
import pro.magisk.databinding.FragmentAppManagerMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Fragment for managing installed apps. */
class AppManagerFragment : BaseFragment<FragmentAppManagerMd2Binding>() {

    override val layoutRes = R.layout.fragment_app_manager_md2
    override val viewModel by viewModel<AppManagerViewModel>()

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.app_manager_title)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.appList.apply {
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }
    }

    override fun onPreBind(binding: FragmentAppManagerMd2Binding) = Unit
}
