/**
 * ViewModel for the home screen. Tracks Magisk installation state (up-to-date / outdated /
 * invalid), manages visibility of the safety notice, and triggers environment checks.
 */
package pro.magisk.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.ktx.await
import pro.magisk.core.ktx.toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pro.magisk.core.R as CoreR

class HomeViewModel : AsyncLoadViewModel() {

    data class UiState(
        val isNoticeVisible: Boolean = Config.safetyNotice,
        val showUninstall: Boolean = false,
        val showHideRestore: Boolean = false,
        val envFixCode: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    enum class State {
        LOADING, INVALID, OUTDATED, UP_TO_DATE
    }

    /** Computed Magisk installation state based on environment and app version. */
    val magiskState
        get() = when {
            Info.isRooted && Info.env.isUnsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

    /** Human-readable version string with optional debug marker. */
    val magiskInstalledVersion: String
        get() = Info.env.run {
            if (isActive)
                "$versionString ($versionCode)" + if (isDebug) " (D)" else ""
            else
                ""
        }

    companion object {
        /** Only run the env check once per process lifetime. */
        private var checkedEnv = false
    }

    override suspend fun doLoadWork() {
        ensureEnv()
    }

    /** Open a URL in an external browser. */
    fun onLinkPressed(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            AppContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppContext.toast(CoreR.string.open_link_failed_toast, Toast.LENGTH_SHORT)
        }
    }

    /** User tapped the uninstall button. */
    fun onDeletePressed() {
        _uiState.update { it.copy(showUninstall = true) }
    }

    fun onUninstallConsumed() {
        _uiState.update { it.copy(showUninstall = false) }
    }

    /** User tapped the hide/restore button. */
    fun onHideRestorePressed() {
        _uiState.update { it.copy(showHideRestore = true) }
    }

    fun onHideRestoreConsumed() {
        _uiState.update { it.copy(showHideRestore = false) }
    }

    fun onEnvFixConsumed() {
        _uiState.update { it.copy(envFixCode = 0) }
    }

    /** Dismiss the safety notice permanently. */
    fun hideNotice() {
        Config.safetyNotice = false
        _uiState.update { it.copy(isNoticeVisible = false) }
    }

    /** Run `env_check` via shell to verify the Magisk environment is consistent. */
    private suspend fun ensureEnv() {
        if (magiskState == State.INVALID || checkedEnv) return
        val cmd = "env_check ${Info.env.versionString} ${Info.env.versionCode}"
        val code = Shell.cmd(cmd).await().code
        if (code != 0) {
            _uiState.update { it.copy(envFixCode = code) }
        }
        checkedEnv = true
    }
}
