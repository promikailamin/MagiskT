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

    /** Remove expired and negative-`until` entries (locked entries are kept). */
    suspend fun deleteOutdated() {
        val query = "DELETE FROM ${Table.POLICY} WHERE " +
            "((until > 0 AND until < strftime(\"%s\", \"now\")) OR until < 0) " +
            "AND (locked IS NULL OR locked = 0)"
        exec(query)
    }

    /** Delete the policy for a given [uid] (locked policies are kept). */
    suspend fun delete(uid: Int) {
        val query = "DELETE FROM ${Table.POLICY} WHERE uid=$uid AND (locked IS NULL OR locked = 0)"
        exec(query)
    }

    /** Fetch the policy for a given [uid], or null if none exists. */
    suspend fun fetch(uid: Int): SuPolicy? {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid=$uid LIMIT 1"
        return exec(query, ::toPolicy).firstOrNull()
    }

    /** Insert or replace the given [policy]. */
    suspend fun update(policy: SuPolicy) {
        val map = policy.toMap()
        val pkg = policy.packageName ?: runCatching {
            AppContext.packageManager.getNameForUid(policy.uid)
        }.getOrNull()
        if (pkg != null) {
            map["package_name"] = pkg
        }
        val query = "REPLACE INTO ${Table.POLICY} ${map.toQuery()}"
        exec(query)
    }

    /** Remap the UID of any policy matching [pkg] to [newUid] (app reinstalled). */
    suspend fun remapUid(pkg: String, newUid: Int) {
        exec("DELETE FROM policies WHERE uid=$newUid AND package_name='$pkg'")
        exec("UPDATE policies SET uid=$newUid " +
            "WHERE package_name='$pkg' AND uid<>$newUid")
    }

    /** Fetch a locked policy held by any uid for [pkg], or null. */
    suspend fun fetchLockedByPackage(pkg: String): SuPolicy? {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} " +
            "WHERE package_name='$pkg' AND locked=1 LIMIT 1"
        return exec(query, ::toPolicy).firstOrNull()
    }

    /** Delete the policy row for [uid] regardless of its locked state. */
    suspend fun forceDelete(uid: Int) {
        exec("DELETE FROM policies WHERE uid=$uid")
    }

    /** Fetch all policies for the current user. */
    suspend fun fetchAll(): List<SuPolicy> {
        val query = "$SELECT_QUERY FROM ${Table.POLICY} WHERE uid/100000=${Const.USER_ID}"
        return exec(query, ::toPolicy).filterNotNull()
    }

    /** Map a row map to a [SuPolicy] instance. */
    private fun toPolicy(map: Map<String, String>): SuPolicy? {
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
        map["locked"]?.toInt()?.let { policy.locked = it != 0 }
        policy.packageName = map["package_name"]
        return policy
    }

}
