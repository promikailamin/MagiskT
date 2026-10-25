/**
 * Quick Settings tile that toggles the global `superuser` setting stored
 * in the Magisk database. When disabled, only the Magisk manager app may
 * gain root; when enabled, all apps may gain root (subject to the
 * denylist).
 */
package pro.magisk.core.view.qstile

class SuperuserTile : SettingToggleTile() {
    override val readCmd =
        "magisk --sqlite \"SELECT value FROM settings WHERE key='superuser'\""

    override val writeCmd: (Boolean) -> String = {
        val value = if (it) 1 else 0
        "magisk --sqlite \"INSERT OR REPLACE INTO settings (key,value) " +
            "VALUES('superuser',$value)\""
    }

    override fun parseState(output: String): Boolean =
        output.substringAfter("value=", "").trim() == "1"
}
