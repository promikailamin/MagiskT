/**
 * Confirmation dialog for revoking a superuser permission from an app.
 *
 * Requires explicit user confirmation before the policy is removed.
 */
package pro.magisk.dialog

import pro.magisk.core.R
import pro.magisk.events.DialogBuilder
import pro.magisk.view.MagiskDialog

/** Dialog confirming the revocation of root access for [appName]. */
class SuperuserRevokeDialog(
    private val app_name: String,
    private val on_success: () -> Unit
) : DialogBuilder {

    override fun build(dialog: MagiskDialog) {
        dialog.apply {
            setTitle(R.string.su_revoke_title)
            setMessage(R.string.su_revoke_msg, app_name)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = android.R.string.ok
                onClick { on_success() }
            }
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = android.R.string.cancel
            }
        }
    }
}
