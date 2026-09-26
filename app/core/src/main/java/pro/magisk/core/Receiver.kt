/**
 * BroadcastReceiver that reacts to package lifecycle events and
 * system configuration changes.
 *
 * Actions handled:
 * - [ACTION_PACKAGE_FULLY_REMOVED] — removes the package from the
 *   denylist.
 * - [ACTION_LOCALE_CHANGED] — refreshes dynamic shortcuts.
 */
package pro.magisk.core

import android.content.Context
import android.content.Intent
import pro.magisk.core.base.BaseReceiver
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell

open class Receiver : BaseReceiver() {

    @Suppress("InlinedApi")
    private fun getPkg(intent: Intent): String? {
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        return pkg ?: intent.data?.schemeSpecificPart
    }

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        super.onReceive(context, intent)

        when (intent.action ?: return) {
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                getPkg(intent)?.let { Shell.cmd("magisk --denylist rm $it").submit() }
            }
            Intent.ACTION_LOCALE_CHANGED -> Shortcuts.setupDynamic(context)
        }
    }
}
