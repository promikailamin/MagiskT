/**
 * BroadcastReceiver that reacts to package lifecycle events and
 * system configuration changes.
 *
 * Actions handled:
 * - [ACTION_PACKAGE_REPLACED] — optionally wipes SU policy for the
 *   replaced package (pre-O).
 * - [ACTION_UID_REMOVED] — cleans up SU policy for the removed UID.
 * - [ACTION_PACKAGE_FULLY_REMOVED] — removes the package from the
 *   denylist.
 * - [ACTION_LOCALE_CHANGED] — refreshes dynamic shortcuts.
 */
package pro.magisk.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import pro.magisk.core.base.BaseReceiver
import pro.magisk.core.di.ServiceLocator
import pro.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class Receiver : BaseReceiver() {

    private val policyDB get() = ServiceLocator.policyDB

    @SuppressLint("InlinedApi")
    private fun getPkg(intent: Intent): String? {
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        return pkg ?: intent.data?.schemeSpecificPart
    }

    private fun getUid(intent: Intent): Int? {
        val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
        return if (uid == -1) null else uid
    }

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        super.onReceive(context, intent)

        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        fun rmPolicy(uid: Int) = GlobalScope.launch {
            policyDB.delete(uid)
        }

        when (intent.action ?: return) {
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (Config.suReAuth)
                    getUid(intent)?.let { rmPolicy(it) }
            }
            Intent.ACTION_UID_REMOVED -> {
                getUid(intent)?.let { rmPolicy(it) }
            }
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                getPkg(intent)?.let { Shell.cmd("magisk --denylist rm $it").submit() }
            }
            Intent.ACTION_LOCALE_CHANGED -> Shortcuts.setupDynamic(context)
        }
    }
}
