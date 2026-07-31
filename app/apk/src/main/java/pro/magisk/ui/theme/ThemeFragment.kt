/**
 * Theme picker screen — displays available colour themes in a two-column grid.
 *
 * Each theme card is inflated with a themed [ContextThemeWrapper] so the preview
 * accurately reflects the theme's actual colours. Selecting a theme persists it
 * and triggers an activity recreate.
 */
package pro.magisk.ui.theme

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.databinding.FragmentThemeMd2Binding
import pro.magisk.databinding.ItemThemeBindingImpl
import pro.magisk.core.R as CoreR

/** Fragment for selecting the app colour theme. */
class ThemeFragment : BaseFragment<FragmentThemeMd2Binding>() {

    override val layout_res = R.layout.fragment_theme_md2
    override val view_model by view_model<ThemeViewModel>()

    /** Pairs adjacent elements; if odd, the last pair has a null second element. */
    private fun <T> Array<T>.paired(): List<Pair<T, T?>> {
        val iterator = iterator()
        if (!iterator.hasNext()) return emptyList()
        val result = mutableListOf<Pair<T, T?>>()
        while (iterator.hasNext()) {
            val a = iterator.next()
            val b = if (iterator.hasNext()) iterator.next() else null
            result.add(a to b)
        }
        return result
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        saved_instance_state: Bundle?
    ): View {
        super.onCreateView(inflater, container, saved_instance_state)

        for ((a, b) in Theme.values().paired()) {
            val c = inflater.inflate(R.layout.item_theme_container, null, false)
            val left = c.findViewById<FrameLayout>(R.id.left)
            val right = c.findViewById<FrameLayout>(R.id.right)

            for ((theme, view) in listOf(a to left, b to right)) {
                theme ?: continue
                val themed = ContextThemeWrapper(activity, theme.theme_res)
                ItemThemeBindingImpl.inflate(LayoutInflater.from(themed), view, true).also {
                    it.setVariable(BR.view_model, view_model)
                    it.setVariable(BR.theme, theme)
                    it.lifecycleOwner = viewLifecycleOwner
                }
            }

            binding.themeContainer.addView(c)
        }

        return binding.root
    }

    override fun onStart() {
        super.onStart()

        activity?.title = getString(CoreR.string.section_theme)
    }

}
