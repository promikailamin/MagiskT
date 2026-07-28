/**
 * Confirmation dialog shown before installing a local module ZIP file.
 *
 * On confirmation, navigates to the flash screen with the selected URI.
 */
package pro.magisk.dialog

import android.net.Uri
import pro.magisk.MainDirections
import pro.magisk.core.Const
import pro.magisk.core.R
import pro.magisk.events.DialogBuilder
import pro.magisk.ui.module.ModuleViewModel
import pro.magisk.view.MagiskDialog

/** Dialog that asks the user to confirm installing the selected module ZIP. */
class LocalModuleInstallDialog(
    private val viewModel: ModuleViewModel,
    private val uri: Uri,
    private val displayName: String
) : DialogBuilder {
    override fun build(dialog: MagiskDialog) {
        dialog.apply {
            setTitle(R.string.confirm_install_title)
            setMessage(context.getString(R.string.confirm_install, displayName))
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = android.R.string.ok
                onClick {
                    viewModel.apply {
                        MainDirections.actionFlashFragment(Const.Value.FLASH_ZIP, uri).navigate()
                    }
                }
            }
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = android.R.string.cancel
            }
        }
    }
}
