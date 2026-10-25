/**
 * Quick Settings tile that toggles the global `superuser` setting stored
 * in the Magisk database. When disabled, only the Magisk manager app may
 * gain root; when enabled, all apps may gain root (subject to the
 * denylist).
 */
package pro.magisk.core.view.qstile

import android.widget.Toast
import pro.magisk.core.BuildConfig
import pro.magisk.core.Info
import pro.magisk.core.R

class SuperuserTile : SettingToggleTile() {
    override fun isAvailable() =
        super.isAvailable() && Info.env.versionCode >= BuildConfig.APP_VERSION_CODE

    override fun onClick() {
        if (isRooted() && !isAvailable()) {
            Toast.makeText(this, R.string.qs_tile_update_required, Toast.LENGTH_SHORT).show()
        }
        super.onClick()
    }

    override val readCmd =
        "magisk --sqlite \"SELECT value FROM settings WHERE key='superuser'\""

    override val writeCmd: (Boolean) -> String = {
        val value = if (it) 1 else 0
        "magisk --sqlite \"INSERT OR REPLACE INTO settings (key,value) " +
            "VALUES('superuser',$value)\""
    }

    override fun parseState(output: String): Boolean =
        output.substringAfterLast("value=", output).trim() == "1"
}
