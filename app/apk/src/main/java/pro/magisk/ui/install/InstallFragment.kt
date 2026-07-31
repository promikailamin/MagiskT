/**
 * Install screen — allows the user to choose a Magisk installation method.
 *
 * Options vary depending on device state (rooted, A/B slots, emulator, SAR, etc.)
 * and include Direct Install, Patch Boot Image, and Install to Inactive Slot.
 */
package pro.magisk.ui.install

import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.databinding.FragmentInstallMd2Binding
import pro.magisk.core.R as CoreR

/** Fragment with installation method selection (Direct / Patch / Second Slot). */
class InstallFragment : BaseFragment<FragmentInstallMd2Binding>() {

    override val layout_res = R.layout.fragment_install_md2
    override val view_model by view_model<InstallViewModel>()

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(CoreR.string.install)
    }
}
