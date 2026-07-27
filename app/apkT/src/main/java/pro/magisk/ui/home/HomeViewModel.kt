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
import pro.magisk.core.repository.NetworkService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pro.magisk.core.R as CoreR

class HomeViewModel(
    private val svc: NetworkService
) : AsyncLoadViewModel() {

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

    val magiskState
        get() = when {
            Info.isRooted && Info.env.isUnsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

    val magiskInstalledVersion: String
        get() = Info.env.run {
            if (isActive)
                "$versionString ($versionCode)" + if (isDebug) " (D)" else ""
            else
                ""
        }

    companion object {
        private var checkedEnv = false
    }

    override suspend fun doLoadWork() {
        ensureEnv()
    }

    private val networkObserver: (Boolean) -> Unit = { startLoading() }

    init {
        Info.isConnected.observeForever(networkObserver)
    }

    override fun onCleared() {
        super.onCleared()
        Info.isConnected.removeObserver(networkObserver)
    }

    fun onLinkPressed(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            AppContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppContext.toast(CoreR.string.open_link_failed_toast, Toast.LENGTH_SHORT)
        }
    }

    fun onDeletePressed() {
        _uiState.update { it.copy(showUninstall = true) }
    }

    fun onUninstallConsumed() {
        _uiState.update { it.copy(showUninstall = false) }
    }

    fun onHideRestorePressed() {
        _uiState.update { it.copy(showHideRestore = true) }
    }

    fun onHideRestoreConsumed() {
        _uiState.update { it.copy(showHideRestore = false) }
    }

    fun onEnvFixConsumed() {
        _uiState.update { it.copy(envFixCode = 0) }
    }

    fun hideNotice() {
        Config.safetyNotice = false
        _uiState.update { it.copy(isNoticeVisible = false) }
    }

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
