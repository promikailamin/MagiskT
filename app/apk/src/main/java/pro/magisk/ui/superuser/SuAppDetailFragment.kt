/**
 * SU app detail screen — full app details and Superuser management for one app.
 *
 * Reached by tapping a policy entry on the Superuser screen. Shows the app icon,
 * name, package and install info, plus the access policy, notification, logging,
 * lock, and revoke controls.
 */
package pro.magisk.ui.superuser

import android.os.Bundle
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.viewModel
import pro.magisk.databinding.FragmentSuAppDetailMd2Binding

/** Fragment showing the SU app detail screen. */
class SuAppDetailFragment : BaseFragment<FragmentSuAppDetailMd2Binding>() {

    override val layoutRes = R.layout.fragment_su_app_detail_md2
    override val viewModel by viewModel<SuAppDetailViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.args = SuAppDetailFragmentArgs.fromBundle(requireArguments())
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(viewModel.args.appName)
    }

    override fun onPreBind(binding: FragmentSuAppDetailMd2Binding) = Unit
}