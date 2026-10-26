/**
 * ViewModel for the home screen.
 *
 * Determines the Magisk installation state (LOADING / INVALID / OUTDATED / UP_TO_DATE),
 * runs an environment check via shell, and offers actions for links, uninstall, and
 * Magisk installation navigation. The safety notice visibility is backed by [Config].
 */
package pro.magisk.ui.home

import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.ActivityExecutor
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.arch.ContextExecutor
import pro.magisk.arch.UIActivity
import pro.magisk.arch.ViewEvent
import pro.magisk.core.AppContext
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.ktx.await
import pro.magisk.core.ktx.toast
import pro.magisk.core.update.AppUpdater
import pro.magisk.core.update.InstallResult
import pro.magisk.core.update.UpdateInfo
import pro.magisk.core.utils.asText
import pro.magisk.databinding.bindExtra
import pro.magisk.databinding.set
import pro.magisk.dialog.EnvFixDialog
import pro.magisk.dialog.UninstallDialog
import pro.magisk.events.SnackbarEvent
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import timber.log.Timber
import pro.magisk.core.R as CoreR

/** ViewModel for the home tab — Magisk version/status and actions. */
class HomeViewModel : AsyncLoadViewModel() {

    enum class State {
        LOADING, INVALID, OUTDATED, UP_TO_DATE
    }

    val extraBindings = bindExtra {
        it.put(BR.viewModel, this)
    }
    val magiskTitleBarrierIds =
        intArrayOf(R.id.home_magisk_icon, R.id.home_magisk_title, R.id.home_magisk_button)
    @get:Bindable
    var isNoticeVisible = Config.safetyNotice
        set(value) = set(value, field, { field = it }, BR.noticeVisible)

    @get:Bindable
    var updateAvailable = false
        private set(value) = set(value, field, { field = it }, BR.updateAvailable)

    @get:Bindable
    var updating = false
        private set(value) = set(value, field, { field = it }, BR.updating)

    @get:Bindable
    var updateProgress = 0
        private set(value) = set(value, field, { field = it }, BR.updateProgress)

    @get:Bindable
    var updateStatus = ""
        private set(value) = set(value, field, { field = it }, BR.updateStatus)

    private var pendingUpdate: UpdateInfo? = null

    val magiskState
        get() = when {
            Info.isRooted && Info.env.isUnsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

    val magiskInstalledVersion
        get() = Info.env.run {
            if (isActive)
                ("$versionString ($versionCode)" + if (isDebug) " (D)" else "").asText()
            else
                CoreR.string.not_available.asText()
        }

    override suspend fun doLoadWork() {
        ensureEnv()
        checkForUpdates()
    }

    fun onLinkPressed(link: String) = object : ViewEvent(), ContextExecutor {
        override fun invoke(context: Context) {
            val intent = Intent(Intent.ACTION_VIEW, link.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.toast(CoreR.string.open_link_failed_toast, Toast.LENGTH_SHORT)
            }
        }
    }.publish()

    fun onDeletePressed() = UninstallDialog().show()

    fun onMagiskPressed() = withExternalRW {
        HomeFragmentDirections.actionHomeFragmentToInstallFragment().navigate()
    }

    fun hideNotice() {
        Config.safetyNotice = false
        isNoticeVisible = false
    }

    /** Checks the rolling release matching this APK's channel. */
    private suspend fun checkForUpdates() {
        if (updating) return
        val info = AppUpdater.checkForUpdate(AppContext) ?: return
        pendingUpdate = info
        val channel = AppContext.getString(
            if (info.channel == AppUpdater.CHANNEL_TEST)
                CoreR.string.update_channel_test
            else
                CoreR.string.update_channel_main
        )
        updateStatus = AppContext.getString(
            CoreR.string.update_available_summary,
            channel,
            info.commit,
            BuildConfig.BUILD_COMMIT
        )
        updateAvailable = true
    }

    /** Downloads the pending update, then installs it through `pm install` or the system installer. */
    @SuppressLint("InlinedApi")
    fun onUpdatePressed() {
        val info = pendingUpdate ?: return
        if (updating) return
        updating = true
        updateProgress = 0
        updateStatus = AppContext.getString(CoreR.string.update_downloading, 0)

        viewModelScope.launch {
            try {
                val apk = AppUpdater.download(AppContext, info) { progress ->
                    updateProgress = progress
                    updateStatus = AppContext.getString(CoreR.string.update_downloading, progress)
                }
                if (AppUpdater.canInstallSilently()) {
                    installUpdate(apk)
                } else {
                    withPermission(REQUEST_INSTALL_PACKAGES) {
                        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                            AppContext.packageManager.canRequestPackageInstalls()
                        if (!allowed) {
                            updating = false
                            SnackbarEvent(CoreR.string.install_unknown_denied).publish()
                        } else {
                            viewModelScope.launch { installUpdate(apk) }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "update download failed")
                updating = false
                updateStatus = ""
                SnackbarEvent(CoreR.string.update_download_failed).publish()
            }
        }
    }

    private suspend fun installUpdate(apk: java.io.File) {
        try {
            updateStatus = AppContext.getString(CoreR.string.update_installing)
            when (AppUpdater.install(AppContext, apk)) {
                InstallResult.INSTALLED -> {
                    updateStatus = AppContext.getString(CoreR.string.update_installed)
                    updateAvailable = false
                    pendingUpdate = null
                }
                InstallResult.USER_ACTION_REQUIRED -> {
                    updateStatus = AppContext.getString(CoreR.string.update_install_started)
                    updateAvailable = false
                    pendingUpdate = null
                }
                InstallResult.FAILED -> {
                    updateStatus = ""
                    SnackbarEvent(CoreR.string.update_install_failed).publish()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "update install failed")
            updateStatus = ""
            SnackbarEvent(CoreR.string.update_install_failed).publish()
        } finally {
            updating = false
        }
    }

    private var checkedEnv = false

    /** Runs `env_check` via shell; shows [EnvFixDialog] on non-zero exit. */
    private suspend fun ensureEnv() {
        if (magiskState == State.INVALID || checkedEnv) return
        val cmd = "env_check ${Info.env.versionString} ${Info.env.versionCode}"
        val code = Shell.cmd(cmd).await().code
        if (code != 0) {
            EnvFixDialog(this, code).show()
        }
        checkedEnv = true
    }

    val showTest = false
    fun onTestPressed() = object : ViewEvent(), ActivityExecutor {
        override fun invoke(activity: UIActivity<*>) {
            /* Entry point to trigger test events within the app */
        }
    }.publish()
}
