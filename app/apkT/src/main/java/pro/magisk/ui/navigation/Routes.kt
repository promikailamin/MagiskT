/**
 * Navigation route definitions for the navigation3 library. Each route is a Parcelable sealed
 * subclass that serves as both a navigation key and a data carrier for parameters.
 */
package pro.magisk.ui.navigation

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

sealed interface Route : NavKey, Parcelable {
    /** The main tab shell. */
    @Parcelize
    @Serializable
    data object Main : Route

    /** The Magisk DenyList configuration screen. */
    @Parcelize
    @Serializable
    data object DenyList : Route

    /** A flash/install operation (zip, uninstall, magisk update, patch, inactive slot). */
    @Parcelize
    @Serializable
    data class Flash(
        val action: String,
        val additionalData: String? = null,
    ) : Route

    /** Detail view for a single superuser policy entry. */
    @Parcelize
    @Serializable
    data class SuperuserDetail(val uid: Int) : Route

    /** Screen that runs a module's action.sh script. */
    @Parcelize
    @Serializable
    data class Action(
        val id: String,
        val name: String,
    ) : Route
}
