/**
 * Quick Settings tile that toggles the developer options menu
 * (`development_settings_enabled` global setting) through the root shell.
 */
package pro.magisk.core.view.qstile

class DeveloperOptionsTile : SettingToggleTile() {
    override val readCmd = "settings get global development_settings_enabled"
    override val writeCmd: (Boolean) -> String = {
        "settings put global development_settings_enabled ${if (it) 1 else 0}"
    }
}