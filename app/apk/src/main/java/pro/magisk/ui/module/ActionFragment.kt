/**
 * Action screen — executes a module's custom action script and streams the console output.
 *
 * Similar to [pro.magisk.ui.flash.FlashFragment] but for module-provided actions.
 * Locks orientation, captures volume keys, and disables back-press during execution.
 * On success, auto-navigates away after the window loses focus (user switched away).
 */
package pro.magisk.ui.module

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import pro.magisk.R
import pro.magisk.arch.BaseFragment
import pro.magisk.arch.view_model
import pro.magisk.core.ktx.toast
import pro.magisk.databinding.FragmentActionMd2Binding
import pro.magisk.core.R as CoreR

/** Fragment that runs a module action script and shows its output. */
class ActionFragment : BaseFragment<FragmentActionMd2Binding>(), MenuProvider {

    override val layout_res = R.layout.fragment_action_md2
    override val view_model by view_model<ActionViewModel>()
    override val snackbar_view: View get() = binding.snackbarContainer

    private var default_orientation = -1

    override fun onCreate(saved_instance_state: Bundle?) {
        super.onCreate(saved_instance_state)
        view_model.args = ActionFragmentArgs.fromBundle(requireArguments())
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(view_model.args.name)
        binding.closeBtn.setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        view_model.state.observe(this) {
            // Show the close button once execution finishes
            if (it != ActionViewModel.State.RUNNING) {
                binding.closeBtn.apply {
                    if (!this.isVisible) this.show()
                    if (!this.isFocused) this.requestFocus()
                }
            }
            // On success, navigate back when the user leaves the app
            if (it != ActionViewModel.State.SUCCESS) return@observe
            view?.viewTreeObserver?.addOnWindowFocusChangeListener(
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus) return
                        view?.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                        view?.context?.apply {
                            toast(
                                getString(CoreR.string.done_action, view_model.args.name),
                                Toast.LENGTH_SHORT
                            )
                        }
                        view_model.back()
                    }
                }
            )
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
            view_model.start_run_action()
        }
    }

    @SuppressLint("WrongConstant")
    override fun onDestroyView() {
        if (default_orientation != -1) {
            activity?.requestedOrientation = default_orientation
        }
        super.onDestroyView()
    }

    override fun on_key_event(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true

            else -> false
        }
    }

    override fun onBackPressed(): Boolean {
        if (view_model.state.value == ActionViewModel.State.RUNNING) return true
        return super.onBackPressed()
    }

    override fun on_pre_bind(binding: FragmentActionMd2Binding) = Unit
}
