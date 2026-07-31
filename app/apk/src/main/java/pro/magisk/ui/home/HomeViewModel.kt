/**
 * ViewModel for the home screen.
 *
 * Determines the Magisk installation state (LOADING / INVALID / OUTDATED / UP_TO_DATE),
 * runs an environment check via shell, and offers actions for links, uninstall, and
 * Magisk installation navigation. The safety notice visibility is backed by [Config].
 */
package pro.magisk.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.arch.ActivityExecutor
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.arch.ContextExecutor
import pro.magisk.arch.UIActivity
import pro.magisk.arch.ViewEvent
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.ktx.await
import pro.magisk.core.ktx.toast
import pro.magisk.core.utils.asText
import pro.magisk.databinding.bind_extra
import pro.magisk.databinding.set
import pro.magisk.dialog.EnvFixDialog
import pro.magisk.dialog.UninstallDialog
import pro.magisk.events.SnackbarEvent
import com.topjohnwu.superuser.Shell
import kotlin.math.roundToInt
import pro.magisk.core.R as CoreR

/** ViewModel for the home tab — Magisk version/status and actions. */
class HomeViewModel : AsyncLoadViewModel() {

    enum class State {
        LOADING, INVALID, OUTDATED, UP_TO_DATE
    }

    val extra_bindings = bind_extra {
        it.put(BR.view_model, this)
    }
    val magisk_title_barrier_ids =
        intArrayOf(R.id.home_magisk_icon, R.id.home_magisk_title, R.id.home_magisk_button)
    @get:Bindable
    var is_notice_visible = Config.safety_notice
        set(value) = set(value, field, { field = it }, BR.noticeVisible)

    val magisk_state
        get() = when {
            Info.is_rooted && Info.env.is_unsupported -> State.OUTDATED
            !Info.env.isActive -> State.INVALID
            Info.env.versionCode < BuildConfig.APP_VERSION_CODE -> State.OUTDATED
            else -> State.UP_TO_DATE
        }

    val magisk_installed_version
        get() = Info.env.run {
            if (isActive)
                ("$version_string ($versionCode)" + if (is_debug) " (D)" else "").asText()
            else
                CoreR.string.not_available.asText()
        }

    override suspend fun do_load_work() {
        ensure_env()
    }

    fun on_link_pressed(link: String) = object : ViewEvent(), ContextExecutor {
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

    fun on_delete_pressed() = UninstallDialog().show()

    fun on_settings_pressed() {
        HomeFragmentDirections.actionHomeFragmentToSettingsFragment().navigate()
    }

    fun on_magisk_pressed() = with_external_r_w {
        HomeFragmentDirections.actionHomeFragmentToInstallFragment().navigate()
    }

    fun hide_notice() {
        Config.safety_notice = false
        is_notice_visible = false
    }

    private var checked_env = false

    /** Runs `env_check` via shell; shows [EnvFixDialog] on non-zero exit. */
    private suspend fun ensure_env() {
        if (magisk_state == State.INVALID || checked_env) return
        val cmd = "env_check ${Info.env.version_string} ${Info.env.versionCode}"
        val code = Shell.cmd(cmd).await().code
        if (code != 0) {
            EnvFixDialog(this, code).show()
        }
        checked_env = true
    }

    val show_test = false
    fun on_test_pressed() = object : ViewEvent(), ActivityExecutor {
        override fun invoke(activity: UIActivity<*>) {
            /* Entry point to trigger test events within the app */
        }
    }.publish()
}
