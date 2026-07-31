/**
 * Dialog offering to fix a broken Magisk environment.
 *
 * Shows different messages and actions based on the failure [code]:
 * - For a simple environment mismatch, triggers [MagiskInstaller.FixEnv].
 * - For a version mismatch or missing module policy, nudges the user to re-install Magisk.
 */
package pro.magisk.dialog

import android.widget.Toast
import androidx.core.os.postDelayed
import androidx.lifecycle.lifecycleScope
import pro.magisk.core.BuildConfig
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.ktx.reboot
import pro.magisk.core.ktx.toast
import pro.magisk.core.tasks.MagiskInstaller
import pro.magisk.events.DialogBuilder
import pro.magisk.ui.home.HomeViewModel
import pro.magisk.view.MagiskDialog
import com.topjohnwu.superuser.internal.UiThreadHandler
import kotlinx.coroutines.launch

/** Dialog for repairing the Magisk environment after detecting corruption/mismatch. */
class EnvFixDialog(private val vm: HomeViewModel, private val code: Int) : DialogBuilder {

    override fun build(dialog: MagiskDialog) {
        dialog.apply {
            setTitle(R.string.env_fix_title)
            setMessage(R.string.env_fix_msg)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = android.R.string.ok
                do_not_dismiss = true
                onClick {
                    dialog.apply {
                        setTitle(R.string.setup_title)
                        setMessage(R.string.setup_msg)
                        reset_buttons()
                        setCancelable(false)
                    }
                    dialog.activity.lifecycleScope.launch {
                        MagiskInstaller.FixEnv().exec { success ->
                            dialog.dismiss()
                            context.toast(
                                if (success) R.string.reboot_delay_toast else R.string.setup_fail,
                                Toast.LENGTH_LONG
                            )
                            if (success)
                                UiThreadHandler.handler.postDelayed(5000) { reboot() }
                        }
                    }
                }
            }
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = android.R.string.cancel
            }
        }

        // code == 2 = module policy not loaded; also handle version mismatches
        if (code == 2 ||
            Info.env.versionCode != BuildConfig.APP_VERSION_CODE ||
            Info.env.version_string != BuildConfig.APP_VERSION_NAME) {
            dialog.setMessage(R.string.env_full_fix_msg)
            dialog.setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = android.R.string.ok
                onClick {
                    vm.on_magisk_pressed()
                    dialog.dismiss()
                }
            }
        }
    }
}
