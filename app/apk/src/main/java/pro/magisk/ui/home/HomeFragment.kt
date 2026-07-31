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
import pro.magisk.arch.view_model
import pro.magisk.databinding.FragmentHomeMd2Binding
import pro.magisk.core.R as CoreR

/** Home tab — status, version info, and primary actions. */
class HomeFragment : BaseFragment<FragmentHomeMd2Binding>() {

    override val layout_res = R.layout.fragment_home_md2
    override val view_model by view_model<HomeViewModel>()

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.section_home)
    }

    /** If the magisk title text is ellipsized, hide the icon to free space. */
    private fun check_title(text: TextView, icon: ImageView) {
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
        saved_instance_state: Bundle?
    ): View {
        super.onCreateView(inflater, container, saved_instance_state)

        with(binding.homeMagiskWrapper) {
            check_title(homeMagiskTitle, homeMagiskIcon)
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
    }
}
