/**
 * App manager screen — lists all installed apps categorized by type.
 *
 * User apps have no card outline, system apps that can be disabled have a
 * warning outline, and core apps that cannot be disabled have an error outline.
 * Tapping an app expands it inline with detail info and uninstall/enable
 * actions. A search field at the top filters the list by name or package name.
 */
package pro.magisk.ui.appmanager

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

        binding.appSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.query = s?.toString().orEmpty()
            }
        })
    }

    override fun onPreBind(binding: FragmentAppManagerMd2Binding) = Unit
}
