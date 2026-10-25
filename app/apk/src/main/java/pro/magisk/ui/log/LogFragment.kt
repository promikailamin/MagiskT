/**
 * Log viewer screen — shows the Magisk daemon log.
 *
 * Supports saving a comprehensive debug log to a file and clearing the log.
 */
package pro.magisk.ui.log

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.view.MenuProvider
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.viewModel
import pro.magisk.databinding.FragmentLogMd2Binding
import pro.magisk.utils.AccessibilityUtils
import pro.magisk.core.R as CoreR

/** Fragment displaying the Magisk daemon log. */
class LogFragment : BaseFragment<FragmentLogMd2Binding>(), MenuProvider {

    override val layoutRes = R.layout.fragment_log_md2
    override val viewModel by viewModel<LogViewModel>()

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.logs)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!AccessibilityUtils.isAnimationEnabled(requireContext().contentResolver)) {
            val scrollView = view.findViewById<HorizontalScrollView>(R.id.log_scroll_magisk)
            scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER)
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_log_md2, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> viewModel.saveMagiskLog()
            R.id.action_clear -> viewModel.clearMagiskLog()
        }
        return false
    }

    override fun onPreBind(binding: FragmentLogMd2Binding) = Unit

}
