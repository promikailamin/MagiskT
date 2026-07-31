/**
 * DAO for the `policies` table in MagiskDB.
 *
 * Provides CRUD operations for [SuPolicy] entries, including
 * automatic cleanup of expired entries and backwards-compatible
 * `package_name` column handling.
 */
package pro.magisk.core.data.magiskdb

import pro.magisk.core.AppContext
import pro.magisk.core.Const
import pro.magisk.core.model.su.SuPolicy

private const val SELECT_QUERY = "SELECT (until - strftime(\"%s\", \"now\")) AS remain, *"

class PolicyDao : MagiskDB() {

    /** Remove expired and negative-`until` entries. */
    suspend fun delete_outdated() {
        val query = "DELETE FROM ${Table.POLICY} WHERE " +
            "(until > 0 AND until < strftime(\"%s\", \"now\")) OR until < 0"
        exec(query)
    }

    /** Delete the policy for a given [uid]. */
    suspend fun delete(uid: Int) {
        val query = "DELETE FROM ${Table.POLICY} WHERE uid=$uid"
        exec(query)
    }

    /** Fetch the policy for a given [uid], or null if none exists. */
    suspend fun fetch(uid: Int): SuPolicy? {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid=$uid LIMIT 1"
        return exec(query, ::to_policy).firstOrNull()
    }

    /** Insert or replace the given [policy]. */
    suspend fun update(policy: SuPolicy) {
        val map = policy.to_map()
        if (!Const.Version.atLeast_25_0()) {
            map["package_name"] = AppContext.packageManager.getNameForUid(policy.uid)!!
        }
        val query = "REPLACE INTO ${Table.POLICY} ${map.toQuery()}"
        exec(query)
    }

    /** Fetch all policies for the current user. */
    suspend fun fetch_all(): List<SuPolicy> {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid/100000=${Const.USER_ID}"
        return exec(query, ::to_policy).filterNotNull()
    }

    /** Map a row map to a [SuPolicy] instance. */
    private fun to_policy(map: Map<String, String>): SuPolicy? {
        val uid = map["uid"]?.toInt() ?: return null
        val policy = SuPolicy(uid)

        map["until"]?.toLong()?.let { until ->
            if (until <= 0) {
                policy.remain = until
            } else {
                map["remain"]?.toLong()?.let { policy.remain = it }
            }
        }

        map["policy"]?.toInt()?.let { policy.policy = it }
        map["logging"]?.toInt()?.let { policy.logging = it != 0 }
        map["notification"]?.toInt()?.let { policy.notification = it != 0 }
        return policy
    }

}
