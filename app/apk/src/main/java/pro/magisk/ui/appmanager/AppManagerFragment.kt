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
import androidx.databinding.Observable
import androidx.databinding.ObservableList
import androidx.recyclerview.widget.LinearLayoutManager
import pro.magisk.BR
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

    private var loadingCallback: Observable.OnPropertyChangedCallback? = null
    private var listCallback: ObservableList.OnListChangedCallback<ObservableList<AppManagerRvItem>>? = null

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

        // Save the scroll position before a reload wipes the list; restore it after
        // the list is rebuilt. This only applies when no search text is set.
        val loadingCallback = object : Observable.OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                if (propertyId != BR.loading) return
                if (viewModel.loading && viewModel.query.isEmpty()) {
                    val lm = binding.appList.layoutManager as? LinearLayoutManager ?: return
                    val pos = lm.findFirstVisibleItemPosition()
                    viewModel.savedPos = pos
                    viewModel.savedOffset = lm.findViewByPosition(pos)?.top ?: 0
                }
            }
        }
        viewModel.addOnPropertyChangedCallback(loadingCallback)
        this.loadingCallback = loadingCallback

        val items = viewModel.items as ObservableList<AppManagerRvItem>
        val listCallback = object : ObservableList.OnListChangedCallback<ObservableList<AppManagerRvItem>>() {
            override fun onChanged(sender: ObservableList<AppManagerRvItem>?, position: Int, count: Int) =
                restoreScroll()
            override fun onItemRangeInserted(
                sender: ObservableList<AppManagerRvItem>?, positionStart: Int, itemCount: Int
            ) = restoreScroll()
            override fun onItemRangeRemoved(
                sender: ObservableList<AppManagerRvItem>?, positionStart: Int, itemCount: Int
            ) = Unit
            override fun onItemRangeMoved(
                sender: ObservableList<AppManagerRvItem>?,
                fromPosition: Int, toPosition: Int, itemCount: Int
            ) = Unit
        }
        items.addOnListChangedCallback(listCallback)
        this.listCallback = listCallback
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadingCallback?.let { viewModel.removeOnPropertyChangedCallback(it) }
        loadingCallback = null
        val items = viewModel.items as ObservableList<AppManagerRvItem>
        listCallback?.let { items.removeOnListChangedCallback(it) }
        listCallback = null
    }

    /** Restores the saved scroll position once the rebuilt list is rendered. */
    private fun restoreScroll() {
        if (viewModel.loading || viewModel.query.isNotEmpty()) return
        val lm = binding.appList.layoutManager as? LinearLayoutManager ?: return
        // Only restore when the reload actually reset the list to the top
        if (lm.findFirstVisibleItemPosition() == 0) {
            lm.scrollToPositionWithOffset(viewModel.savedPos, viewModel.savedOffset)
        }
    }

    override fun onPreBind(binding: FragmentAppManagerMd2Binding) = Unit
}
