/**
 * Manages app shortcuts (dynamic and pinned).
 *
 * Dynamic shortcuts give quick access to Superuser and Modules
 * screens. The home-screen icon can also be pinned via
 * [addHomeIcon].
 */
package pro.magisk.view

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.is_running_as_stub
import pro.magisk.core.ktx.getBitmap

object Shortcuts {

    /** Set dynamic shortcuts when supported (API 25+). */
    fun setup_dynamic(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = context.getSystemService<ShortcutManager>() ?: return
            manager.dynamicShortcuts = get_short_cuts(context)
        }
    }

    /** Pin a home-screen shortcut via [ShortcutManagerCompat]. */
    fun add_home_icon(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val info = ShortcutInfoCompat.Builder(context, Const.Nav.HOME)
            .setShortLabel(context.getString(R.string.magisk))
            .setIntent(intent)
            .setIcon(context.getIconCompat(R.drawable.ic_launcher))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** Resolve an [Icon] from a drawable resource ID. */
    private fun Context.getIcon(id: Int): Icon {
        return if (is_running_as_stub) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Icon.createWithAdaptiveBitmap(getBitmap(id))
            else
                Icon.createWithBitmap(getBitmap(id))
        } else {
            Icon.createWithResource(this, id)
        }
    }

    /** Resolve an [IconCompat] from a drawable resource ID. */
    private fun Context.getIconCompat(id: Int): IconCompat {
        return if (is_running_as_stub) {
            val bitmap = getBitmap(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                IconCompat.createWithAdaptiveBitmap(bitmap)
            else
                IconCompat.createWithBitmap(bitmap)
        } else {
            IconCompat.createWithResource(this, id)
        }
    }

    /** Build the list of dynamic shortcuts. */
    @RequiresApi(api = 25)
    private fun get_short_cuts(context: Context): List<ShortcutInfo> {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return emptyList()

        val short_cuts = mutableListOf<ShortcutInfo>()

        if (Info.show_super_user) {
            short_cuts.add(
                ShortcutInfo.Builder(context, Const.Nav.SUPERUSER)
                    .setShortLabel(context.getString(R.string.superuser))
                    .setIntent(
                        Intent(intent).putExtra(Const.Key.OPEN_SECTION, Const.Nav.SUPERUSER)
                    )
                    .setIcon(context.getIcon(R.drawable.sc_superuser))
                    .setRank(0)
                    .build()
            )
        }
        if (Info.env.isActive) {
            short_cuts.add(
                ShortcutInfo.Builder(context, Const.Nav.MODULES)
                    .setShortLabel(context.getString(R.string.modules))
                    .setIntent(
                        Intent(intent).putExtra(Const.Key.OPEN_SECTION, Const.Nav.MODULES)
                    )
                    .setIcon(context.getIcon(R.drawable.sc_extension))
                    .setRank(1)
                    .build()
            )
        }
        return short_cuts
    }
}
