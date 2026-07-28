/**
 * Warning dialog shown before installing Magisk to the inactive (secondary) slot on
 * A/B partition devices.
 *
 * Informs the user that the installation targets the currently _inactive_ slot and will
 * only take effect after a reboot.
 */
package pro.magisk.dialog

import pro.magisk.core.R
import pro.magisk.events.DialogBuilder
import pro.magisk.view.MagiskDialog

/** Dialog warning about installing to the inactive A/B slot. */
class SecondSlotWarningDialog : DialogBuilder {

    override fun build(dialog: MagiskDialog) {
        dialog.apply {
            setTitle(android.R.string.dialog_alert_title)
            setMessage(R.string.install_inactive_slot_msg)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = android.R.string.ok
            }
            setCancelable(true)
        }
    }
}
