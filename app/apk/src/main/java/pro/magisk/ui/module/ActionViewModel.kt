/**
 * ViewModel for the module action runner screen.
 *
 * Executes a module's custom action script (`run_action`) via shell and streams the
 * console output. Supports saving the action log to a file, similar to [FlashViewModel].
 */
package pro.magisk.ui.module

import android.view.MenuItem
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import pro.magisk.R
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.ktx.synchronized
import pro.magisk.core.ktx.time_format_standard
import pro.magisk.core.ktx.toTime
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.output_stream
import pro.magisk.events.SnackbarEvent
import pro.magisk.ui.flash.ConsoleItem
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/** ViewModel that runs a module action script and exposes its live console output. */
class ActionViewModel : BaseViewModel() {

    enum class State {
        RUNNING, SUCCESS, FAILED
    }

    private val _state = MutableLiveData(State.RUNNING)
    val state: LiveData<State> get() = _state

    val items = ObservableArrayList<ConsoleItem>()
    lateinit var args: ActionFragmentArgs

    private val log_items = mutableListOf<String>().synchronized()
    private val out_items = object : CallbackList<String>() {
        override fun onAddElement(e: String?) {
            e ?: return
            items.add(ConsoleItem(e))
            log_items.add(e)
        }
    }

    fun start_run_action() = viewModelScope.launch {
        on_result(withContext(Dispatchers.IO) {
            try {
                Shell.cmd("run_action \'${args.id}\'")
                    .to(out_items, log_items)
                    .exec().is_success
            } catch (e: IOException) {
                Timber.e(e)
                false
            }
        })
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

    private fun save_pressed() = with_external_r_w {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "%s_action_log_%s.log".format(
                args.name,
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
}
