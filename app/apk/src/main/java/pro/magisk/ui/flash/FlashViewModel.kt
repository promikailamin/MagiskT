/**
 * ViewModel for the flash/console screen.
 *
 * Dispatches the flash action (install ZIP, uninstall, direct install, second-slot,
 * or patch file) to the appropriate [MagiskInstaller] or [FlashZip] task. Live console
 * output is collected via [CallbackList] and exposed as [ObservableArrayList] for the
 * RecyclerView. The log can be saved to a file, and the device can be rebooted on success.
 */
package pro.magisk.ui.flash

import android.os.Build
import android.view.MenuItem
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.ktx.reboot
import pro.magisk.core.ktx.synchronized
import pro.magisk.core.ktx.time_format_standard
import pro.magisk.core.ktx.toTime
import pro.magisk.core.tasks.FlashZip
import pro.magisk.core.tasks.MagiskInstaller
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.output_stream
import pro.magisk.databinding.set
import pro.magisk.events.SnackbarEvent
import com.topjohnwu.superuser.CallbackList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** ViewModel that orchestrates Magisk flash/install/patch/uninstall operations. */
class FlashViewModel : BaseViewModel() {

    enum class State {
        FLASHING, SUCCESS, FAILED
    }

    private val _state = MutableLiveData(State.FLASHING)
    val state: LiveData<State> get() = _state
    val flashing = state.map { it == State.FLASHING }

    @get:Bindable
    var show_reboot = Info.is_rooted
        set(value) = set(value, field, { field = it }, BR.show_reboot)

    val items = ObservableArrayList<ConsoleItem>()
    lateinit var args: FlashFragmentArgs

    private val log_items = mutableListOf<String>().synchronized()
    private val out_items = object : CallbackList<String>() {
        override fun onAddElement(e: String?) {
            e ?: return
            items.add(ConsoleItem(e))
            log_items.add(e)
        }
    }

    fun start_flashing() {
        val (action, uri) = args

        viewModelScope.launch {
            val result = when (action) {
                Const.Value.FLASH_ZIP -> {
                    uri ?: return@launch
                    FlashZip(uri, out_items, log_items).exec()
                }
                Const.Value.UNINSTALL -> {
                    show_reboot = false
                    MagiskInstaller.Uninstall(out_items, log_items).exec()
                }
                Const.Value.FLASH_MAGISK -> {
                    if (Info.is_emulator)
                        MagiskInstaller.Emulator(out_items, log_items).exec()
                    else
                        MagiskInstaller.Direct(out_items, log_items).exec()
                }
                Const.Value.FLASH_INACTIVE_SLOT -> {
                    show_reboot = false
                    MagiskInstaller.SecondSlot(out_items, log_items).exec()
                }
                Const.Value.PATCH_FILE -> {
                    uri ?: return@launch
                    show_reboot = false
                    MagiskInstaller.Patch(uri, out_items, log_items).exec()
                }
                else -> {
                    back()
                    return@launch
                }
            }
            on_result(result)
        }
    }

    private fun on_result(success: Boolean) {
        _state.value = if (success) State.SUCCESS else State.FAILED
    }

    fun on_menu_item_clicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> save_pressed()
        }
        return true
    }

    /** Saves the console log to a file in the MediaStore. */
    private fun save_pressed() = with_external_r_w {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "magisk_install_log_%s.log".format(
                System.currentTimeMillis().toTime(time_format_standard)
            )
            val file = MediaStoreUtils.get_file(name)
            file.uri.output_stream().bufferedWriter().use { writer ->
                synchronized(log_items) {
                    log_items.forEach {
                        writer.write(it)
                        writer.newLine()
                    }
                }
            }
            SnackbarEvent(file.toString()).publish()
        }
    }

    fun restart_pressed() = reboot()
}
