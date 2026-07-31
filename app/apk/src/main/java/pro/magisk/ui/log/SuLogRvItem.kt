/**
 * A single Superuser access log entry displayed in the log viewer.
 *
 * Renders a formatted string showing: timestamp, target UID, source PID, target PID,
 * SELinux context, supplemental groups, and the command that triggered the request.
 * [isTop] and [isBottom] drive dividers in the list for visual grouping.
 */
package pro.magisk.ui.log

import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.AppContext
import pro.magisk.core.ktx.time_date_format
import pro.magisk.core.ktx.toTime
import pro.magisk.core.model.su.SuLog
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.databinding.set
import pro.magisk.core.R as CoreR

/** A RecyclerView item representing one Superuser access log entry. */
class SuLogRvItem(val log: SuLog) : ObservableRvItem(), DiffItem<SuLogRvItem> {

    override val layout_res = R.layout.item_log_access_md2

    val info = gen_info()

    @get:Bindable
    var is_top = false
        set(value) = set(value, field, { field = it }, BR.top)

    @get:Bindable
    var is_bottom = false
        set(value) = set(value, field, { field = it }, BR.bottom)

    override fun item_same_as(other: SuLogRvItem) = log.app_name == other.log.app_name

    /** Builds the human-readable log line from [SuLog] fields. */
    private fun gen_info(): String {
        val res = AppContext.resources
        val sb = StringBuilder()
        val date = log.time.toTime(time_date_format)
        val to_uid = res.getString(CoreR.string.target_uid, log.to_uid)
        val from_pid = res.getString(CoreR.string.pid, log.from_pid)
        sb.append("$date\n$to_uid  $from_pid")
        if (log.target != -1) {
            val pid = if (log.target == 0) "magiskd" else log.target.toString()
            val target = res.getString(CoreR.string.target_pid, pid)
            sb.append("  $target")
        }
        if (log.context.isNotEmpty()) {
            val context = res.getString(CoreR.string.selinux_context, log.context)
            sb.append("\n$context")
        }
        if (log.gids.isNotEmpty()) {
            val gids = res.getString(CoreR.string.supp_group, log.gids)
            sb.append("\n$gids")
        }
        sb.append("\n${log.command}")
        return sb.toString()
    }
}
