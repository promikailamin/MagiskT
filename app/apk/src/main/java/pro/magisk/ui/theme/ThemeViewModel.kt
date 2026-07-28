/**
 * ViewModel for the theme picker screen and the dark-mode switcher.
 *
 * Handles selection of both the colour theme (via [saveTheme]) and the dark-mode mode
 * (Light / System / Dark) via [DarkThemeDialog].
 */
package pro.magisk.ui.theme

import pro.magisk.arch.BaseViewModel
import pro.magisk.core.Config
import pro.magisk.dialog.DarkThemeDialog
import pro.magisk.events.RecreateEvent
import pro.magisk.view.TappableHeadlineItem

/** ViewModel for theme selection. */
class ThemeViewModel : BaseViewModel(), TappableHeadlineItem.Listener {

    val themeHeadline = TappableHeadlineItem.ThemeMode

    override fun onItemPressed(item: TappableHeadlineItem) = when (item) {
        is TappableHeadlineItem.ThemeMode -> DarkThemeDialog().show()
    }

    fun saveTheme(theme: Theme) {
        if (!theme.isSelected) {
            Config.themeOrdinal = theme.ordinal
            RecreateEvent().publish()
        }
    }
}
