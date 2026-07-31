/**
 * DenyList screen — manages the list of apps/processes subject to Zygisk denylist.
 *
 * Provides search/filter, show-system-apps and show-OS-apps toggles, and scroll-to-hide-keyboard
 * behaviour. Each app entry can be expanded to toggle individual processes on/off.
 */
package pro.magisk.ui.deny

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.recyclerview.widget.RecyclerView
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.core.ktx.hideKeyboard
import pro.magisk.databinding.FragmentDenyMd2Binding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Fragment for managing the Zygisk DenyList. */
class DenyListFragment : BaseFragment<FragmentDenyMd2Binding>(), MenuProvider {

    override val layout_res = R.layout.fragment_deny_md2
    override val view_model by view_model<DenyListViewModel>()

    private lateinit var search_view: SearchView

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.denylist)
    }

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)

        binding.appList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recycler_view: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) activity?.hideKeyboard()
            }
        })

        binding.appList.apply {
            addEdgeSpacing(top = R.dimen.l_50, bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }
    }

    override fun on_pre_bind(binding: FragmentDenyMd2Binding) = Unit

    override fun onBackPressed(): Boolean {
        // Collapse the search view on back-press instead of navigating away
        if (search_view.isIconfiedByDefault && !search_view.isIconified) {
            search_view.isIconified = true
            return true
        }
        return super.onBackPressed()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_deny_md2, menu)
        search_view = menu.findItem(R.id.action_search).actionView as SearchView
        search_view.queryHint = search_view.context.getString(CoreR.string.hide_filter_hint)
        search_view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                view_model.query = query ?: ""
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                view_model.query = newText ?: ""
                return true
            }
        })
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_show_system -> {
                val check = !item.isChecked
                view_model.is_show_system = check
                item.isChecked = check
                return true
            }
            R.id.action_show_OS -> {
                val check = !item.isChecked
                view_model.is_show_o_s = check
                item.isChecked = check
                return true
            }
        }
        return false
    }

    override fun onPrepareMenu(menu: Menu) {
        val show_system = menu.findItem(R.id.action_show_system)
        val show_o_s = menu.findItem(R.id.action_show_OS)
        show_o_s.isEnabled = show_system.isChecked
    }
}
