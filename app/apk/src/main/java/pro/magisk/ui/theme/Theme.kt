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
    val themeName: String,
    val themeRes: Int
) {
    Rayquaza(
        themeName = "Rayquaza",
        themeRes = R.style.ThemeFoundationMD2_Rayquaza
    ),
    Piplup(
        themeName = "Piplup",
        themeRes = R.style.ThemeFoundationMD2_Piplup
    ),
    PiplupAmoled(
        themeName = "AMOLED",
        themeRes = R.style.ThemeFoundationMD2_Amoled
    ),
    Zapdos(
        themeName = "Zapdos",
        themeRes = R.style.ThemeFoundationMD2_Zapdos
    ),
    Charmeleon(
        themeName = "Charmeleon",
        themeRes = R.style.ThemeFoundationMD2_Charmeleon
    ),
    Mew(
        themeName = "Mew",
        themeRes = R.style.ThemeFoundationMD2_Mew
    ),
    Salamence(
        themeName = "Salamence",
        themeRes = R.style.ThemeFoundationMD2_Salamence
    ),
    Fraxure(
        themeName = "Fraxure (Legacy)",
        themeRes = R.style.ThemeFoundationMD2_Fraxure
    );

    val isSelected get() = Config.themeOrdinal == ordinal

    companion object {
        val selected get() = values().getOrNull(Config.themeOrdinal) ?: Piplup
    }

}
