/**
 * Base DAO for Magisk's SQLite database, accessed through the
 * `magisk --sqlite` CLI command.
 *
 * Provides helpers to execute queries and parse pipe-delimited
 * `key=value` result lines. Three tables are defined in [Table].
 */
package pro.magisk.core.data.magiskdb

import pro.magisk.core.ktx.await
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class MagiskDB {

    /** Wrapper to embed a raw SQL literal (e.g. an expression) in a values list. */
    class Literal(
        val str: String
    )

    /**
     * Execute a query and map every result row through [mapper].
     * Each row is parsed as `key=value` pairs delimited by `|`.
     */
    suspend inline fun <R> exec(
        query: String,
        crossinline mapper: (Map<String, String>) -> R
    ): List<R> {
        return withContext(Dispatchers.IO) {
            val out = Shell.cmd("magisk --sqlite '$query'").await().out
            out.map { line ->
                line.split("\\|".toRegex())
                    .map { it.split("=", limit = 2) }
                    .filter { it.size == 2 }
                    .associate { it[0] to it[1] }
                    .let(mapper)
            }
        }
    }

    /** Execute a query that does not return rows (e.g. DELETE, REPLACE). */
    suspend fun exec(query: String) {
        withContext(Dispatchers.IO) {
            Shell.cmd("magisk --sqlite '$query'").await()
        }
    }

    /** Build an SQL `(keys) VALUES(values)` snippet from a map. */
    fun Map<String, Any>.toQuery(): String {
        val keys = this.keys.joinToString(",")
        val values = this.values.joinToString(",") {
            when (it) {
                is Boolean -> if (it) "1" else "0"
                is Number -> it.toString()
                is Literal -> it.str
                else -> "\"$it\""
            }
        }
        return "($keys) VALUES($values)"
    }

    object Table {
        const val POLICY = "policies"
        const val SETTINGS = "settings"
        const val STRINGS = "strings"
    }
}
