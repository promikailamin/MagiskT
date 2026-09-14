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
import timber.log.Timber

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
            val time = System.currentTimeMillis()
            val result = Shell.cmd("magisk --sqlite '$query'").await()
            val rows = result.out.map { line ->
                line.split("\\|".toRegex())
                    .map { it.split("=", limit = 2) }
                    .filter { it.size == 2 }
                    .associate { it[0] to it[1] }
                    .let(mapper)
            }
            logQuery(query, result.isSuccess, rows.size, time)
            rows
        }
    }

    /** Execute a query that does not return rows (e.g. DELETE, REPLACE). */
    suspend fun exec(query: String) {
        withContext(Dispatchers.IO) {
            val time = System.currentTimeMillis()
            val result = Shell.cmd("magisk --sqlite '$query'").await()
            if (!result.isSuccess) {
                Timber.e("SQL FAILED [%dms] %s | stderr: %s",
                    System.currentTimeMillis() - time, query.sanitize(), result.err)
            } else {
                Timber.d("SQL [%dms] %s", System.currentTimeMillis() - time, query.sanitize())
            }
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

    @PublishedApi
    internal fun logQuery(query: String, success: Boolean, rowCount: Int, start: Long) {
        val ms = System.currentTimeMillis() - start
        if (!success) {
            Timber.e("SQL FAILED [%dms] %s", ms, query.sanitize())
        } else {
            Timber.d("SQL [%dms] %d rows: %s", ms, rowCount, query.sanitize())
        }
    }

    /** Strip the value halves to keep secrets/sensitive data out of logs. */
    @PublishedApi
    internal fun String.sanitize(): String =
        substring(0, minOf(length, 200))
            .replace(Regex("=\\s*'[^']*'"), "='...'")
            .replace(Regex("=\\s*\"[^\"]*\""), "=\"...\"")
}