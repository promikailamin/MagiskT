/**
 * Home screen — the first tab of the main navigation.
 *
 * Displays Magisk version/status info and provides access to settings, reboot options,
 * and installation. If the Magisk title is too long for the layout, the associated icon
 * is hidden to avoid squishing.
 */
package pro.magisk.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.viewModel
import pro.magisk.databinding.FragmentHomeMd2Binding
import pro.magisk.core.R as CoreR

/** Home tab — status, version info, and primary actions. */
class HomeFragment : BaseFragment<FragmentHomeMd2Binding>() {

    override val layoutRes = R.layout.fragment_home_md2
    override val viewModel by viewModel<HomeViewModel>()

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.section_home)
    }

    /** If the magisk title text is ellipsized, hide the icon to free space. */
    private fun checkTitle(text: TextView, icon: ImageView) {
        text.post {
            if (text.layout?.getEllipsisCount(0) != 0) {
                with (icon) {
                    layoutParams.width = 0
                    layoutParams.height = 0
                    requestLayout()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        with(binding.homeMagiskWrapper) {
            checkTitle(homeMagiskTitle, homeMagiskIcon)
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
    }
}
