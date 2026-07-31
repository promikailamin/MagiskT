/**
 * Room entity for SU access log entries.
 *
 * Each row records a single SU request: the originating app,
 * the action taken, and metadata about the request context.
 */
package pro.magisk.core.model.su

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.room.Entity
import androidx.room.PrimaryKey
import pro.magisk.core.ktx.getLabel

@Entity(tableName = "logs")
class SuLog(
    val from_uid: Int,
    val to_uid: Int,
    val from_pid: Int,
    val packageName: String,
    val app_name: String,
    val command: String,
    val action: Int,
    val target: Int,
    val context: String,
    val gids: String,
    val time: Long = System.currentTimeMillis()
) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}

/** Create a [SuLog] from a resolved [ApplicationInfo]. */
fun PackageManager.create_su_log(
    info: ApplicationInfo,
    to_uid: Int,
    from_pid: Int,
    command: String,
    policy: Int,
    target: Int,
    context: String,
    gids: String,
): SuLog {
    return SuLog(
        from_uid = info.uid,
        to_uid = to_uid,
        from_pid = from_pid,
        packageName = getNameForUid(info.uid)!!,
        app_name = info.getLabel(this),
        command = command,
        action = policy,
        target = target,
        context = context,
        gids = gids,
    )
}

/** Create a [SuLog] when only the raw UID is known (no package info). */
fun create_su_log(
    from_uid: Int,
    to_uid: Int,
    from_pid: Int,
    command: String,
    policy: Int,
    target: Int,
    context: String,
    gids: String,
): SuLog {
    return SuLog(
        from_uid = from_uid,
        to_uid = to_uid,
        from_pid = from_pid,
        packageName = "[UID] $from_uid",
        app_name = "[UID] $from_uid",
        command = command,
        action = policy,
        target = target,
        context = context,
        gids = gids,
    )
}
