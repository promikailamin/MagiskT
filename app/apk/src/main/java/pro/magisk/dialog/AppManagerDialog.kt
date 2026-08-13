/**
 * App info dialog for the App manager screen.
 *
 * Shows detailed information about the selected app (name, package name,
 * install/data directories, version, size, signature, UID, install time and
 * source) with Uninstall / Disable actions at the bottom.
 */
package pro.magisk.dialog

import pro.magisk.core.R
import pro.magisk.events.DialogBuilder
import pro.magisk.ui.appmanager.AppDetail
import pro.magisk.ui.appmanager.AppManagerRvItem
import pro.magisk.view.MagiskDialog

/** Dialog showing app details with uninstall/disable actions. */
class AppManagerDialog(
    private val item: AppManagerRvItem,
    private val detail: AppDetail,
    private val onUninstall: () -> Unit,
    private val onDisable: () -> Unit
) : DialogBuilder {

    override fun build(dialog: MagiskDialog) {
        val res = dialog.context.resources
        val dataDir1 = "/storage/emulated/0/Android/data/${item.packageName}"
        val dataDir2 = item.dataDir.ifBlank { "/data/data/${item.packageName}" }
        val message = buildString {
            appendLine("${res.getString(R.string.app_manager_info_name)}: ${item.label}")
            appendLine("${res.getString(R.string.app_manager_info_package)}: ${item.packageName}")
            appendLine("${res.getString(R.string.app_manager_info_install_dir)}: ${item.sourceDir}")
            appendLine("${res.getString(R.string.app_manager_info_data_dir1)}: $dataDir1")
            appendLine("${res.getString(R.string.app_manager_info_data_dir2)}: $dataDir2")
            appendLine("${res.getString(R.string.app_manager_info_version)}: ${detail.version}")
            appendLine("${res.getString(R.string.app_manager_info_size)}: ${detail.size}")
            appendLine("${res.getString(R.string.app_manager_info_signature)}: ${detail.signature}")
            appendLine("${res.getString(R.string.app_manager_info_uid)}: ${item.uid}")
            appendLine("${res.getString(R.string.app_manager_info_install_time)}: ${detail.installTime}")
            append(res.getString(R.string.app_manager_info_install_source) + ": " + detail.installSource)
        }
        dialog.apply {
            setTitle(R.string.app_manager_title)
            setMessage(message)
            setButton(MagiskDialog.ButtonType.POSITIVE) {
                text = R.string.app_manager_uninstall
                onClick { onUninstall() }
            }
            if (item.canDisable) {
                setButton(MagiskDialog.ButtonType.NEUTRAL) {
                    text = R.string.app_manager_disable
                    onClick { onDisable() }
                }
            }
            setButton(MagiskDialog.ButtonType.NEGATIVE) {
                text = android.R.string.cancel
            }
        }
    }
}
