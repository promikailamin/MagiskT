/**
 * Room database and DAO for SU access logs.
 *
 * [SuLogDatabase] is the Room database (version 2) with a single
 * [SuLogDao] that provides fetch, insert, delete-all, and automatic
 * cleanup of entries older than two weeks.
 */
package pro.magisk.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import pro.magisk.core.model.su.SuLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Database(version = 2, entities = [SuLog::class], exportSchema = false)
abstract class SuLogDatabase : RoomDatabase() {

    abstract fun su_log_dao(): SuLogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = with(db) {
                execSQL("ALTER TABLE logs ADD COLUMN target INTEGER NOT NULL DEFAULT -1")
                execSQL("ALTER TABLE logs ADD COLUMN context TEXT NOT NULL DEFAULT ''")
                execSQL("ALTER TABLE logs ADD COLUMN gids TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

@Dao
abstract class SuLogDao(private val db: SuLogDatabase) {

    private val two_weeks_ago =
        Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, -2) }.timeInMillis

    suspend fun delete_all() = withContext(Dispatchers.IO) { db.clearAllTables() }

    /** Fetch all log entries (deleting outdated ones first). */
    suspend fun fetch_all(): MutableList<SuLog> {
        delete_outdated()
        return fetch()
    }

    @Query("SELECT * FROM logs ORDER BY time DESC")
    protected abstract suspend fun fetch(): MutableList<SuLog>

    @Query("DELETE FROM logs WHERE time < :timeout")
    protected abstract suspend fun delete_outdated(timeout: Long = two_weeks_ago)

    @Insert
    abstract suspend fun insert(log: SuLog)

}
