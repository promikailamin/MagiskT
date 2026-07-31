/**
 * ViewModel for the install method selection screen.
 *
 * Determines available installation methods based on device state (rooted, emulator,
 * SAR, A/B slots) and handles method selection (Direct, Patch, Inactive Slot).
 * The installation flow is multi-step; state is saved/restored across config changes.
 */
package pro.magisk.ui.install

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Spanned
import android.text.SpannedString
import android.widget.Toast
import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.BuildConfig.APP_VERSION_CODE
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.base.ContentResultCallback
import pro.magisk.core.ktx.toast
import pro.magisk.databinding.set
import pro.magisk.dialog.SecondSlotWarningDialog
import pro.magisk.events.GetContentEvent
import pro.magisk.ui.flash.FlashFragment
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.File
import java.io.IOException
import pro.magisk.core.R as CoreR

/** ViewModel for selecting and configuring the Magisk installation method. */
class InstallViewModel : BaseViewModel() {

    val is_rooted get() = Info.is_rooted
    val skip_options = Info.is_emulator || (Info.is_s_a_r && !Info.is_f_d_e && Info.ramdisk)
    val no_second_slot = !is_rooted || !Info.is_a_b || Info.is_emulator

    @get:Bindable
    var step = if (skip_options) 1 else 0
        set(value) = set(value, field, { field = it }, BR.step)

    private var method_id = -1

    @get:Bindable
    var method
        get() = method_id
        set(value) = set(value, method_id, { method_id = it }, BR.method) {
            when (it) {
                R.id.method_patch -> {
                    GetContentEvent("*/*", UriCallback()).publish()
                }
                R.id.method_inactive_slot -> {
                    SecondSlotWarningDialog().show()
                }
            }
        }

    val data: LiveData<Uri?> get() = uri

    @get:Bindable
    var notes: Spanned = SpannedString("")
        set(value) = set(value, field, { field = it }, BR.notes)

    fun install() {
        when (method) {
            R.id.method_patch -> FlashFragment.patch(data.value!!).navigate(true)
            R.id.method_direct -> FlashFragment.flash(false).navigate(true)
            R.id.method_inactive_slot -> FlashFragment.flash(true).navigate(true)
            else -> error("Unknown value")
        }
    }

    override fun on_save_state(state: Bundle) {
        state.putParcelable(
            INSTALL_STATE_KEY, InstallState(
                method_id,
                step,
                Config.keep_verity,
                Config.keep_enc,
                Config.recovery
            )
        )
    }

    override fun on_restore_state(state: Bundle) {
        state.getParcelable(INSTALL_STATE_KEY, InstallState::class.java)?.let {
            method_id = it.method
            step = it.step
            Config.keep_verity = it.keep_verity
            Config.keep_enc = it.keep_enc
            Config.recovery = it.recovery
        }
    }

    @Parcelize
    class UriCallback : ContentResultCallback {
        override fun on_activity_launch() {
            AppContext.toast(CoreR.string.patch_file_msg, Toast.LENGTH_LONG)
        }

        override fun onActivityResult(result: Uri) {
            uri.value = result
        }
    }

    @Parcelize
    class InstallState(
        val method: Int,
        val step: Int,
        val keep_verity: Boolean,
        val keep_enc: Boolean,
        val recovery: Boolean,
    ) : Parcelable

    companion object {
        private const val INSTALL_STATE_KEY = "install_state"
        private val uri = MutableLiveData<Uri?>()
    }
}
