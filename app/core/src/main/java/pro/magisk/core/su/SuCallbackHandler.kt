/**
 * Handles SU-related callbacks invoked by the Magisk daemon via
 * [Provider.call].
 *
 * Two actions are supported:
 * - **log** — a SU request was processed; persist the log entry
 *   and optionally notify the user.
 * - **notify** — a SU request was handled by another process;
 *   just show a notification / toast.
 */
package pro.magisk.core.su

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import pro.magisk.core.AppContext
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.R
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.ktx.getLabel
import pro.magisk.core.ktx.getPackageInfo
import pro.magisk.core.ktx.toast
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.core.model.su.create_su_log
import pro.magisk.view.Notifications
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object SuCallbackHandler {

    const val REQUEST = "request"
    const val LOG = "log"
    const val NOTIFY = "notify"

    /**
     * Dispatch a callback from the Magisk daemon.
     *
     * @param context Caller context.
     * @param action  One of [LOG] or [NOTIFY].
     * @param data    Bundle with uid, pid, policy, etc.
     */
    fun run(context: Context, action: String?, data: Bundle?) {
        data ?: return

        if (BuildConfig.DEBUG) {
            @Suppress("DEPRECATION")
            Timber.d(action)
            data.let { bundle ->
                bundle.keySet().forEach {
                    @Suppress("DEPRECATION")
                    Timber.d("[%s]=[%s]", it, bundle[it])
                }
            }
        }

        when (action) {
            LOG -> handle_logging(context, data)
            NOTIFY -> handle_notify(context, data)
        }
    }

    /**
     * Get an int from a Bundle, handling the case where the value
     * was serialised as a Long. See
     * https://android.googlesource.com/platform/frameworks/base/+/547bf5487d52b93c9fe183aa6d56459c170b17a4
     */
    private fun Bundle.getIntComp(key: String, defaultValue: Int): Int {
        @Suppress("DEPRECATION")
        val value = get(key) ?: return defaultValue
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> defaultValue
        }
    }

    /** Handle a log callback: persist the entry and optionally notify. */
    private fun handle_logging(context: Context, data: Bundle) {
        val from_uid = data.getIntComp("from.uid", -1)
        val notify = data.getBoolean("notify", true)
        val policy = data.getIntComp("policy", SuPolicy.ALLOW)
        val to_uid = data.getIntComp("to.uid", -1)
        val pid = data.getIntComp("pid", -1)
        val command = data.getString("command", "")
        val target = data.getIntComp("target", -1)
        val se_context = data.getString("context", "")
        val gids = data.getString("gids", "")

        val pm = context.packageManager

        val log = runCatching {
            pm.getPackageInfo(from_uid, pid)?.applicationInfo?.let {
                pm.create_su_log(it, to_uid, pid, command, policy, target, se_context, gids)
            }
        }.getOrNull() ?: create_su_log(from_uid, to_uid, pid, command, policy, target, se_context, gids)

        runBlocking { ServiceLocator.log_repo.insert(log) }

        if (notify || Config.su_notification == Config.Value.NOTIFICATION_STATUS_BAR)
            notify(context, log.action >= SuPolicy.ALLOW, log.app_name)
        SuEvents.notify_log_updated()
        SuEvents.notify_policy_changed()
    }

    /** Handle a notify callback: show a toast or notification. */
    private fun handle_notify(context: Context, data: Bundle) {
        val uid = data.getIntComp("from.uid", -1)
        val pid = data.getIntComp("pid", -1)
        val policy = data.getIntComp("policy", SuPolicy.ALLOW)

        val pm = context.packageManager

        val app_name = runCatching {
            pm.getPackageInfo(uid, pid)?.applicationInfo?.getLabel(pm)
        }.getOrNull() ?: "[UID] $uid"

        notify(context, policy >= SuPolicy.ALLOW, app_name)
        SuEvents.notify_policy_changed()
    }

    /** Notify the user (toast or status-bar notification) using [AppContext]. */
    fun notify(granted: Boolean, app_name: String) {
        when (Config.su_notification) {
            Config.Value.NOTIFICATION_TOAST -> {
                val res_id = if (granted) R.string.su_allow_toast else R.string.su_deny_toast
                AppContext.toast(AppContext.getString(res_id, app_name), Toast.LENGTH_SHORT)
            }
            Config.Value.NOTIFICATION_STATUS_BAR -> {
                Notifications.su_notification(granted, app_name)
            }
        }
    }

    /** Notify the user (toast or status-bar notification) using the provided context. */
    private fun notify(context: Context, granted: Boolean, app_name: String) {
        when (Config.su_notification) {
            Config.Value.NOTIFICATION_TOAST -> {
                val res_id = if (granted) R.string.su_allow_toast else R.string.su_deny_toast
                context.toast(context.getString(res_id, app_name), Toast.LENGTH_SHORT)
            }
            Config.Value.NOTIFICATION_STATUS_BAR -> {
                Notifications.su_notification(granted, app_name)
            }
        }
    }
}
