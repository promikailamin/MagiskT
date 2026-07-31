/**
 * Log viewer screen — shows both Superuser access logs and the Magisk daemon log.
 *
 * Uses a toggle (via [MotionRevealHelper]) to switch between the two views.
 * When showing the Magisk log, the bottom navigation is hidden and an up-indicator
 * is shown so the user can dismiss the detail view.
 */
package pro.magisk.ui.log

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.databinding.FragmentLogMd2Binding
import pro.magisk.ui.MainActivity
import pro.magisk.utils.AccessibilityUtils
import pro.magisk.utils.MotionRevealHelper
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import pro.magisk.core.R as CoreR

/** Fragment displaying Superuser logs and Magisk daemon logs. */
class LogFragment : BaseFragment<FragmentLogMd2Binding>(), MenuProvider {

    override val layout_res = R.layout.fragment_log_md2
    override val view_model by view_model<LogViewModel>()
    override val snackbar_view: View?
        get() = if (is_magisk_log_visible) binding.logFilterSuperuser.snackbarContainer
                else super.snackbar_view
    override val snackbar_anchor_view get() = binding.logFilterToggle

    private var action_save: MenuItem? = null
    private var is_magisk_log_visible
        get() = binding.logFilter.isVisible
        set(value) {
            MotionRevealHelper.withViews(binding.logFilter, binding.logFilterToggle, value)
            action_save?.isVisible = !value
            with(activity as MainActivity) {
                request_navigation_hidden(value)
            }
        }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.logs)
    }

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)
        binding.logFilterToggle.setOnClickListener {
            is_magisk_log_visible = true
        }

        binding.logFilterSuperuser.logSuperuser.apply {
            addEdgeSpacing(bottom = R.dimen.l1)
            addItemSpacing(R.dimen.l1, R.dimen.l_50, R.dimen.l1)
            fixEdgeEffect()
        }

        if (!AccessibilityUtils.is_animation_enabled(requireContext().contentResolver)) {
            val scroll_view = view.findViewById<HorizontalScrollView>(R.id.log_scroll_magisk)
            scroll_view.setOverScrollMode(View.OVER_SCROLL_NEVER)
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_log_md2, menu)
        action_save = menu.findItem(R.id.action_save)?.also {
            it.isVisible = !is_magisk_log_visible
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> view_model.save_magisk_log()
            R.id.action_clear ->
                if (!is_magisk_log_visible) view_model.clear_magisk_log()
                else view_model.clear_log()
        }
        return false
    }

    override fun on_pre_bind(binding: FragmentLogMd2Binding) = Unit

    // Dismiss the Magisk log view when back is pressed
    override fun onBackPressed(): Boolean {
        if (binding.logFilter.isVisible) {
            is_magisk_log_visible = false
            return true
        }
        return super.onBackPressed()
    }

}
