/**
 * Available colour themes for the Magisk Manager app.
 *
 * Each theme maps to an Android style resource. The active theme is persisted via
 * [Config.themeOrdinal]. Defaults to [Piplup] when the stored ordinal is out of range.
 */
package pro.magisk.ui.theme

import pro.magisk.R
import pro.magisk.core.Config

/** Enum of all app colour themes with their display name and style resource. */
enum class Theme(
    val theme_name: String,
    val theme_res: Int
) {
    Rayquaza(
        theme_name = "Rayquaza",
        theme_res = R.style.ThemeFoundationMD2_Rayquaza
    ),
    Piplup(
        theme_name = "Piplup",
        theme_res = R.style.ThemeFoundationMD2_Piplup
    ),
    PiplupAmoled(
        theme_name = "AMOLED",
        theme_res = R.style.ThemeFoundationMD2_Amoled
    ),
    Zapdos(
        theme_name = "Zapdos",
        theme_res = R.style.ThemeFoundationMD2_Zapdos
    ),
    Charmeleon(
        theme_name = "Charmeleon",
        theme_res = R.style.ThemeFoundationMD2_Charmeleon
    ),
    Mew(
        theme_name = "Mew",
        theme_res = R.style.ThemeFoundationMD2_Mew
    ),
    Salamence(
        theme_name = "Salamence",
        theme_res = R.style.ThemeFoundationMD2_Salamence
    ),
    Fraxure(
        theme_name = "Fraxure (Legacy)",
        theme_res = R.style.ThemeFoundationMD2_Fraxure
    );

    val isSelected get() = Config.theme_ordinal == ordinal

    companion object {
        val selected get() = values().getOrNull(Config.theme_ordinal) ?: Piplup
    }

}
