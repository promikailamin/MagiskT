/**
 * Dialog for uninstalling Magisk.
 *
 * Offers two options:
 * - Restore stock boot images (removes Magisk but keeps the app)
 * - Complete uninstall (removes everything, including the app)
 */
package pro.magisk.dialog

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import pro.magisk.arch.NavigationActivity
import pro.magisk.arch.UIActivity
import pro.magisk.core.R
import pro.magisk.core.ktx.toast
import pro.magisk.core.tasks.MagiskInstaller
import pro.magisk.events.DialogBuilder
import pro.magisk.ui.flash.FlashFragment
import pro.magisk.view.MagiskDialog
import kotlinx.coroutines.launch

/** Dialog with Restore / Complete Uninstall options for Magisk removal. */
class UninstallDialog : DialogBuilder {

    override fun build(dialog: MagiskDialog) {
        dialog.apply {
            setTitle(R.string.uninstall_magisk_title)
            setMessage(R.string.uninstall_magisk_msg)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = R.string.restore_img
                onClick { restore(dialog.activity) }
            }
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = R.string.complete_uninstall
                onClick { complete_uninstall(dialog) }
            }
        }
    }

    private fun restore(activity: UIActivity<*>) {
        val dialog = AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.restore_img_msg))
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            MagiskInstaller.Restore().exec { success ->
                dialog.dismiss()
                if (success) {
                    activity.toast(R.string.restore_done, Toast.LENGTH_SHORT)
                } else {
                    activity.toast(R.string.restore_fail, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun complete_uninstall(dialog: MagiskDialog) {
        (dialog.ownerActivity as NavigationActivity<*>)
            .navigation.navigate(FlashFragment.uninstall())
    }

}
