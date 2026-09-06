/**
 * SU access policy for a single UID.
 *
 * @property uid     Android UID the policy applies to.
 * @property policy  One of [QUERY], [DENY], [ALLOW], [RESTRICT].
 * @property remain  Remaining time in seconds (-1 = forever, 0 = single use).
 * @property logging Whether SU access for this UID is logged.
 * @property notification Whether a notification is shown.
 * @property packageName Package name of the app, used to remap the policy if the
 *               app is reinstalled under a new UID.
 */
package pro.magisk.core.model.su

import pro.magisk.core.data.magiskdb.MagiskDB

class SuPolicy(
    var uid: Int,
    var policy: Int = QUERY,
    var remain: Long = -1L,
    var logging: Boolean = true,
    var notification: Boolean = true,
    var locked: Boolean = false,
    var packageName: String? = null,
) {
    companion object {
        const val QUERY = 0
        const val DENY = 1
        const val ALLOW = 2
        const val RESTRICT = 3
    }

    /** Serialise to a map suitable for an SQL REPLACE query. */
    fun toMap(): MutableMap<String, Any> {
        val until = if (remain <= 0) {
            remain
        } else {
            MagiskDB.Literal("(strftime(\"%s\", \"now\") + $remain)")
        }
        val map = mutableMapOf(
            "uid" to uid,
            "policy" to policy,
            "until" to until,
            "logging" to logging,
            "notification" to notification,
            "locked" to locked
        )
        packageName?.let { map["package_name"] = it }
        return map
    }
}
