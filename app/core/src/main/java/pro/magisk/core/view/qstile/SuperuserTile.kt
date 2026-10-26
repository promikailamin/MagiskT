/**
 * Quick Settings tile that toggles the global `superuser` setting stored
 * in the Magisk database. When disabled, only the Magisk manager app may
 * gain root; when enabled, all apps may gain root (subject to the
 * denylist).
 */
package pro.magisk.core.view.qstile

import android.widget.Toast
import com.topjohnwu.superuser.Shell
import pro.magisk.core.BuildConfig
import pro.magisk.core.Info
import pro.magisk.core.R

class SuperuserTile : SettingToggleTile() {

    override val readCmd =
        "magisk --sqlite \"SELECT value FROM settings WHERE key='superuser'\""

    override val writeCmd: (Boolean) -> String = {
        val value = if (it) 1 else 0
        "magisk --sqlite \"INSERT OR REPLACE INTO settings (key,value) " +
            "VALUES('superuser',$value)\""
    }

    override fun parseState(output: String): Boolean =
        output.substringAfterLast("value=", output).trim() == "1"

    override fun isSupported(): Boolean {
        // The daemon environment is populated by the first root shell. Create
        // it here as well so a click right after a cold start is not rejected.
        if (Info.env.versionCode <= 0) {
            runCatching { Shell.getShell() }
        }
        return Info.env.versionCode >= BuildConfig.APP_VERSION_CODE
    }

    override fun onUnsupported() {
        Toast.makeText(this, R.string.qs_tile_update_required, Toast.LENGTH_SHORT).show()
    }
}
