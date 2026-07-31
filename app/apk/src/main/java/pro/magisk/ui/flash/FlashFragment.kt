/**
 * Flash/console screen — shows live output of Magisk operations (install, patch,
 * uninstall, module flash).
 *
 * Locks screen orientation during the operation, prevents back-press while flashing,
 * and captures volume keys for interactive console prompts. When flashing succeeds
 * and a reboot is appropriate, a "Restart" button is displayed.
 */
package pro.magisk.ui.flash

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.navigation.NavDeepLinkBuilder
import pro.magisk.MainDirections
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.core.Const
import pro.magisk.core.cmp
import pro.magisk.databinding.FragmentFlashMd2Binding
import pro.magisk.ui.MainActivity
import pro.magisk.core.R as CoreR

/** Fragment that streams console output for install/patch/uninstall operations. */
class FlashFragment : BaseFragment<FragmentFlashMd2Binding>(), MenuProvider {

    override val layout_res = R.layout.fragment_flash_md2
    override val view_model by view_model<FlashViewModel>()
    override val snackbar_view: View get() = binding.snackbarContainer
    override val snackbar_anchor_view: View?
        get() = if (binding.restartBtn.isShown) binding.restartBtn else super.snackbar_anchor_view

    private var default_orientation = -1

    override fun onCreate(saved_instance_state: Bundle?) {
        super.onCreate(saved_instance_state)
        view_model.args = FlashFragmentArgs.fromBundle(requireArguments())
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(CoreR.string.flash_screen_title)

        view_model.state.observe(this) {
            activity?.supportActionBar?.setSubtitle(
                when (it) {
                    FlashViewModel.State.FLASHING -> CoreR.string.flashing
                    FlashViewModel.State.SUCCESS -> CoreR.string.done
                    FlashViewModel.State.FAILED -> CoreR.string.failure
                }
            )
            // Show restart button on success when user has root
            if (it == FlashViewModel.State.SUCCESS && view_model.show_reboot) {
                binding.restartBtn.apply {
                    if (!this.isVisible) this.show()
                    if (!this.isFocused) this.requestFocus()
                }
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_flash, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return view_model.on_menu_item_clicked(item)
    }

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)

        default_orientation = activity?.requestedOrientation ?: -1
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        if (saved_instance_state == null) {
            view_model.start_flashing()
        }
    }

    @SuppressLint("WrongConstant")
    override fun onDestroyView() {
        if (default_orientation != -1) {
            activity?.requestedOrientation = default_orientation
        }
        super.onDestroyView()
    }

    // Capture volume keys so the flashing script can use them for prompts
    override fun on_key_event(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> false
        }
    }

    // Disable back-press while a flash operation is in progress
    override fun onBackPressed(): Boolean {
        if (view_model.flashing.value == true)
            return true
        return super.onBackPressed()
    }

    override fun on_pre_bind(binding: FragmentFlashMd2Binding) = Unit

    companion object {

        private fun createIntent(context: Context, args: FlashFragmentArgs) =
            NavDeepLinkBuilder(context)
                .setGraph(R.navigation.main)
                .setComponentName(MainActivity::class.java.cmp(context.packageName))
                .setDestination(R.id.flashFragment)
                .setArguments(args.toBundle())
                .createPendingIntent()

        private fun flash_type(is_second_slot: Boolean) =
            if (is_second_slot) Const.Value.FLASH_INACTIVE_SLOT else Const.Value.FLASH_MAGISK

        /* Flashing is understood as installing / flashing magisk itself */

        fun flash(is_second_slot: Boolean) = MainDirections.actionFlashFragment(
            action = flash_type(is_second_slot)
        )

        /* Patching is understood as injecting img files with magisk */

        fun patch(uri: Uri) = MainDirections.actionFlashFragment(
            action = Const.Value.PATCH_FILE,
            additionalData = uri
        )

        /* Uninstalling is understood as removing magisk entirely */

        fun uninstall() = MainDirections.actionFlashFragment(
            action = Const.Value.UNINSTALL
        )

        /* Installing is understood as flashing modules / zips */

        fun install_intent(context: Context, file: Uri) = FlashFragmentArgs(
            action = Const.Value.FLASH_ZIP,
            additionalData = file,
        ).let { createIntent(context, it) }

        fun install(file: Uri) = MainDirections.actionFlashFragment(
            action = Const.Value.FLASH_ZIP,
            additionalData = file,
        )
    }

}
