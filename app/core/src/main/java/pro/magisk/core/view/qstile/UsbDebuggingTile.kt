/**
 * Quick Settings tile that toggles USB debugging
 * (`adb_enabled` global setting) through the root shell.
 */
package pro.magisk.core.view.qstile

class UsbDebuggingTile : SettingToggleTile() {
    override val readCmd = "settings get global adb_enabled"
    override val writeCmd: (Boolean) -> String = {
        "settings put global adb_enabled ${if (it) 1 else 0}"
    }
}