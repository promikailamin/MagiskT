/**
 * Settings screen — a scrollable list of configurable options.
 *
 * Settings are modelled as [BaseSettingsItem] objects. Each item is refreshed on
 * `onResume` to reflect latest state (e.g. device-lock status for the auth toggle).
 */
package pro.magisk.ui.settings

import android.os.Bundle
import android.view.View
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.databinding.FragmentSettingsMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Settings screen — categories and toggles for customisation, Magisk, and Superuser. */
class SettingsFragment : BaseFragment<FragmentSettingsMd2Binding>() {

    override val layout_res = R.layout.fragment_settings_md2
    override val view_model by view_model<SettingsViewModel>()
    override val snackbar_view: View get() = binding.snackbarContainer

    override fun onStart() {
        super.onStart()

        activity?.title = resources.getString(CoreR.string.settings)
    }

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)
        binding.settingsList.apply {
            addEdgeSpacing(bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }
    }

    override fun onResume() {
        super.onResume()
        view_model.items.forEach { it.refresh() }
    }

}
