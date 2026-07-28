/**
 * Manual service-locator (no DI framework).
 *
 * Owns long-lived singletons — shell-backed MagiskDB DAOs,
 * Room database for SU logs, and a pre-configured Markwon
 * instance for rendering Markdown in-app.
 *
 * All properties are lazy so nothing is initialised before
 * it is first needed.
 */
package pro.magisk.core.di

import android.annotation.SuppressLint
import android.content.Context
import android.text.method.LinkMovementMethod
import androidx.room.Room
import pro.magisk.core.AppContext
import pro.magisk.core.data.SuLogDatabase
import pro.magisk.core.data.magiskdb.PolicyDao
import pro.magisk.core.data.magiskdb.SettingsDao
import pro.magisk.core.data.magiskdb.StringDao
import pro.magisk.core.ktx.deviceProtectedContext
import pro.magisk.core.repository.LogRepository
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory

@SuppressLint("StaticFieldLeak")
object ServiceLocator {

    /** Device-protected context – survives reboots. */
    val deContext by lazy { AppContext.deviceProtectedContext }
    val timeoutPrefs by lazy { deContext.getSharedPreferences("su_timeout", 0) }

    // ---- Shell-backed MagiskDB DAOs ----
    val policyDB = PolicyDao()
    val settingsDB = SettingsDao()
    val stringDB = StringDao()

    // ---- Room (SU access logs) ----
    val sulogDB by lazy { createSuLogDatabase(deContext).suLogDao() }
    val logRepo by lazy { LogRepository(sulogDB) }

    // ---- Markdown renderer ----
    val markwon by lazy { createMarkwon(AppContext) }
}

private fun createSuLogDatabase(context: Context) =
    Room.databaseBuilder(context, SuLogDatabase::class.java, "sulogs.db")
        .addMigrations(SuLogDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(true)
        .build()

private fun createMarkwon(context: Context) =
    Markwon.builder(context).textSetter { textView, spanned, bufferType, onComplete ->
        textView.apply {
            movementMethod = LinkMovementMethod.getInstance()
            setSpannableFactory(NoCopySpannableFactory.getInstance())
            setText(spanned, bufferType)
            onComplete.run()
        }
    }.build()
