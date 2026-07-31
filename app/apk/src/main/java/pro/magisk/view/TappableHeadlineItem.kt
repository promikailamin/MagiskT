/**
 * A tappable headline item used in settings-like screens.
 *
 * Currently used for the "Dark Mode" theme selector on the theme picker screen.
 * The [Listener] interface allows the hosting ViewModel to handle taps.
 */
package pro.magisk.view

import pro.magisk.R
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.RvItem
import pro.magisk.core.R as CoreR

/** Base class for a tappable headline row with an icon and title. */
sealed class TappableHeadlineItem : RvItem(), DiffItem<TappableHeadlineItem> {

    abstract val title: Int
    abstract val icon: Int

    override val layout_res = R.layout.item_tappable_headline

    interface Listener {
        fun on_item_pressed(item: TappableHeadlineItem)
    }

    object ThemeMode : TappableHeadlineItem() {
        override val title = CoreR.string.settings_dark_mode_title
        override val icon = R.drawable.ic_day_night
    }

}
