/**
 * Data model for the App manager screen.
 *
 * [AppManagerRvItem] wraps an installed application, classifying it into three
 * categories for visual distinction:
 * - [AppType.USER] — user-installed apps (shown white)
 * - [AppType.SYSTEM] — system apps that can be disabled (shown in warning color)
 * - [AppType.CORE] — core system apps that cannot be disabled (shown in error color)
 *
 * Detailed app information (version, size, signature, install time/source) is
 * gathered on demand by the ViewModel when the info dialog is opened.
 */
package pro.magisk.ui.appmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import pro.magisk.R
import pro.magisk.core.AppContext
import pro.magisk.core.ktx.getLabel
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.core.R as CoreR
import java.util.Locale

/** Classification of an installed app on the App manager screen. */
enum class AppType { USER, SYSTEM, CORE }

/** Extra detail gathered lazily when the app info dialog is opened. */
data class AppDetail(
    val version: String,
    val size: String,
    val signature: String,
    val installTime: String,
    val installSource: String
)

/** A single installed-app entry in the App manager list. */
class AppManagerRvItem(
    private val info: ApplicationInfo,
    pm: PackageManager
) : ObservableRvItem(), DiffItem<AppManagerRvItem>, Comparable<AppManagerRvItem> {

    override val layoutRes get() = R.layout.item_app_manager_md2

    val label = info.getLabel(pm)
    val iconImage: Drawable =
        runCatching { info.loadIcon(pm) }.getOrDefault(pm.defaultActivityIcon)
    val packageName get() = info.packageName
    val sourceDir get() = info.publicSourceDir ?: info.sourceDir.orEmpty()
    val dataDir get() = info.dataDir.orEmpty()
    val uid get() = info.uid

    val isSystemApp get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    val isCoreApp get() = isSystemApp && info.uid < 10000

    val type: AppType get() = when {
        isCoreApp -> AppType.CORE
        isSystemApp -> AppType.SYSTEM
        else -> AppType.USER
    }

    val canDisable get() = type != AppType.CORE

    val nameColor: Int get() = ContextCompat.getColor(AppContext, when (type) {
        AppType.USER -> CoreR.color.app_manager_user
        AppType.SYSTEM -> CoreR.color.app_manager_system
        AppType.CORE -> CoreR.color.app_manager_core
    })

    override fun itemSameAs(other: AppManagerRvItem) = packageName == other.packageName

    override fun contentSameAs(other: AppManagerRvItem) =
        label == other.label && type == other.type

    override fun compareTo(other: AppManagerRvItem) = comparator.compare(this, other)

    companion object {
        private val comparator = compareBy<AppManagerRvItem>(
            { it.label.lowercase(Locale.ROOT) }, { it.packageName }
        )
    }
}
