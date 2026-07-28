/**
 * ViewModel for the flash/install screen. Supports multiple installation modes:
 * direct install, module ZIP flashing (via terminal emulator), uninstall, patch file,
 * and inactive-slot install. Collects console output either from a PTY terminal or
 * from a [CallbackList] emitted by [MagiskInstaller].
 */
package pro.magisk.ui.flash

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.core.net.toFile
import androidx.lifecycle.viewModelScope
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.ktx.reboot
import pro.magisk.core.ktx.synchronized
import pro.magisk.core.ktx.timeFormatStandard
import pro.magisk.core.ktx.toTime
import pro.magisk.core.ktx.writeTo
import pro.magisk.core.tasks.MagiskInstaller
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.displayName
import pro.magisk.core.utils.MediaStoreUtils.inputStream
import pro.magisk.core.utils.MediaStoreUtils.outputStream
import pro.magisk.terminal.TerminalEmulator
import pro.magisk.terminal.appendLineOnMain
import pro.magisk.terminal.runSuCommand
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class FlashViewModel : BaseViewModel() {

    enum class State {
        FLASHING, SUCCESS, FAILED
    }

    private val _flashState = MutableStateFlow(State.FLASHING)
    val flashState: StateFlow<State> = _flashState.asStateFlow()

    private val _showReboot = MutableStateFlow(Info.isRooted)
    val showReboot: StateFlow<Boolean> = _showReboot.asStateFlow()

    var flashAction: String = ""
    var flashUri: Uri? = null

    // --- TerminalScreen mode (FLASH_ZIP) ---

    private var emulator: TerminalEmulator? = null
    private val emulatorReady = CompletableDeferred<TerminalEmulator>()

    fun onEmulatorCreated(emu: TerminalEmulator) {
        emulator = emu
        emulatorReady.complete(emu)
    }

    // --- LazyColumn mode (MagiskInstaller) ---

    /** Console lines displayed in the simple text-based flash UI. */
    val consoleItems = mutableStateListOf<String>()
    private val logItems = mutableListOf<String>().synchronized()
    private val outItems = object : CallbackList<String>() {
        override fun onAddElement(e: String?) {
            e ?: return
            consoleItems.add(e)
            logItems.add(e)
        }
    }

    // --- Shared ---

    /** Dispatch to the correct flash handler based on [flashAction]. */
    fun startFlashing() {
        val action = flashAction
        val uri = flashUri

        viewModelScope.launch {
            when (action) {
                Const.Value.FLASH_ZIP -> {
                    uri ?: return@launch
                    flashZip(uri)
                }
                Const.Value.UNINSTALL -> {
                    _showReboot.value = false
                    onResult(withContext(Dispatchers.IO) {
                        MagiskInstaller.Uninstall(outItems, logItems).exec()
                    })
                }
                Const.Value.FLASH_MAGISK -> {
                    onResult(withContext(Dispatchers.IO) {
                        if (Info.isEmulator)
                            MagiskInstaller.Emulator(outItems, logItems).exec()
                        else
                            MagiskInstaller.Direct(outItems, logItems).exec()
                    })
                }
                Const.Value.FLASH_INACTIVE_SLOT -> {
                    _showReboot.value = false
                    onResult(withContext(Dispatchers.IO) {
                        MagiskInstaller.SecondSlot(outItems, logItems).exec()
                    })
                }
                Const.Value.PATCH_FILE -> {
                    uri ?: return@launch
                    _showReboot.value = false
                    onResult(withContext(Dispatchers.IO) {
                        MagiskInstaller.Patch(uri, outItems, logItems).exec()
                    })
                }
            }
        }
    }

    private fun onResult(success: Boolean) {
        _flashState.value = if (success) State.SUCCESS else State.FAILED
    }

    /**
     * Flash a module ZIP. Copies the zip to cache, extracts the installer script from assets,
     * then runs it in a root PTY via busybox script.
     */
    private suspend fun flashZip(uri: Uri) {
        val emu = emulatorReady.await()

        val installDir = File(AppContext.cacheDir, "flash")
        val result = withContext(Dispatchers.IO) {
            try {
                installDir.deleteRecursively()
                installDir.mkdirs()

                val zipFile = if (uri.scheme == "file") {
                    uri.toFile()
                } else {
                    File(installDir, "install.zip").also {
                        try {
                            uri.inputStream().writeTo(it)
                        } catch (e: IOException) {
                            val msg = if (e is FileNotFoundException) "Invalid Uri" else "Cannot copy to cache"
                            return@withContext msg to null
                        }
                    }
                }

                val binary = File(installDir, "update-binary")
                AppContext.assets.open("module_installer.sh").use { it.writeTo(binary) }

                val name = uri.displayName
                null to Triple(installDir, zipFile, name)
            } catch (e: IOException) {
                Timber.e(e)
                "Unable to extract files" to null
            }
        }

        val (error, prepResult) = result
        if (prepResult == null) {
            emu.appendLineOnMain("! ${error ?: "Installation failed"}")
            _flashState.value = State.FAILED
            return
        }

        val (dir, zipFile, displayName) = prepResult

        val success = withContext(Dispatchers.IO) {
            runSuCommand(
                emu,
                "echo '- Installing $displayName'; " +
                "sh $dir/update-binary dummy 1 '${zipFile.absolutePath}'; " +
                "EXIT=\$?; " +
                "if [ \$EXIT -ne 0 ]; then echo '! Installation failed'; fi; " +
                "exit \$EXIT"
            )
        }

        Shell.cmd("cd /", "rm -rf $dir ${Const.TMPDIR}").submit()
        _flashState.value = if (success) State.SUCCESS else State.FAILED
    }

    /** Save the flash/install log to a file in the MediaStore. */
    fun saveLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "magisk_install_log_%s.log".format(
                System.currentTimeMillis().toTime(timeFormatStandard)
            )
            val file = MediaStoreUtils.getFile(name)
            file.uri.outputStream().bufferedWriter().use { writer ->
                val transcript = emulator?.screen?.transcriptText
                if (transcript != null) {
                    writer.write(transcript)
                } else {
                    synchronized(logItems) {
                        logItems.forEach {
                            writer.write(it)
                            writer.newLine()
                        }
                    }
                }
            }
            showSnackbar(file.toString())
        }
    }

    /** Initiate a reboot. */
    fun restartPressed() = reboot()
}
