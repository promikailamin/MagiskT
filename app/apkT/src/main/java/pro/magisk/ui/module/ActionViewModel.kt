/**
 * ViewModel for the module action runner. Waits for the [TerminalEmulator] to be created
 * (via [onEmulatorCreated]), then executes the module's action.sh script in a root PTY.
 */
package pro.magisk.ui.module

import androidx.lifecycle.viewModelScope
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.ktx.timeFormatStandard
import pro.magisk.core.ktx.toTime
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.outputStream
import pro.magisk.terminal.TerminalEmulator
import pro.magisk.terminal.runSuCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActionViewModel : BaseViewModel() {

    enum class State {
        RUNNING, SUCCESS, FAILED
    }

    private val _actionState = MutableStateFlow(State.RUNNING)
    val actionState: StateFlow<State> = _actionState.asStateFlow()

    var actionId: String = ""
    var actionName: String = ""

    private var emulator: TerminalEmulator? = null
    /** Deferred that resolves once the terminal emulator composable is ready. */
    private val emulatorReady = CompletableDeferred<TerminalEmulator>()

    /** Called by the terminal composable once it finishes initialization. */
    fun onEmulatorCreated(emu: TerminalEmulator) {
        emulator = emu
        emulatorReady.complete(emu)
    }

    /** Wait for the emulator and run the action script. */
    fun startRunAction() {
        viewModelScope.launch {
            val emu = emulatorReady.await()

            val success = withContext(Dispatchers.IO) {
                runSuCommand(
                    emu,
                    "cd /data/adb/modules/$actionId && sh ./action.sh"
                )
            }

            _actionState.value = if (success) State.SUCCESS else State.FAILED
        }
    }

    /** Save the terminal transcript to a file in the MediaStore. */
    fun saveLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "%s_action_log_%s.log".format(
                actionName,
                System.currentTimeMillis().toTime(timeFormatStandard)
            )
            val file = MediaStoreUtils.getFile(name)
            file.uri.outputStream().bufferedWriter().use { writer ->
                val transcript = emulator?.screen?.transcriptText
                if (transcript != null) {
                    writer.write(transcript)
                }
            }
            showSnackbar(file.toString())
        }
    }
}
