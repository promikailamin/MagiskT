/**
 * Settings screen — a scrollable list of configurable options.
 *
 * Settings are modelled as [BaseSettingsItem] objects. Each item is refreshed on
 * `onResume` to reflect latest state (e.g. device-lock status for the auth toggle).
 */
package pro.magisk.ui.settings

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.viewModel
import pro.magisk.databinding.FragmentSettingsMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Settings screen — categories and toggles for customisation, Magisk, and Superuser. */
class SettingsFragment : BaseFragment<FragmentSettingsMd2Binding>() {

    override val layoutRes = R.layout.fragment_settings_md2
    override val viewModel by viewModel<SettingsViewModel>()
    override val snackbarView: View get() = binding.snackbarContainer

    override fun onStart() {
        super.onStart()

        activity?.title = resources.getString(CoreR.string.settings)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.settingsList.apply {
            addEdgeSpacing(top = R.dimen.l1, bottom = R.dimen.l1)
            val gap = resources.getDimensionPixelSize(R.dimen.l1) / 2
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos == RecyclerView.NO_POSITION) return
                    if (viewModel.items[pos] is BaseSettingsItem.Section) {
                        outRect.top = -gap
                        outRect.bottom = -gap
                    } else {
                        outRect.top = gap
                        outRect.bottom = gap
                    }
                }
            })
            fixEdgeEffect()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.items.forEach { it.refresh() }
    }

}
