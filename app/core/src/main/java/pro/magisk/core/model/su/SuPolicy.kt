/**
 * SU access policy for a single UID.
 *
 * @property uid     Android UID the policy applies to.
 * @property policy  One of [QUERY], [DENY], [ALLOW], [RESTRICT].
 * @property remain  Remaining time in seconds (-1 = forever, 0 = single use).
 * @property logging Whether SU access for this UID is logged.
 * @property notification Whether a notification is shown.
 */
package pro.magisk.core.model.su

import pro.magisk.core.data.magiskdb.MagiskDB

class SuPolicy(
    val uid: Int,
    var policy: Int = QUERY,
    var remain: Long = -1L,
    var logging: Boolean = true,
    var notification: Boolean = true,
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
        return mutableMapOf(
            "uid" to uid,
            "policy" to policy,
            "until" to until,
            "logging" to logging,
            "notification" to notification
        )
    }
}
